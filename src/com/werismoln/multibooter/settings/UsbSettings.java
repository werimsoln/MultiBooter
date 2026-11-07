/******************************************************************************
 * UsbSettings.java
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

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.widget.LinearLayout;

/** Write verification and USB safety options. */
public final class UsbSettings {

    private UsbSettings() {
    }

    public static void build(final SettingsPage page) {

        SettingsUi ui = page.ui;

        ui.title(
            page.content,
            "USB Configuration",
            "How the data written to a USB drive is checked."
        );

        LinearLayout card = ui.card(page.content);

        ui.chipRow(
            card,
            "Write verification",
            "Full reads everything back and compares SHA-256. Partial checks the " +
            "start, the end and spread-out samples (faster). Disabled does not " +
            "check anything.",
            SettingsDefaults.VERIFY_LEVEL,
            new String[] {
                SettingsDefaults.VERIFY_FULL,
                SettingsDefaults.VERIFY_PARTIAL,
                SettingsDefaults.VERIFY_OFF
            },
            new String[] { "Full", "Partial", "Disabled" },
            new SettingsUi.OnChanged() {

                @Override
                public void onChanged() {

                    if (
                        SettingsDefaults.VERIFY_OFF.equals(
                            page.settings.getString(
                                SettingsDefaults.VERIFY_LEVEL
                            )
                        )
                    ) {
                        warnDisabled(page);
                    }

                    page.host.rebuild();
                }
            }
        );

        ui.switchRow(
            card,
            "Safe eject warning",
            "Reminds you to remove the drive safely after writing.",
            SettingsDefaults.SAFE_EJECT_WARNING,
            true,
            null
        );
    }

    private static void warnDisabled(final SettingsPage page) {

        new AlertDialog.Builder(page.activity)
            .setTitle("Verification is off")
            .setMessage(
                "Without verification a damaged or fake USB drive can look " +
                "like a successful write and then fail to boot. Keep " +
                "verification on unless you need the speed."
            )
            .setNegativeButton(
                "Use Full",
                new DialogInterface.OnClickListener() {

                    @Override
                    public void onClick(DialogInterface d, int which) {

                        page.settings.setString(
                            SettingsDefaults.VERIFY_LEVEL,
                            SettingsDefaults.VERIFY_FULL
                        );

                        page.host.rebuild();
                    }
                }
            )
            .setPositiveButton("Keep disabled", null)
            .show();
    }
}
