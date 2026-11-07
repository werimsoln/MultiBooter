/******************************************************************************
 * NotificationSettings.java
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

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.widget.LinearLayout;

/** What the running-mode and result notifications show. */
public final class NotificationSettings {

    private NotificationSettings() {
    }

    public static void build(final SettingsPage page) {

        SettingsUi ui = page.ui;

        ui.title(
            page.content,
            "Notifications",
            "Choose how much a running mode tells you."
        );

        NotificationManager manager =
            (NotificationManager)
            page.activity.getSystemService(Context.NOTIFICATION_SERVICE);

        boolean allowed =
            manager != null && manager.areNotificationsEnabled();

        LinearLayout status = ui.card(page.content);

        ui.info(
            status,
            "Android notification permission",
            allowed
            ? "Allowed"
            : "Not allowed. Modes keep running, but nothing is shown."
        );

        ui.actionRow(
            status,
            "Open notification settings",
            "Change the permission in Android settings.",
            new Runnable() {

                @Override
                public void run() {

                    Intent intent =
                        new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);

                    intent.putExtra(
                        Settings.EXTRA_APP_PACKAGE,
                        page.activity.getPackageName()
                    );

                    page.activity.startActivity(intent);
                }
            }
        );

        LinearLayout card = ui.card(page.content);

        ui.switchRow(
            card,
            "Progress notifications",
            "Show the progress bar and percentage.",
            SettingsDefaults.NOTIFY_PROGRESS,
            true,
            null
        );

        ui.switchRow(
            card,
            "Completion notifications",
            "Tell me when ISO writing or Ventoy installation finishes.",
            SettingsDefaults.NOTIFY_COMPLETION,
            true,
            null
        );

        ui.switchRow(
            card,
            "Error notifications",
            "Tell me when ISO writing or Ventoy installation fails.",
            SettingsDefaults.NOTIFY_ERROR,
            true,
            null
        );

        ui.switchRow(
            card,
            "Persistent notification",
            "Keep the notification of a running mode from being swiped away.",
            SettingsDefaults.NOTIFY_PERSISTENT,
            true,
            null
        );

        ui.switchRow(
            card,
            "Vibrate on completion",
            "Vibrate when a result notification is shown. Takes effect for " +
            "new results.",
            SettingsDefaults.NOTIFY_VIBRATE,
            true,
            null
        );
    }
}
