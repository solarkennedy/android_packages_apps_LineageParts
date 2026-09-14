/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.lineageparts.perf;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import org.lineageos.lineageparts.R;
import org.lineageos.lineageparts.SettingsPreferenceFragment;

/**
 * Individually user-toggleable Android-Go-style low-RAM tunables, plus other
 * pepito-specific tweaks. See PLAN-perf-battery.md. Each toggle writes a
 * persist.gotweak.* property; most are applied by
 * device/xiaomi/mithorium-common's init.gotweaks.rc (ro.config.low_ram /
 * dalvik.vm.* / ro.lmk.* / pm.dexopt.* writes) - zram_zstd is the exception,
 * read directly by init.qcom.post_boot.sh's configure_zram_parameters()
 * instead, since that's the existing code that already owns zram's full
 * setup (disksize/mkswap/swapon) lifecycle.
 *
 * Three of the four (low_ram/heap_trim/lmk) are gated behind a reboot
 * prompt: all backed by ro.* or zygote/lmkd-read-once properties that only
 * take effect on the next boot either way. zram_zstd is read once by
 * post_boot.sh while setting up zram as swap, same story. dexopt needs a
 * reboot to fully revert when turned back off (its "on" trigger applies
 * live, but there is no live "off" trigger - turning it off just lets the
 * next boot's static defaults take over unmodified). Uniform reboot
 * messaging keeps that last asymmetry from being confusing.
 *
 * The PepitoLauncher2 entry is informational only, not a toggle: it's
 * already installed and selectable today (device.mk ships it alongside
 * Trebuchet, not as default), and there's no simple flag to flip - Android's
 * default-Home-app assignment is a system Role, not a boolean. Tapping it
 * just opens Settings' Home app picker (ACTION_HOME_SETTINGS).
 *
 * The "Extreme Battery Saver" pair (battery_saver_cpu/battery_saver_gpu) are
 * a different shape entirely from the rest of this screen: they don't do
 * anything by themselves. Each just enables one lever
 * (persist.gotweak.cpu_cluster_saver / gpu_clock_cap) for
 * GotweaksBatterySaverReceiver (XiaomiParts) to apply automatically
 * whenever stock Battery Saver is on - our own take on the Pixel-exclusive
 * Extreme Battery Saver tier, built from root access this device actually
 * has. Both apply live (no reboot prompt): flipping either one here
 * immediately recomputes its output property against the *current*
 * isPowerSaveMode(), rather than waiting for the next Battery Saver change,
 * matching the (enabled && isPowerSaveMode()) check in
 * GotweaksBatterySaverReceiver.apply() - keep both in sync if it ever
 * changes.
 *
 * Life Mode is no longer here at all: it outgrew being a category on this screen
 * and is now its own Settings section (LifeModeSettings), reached from System
 * alongside this one, from search, or from its QS tile's long-press.
 *
 * Note Life Mode's own "Turn on Battery Saver" knob stacks with the two Extreme
 * Battery Saver levers above rather than duplicating them: it makes Life Mode
 * switch *stock* Battery Saver on for the duration of screen-off, which
 * GotweaksBatterySaverReceiver then hears as it would any other Battery Saver
 * change - so whichever levers are enabled apply too, with nothing extra wired up.
 */
