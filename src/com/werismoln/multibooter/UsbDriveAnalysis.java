/******************************************************************************
 * UsbDriveAnalysis.java
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

package com.werismoln.multibooter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;

/**
 * Android side of the drive analysis: opens the USB drive, runs UsbAnalyzer
 * on a background thread with a cancellable progress dialog and shows the
 * result. The drive is only read, never written.
 */
public final class UsbDriveAnalysis {

    private UsbDriveAnalysis() {
    }

    public static void run(final Activity activity, final UsbDevice device) {

        if (device == null) {
            return;
        }

        final boolean[] cancelled = { false };

        final AlertDialog progress =
            new AlertDialog.Builder(activity)
                .setTitle("Analyzing drive")
                .setMessage("Reading the partition table...")
                .setCancelable(false)
                .setNegativeButton(
                    "CANCEL",
                    new DialogInterface.OnClickListener() {

                        @Override
                        public void onClick(DialogInterface d, int which) {
                            cancelled[0] = true;
                        }
                    }
                )
                .show();

        Thread thread = new Thread(
            new Runnable() {

                @Override
                public void run() {

                    UsbAnalyzer.Report report = null;
                    String error = "";

                    final UsbVentoy usb =
                        new UsbVentoy(activity.getApplicationContext());

                    if (!usb.hasPermission(device)) {

                        error = "USB permission is not granted.";

                    } else if (usb.open(device) != UsbVentoy.RESULT_OK) {

                        error =
                            "Could not open the USB drive: " +
                            usb.getLastError();

                    } else {

                        try {

                            report = analyze(activity, usb, progress, cancelled);

                        } finally {

                            usb.close();
                        }
                    }

                    final UsbAnalyzer.Report finalReport = report;
                    final String finalError = error;

                    activity.runOnUiThread(
                        new Runnable() {

                            @Override
                            public void run() {

                                progress.dismiss();

                                if (activity.isFinishing()) {
                                    return;
                                }

                                show(
                                    activity,
                                    finalReport == null
                                        ? finalError
                                        : UsbAnalyzer.describe(finalReport, 60)
                                );
                            }
                        }
                    );
                }
            },
            "UsbAnalysis"
        );

        thread.setDaemon(true);
        thread.start();
    }

    private static UsbAnalyzer.Report analyze(
        final Activity activity,
        final UsbVentoy usb,
        final AlertDialog progress,
        final boolean[] cancelled
    ) {

        final int sector = usb.getBlockSize();

        final long size = usb.getBlockCount() * (long) sector;

        UsbAnalyzer.Device device =
            new UsbAnalyzer.Device() {

                @Override
                public byte[] read(long offset, int length) throws IOException {

                    if (offset % sector != 0 || length % sector != 0) {
                        throw new IOException("Unaligned read.");
                    }

                    byte[] data =
                        usb.readData(offset / sector, length / sector);

                    if (data == null) {
                        throw new IOException(usb.getLastError());
                    }

                    return data;
                }

                @Override
                public long sizeBytes() {
                    return size;
                }

                @Override
                public int sectorSize() {
                    return sector;
                }
            };

        return UsbAnalyzer.analyze(
            device,
            new ExFatReader.Options(),
            new UsbAnalyzer.Control() {

                @Override
                public boolean cancelled() {
                    return cancelled[0];
                }

                @Override
                public void onProgress(final String what) {

                    activity.runOnUiThread(
                        new Runnable() {

                            @Override
                            public void run() {
                                progress.setMessage("Scanning " + what);
                            }
                        }
                    );
                }
            }
        );
    }

    private static void show(Activity activity, String text) {

        ScrollView scroll = new ScrollView(activity);

        TextView view = new TextView(activity);

        float density = activity.getResources().getDisplayMetrics().density;

        view.setText(text);
        view.setTextSize(12);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        view.setPadding(
            (int) (16 * density),
            (int) (8 * density),
            (int) (16 * density),
            (int) (8 * density)
        );

        scroll.addView(view);

        new AlertDialog.Builder(activity)
            .setTitle("Drive analysis")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show();
    }
}
