/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.lineageparts.lifemode;

import android.content.Context;
import android.hardware.display.ColorDisplayManager;
import android.os.Bundle;
import android.os.SystemProperties;

import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import org.lineageos.lineageparts.R;
import org.lineageos.lineageparts.SettingsPreferenceFragment;

/**
 * Life Mode's knobs - a reimplementation of the Palm PVG100's headline feature;
 * see PLAN-lifemode.md. Split out of Pepito Tweaks (GoTweaksSettings) into a part
 * of its own, and surfaced in Settings > System alongside it, which is where
 * stock puts Life Mode too. That also gives the QS tile's long-press somewhere to
 * land: SystemUI resolves ACTION_QS_TILE_PREFERENCES against the tile's own
 * package, so XiaomiParts' LifeModeSettingsActivity trampolines into this screen.
 *
 * Everything here is a knob only - they configure what Life Mode does, they do
 * not turn it on; the master switch is the QS tile (LifeModeController in
 * XiaomiParts). All but greyscale need no apply step: the controller reads
 * persist.lifemode.* fresh on every screen-off, so a flip here simply lands the
 * next time Life Mode engages. Greyscale is the exception - it follows the
 * master switch rather than the screen and is instantly visible, so it applies
 * live; see applyGrayscale().
 *
 * Note life_mode_battery_saver stacks with Pepito Tweaks' two Extreme Battery
 * Saver levers rather than duplicating them: it makes Life Mode switch *stock*
 * Battery Saver on for the duration of screen-off, which
 * GotweaksBatterySaverReceiver then hears as it would any other Battery Saver
 * change - so whichever levers are enabled apply too, with nothing extra wired up.
 */
public class LifeModeSettings extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String KEY_RESTRICT_DATA = "life_mode_restrict_data";
    private static final String KEY_BATTERY_SAVER = "life_mode_battery_saver";
    private static final String KEY_WIFI_OFF = "life_mode_wifi_off";
    private static final String KEY_GPS_OFF = "life_mode_gps_off";
    private static final String KEY_BT_OFF = "life_mode_bt_off";
    private static final String KEY_GRAYSCALE = "life_mode_grayscale";

    private static final String PROP_RESTRICT_DATA = "persist.lifemode.restrict_data";
    private static final String PROP_BATTERY_SAVER = "persist.lifemode.battery_saver";
    private static final String PROP_WIFI_OFF = "persist.lifemode.wifi_off";
    private static final String PROP_GPS_OFF = "persist.lifemode.gps_off";
    private static final String PROP_BT_OFF = "persist.lifemode.bt_off";
    private static final String PROP_GRAYSCALE = "persist.lifemode.grayscale";
    private static final String PROP_ENABLED = "persist.lifemode.enabled";

    private SwitchPreferenceCompat mRestrictDataPref;
    private SwitchPreferenceCompat mBatterySaverPref;
    private SwitchPreferenceCompat mWifiOffPref;
    private SwitchPreferenceCompat mGpsOffPref;
    private SwitchPreferenceCompat mBtOffPref;
    private SwitchPreferenceCompat mGrayscalePref;

    @Override
    public void onActivityCreated(final Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        addPreferencesFromResource(R.xml.life_mode_settings);
        requireActivity().getActionBar().setTitle(R.string.life_mode_title);

        final PreferenceScreen prefSet = getPreferenceScreen();

        mRestrictDataPref = prefSet.findPreference(KEY_RESTRICT_DATA);
        mBatterySaverPref = prefSet.findPreference(KEY_BATTERY_SAVER);
        mWifiOffPref = prefSet.findPreference(KEY_WIFI_OFF);
        mGpsOffPref = prefSet.findPreference(KEY_GPS_OFF);
        mBtOffPref = prefSet.findPreference(KEY_BT_OFF);
        mGrayscalePref = prefSet.findPreference(KEY_GRAYSCALE);

        mRestrictDataPref.setChecked(SystemProperties.getBoolean(PROP_RESTRICT_DATA, true));
        mBatterySaverPref.setChecked(SystemProperties.getBoolean(PROP_BATTERY_SAVER, true));
        mWifiOffPref.setChecked(SystemProperties.getBoolean(PROP_WIFI_OFF, false));
        mGpsOffPref.setChecked(SystemProperties.getBoolean(PROP_GPS_OFF, false));
        mBtOffPref.setChecked(SystemProperties.getBoolean(PROP_BT_OFF, false));
        mGrayscalePref.setChecked(SystemProperties.getBoolean(PROP_GRAYSCALE, true));

        mRestrictDataPref.setOnPreferenceChangeListener(this);
        mBatterySaverPref.setOnPreferenceChangeListener(this);
        mWifiOffPref.setOnPreferenceChangeListener(this);
        mGpsOffPref.setOnPreferenceChangeListener(this);
        mBtOffPref.setOnPreferenceChangeListener(this);
        mGrayscalePref.setOnPreferenceChangeListener(this);
    }

    @Override
    public boolean onPreferenceChange(final Preference preference, final Object newValue) {
        final boolean enabled = (Boolean) newValue;
        final String value = enabled ? "1" : "0";

        if (preference == mGrayscalePref) {
            applyGrayscale(enabled);
            return true;
        }

        // The rest are inert until the next screen-off, when LifeModeController
        // reads them fresh - nothing to apply here, and no reboot needed.
        if (preference == mRestrictDataPref) {
            SystemProperties.set(PROP_RESTRICT_DATA, value);
        } else if (preference == mBatterySaverPref) {
            SystemProperties.set(PROP_BATTERY_SAVER, value);
        } else if (preference == mWifiOffPref) {
            SystemProperties.set(PROP_WIFI_OFF, value);
        } else if (preference == mGpsOffPref) {
            SystemProperties.set(PROP_GPS_OFF, value);
        } else if (preference == mBtOffPref) {
            SystemProperties.set(PROP_BT_OFF, value);
        } else {
            return false;
        }

        return true;
    }

    /**
     * The one Life Mode knob that needs an apply step. Greyscale is instantly
     * visible and, unlike the other levers, follows the master switch rather than
     * the screen - so if Life Mode is already on, flipping this has to take effect
     * right now, not at some future screen-off nobody is watching. Set the flag,
     * then recompute the output against the current state.
     *
     * This duplicates LifeModeController.applyGrayscale() (XiaomiParts) rather than
     * calling into it - the two are separate apps. Keep them in sync: the condition
     * is (life mode enabled && greyscale knob on).
     */
    private void applyGrayscale(final boolean grayscaleEnabled) {
        SystemProperties.set(PROP_GRAYSCALE, grayscaleEnabled ? "1" : "0");

        final Context context = getContext();
        final ColorDisplayManager cdm = context == null
                ? null : context.getSystemService(ColorDisplayManager.class);
        if (cdm == null) {
            return;
        }
        final boolean grey = grayscaleEnabled
                && SystemProperties.getBoolean(PROP_ENABLED, false);
        cdm.setSaturationLevel(grey ? 0 : 100);
    }
}
