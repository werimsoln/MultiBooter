/******************************************************************************
 * AppearanceSettings.java
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

import android.widget.LinearLayout;

/** Theme, accent color, background, font size, animations and haptics. */
public final class AppearanceSettings {

    private AppearanceSettings() {
    }

    public static void build(final SettingsPage page) {

        SettingsUi ui = page.ui;

        ui.title(
            page.content,
            "Appearance",
            "Changes are applied to every screen immediately."
        );

        LinearLayout card = ui.card(page.content);

        ui.chipRow(
            card,
            "Theme",
            "Light is a beta: some icons may be hard to see.",
            SettingsDefaults.THEME,
            new String[] {
                SettingsDefaults.THEME_SYSTEM,
                SettingsDefaults.THEME_LIGHT,
                SettingsDefaults.THEME_DARK,
                SettingsDefaults.THEME_AMOLED
            },
            new String[] { "System", "Light", "Dark", "AMOLED" },
            page.rebuildOnChange()
        );

        ui.accentRow(card, page.rebuildOnChange());

        ui.chipRow(
            card,
            "Background",
            null,
            SettingsDefaults.BACKGROUND,
            new String[] {
                SettingsDefaults.BACKGROUND_SOLID,
                SettingsDefaults.BACKGROUND_GRADIENT
            },
            new String[] { "Solid", "Subtle gradient" },
            page.rebuildOnChange()
        );

        LinearLayout text = ui.card(page.content);

        ui.sliderRow(
            text,
            "Font size",
            "Scales the text of the whole app.",
            SettingsDefaults.FONT_SCALE,
            SettingsDefaults.FONT_SCALE_MIN,
            SettingsDefaults.FONT_SCALE_MAX,
            5,
            "%",
            new SettingsUi.OnChanged() {

                @Override
                public void onChanged() {
                    page.host.recreateScreen();
                }
            }
        );

        LinearLayout behavior = ui.card(page.content);

        ui.switchRow(
            behavior,
            "Animations",
            "Screen transitions. Turn off for the fastest response.",
            SettingsDefaults.ANIMATIONS,
            true,
            null
        );

        ui.switchRow(
            behavior,
            "Haptic feedback",
            "A short tick when you change a setting.",
            SettingsDefaults.HAPTICS,
            true,
            null
        );
    }
}
