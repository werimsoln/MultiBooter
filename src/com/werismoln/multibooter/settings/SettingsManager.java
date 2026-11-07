/******************************************************************************
 * SettingsManager.java
 *
 * Copyright (c) 2026, Volkan Belek <vlkanblek@gmail.com>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.werismoln.multibooter.settings;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;
import java.util.TreeMap;

/**
 * Typed access to the app preferences (SharedPreferences, no AndroidX).
 *
 * System permissions are NOT stored here. They are always read from the real
 * Android state so the UI never shows a permission as granted when it is not.
 */
public final class SettingsManager {

    private static final String FILE = "multibooter_settings";

    private static SettingsManager instance;

    /** Bumped whenever something that changes how screens look is written. */
    private static volatile int appearanceVersion = 0;

    private final SharedPreferences prefs;

    private SettingsManager(Context context) {

        prefs =
            context
                .getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static synchronized SettingsManager get(Context context) {

        if (instance == null) {
            instance = new SettingsManager(context);
        }

        return instance;
    }

    /** Activities re-create themselves when this number changes. */
    public static int appearanceVersion() {

        return appearanceVersion;
    }

    public String getString(String key) {

        return prefs.getString(key, SettingsDefaults.stringDefault(key));
    }

    public boolean getBoolean(String key) {

        return prefs.getBoolean(key, SettingsDefaults.booleanDefault(key));
    }

    public int getInt(String key) {

        return prefs.getInt(key, SettingsDefaults.intDefault(key));
    }

    public void setString(String key, String value) {

        prefs.edit().putString(key, value).apply();
        touch(key);
    }

    public void setBoolean(String key, boolean value) {

        prefs.edit().putBoolean(key, value).apply();
        touch(key);
    }

    public void setInt(String key, int value) {

        prefs.edit().putInt(key, value).apply();
        touch(key);
    }

    /** Removes every stored value, so every setting is back to its default. */
    public void resetAll() {

        prefs.edit().clear().apply();
        appearanceVersion++;
    }

    /** All stored (non-default) values, for the diagnostic report. */
    public Map<String, Object> dump() {

        return new TreeMap<String, Object>(prefs.getAll());
    }

    private static void touch(String key) {

        if (key.startsWith("appearance.")) {
            appearanceVersion++;
        }
    }
}
