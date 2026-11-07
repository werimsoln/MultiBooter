/******************************************************************************
 * DebugSettings.java
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
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.werismoln.multibooter.DebugLog;
import com.werismoln.multibooter.DiagnosticReport;

import java.util.List;
import java.util.Locale;

/** Debug mode, log level, diagnostic modules and the diagnostic tools. */
public final class DebugSettings {

    private DebugSettings() {
    }

    public static void build(final SettingsPage page) {

        final SettingsUi ui = page.ui;

        DebugLog.init(page.activity);

        ui.title(
            page.content,
            "Developer & Debug",
            "Detailed logs are only recorded while Debug Mode is on."
        );

        LinearLayout mode = ui.card(page.content);

        ui.switchRow(
            mode,
            "Debug Mode",
            "Enable advanced diagnostic features. Sensitive values (device " +
            "serials, addresses, file names) are masked in the log.",
            SettingsDefaults.DEBUG_MODE,
            true,
            page.rebuildOnChange()
        );

        boolean on = DebugLog.isEnabled();

        LinearLayout levels = ui.card(page.content);

        ui.chipRow(
            levels,
            "Logging level",
            on ? null : "Turn on Debug Mode to change this.",
            SettingsDefaults.LOG_LEVEL,
            new String[] {
                SettingsDefaults.LEVEL_VERBOSE,
                SettingsDefaults.LEVEL_DEBUG,
                SettingsDefaults.LEVEL_INFO,
                SettingsDefaults.LEVEL_WARN
            },
            new String[] { "Verbose", "Debug", "Info", "Warn/Error" },
            null
        );

        ui.section(page.content, "Diagnostic modules");

        LinearLayout modules = ui.card(page.content);

        for (DebugLog.Module module : DebugLog.Module.values()) {

            ui.switchRow(
                modules,
                module.label + " logging",
                null,
                module.settingsKey(),
                on,
                null
            );
        }

        ui.info(
            modules,
            "Native (C) libraries",
            "They log to Android logcat and are not part of this log."
        );

        ui.section(page.content, "Diagnostic tools");

        LinearLayout tools = ui.card(page.content);

        ui.info(
            tools,
            "Log size",
            String.format(
                Locale.US,
                "%.1f KiB (rotated automatically, 3 files)",
                DebugLog.sizeBytes(page.activity) / 1024.0
            )
        );

        ui.actionRow(
            tools,
            "View Logs",
            "Show the most recent lines.",
            new Runnable() {

                @Override
                public void run() {
                    viewLogs(page);
                }
            }
        );

        ui.actionRow(
            tools,
            "Export Logs",
            "Save the log as a text file.",
            new Runnable() {

                @Override
                public void run() {

                    String log = DebugLog.readAll(page.activity);

                    if (log.length() == 0) {

                        Toast.makeText(
                            page.activity,
                            "The log is empty.",
                            Toast.LENGTH_SHORT
                        ).show();

                        return;
                    }

                    page.host.exportText("multibooter-log.txt", log);
                }
            }
        );

        ui.actionRow(
            tools,
            "Clear Logs",
            "Delete every log file.",
            new Runnable() {

                @Override
                public void run() {

                    ui.confirm(
                        "Clear logs?",
                        "All recorded log lines will be deleted.",
                        "Clear",
                        new Runnable() {

                            @Override
                            public void run() {

                                DebugLog.clear(page.activity);

                                page.host.rebuild();
                            }
                        }
                    );
                }
            }
        );

        ui.actionRow(
            tools,
            "Generate Diagnostic Report",
            "Shows the report first. Nothing is sent until you choose to.",
            new Runnable() {

                @Override
                public void run() {
                    showReport(page);
                }
            }
        );

        ui.actionRow(
            tools,
            "System Information",
            null,
            new Runnable() {

                @Override
                public void run() {

                    ui.textDialog(
                        "System Information",
                        DiagnosticReport.systemInfo(page.activity)
                    ).setPositiveButton("Close", null).show();
                }
            }
        );

        ui.actionRow(
            tools,
            "USB Device Information",
            "Vendor/product IDs and interfaces. Serial numbers are not shown.",
            new Runnable() {

                @Override
                public void run() {

                    ui.textDialog(
                        "USB Device Information",
                        DiagnosticReport.usbInfo(page.activity)
                    ).setPositiveButton("Close", null).show();
                }
            }
        );
    }

    private static void viewLogs(SettingsPage page) {

        List<String> lines = DebugLog.tail(page.activity, 300);

        StringBuilder text = new StringBuilder();

        for (String line : lines) {
            text.append(line).append('\n');
        }

        if (text.length() == 0) {
            text.append("The log is empty.\n");
        }

        page.ui.textDialog(
            "Log (last " + lines.size() + " lines)",
            text.toString()
        ).setPositiveButton("Close", null).show();
    }

    private static void showReport(final SettingsPage page) {

        final String report = DiagnosticReport.build(page.activity);

        page.ui.textDialog("Diagnostic report", report)
            .setNegativeButton("Close", null)
            .setNeutralButton(
                "Copy",
                new DialogInterface.OnClickListener() {

                    @Override
                    public void onClick(DialogInterface d, int which) {

                        ClipboardManager clipboard =
                            (ClipboardManager)
                            page.activity.getSystemService(
                                Context.CLIPBOARD_SERVICE
                            );

                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("MultiBooter report", report)
                        );

                        Toast.makeText(
                            page.activity,
                            "Copied.",
                            Toast.LENGTH_SHORT
                        ).show();
                    }
                }
            )
            .setPositiveButton(
                "Share",
                new DialogInterface.OnClickListener() {

                    @Override
                    public void onClick(DialogInterface d, int which) {

                        Intent send = new Intent(Intent.ACTION_SEND);

                        send.setType("text/plain");
                        send.putExtra(
                            Intent.EXTRA_SUBJECT,
                            "MultiBooter diagnostic report"
                        );
                        send.putExtra(Intent.EXTRA_TEXT, report);

                        page.activity.startActivity(
                            Intent.createChooser(send, "Share report")
                        );
                    }
                }
            )
            .show();
    }
}
