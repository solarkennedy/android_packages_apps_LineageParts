/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.lineageparts.perf;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.provider.Settings;

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
 */
public class GoTweaksSettings extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String KEY_LOW_RAM = "go_tweak_low_ram";
    private static final String KEY_HEAP_TRIM = "go_tweak_heap_trim";
    private static final String KEY_LMK = "go_tweak_lmk";
    private static final String KEY_DEXOPT = "go_tweak_dexopt";
    private static final String KEY_ZRAM_ZSTD = "go_tweak_zram_zstd";
    private static final String KEY_BATTERY_SAVER_CPU = "go_tweak_battery_saver_cpu";
    private static final String KEY_BATTERY_SAVER_GPU = "go_tweak_battery_saver_gpu";
    private static final String KEY_PEPITOLAUNCHER2_INFO = "go_tweak_pepitolauncher2_info";

    private static final String PROP_LOW_RAM = "persist.gotweak.low_ram";
    private static final String PROP_HEAP_TRIM = "persist.gotweak.heap_trim";
    private static final String PROP_LMK = "persist.gotweak.lmk";
    private static final String PROP_DEXOPT = "persist.gotweak.dexopt";
    private static final String PROP_ZRAM_ZSTD = "persist.gotweak.zram_zstd";
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
    private SwitchPreferenceCompat mBatterySaverCpuPref;
    private SwitchPreferenceCompat mBatterySaverGpuPref;

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
        mBatterySaverCpuPref = prefSet.findPreference(KEY_BATTERY_SAVER_CPU);
        mBatterySaverGpuPref = prefSet.findPreference(KEY_BATTERY_SAVER_GPU);

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
        mZramZstdPref.setChecked(SystemProperties.getBoolean(PROP_ZRAM_ZSTD, false));
        mBatterySaverCpuPref.setChecked(
                SystemProperties.getBoolean(PROP_BATTERY_SAVER_CPU_ENABLE, true));
        mBatterySaverGpuPref.setChecked(
                SystemProperties.getBoolean(PROP_BATTERY_SAVER_GPU_ENABLE, false));

        mLowRamPref.setOnPreferenceChangeListener(this);
        mHeapTrimPref.setOnPreferenceChangeListener(this);
        mLmkPref.setOnPreferenceChangeListener(this);
        mDexoptPref.setOnPreferenceChangeListener(this);
        mZramZstdPref.setOnPreferenceChangeListener(this);
        mBatterySaverCpuPref.setOnPreferenceChangeListener(this);
        mBatterySaverGpuPref.setOnPreferenceChangeListener(this);
    }

    @Override
    public boolean onPreferenceChange(final Preference preference, final Object newValue) {
        final boolean enabled = (Boolean) newValue;
        final String value = enabled ? "1" : "0";

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
