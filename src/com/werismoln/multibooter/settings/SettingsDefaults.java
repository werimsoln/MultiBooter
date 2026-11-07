/******************************************************************************
 * SettingsDefaults.java
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

/**
 * Every persisted preference key and its default value.
 *
 * Only settings that really change behavior are listed here. A key must
 * not be added before the code that reads it exists.
 */
public final class SettingsDefaults {

    private SettingsDefaults() {
    }

    // ---- Appearance -------------------------------------------------------

    public static final String THEME = "appearance.theme";
    public static final String THEME_SYSTEM = "system";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";
    public static final String THEME_AMOLED = "amoled";
    public static final String THEME_DEFAULT = THEME_DARK;

    public static final String ACCENT = "appearance.accent";
    public static final String ACCENT_DEFAULT = "blue";

    /** Used when ACCENT is "custom", as #RRGGBB. */
    public static final String ACCENT_CUSTOM = "appearance.accent_custom";
    public static final String ACCENT_CUSTOM_DEFAULT = "#2563EB";

    public static final String BACKGROUND = "appearance.background";
    public static final String BACKGROUND_SOLID = "solid";
    public static final String BACKGROUND_GRADIENT = "gradient";

    public static final String FONT_SCALE = "appearance.font_scale";
    public static final int FONT_SCALE_DEFAULT = 100;
    public static final int FONT_SCALE_MIN = 85;
    public static final int FONT_SCALE_MAX = 125;

    public static final String ANIMATIONS = "appearance.animations";
    public static final String HAPTICS = "appearance.haptics";

    // ---- USB --------------------------------------------------------------

    public static final String VERIFY_LEVEL = "usb.verify_level";
    public static final String VERIFY_FULL = "full";
    public static final String VERIFY_PARTIAL = "partial";
    public static final String VERIFY_OFF = "off";

    public static final String SAFE_EJECT_WARNING = "usb.safe_eject_warning";

    // ---- Ventoy -----------------------------------------------------------

    public static final String PARTITION_SCHEME = "ventoy.partition_scheme";
    public static final String SCHEME_MBR = "mbr";
    public static final String SCHEME_GPT = "gpt";

    // ---- Notifications ----------------------------------------------------

    public static final String NOTIFY_PROGRESS = "notify.progress";
    public static final String NOTIFY_COMPLETION = "notify.completion";
    public static final String NOTIFY_ERROR = "notify.error";
    public static final String NOTIFY_PERSISTENT = "notify.persistent";
    public static final String NOTIFY_VIBRATE = "notify.vibrate";

    // ---- Network & PXE ----------------------------------------------------

    public static final String DEFAULT_INTERFACE = "network.default_interface";

    // ---- Developer & Debug ------------------------------------------------

    public static final String DEBUG_MODE = "debug.enabled";

    public static final String LOG_LEVEL = "debug.level";
    public static final String LEVEL_VERBOSE = "verbose";
    public static final String LEVEL_DEBUG = "debug";
    public static final String LEVEL_INFO = "info";
    public static final String LEVEL_WARN = "warn";

    public static final String LOG_MODULE_PREFIX = "debug.module.";

    // ---- Advanced ---------------------------------------------------------

    public static final String EXPERIMENTAL = "advanced.experimental";

    /** Default of a boolean key that has no explicit entry below. */
    public static boolean booleanDefault(String key) {

        if (
            ANIMATIONS.equals(key) ||
            HAPTICS.equals(key) ||
            SAFE_EJECT_WARNING.equals(key) ||
            NOTIFY_PROGRESS.equals(key) ||
            NOTIFY_COMPLETION.equals(key) ||
            NOTIFY_ERROR.equals(key) ||
            NOTIFY_PERSISTENT.equals(key)
        ) {
            return true;
        }

        if (key.startsWith(LOG_MODULE_PREFIX)) {
            return true;
        }

        return false;
    }

    public static String stringDefault(String key) {

        if (THEME.equals(key)) return THEME_DEFAULT;
        if (ACCENT.equals(key)) return ACCENT_DEFAULT;
        if (ACCENT_CUSTOM.equals(key)) return ACCENT_CUSTOM_DEFAULT;
        if (BACKGROUND.equals(key)) return BACKGROUND_SOLID;
        if (VERIFY_LEVEL.equals(key)) return VERIFY_FULL;
        if (PARTITION_SCHEME.equals(key)) return SCHEME_MBR;
        if (LOG_LEVEL.equals(key)) return LEVEL_INFO;

        return "";
    }

    public static int intDefault(String key) {

        if (FONT_SCALE.equals(key)) return FONT_SCALE_DEFAULT;

        return 0;
    }
}
