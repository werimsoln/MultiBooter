/******************************************************************************
 * AdvancedSettings.java
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
import android.widget.LinearLayout;
import android.widget.Toast;

import com.werismoln.multibooter.ModeNames;
import com.werismoln.multibooter.OperationHistory;

import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Operation history, temporary files and resetting the settings. */
public final class AdvancedSettings {

    private AdvancedSettings() {
    }

    public static void build(final SettingsPage page) {

        final SettingsUi ui = page.ui;

        ui.title(
            page.content,
            "Advanced",
            "Maintenance tools."
        );

        LinearLayout card = ui.card(page.content);

        List<OperationHistory.Entry> entries =
            OperationHistory.list(page.activity);

        ui.actionRow(
            card,
            "Operation history",
            entries.isEmpty()
            ? "No operations yet."
            : "Last " + entries.size() + " operations and their results.",
            new Runnable() {

                @Override
                public void run() {
                    showHistory(page);
                }
            }
        );

        ui.actionRow(
            card,
            "Clear operation history",
            null,
            new Runnable() {

                @Override
                public void run() {

                    ui.confirm(
                        "Clear history?",
                        "The list of past operations will be deleted.",
                        "Clear",
                        new Runnable() {

                            @Override
                            public void run() {

                                OperationHistory.clear(page.activity);

                                page.host.rebuild();
                            }
                        }
                    );
                }
            }
        );

        final File cache = page.activity.getCacheDir();

        ui.actionRow(
            card,
            "Cache management",
            String.format(
                Locale.US,
                "Temporary files: %.1f MiB. Tap to delete them.",
                sizeOf(cache) / (1024.0 * 1024.0)
            ),
            new Runnable() {

                @Override
                public void run() {

                    ui.confirm(
                        "Delete temporary files?",
                        "Only cached copies are removed. Your settings and " +
                        "images are not touched.",
                        "Delete",
                        new Runnable() {

                            @Override
                            public void run() {

                                deleteChildren(cache);

                                Toast.makeText(
                                    page.activity,
                                    "Temporary files deleted.",
                                    Toast.LENGTH_SHORT
                                ).show();

                                page.host.rebuild();
                            }
                        }
                    );
                }
            }
        );

        LinearLayout reset = ui.card(page.content);

        ui.actionRow(
            reset,
            "Reset settings",
            "Return every setting on these screens to its default. " +
            "Permissions, logs and history are not changed.",
            new Runnable() {

                @Override
                public void run() {

                    ui.confirm(
                        "Reset settings?",
                        "All settings go back to their defaults.",
                        "Reset",
                        new Runnable() {

                            @Override
                            public void run() {

                                page.settings.resetAll();

                                page.host.recreateScreen();
                            }
                        }
                    );
                }
            }
        );
    }

    private static void showHistory(SettingsPage page) {

        List<OperationHistory.Entry> entries =
            OperationHistory.list(page.activity);

        StringBuilder text = new StringBuilder();

        DateFormat format =
            DateFormat.getDateTimeInstance(
                DateFormat.SHORT,
                DateFormat.SHORT
            );

        for (OperationHistory.Entry entry : entries) {

            text.append(format.format(new Date(entry.time)))
                .append("  ")
                .append(ModeNames.of(entry.mode))
                .append("  ")
                .append(entry.success ? "OK" : "FAILED")
                .append('\n');

            if (entry.message.length() > 0) {
                text.append("  ").append(entry.message).append('\n');
            }

            text.append('\n');
        }

        if (text.length() == 0) {
            text.append("No operations yet.\n");
        }

        page.ui.textDialog("Operation history", text.toString())
            .setPositiveButton("Close", null)
            .show();
    }

    private static long sizeOf(File file) {

        if (file == null || !file.exists()) {
            return 0;
        }

        if (file.isFile()) {
            return file.length();
        }

        long total = 0;

        File[] children = file.listFiles();

        if (children != null) {

            for (File child : children) {
                total += sizeOf(child);
            }
        }

        return total;
    }

    private static void deleteChildren(File dir) {

        File[] children = dir.listFiles();

        if (children == null) {
            return;
        }

        for (File child : children) {

            if (child.isDirectory()) {
                deleteChildren(child);
            }

            child.delete();
        }
    }
}
