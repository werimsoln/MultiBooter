/******************************************************************************
 * NetworkSettings.java
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

import com.werismoln.multibooter.DebugLog;

/** TFTP / PXE options that exist today. */
public final class NetworkSettings {

    private NetworkSettings() {
    }

    public static void build(SettingsPage page) {

        SettingsUi ui = page.ui;

        ui.title(
            page.content,
            "Network & PXE",
            "The TFTP / PXE server itself is configured on its own screen."
        );

        LinearLayout card = ui.card(page.content);

        ui.switchRow(
            card,
            "TFTP / PXE logging",
            "Write TFTP and PXE events to the debug log. Needs Debug Mode " +
            "(Developer & Debug).",
            SettingsDefaults.LOG_MODULE_PREFIX + DebugLog.Module.TFTP.id,
            true,
            null
        );

        ui.info(
            card,
            "Interface, boot file and address range",
            "Chosen when you start the server and remembered for next time."
        );
    }
}
