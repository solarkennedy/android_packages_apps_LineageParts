/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.lineageparts.radio;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.text.TextUtils;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import org.lineageos.lineageparts.R;
import org.lineageos.lineageparts.SettingsPreferenceFragment;

/**
 * Modem tweaks: the collection of modem-side settings the pepito port has had
 * to take over from the framework, in one place. See PLAN-emergency-only.md.
 *
 * Split out of Pepito Tweaks (GoTweaksSettings), which started as Android-Go
 * low-RAM tuning and grew into a catch-all; the mobile-network rows were the
 * odd ones out there, and the data-centric switch below is the first of a set
 * that this screen is meant to collect.
 *
 * Two kinds of row live here:
 *
 * <ul>
 * <li><b>Modem NV settings</b> (data-centric). The switch writes a
 * persist.gotweak.* property; nothing reads it live. The vendor boot oneshot
 * ims_enabler (device/xiaomi/Mi8937/qmux) reads it on the next boot and
 * asserts the matching value in the modem's NV over QMI - read-compare-write,
 * so once it holds the boot is read-only. The switch therefore has both an ON
 * and an OFF value (off restores the stock voice-centric setting, it does not
 * just leave whatever was there), and it needs a restart: a live write would
 * work, but it kicks the modem into a fresh PLMN selection and attach while
 * the user is sitting in Settings, and a restart gives the modem one clean
 * cold attach from a known state instead. The row below the switch shows what
 * the modem actually reads back (published by ims_enabler in
 * vendor.pepito.modem.usage), so "pending restart" and "applied" are visible
 * without adb.</li>
 *
 * <li><b>The emergency-only watchdog</b>. Read fresh by
 * EmergencyWatchdogController (XiaomiParts) at every decision, so these apply
 * live with no restart. Moved here unchanged from Pepito Tweaks: same keys,
 * same persist.gotweak.* properties, so existing settings carry over.</li>
 * </ul>
 */