public class GoTweaksSettings extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String KEY_LOW_RAM = "go_tweak_low_ram";
    private static final String KEY_HEAP_TRIM = "go_tweak_heap_trim";
    private static final String KEY_LMK = "go_tweak_lmk";
    private static final String KEY_DEXOPT = "go_tweak_dexopt";
    private static final String KEY_ZRAM_ZSTD = "go_tweak_zram_zstd";
    private static final String KEY_GPU_PERF_FLOOR = "go_tweak_gpu_perf_floor";
    private static final String KEY_BATTERY_SAVER_CPU = "go_tweak_battery_saver_cpu";
    private static final String KEY_BATTERY_SAVER_GPU = "go_tweak_battery_saver_gpu";
    private static final String KEY_PEPITOLAUNCHER2_INFO = "go_tweak_pepitolauncher2_info";
    private static final String KEY_PLAY_CERT_CATEGORY = "play_cert_category";
    private static final String KEY_PLAY_CERT_STATUS = "play_cert_status";
    private static final String KEY_PLAY_CERT_ID = "play_cert_id";
    private static final String KEY_PLAY_CERT_REGISTER = "play_cert_register";

    /**
     * GMS's Gservices provider. Reading it needs
     * com.google.android.providers.gsf.permission.READ_GSERVICES, which GMS declares at
     * protectionLevel=normal - so it is auto-granted at install and this needs no
     * privileged status. Absent entirely on vanilla builds, hence the null checks.
     */
    private static final Uri GSERVICES_URI =
            Uri.parse("content://com.google.android.gsf.gservices");
    private static final String GSERVICES_AUTHORITY = "com.google.android.gsf.gservices";
    private static final String GSERVICES_ANDROID_ID = "android_id";
    private static final String GSERVICES_UNCERTIFIED_STATUS = "uncertified_status";
    private static final String GSERVICES_UNCERTIFIED_EXPIRY =
            "uncertified_status_expiration_time_ms";

    /**
     * The registration page does not read an id from the query string - it is a plain
     * form. We append one anyway so the device ID is visible in the browser's address
     * bar, which gives the user something to read off if the clipboard copy is lost
     * (switching apps, a clipboard manager, or pasting on a different device).
     */
    private static final String PLAY_CERT_REGISTER_URL =
            "https://www.google.com/android/uncertified/";
    private static final String PLAY_CERT_REGISTER_ID_PARAM = "id";

    /**
     * Fallback source for the device ID, published at boot by
     * init.pepito-gsfid.sh (device/xiaomi/Mi8937/rootdir).
     *
     * ⚠️ Needed because GMS serves uncertified_status through the Gservices
     * provider but WITHHOLDS android_id from third-party callers - verified
     * 2026-09-04 on a unit whose gservices.db demonstrably contained the key
     * while this app, holding both READ_GSERVICES and
     * READ_PRIVILEGED_PHONE_STATE, got nothing back. The value lives only in
     * GMS's 0660 app-private storage, so a privileged boot-time read into a
     * property is the only way to show it without making the user enable adb,
     * root the device and run a SQL query by hand.
     */
    private static final String PROP_GSF_ID = "sys.pepito.gsf_id";

    /**
     * Re-runs the publisher on demand, so a user who has no ID does not have to reboot.
     *
     * ⚠️ Why this is needed at all: the publisher is triggered at boot, but the file it
     * parses lives in CREDENTIAL-ENCRYPTED storage, which stays locked until the user's
     * first unlock. On any device with a screen lock the boot-time run therefore finds
     * nothing and exits silently, and the row shows no ID (community report 2026-09-09;
     * our bench units all missed it because none of them has a lock screen).
     *
     * Tapping the row cannot hit that race: if the user is in Settings they have already
     * unlocked, so CE storage is available and the re-run succeeds.
     */
    private static final String PROP_GSF_REFRESH = "sys.pepito.gsf_refresh";

    /** The publisher is a few file reads; poll briefly rather than making the user wait. */
    private static final int GSFID_POLL_STEPS = 10;
    private static final long GSFID_POLL_INTERVAL_MS = 200L;

    /**
     * Observed values of uncertified_status. Only 1 and 3 have ever been seen on the
     * bench (2026-09-04): a unit showing the "not Play Protect certified" dialog read 3,
     * and both a long-running unit and a freshly-registered one read 1 alongside an
     * expiry timestamp 75 days out. The full value set is undocumented, so anything else
     * is surfaced verbatim rather than guessed at.
     */
    private static final String UNCERTIFIED_STATUS_GRACE = "1";
    private static final String UNCERTIFIED_STATUS_UNREGISTERED = "3";

    private static final String PROP_LOW_RAM = "persist.gotweak.low_ram";
    private static final String PROP_HEAP_TRIM = "persist.gotweak.heap_trim";
    private static final String PROP_LMK = "persist.gotweak.lmk";
    private static final String PROP_DEXOPT = "persist.gotweak.dexopt";
    private static final String PROP_ZRAM_ZSTD = "persist.gotweak.zram_zstd";
    private static final String PROP_GPU_PERF_FLOOR = "persist.gotweak.gpu_perf_floor";
    private static final String PROP_BATTERY_SAVER_CPU_ENABLE =
            "persist.gotweak.battery_saver_cpu_enable";
    private static final String PROP_CPU_CLUSTER_SAVER =
            "persist.gotweak.cpu_cluster_saver";
    private static final String PROP_BATTERY_SAVER_GPU_ENABLE =
            "persist.gotweak.battery_saver_gpu_enable";
    private static final String PROP_GPU_CLOCK_CAP = "persist.gotweak.gpu_clock_cap";

    private SwitchPreferenceCompat mLowRamPref;
    private SwitchPreferenceCompat mHeapTrimPref;
    private SwitchPreferenceCompat mLmkPref;
    private SwitchPreferenceCompat mDexoptPref;
    private SwitchPreferenceCompat mZramZstdPref;
    private SwitchPreferenceCompat mGpuPerfFloorPref;
    private SwitchPreferenceCompat mBatterySaverCpuPref;
    private SwitchPreferenceCompat mBatterySaverGpuPref;
    private Preference mPlayCertStatusPref;
    private Preference mPlayCertIdPref;

    @Override
    public void onActivityCreated(final Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        addPreferencesFromResource(R.xml.go_tweaks_settings);
        requireActivity().getActionBar().setTitle(R.string.go_tweaks_title);

        final PreferenceScreen prefSet = getPreferenceScreen();

        mLowRamPref = prefSet.findPreference(KEY_LOW_RAM);
        mHeapTrimPref = prefSet.findPreference(KEY_HEAP_TRIM);
        mLmkPref = prefSet.findPreference(KEY_LMK);
        mDexoptPref = prefSet.findPreference(KEY_DEXOPT);
        mZramZstdPref = prefSet.findPreference(KEY_ZRAM_ZSTD);
        mGpuPerfFloorPref = prefSet.findPreference(KEY_GPU_PERF_FLOOR);
        mBatterySaverCpuPref = prefSet.findPreference(KEY_BATTERY_SAVER_CPU);
        mBatterySaverGpuPref = prefSet.findPreference(KEY_BATTERY_SAVER_GPU);

        setUpPlayCertPrefs(prefSet);

        final Preference pepitoLauncher2Pref = prefSet.findPreference(KEY_PEPITOLAUNCHER2_INFO);
        if (pepitoLauncher2Pref != null) {
            pepitoLauncher2Pref.setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
                return true;
            });
        }

        mLowRamPref.setChecked(SystemProperties.getBoolean(PROP_LOW_RAM, false));
        mHeapTrimPref.setChecked(SystemProperties.getBoolean(PROP_HEAP_TRIM, false));
        mLmkPref.setChecked(SystemProperties.getBoolean(PROP_LMK, false));
        mDexoptPref.setChecked(SystemProperties.getBoolean(PROP_DEXOPT, false));
        // Default OFF since 2026-08-22 (must stay in sync with the "unset counts
        // as off" check in init.qcom.post_boot.sh's configure_zram_parameters()):
        // Gold A/B showed zstd's slower swap-out drives lmkd to kill backgrounded
        // apps under pressure that lz4 rides out as plain swap.
        mZramZstdPref.setChecked(SystemProperties.getBoolean(PROP_ZRAM_ZSTD, false));
        // Default ON, matching the persist.gotweak.gpu_perf_floor=1 build-prop
        // default in mithorium.mk (keep in sync). The fallback here only shows
        // on builds missing that default; the getprop normally resolves.
        mGpuPerfFloorPref.setChecked(SystemProperties.getBoolean(PROP_GPU_PERF_FLOOR, true));
        mBatterySaverCpuPref.setChecked(
                SystemProperties.getBoolean(PROP_BATTERY_SAVER_CPU_ENABLE, true));
        mBatterySaverGpuPref.setChecked(
                SystemProperties.getBoolean(PROP_BATTERY_SAVER_GPU_ENABLE, false));

        mLowRamPref.setOnPreferenceChangeListener(this);
        mHeapTrimPref.setOnPreferenceChangeListener(this);
        mLmkPref.setOnPreferenceChangeListener(this);
        mDexoptPref.setOnPreferenceChangeListener(this);
        mZramZstdPref.setOnPreferenceChangeListener(this);
        mGpuPerfFloorPref.setOnPreferenceChangeListener(this);
        mBatterySaverCpuPref.setOnPreferenceChangeListener(this);
        mBatterySaverGpuPref.setOnPreferenceChangeListener(this);
    }

    @Override
    public boolean onPreferenceChange(final Preference preference, final Object newValue) {
        final boolean enabled = (Boolean) newValue;
        final String value = enabled ? "1" : "0";

        // GPU perf floor applies live in both directions - init.gotweaks.rc's
        // `on property:` triggers write kgsl min_pwrlevel immediately, and the
        // registration-time evaluation applies a persisted "1" on every boot.
        // No reboot prompt.
        if (preference == mGpuPerfFloorPref) {
            SystemProperties.set(PROP_GPU_PERF_FLOOR, value);
            return true;
        }

        if (preference == mBatterySaverCpuPref) {
            applyBatterySaverLever(PROP_BATTERY_SAVER_CPU_ENABLE, PROP_CPU_CLUSTER_SAVER, enabled);
            return true;
        }

        if (preference == mBatterySaverGpuPref) {
            applyBatterySaverLever(PROP_BATTERY_SAVER_GPU_ENABLE, PROP_GPU_CLOCK_CAP, enabled);
            return true;
        }

        if (preference == mLowRamPref) {
            SystemProperties.set(PROP_LOW_RAM, value);
        } else if (preference == mHeapTrimPref) {
            SystemProperties.set(PROP_HEAP_TRIM, value);
        } else if (preference == mLmkPref) {
            SystemProperties.set(PROP_LMK, value);
        } else if (preference == mDexoptPref) {
            SystemProperties.set(PROP_DEXOPT, value);
        } else if (preference == mZramZstdPref) {
            SystemProperties.set(PROP_ZRAM_ZSTD, value);
        } else {
            return false;
        }

        promptReboot();
        return true;
    }

    /**
     * Sets the enable flag, then immediately recomputes the output property
     * against the current Battery Saver state rather than waiting for the
     * next change - see GotweaksBatterySaverReceiver.apply(), which this
     * mirrors per-lever.
     */
    private void applyBatterySaverLever(final String enableProp, final String outputProp,
            final boolean enabled) {
        SystemProperties.set(enableProp, enabled ? "1" : "0");

        final Context context = getContext();
        final PowerManager pm = context == null
                ? null : context.getSystemService(PowerManager.class);
        final boolean apply = enabled && pm != null && pm.isPowerSaveMode();
        SystemProperties.set(outputProp, apply ? "1" : "0");
    }

    /**
     * Wires the "Google Play certification" rows, or removes the whole category on builds
     * with no GMS (vanilla), where the Gservices provider does not exist.
     */
    private void setUpPlayCertPrefs(final PreferenceScreen prefSet) {
        final Preference category = prefSet.findPreference(KEY_PLAY_CERT_CATEGORY);
        mPlayCertStatusPref = prefSet.findPreference(KEY_PLAY_CERT_STATUS);
        mPlayCertIdPref = prefSet.findPreference(KEY_PLAY_CERT_ID);
        final Preference registerPref = prefSet.findPreference(KEY_PLAY_CERT_REGISTER);

        final Context context = getContext();
        final boolean haveGservices = context != null
                && context.getPackageManager()
                        .resolveContentProvider(GSERVICES_AUTHORITY, 0) != null;
        if (!haveGservices) {
            if (category != null) {
                prefSet.removePreference(category);
            }
            mPlayCertStatusPref = null;
            mPlayCertIdPref = null;
            return;
        }

        if (mPlayCertStatusPref != null) {
            // Re-read on demand. The status only moves after a GMS check-in, so this
            // will not change immediately after registering - hence the extra toast.
            mPlayCertStatusPref.setOnPreferenceClickListener(preference -> {
                refreshPlayCert();
                toast(R.string.play_cert_status_rechecked);
                return true;
            });
        }
        if (mPlayCertIdPref != null) {
            mPlayCertIdPref.setOnPreferenceClickListener(preference -> {
                final String androidId = readDeviceId();
                if (TextUtils.isEmpty(androidId)) {
                    retryPublishDeviceId();
                } else {
                    shareDeviceId(androidId);
                }
                return true;
            });
        }
        if (registerPref != null) {
            registerPref.setOnPreferenceClickListener(preference -> {
                // Copy as well as putting it in the URL: pasting into the form is the
                // normal path, and the page itself never displays the ID.
                final String androidId = readDeviceId();
                copyDeviceIdToClipboard(androidId);
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            buildRegistrationUri(androidId)));
                } catch (ActivityNotFoundException e) {
                    toast(R.string.play_cert_no_browser);
                }
                return true;
            });
        }

        refreshPlayCert();
    }

    /**
     * Re-reads the device ID and certification status. Called on resume so returning from
     * the browser reflects reality.
     *
     * <p>⚠️ The status only changes after the next GMS check-in, i.e. after a reboot - so
     * immediately after registering this still reads unregistered. That is expected, and
     * the summary says so rather than looking like a failure.
     */
    private void refreshPlayCert() {
        final String androidId = readDeviceId();

        if (mPlayCertIdPref != null) {
            if (TextUtils.isEmpty(androidId)) {
                mPlayCertIdPref.setSummary(R.string.play_cert_id_unavailable);
                mPlayCertIdPref.setEnabled(false);
            } else {
                mPlayCertIdPref.setSummary(
                        getString(R.string.play_cert_id_summary, androidId));
                mPlayCertIdPref.setEnabled(true);
            }
        }

        if (mPlayCertStatusPref == null) {
            return;
        }

        final String status = readGservices(GSERVICES_UNCERTIFIED_STATUS);
        if (TextUtils.isEmpty(androidId) && TextUtils.isEmpty(status)) {
            mPlayCertStatusPref.setSummary(R.string.play_cert_status_unavailable);
        } else if (TextUtils.isEmpty(status)) {
            // No row at all: seen on units GMS has never flagged. Nothing is wrong.
            mPlayCertStatusPref.setSummary(R.string.play_cert_status_registered);
        } else if (UNCERTIFIED_STATUS_UNREGISTERED.equals(status)) {
            mPlayCertStatusPref.setSummary(appendStaleHint(
                    getString(R.string.play_cert_status_unregistered)));
        } else if (UNCERTIFIED_STATUS_GRACE.equals(status)) {
            final String expiry = formatExpiry(readGservices(GSERVICES_UNCERTIFIED_EXPIRY));
            mPlayCertStatusPref.setSummary(TextUtils.isEmpty(expiry)
                    ? getString(R.string.play_cert_status_registered)
                    : getString(R.string.play_cert_status_grace, expiry));
        } else {
            mPlayCertStatusPref.setSummary(
                    getString(R.string.play_cert_status_other, status));
        }
    }

    /**
     * Only shown while still unregistered: the most likely reason someone is staring at
     * this row is that they just registered and expected it to flip.
     */
    private String appendStaleHint(final String summary) {
        return summary + "\n\n" + getString(R.string.play_cert_status_stale_warning);
    }

    private String formatExpiry(final String millis) {
        final Context context = getContext();
        if (context == null || TextUtils.isEmpty(millis)) {
            return "";
        }
        try {
            return DateFormat.getDateFormat(context)
                    .format(new java.util.Date(Long.parseLong(millis)));
        } catch (NumberFormatException e) {
            return "";
        }
    }

    /**
     * Gservices takes its keys as selectionArgs rather than a WHERE clause, and returns
     * name/value in columns 0/1. Returns null when the key is absent or unreadable.
     */
    /**
     * The device ID, from the Gservices provider if GMS will part with it and
     * otherwise from the property our boot service publishes. Empty only when
     * GMS has never checked in - a reboot fixes that, which the UI says.
     */
    /**
     * Asks init to re-run the GSF-ID publisher, then polls for the result.
     *
     * <p>The service is a oneshot, so starting it again is safe and idempotent. We poll
     * instead of sleeping a fixed time so a fast device updates immediately, and give up
     * after ~2s rather than blocking the UI thread indefinitely.
     */
    private void retryPublishDeviceId() {
        // Not ctl.start: an appdomain may never set a control property (neverallow in
        // system/sepolicy/private/property.te). init watches this property instead, and
        // the publisher clears it back to 0 so a later tap is a fresh change.
        SystemProperties.set(PROP_GSF_REFRESH, "1");
        pollForDeviceId(GSFID_POLL_STEPS);
    }

    private void pollForDeviceId(final int stepsLeft) {
        if (mPlayCertIdPref == null) {
            return;
        }
        if (!TextUtils.isEmpty(SystemProperties.get(PROP_GSF_ID, ""))) {
            refreshPlayCert();
            return;
        }
        if (stepsLeft <= 0) {
            // Still nothing: the device has genuinely never checked in, so there is no
            // ID to publish yet. Leave the row's existing explanation in place.
            toast(R.string.play_cert_id_still_unavailable);
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> pollForDeviceId(stepsLeft - 1), GSFID_POLL_INTERVAL_MS);
    }

    private String readDeviceId() {
        final String fromProvider = readGservices(GSERVICES_ANDROID_ID);
        if (!TextUtils.isEmpty(fromProvider)) {
            return fromProvider;
        }
        return SystemProperties.get(PROP_GSF_ID, "");
    }

    private String readGservices(final String key) {
        final Context context = getContext();
        if (context == null) {
            return null;
        }
        try (Cursor c = context.getContentResolver().query(
                GSERVICES_URI, null, null, new String[] { key }, null)) {
            if (c != null && c.moveToFirst() && c.getColumnCount() >= 2) {
                return c.getString(1);
            }
        } catch (Exception e) {
            // Provider missing, permission refused, or GMS mid-update - all non-fatal.
        }
        return null;
    }

    /**
     * Offers the ID through the system share sheet rather than only the clipboard:
     * registering usually happens on a different machine, so mailing or messaging the
     * number to yourself is often more useful than a local copy. The share sheet still
     * offers "copy" among its targets.
     */
    private void shareDeviceId(final String androidId) {
        if (TextUtils.isEmpty(androidId)) {
            return;
        }
        final Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, androidId);
        send.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.play_cert_id_share_subject));
        try {
            startActivity(Intent.createChooser(
                    send, getString(R.string.play_cert_id_share_title)));
        } catch (ActivityNotFoundException e) {
            toast(R.string.play_cert_no_share);
        }
    }

    /**
     * Builds the registration URL, appending the device ID as a query parameter when we
     * have one. Google's form ignores it; it is there so the number is legible in the
     * address bar as a fallback for a lost clipboard.
     */
    private Uri buildRegistrationUri(final String androidId) {
        final Uri base = Uri.parse(PLAY_CERT_REGISTER_URL);
        if (TextUtils.isEmpty(androidId)) {
            return base;
        }
        return base.buildUpon()
                .appendQueryParameter(PLAY_CERT_REGISTER_ID_PARAM, androidId)
                .build();
    }

    private void copyDeviceIdToClipboard(final String androidId) {
        final Context context = getContext();
        if (context == null || TextUtils.isEmpty(androidId)) {
            return;
        }
        final ClipboardManager cm = context.getSystemService(ClipboardManager.class);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(
                    context.getString(R.string.play_cert_id_title), androidId));
            toast(R.string.play_cert_id_copied);
        }
    }

    private void toast(final int resId) {
        final Context context = getContext();
        if (context != null) {
            Toast.makeText(context, resId, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshPlayCert();
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
