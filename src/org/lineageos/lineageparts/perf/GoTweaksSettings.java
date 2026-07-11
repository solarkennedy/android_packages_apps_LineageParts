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
 * persist.gotweak.* property; device/xiaomi/mithorium-common's
 * init.gotweaks.rc does the actual ro.config.low_ram / dalvik.vm.* /
 * ro.lmk.* / pm.dexopt.* / kgsl-3d0 max_pwrlevel writes.
 *
 * Four of the five are gated behind a reboot prompt: three are backed by
 * ro.* or zygote/lmkd-read-once properties that only take effect on the next
 * boot either way, and dexopt needs a reboot to fully revert when turned
 * back off (its "on" trigger applies live, but there is no live "off"
 * trigger - turning it off just lets the next boot's static defaults take
 * over unmodified). Uniform reboot messaging keeps that asymmetry from being
 * confusing. gpu_clock_cap is the exception: it's a plain sysfs write with a
 * real trigger in both directions, so it applies immediately and skips the
 * reboot prompt entirely.
 *
 * The PepitoLauncher2 entry is informational only, not a toggle: it's
 * already installed and selectable today (device.mk ships it alongside
 * Trebuchet, not as default), and there's no simple flag to flip - Android's
 * default-Home-app assignment is a system Role, not a boolean. Tapping it
 * just opens Settings' Home app picker (ACTION_HOME_SETTINGS).
 */
public class GoTweaksSettings extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String KEY_LOW_RAM = "go_tweak_low_ram";
    private static final String KEY_HEAP_TRIM = "go_tweak_heap_trim";
    private static final String KEY_LMK = "go_tweak_lmk";
    private static final String KEY_DEXOPT = "go_tweak_dexopt";
    private static final String KEY_GPU_CLOCK_CAP = "go_tweak_gpu_clock_cap";
    private static final String KEY_PEPITOLAUNCHER2_INFO = "go_tweak_pepitolauncher2_info";

    private static final String PROP_LOW_RAM = "persist.gotweak.low_ram";
    private static final String PROP_HEAP_TRIM = "persist.gotweak.heap_trim";
    private static final String PROP_LMK = "persist.gotweak.lmk";
    private static final String PROP_DEXOPT = "persist.gotweak.dexopt";
    private static final String PROP_GPU_CLOCK_CAP = "persist.gotweak.gpu_clock_cap";

    private SwitchPreferenceCompat mLowRamPref;
    private SwitchPreferenceCompat mHeapTrimPref;
    private SwitchPreferenceCompat mLmkPref;
    private SwitchPreferenceCompat mDexoptPref;
    private SwitchPreferenceCompat mGpuClockCapPref;

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
        mGpuClockCapPref = prefSet.findPreference(KEY_GPU_CLOCK_CAP);

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
        mGpuClockCapPref.setChecked(SystemProperties.getBoolean(PROP_GPU_CLOCK_CAP, false));

        mLowRamPref.setOnPreferenceChangeListener(this);
        mHeapTrimPref.setOnPreferenceChangeListener(this);
        mLmkPref.setOnPreferenceChangeListener(this);
        mDexoptPref.setOnPreferenceChangeListener(this);
        mGpuClockCapPref.setOnPreferenceChangeListener(this);
    }

    @Override
    public boolean onPreferenceChange(final Preference preference, final Object newValue) {
        final boolean enabled = (Boolean) newValue;
        final String value = enabled ? "1" : "0";

        if (preference == mGpuClockCapPref) {
            // Plain sysfs write via init.gotweaks.rc, live in both
            // directions - no reboot prompt needed.
            SystemProperties.set(PROP_GPU_CLOCK_CAP, value);
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
        } else {
            return false;
        }

        promptReboot();
        return true;
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
