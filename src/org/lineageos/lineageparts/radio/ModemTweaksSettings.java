/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.lineageparts.radio;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
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
 * Three kinds of row live here:
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
 * <li><b>LTE Band 12</b>. Also a persist.gotweak.* switch, but applied by its
 * own vendor oneshot, modem_b12, which init starts on every flip as well as at
 * boot: it writes the B12 calibration items and NV 6828 bit 11 over diag, then
 * needs about 3 minutes for the modem to sync EFS before a restart, so this
 * row tells the user to wait rather than offering an immediate restart.</li>
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
    private static final String KEY_B12 = "modem_tweak_b12";
    private static final String KEY_B12_STATUS = "modem_tweak_b12_status";
    private static final String KEY_EMERGENCY_WATCHDOG = "go_tweak_emergency_watchdog";
    private static final String KEY_EWD_NUDGE = "go_tweak_ewd_nudge";
    private static final String KEY_EWD_NUDGE_MECH = "go_tweak_ewd_nudge_mech";
    private static final String KEY_STATUS_VOICE = "modem_status_voice";
    private static final String KEY_STATUS_FEEDER = "modem_status_feeder";

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

    /**
     * The WLAN feeder's live state, published by wfc_wlan_bridge: off | searching
     * | feeding | up | error. Public (vendor.pepito.modem.*, vendor_public_prop)
     * so this coredomain app may read it - the feeder's own vendor.qmux.* status
     * prop is vendor-internal and not readable here.
     */
    private static final String PROP_WFC_STATUS = "vendor.pepito.modem.wfc";

    /**
     * LTE Band 12 switch, applied by the vendor oneshot modem_b12
     * (device/xiaomi/Mi8937/qmux) on every change and at boot. Default off via
     * persist.gotweak.modem_b12=0 in device/xiaomi/Mi8937/device.mk.
     */
    private static final String PROP_B12 = "persist.gotweak.modem_b12";

    /**
     * modem_b12's state: active | inactive | saving | restart | factory |
     * unsupported | failed | off. vendor_public_prop like the usage readback.
     */
    private static final String PROP_B12_STATUS = "vendor.pepito.modem.b12";
    private static final String B12_SAVING = "saving";
    private static final long B12_POLL_MS = 5000;

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
    private SwitchPreferenceCompat mB12Pref;
    private Preference mB12StatusPref;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mB12Poll = this::refreshB12Status;
    private SwitchPreferenceCompat mEmergencyWatchdogPref;
    private SwitchPreferenceCompat mEwdNudgePref;
    private ListPreference mEwdNudgeMechPref;
    private Preference mVoiceStatusPref;
    private Preference mFeederStatusPref;

    @Override
    public void onActivityCreated(final Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        addPreferencesFromResource(R.xml.modem_tweaks_settings);
        requireActivity().getActionBar().setTitle(R.string.modem_tweaks_title);

        final PreferenceScreen prefSet = getPreferenceScreen();

        mDataCentricPref = prefSet.findPreference(KEY_DATA_CENTRIC);
        mDataCentricStatusPref = prefSet.findPreference(KEY_DATA_CENTRIC_STATUS);
        mB12Pref = prefSet.findPreference(KEY_B12);
        mB12StatusPref = prefSet.findPreference(KEY_B12_STATUS);
        mEmergencyWatchdogPref = prefSet.findPreference(KEY_EMERGENCY_WATCHDOG);
        mEwdNudgePref = prefSet.findPreference(KEY_EWD_NUDGE);
        mEwdNudgeMechPref = prefSet.findPreference(KEY_EWD_NUDGE_MECH);
        mVoiceStatusPref = prefSet.findPreference(KEY_STATUS_VOICE);
        mFeederStatusPref = prefSet.findPreference(KEY_STATUS_FEEDER);

        mDataCentricPref.setChecked(SystemProperties.getBoolean(PROP_DATA_CENTRIC, true));
        mB12Pref.setChecked(SystemProperties.getBoolean(PROP_B12, false));
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
        mB12Pref.setOnPreferenceChangeListener(this);
        mEmergencyWatchdogPref.setOnPreferenceChangeListener(this);
        mEwdNudgePref.setOnPreferenceChangeListener(this);
        mEwdNudgeMechPref.setOnPreferenceChangeListener(this);

        refreshDataCentricStatus();
        refreshB12Status();
        refreshTelephonyStatus();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshDataCentricStatus();
        refreshB12Status();
        refreshTelephonyStatus();
    }

    @Override
    public void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mB12Poll);
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

        if (preference == mB12Pref) {
            SystemProperties.set(PROP_B12, value);
            showB12SavingDialog();
            // modem_b12 starts on the property change; pick up its "saving".
            mHandler.removeCallbacks(mB12Poll);
            mHandler.postDelayed(mB12Poll, B12_POLL_MS);
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

    /**
     * Shows modem_b12's state. While it is saving, polls so the row moves on
     * to "restart" without the user leaving the screen. A unit calibrated for
     * B12 at the factory, or one the tool does not recognise, gets the switch
     * greyed out: modem_b12 will not touch it either way.
     */
    private void refreshB12Status() {
        if (mB12StatusPref == null) {
            return;
        }
        final String have = SystemProperties.get(PROP_B12_STATUS, "");
        final int summary;
        boolean switchable = true;
        switch (have) {
            case "active":      summary = R.string.modem_tweak_b12_status_active; break;
            case "inactive":    summary = R.string.modem_tweak_b12_status_inactive; break;
            case B12_SAVING:    summary = R.string.modem_tweak_b12_status_saving; break;
            case "restart":     summary = R.string.modem_tweak_b12_status_restart; break;
            case "factory":
                summary = R.string.modem_tweak_b12_status_factory;
                switchable = false;
                // B12 is on regardless of the switch; show it that way.
                if (mB12Pref != null) {
                    mB12Pref.setChecked(true);
                }
                break;
            case "unsupported":
                summary = R.string.modem_tweak_b12_status_unsupported;
                switchable = false;
                break;
            case "failed":      summary = R.string.modem_tweak_status_failed; break;
            case "off":
                summary = R.string.modem_tweak_status_off;
                switchable = false;
                break;
            default:            summary = R.string.modem_tweak_status_unknown; break;
        }
        mB12StatusPref.setSummary(summary);
        if (mB12Pref != null) {
            mB12Pref.setEnabled(switchable);
        }
        mHandler.removeCallbacks(mB12Poll);
        if (B12_SAVING.equals(have) || TextUtils.isEmpty(have)) {
            mHandler.postDelayed(mB12Poll, B12_POLL_MS);
        }
    }

    private void showB12SavingDialog() {
        final Context context = getContext();
        if (context == null) {
            return;
        }
        new AlertDialog.Builder(context)
                .setTitle(R.string.modem_tweak_b12_dialog_title)
                .setMessage(R.string.modem_tweak_b12_dialog_message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /**
     * The live status rows. Voice state is read from ServiceState (needs
     * READ_PRIVILEGED_PHONE_STATE, allowlisted in org.lineageos.lineageparts.xml);
     * the feeder row reflects vendor.pepito.modem.wfc, the public mirror
     * wfc_wlan_bridge publishes. Refreshed on open/resume rather than
     * continuously - enough to triage "Emergency calls only" without adb.
     */
    private void refreshTelephonyStatus() {
        if (mVoiceStatusPref != null) {
            int summary = R.string.modem_tweak_status_unknown;
            final Context context = getContext();
            final TelephonyManager tm = context != null
                    ? context.getSystemService(TelephonyManager.class) : null;
            final ServiceState ss = tm != null ? tm.getServiceState() : null;
            if (ss != null) {
                if (ss.isEmergencyOnly()) {
                    summary = R.string.modem_status_voice_emergency;
                } else {
                    switch (ss.getState()) {
                        case ServiceState.STATE_IN_SERVICE:
                            summary = R.string.modem_status_voice_in_service;
                            break;
                        case ServiceState.STATE_EMERGENCY_ONLY:
                            summary = R.string.modem_status_voice_emergency;
                            break;
                        case ServiceState.STATE_POWER_OFF:
                            summary = R.string.modem_status_voice_power_off;
                            break;
                        default:
                            summary = R.string.modem_status_voice_no_service;
                            break;
                    }
                }
            }
            mVoiceStatusPref.setSummary(summary);
        }

        if (mFeederStatusPref != null) {
            final int summary;
            switch (SystemProperties.get(PROP_WFC_STATUS, "")) {
                case "up":        summary = R.string.modem_status_feeder_up; break;
                case "feeding":   summary = R.string.modem_status_feeder_feeding; break;
                case "searching": summary = R.string.modem_status_feeder_searching; break;
                case "off":       summary = R.string.modem_status_feeder_off; break;
                case "error":     summary = R.string.modem_status_feeder_error; break;
                default:          summary = R.string.modem_tweak_status_unknown; break;
            }
            mFeederStatusPref.setSummary(summary);
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