public class ModemTweaksSettings extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String KEY_DATA_CENTRIC = "modem_tweak_data_centric";
    private static final String KEY_DATA_CENTRIC_STATUS = "modem_tweak_data_centric_status";
    private static final String KEY_EMERGENCY_WATCHDOG = "go_tweak_emergency_watchdog";
    private static final String KEY_EWD_NUDGE = "go_tweak_ewd_nudge";
    private static final String KEY_EWD_NUDGE_MECH = "go_tweak_ewd_nudge_mech";

    /**
     * Read by ims_enabler at boot: 1 -> NAS usage_preference=2 (DATA_CENTRIC),
     * 0 -> usage_preference=1 (VOICE_CENTRIC, the stock value). Default ON via
     * the persist.gotweak.modem_data_centric=1 build-prop default in
     * device/xiaomi/Mi8937/device.mk (keep in sync); the fallback here only
     * shows on a build missing that default.
     *
     * Why on by default (bench, 2026-10-14, matched A/B on new2 with F3 modem
     * logs): a voice-centric UE whose IMS registration drops while camped on
     * its home cell asks the modem for voice, the modem finds none, puts the
     * home PLMN on its backoff-forbidden list and the phone sits on "Emergency
     * calls only" until something forces a fresh attach. Data-centric stops the
     * modem demanding voice at all: same scenario held REGISTERED with full
     * service and zero forbiddance lines in the modem log. SMS still rides IMS.
     */
    private static final String PROP_DATA_CENTRIC = "persist.gotweak.modem_data_centric";

    /**
     * What the modem actually holds, as ims_enabler read it back after its
     * boot-time assert: "1" / "2" (the NAS usage_preference value, verified),
     * "failed" (the modem never answered within its budget), "off" (qmux
     * disabled - not pepito), or unset (the oneshot has not got there yet).
     * vendor_public_prop, with an explicit get_prop(system_app, ...) grant:
     * the enabler's other status props (vendor.qmux.*) are vendor-internal
     * and a coredomain app may never read those.
     */
    private static final String PROP_USAGE_READBACK = "vendor.pepito.modem.usage";
    private static final String USAGE_VOICE_CENTRIC = "1";
    private static final String USAGE_DATA_CENTRIC = "2";
    private static final String USAGE_FAILED = "failed";
    private static final String USAGE_OFF = "off";

    /** Read fresh by EmergencyWatchdogController (XiaomiParts); applies live. */
    private static final String PROP_EMERGENCY_WATCHDOG =
            "persist.gotweak.emergency_watchdog";

    /**
     * The watchdog's real-recovery switch and its mechanism. With the first off the
     * watchdog only detects and notifies (dry run). Both are read fresh at decision
     * time, so they apply live too. Defaults must match EmergencyWatchdogController.
     */
    private static final String PROP_EWD_NUDGE = "persist.gotweak.ewd_nudge";
    private static final String PROP_EWD_NUDGE_MECH = "persist.gotweak.ewd_nudge_mech";
    private static final String EWD_NUDGE_MECH_DEFAULT = "rescan";

    private SwitchPreferenceCompat mDataCentricPref;
    private Preference mDataCentricStatusPref;
    private SwitchPreferenceCompat mEmergencyWatchdogPref;
    private SwitchPreferenceCompat mEwdNudgePref;
    private ListPreference mEwdNudgeMechPref;

    @Override
    public void onActivityCreated(final Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        addPreferencesFromResource(R.xml.modem_tweaks_settings);
        requireActivity().getActionBar().setTitle(R.string.modem_tweaks_title);

        final PreferenceScreen prefSet = getPreferenceScreen();

        mDataCentricPref = prefSet.findPreference(KEY_DATA_CENTRIC);
        mDataCentricStatusPref = prefSet.findPreference(KEY_DATA_CENTRIC_STATUS);
        mEmergencyWatchdogPref = prefSet.findPreference(KEY_EMERGENCY_WATCHDOG);
        mEwdNudgePref = prefSet.findPreference(KEY_EWD_NUDGE);
        mEwdNudgeMechPref = prefSet.findPreference(KEY_EWD_NUDGE_MECH);

        mDataCentricPref.setChecked(SystemProperties.getBoolean(PROP_DATA_CENTRIC, true));
        // Off by default (ships disabled; Kyle 2026-10-11: keep it, off).
        mEmergencyWatchdogPref.setChecked(
                SystemProperties.getBoolean(PROP_EMERGENCY_WATCHDOG, false));
        mEwdNudgePref.setChecked(SystemProperties.getBoolean(PROP_EWD_NUDGE, false));
        // Anything the controller would not treat as "radio" is a rescan to it.
        String mech = SystemProperties.get(PROP_EWD_NUDGE_MECH, EWD_NUDGE_MECH_DEFAULT);
        if (mEwdNudgeMechPref.findIndexOfValue(mech) < 0) {
            mech = EWD_NUDGE_MECH_DEFAULT;
        }
        mEwdNudgeMechPref.setValue(mech);

        mDataCentricPref.setOnPreferenceChangeListener(this);
        mEmergencyWatchdogPref.setOnPreferenceChangeListener(this);
        mEwdNudgePref.setOnPreferenceChangeListener(this);
        mEwdNudgeMechPref.setOnPreferenceChangeListener(this);

        refreshDataCentricStatus();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshDataCentricStatus();
    }

    @Override
    public boolean onPreferenceChange(final Preference preference, final Object newValue) {
        // The one non-switch on this screen; its value is the property value.
        if (preference == mEwdNudgeMechPref) {
            SystemProperties.set(PROP_EWD_NUDGE_MECH, (String) newValue);
            return true;
        }

        final boolean enabled = (Boolean) newValue;
        final String value = enabled ? "1" : "0";

        // Applies live: EmergencyWatchdogController reads the prop fresh at every
        // decision, so this takes effect without a reboot.
        if (preference == mEmergencyWatchdogPref) {
            SystemProperties.set(PROP_EMERGENCY_WATCHDOG, value);
            return true;
        }
        if (preference == mEwdNudgePref) {
            SystemProperties.set(PROP_EWD_NUDGE, value);
            return true;
        }

        if (preference == mDataCentricPref) {
            SystemProperties.set(PROP_DATA_CENTRIC, value);
            refreshDataCentricStatus();
            promptReboot();
            return true;
        }

        return false;
    }

    /**
     * Compares what the user asked for against what the modem read back, so the
     * row says "applied" only when the two agree. The readback is written once
     * per boot (ims_enabler verifies by re-reading after any write), so after a
     * flip it reads the OLD value until the restart - that is the "pending
     * restart" case, not an error.
     */
    private void refreshDataCentricStatus() {
        if (mDataCentricStatusPref == null) {
            return;
        }
        final boolean wantDataCentric = SystemProperties.getBoolean(PROP_DATA_CENTRIC, true);
        final String want = wantDataCentric ? USAGE_DATA_CENTRIC : USAGE_VOICE_CENTRIC;
        final String have = SystemProperties.get(PROP_USAGE_READBACK, "");

        if (TextUtils.isEmpty(have)) {
            mDataCentricStatusPref.setSummary(R.string.modem_tweak_status_unknown);
        } else if (USAGE_OFF.equals(have)) {
            mDataCentricStatusPref.setSummary(R.string.modem_tweak_status_off);
        } else if (USAGE_FAILED.equals(have)) {
            mDataCentricStatusPref.setSummary(R.string.modem_tweak_status_failed);
        } else if (have.equals(want)) {
            mDataCentricStatusPref.setSummary(wantDataCentric
                    ? R.string.modem_tweak_status_applied_data_centric
                    : R.string.modem_tweak_status_applied_voice_centric);
        } else {
            mDataCentricStatusPref.setSummary(R.string.modem_tweak_status_pending);
        }
    }

    private void promptReboot() {
        final Context context = getContext();
        if (context == null) {
            return;
        }

        new AlertDialog.Builder(context)
                .setTitle(R.string.go_tweaks_reboot_title)
                .setMessage(R.string.go_tweaks_reboot_message)
                .setPositiveButton(R.string.go_tweaks_reboot_now, (dialog, which) -> {
                    final PowerManager pm = context.getSystemService(PowerManager.class);
                    if (pm != null) {
                        pm.reboot(null);
                    }
                })
                .setNegativeButton(R.string.go_tweaks_reboot_later, null)
                .show();
    }
}
