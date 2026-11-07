/******************************************************************************
 * VentoySettings.java
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

import com.werismoln.multibooter.VentoyBundle;

/** Defaults for the Ventoy installer. */
public final class VentoySettings {

    private VentoySettings() {
    }

    public static void build(SettingsPage page) {

        SettingsUi ui = page.ui;

        ui.title(
            page.content,
            "Ventoy",
            "Defaults used on the Ventoy install screen."
        );

                LinearLayout card = ui.card(page.content);

        String bundled = VentoyBundle.version(page.activity);

        ui.info(
            card,
            "Ventoy version in this app",
            bundled.length() == 0 ? "Unknown" : bundled
        );


        ui.chipRow(
            card,
            "Partition style",
            "MBR works on almost every PC (BIOS and UEFI). GPT is for UEFI " +
            "PCs; some old BIOS PCs cannot boot it. When a drive already " +
            "has Ventoy, its style is selected automatically.",
            SettingsDefaults.PARTITION_SCHEME,
            new String[] {
                SettingsDefaults.SCHEME_MBR,
                SettingsDefaults.SCHEME_GPT
            },
            new String[] { "MBR", "GPT" },
            null
        );
    }
}
