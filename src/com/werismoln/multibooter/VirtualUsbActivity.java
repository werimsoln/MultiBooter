/******************************************************************************
 * VirtualUsbActivity.java
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

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.StatFs;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.werismoln.multibooter.settings.SettingsUi;
import com.werismoln.multibooter.settings.ThemeManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/**
 * Virtual USB flash drive: the phone shows up as a USB flash drive on a PC.
 *
 * The drive is an image file inside the app's storage. It can hold Ventoy
 * (so the PC can copy ISO files onto it and boot from it), an empty exFAT
 * file system, or nothing at all. It is shared with the PC through the USB
 * Gadget (ConfigFS) mass storage function, so sharing needs root like the
 * other gadget modes.
 */
public class VirtualUsbActivity extends BaseActivity {

    private static final long GIB = 1024L * 1024L * 1024L;

    private static final long[] SIZES_GIB = { 1, 2, 4, 8, 16, 32, 64, 128 };

    private File directory;

    private ScrollView scroll;

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        directory = driveDirectory(this);

        build();
    }

    @Override
    protected void onResume() {

        super.onResume();

        // Sharing may have been stopped from the notification.
        if (scroll != null) {
            build();
        }
    }

    static File driveDirectory(Context context) {

        File dir = context.getExternalFilesDir("virtual-usb");

        if (dir == null) {
            dir = new File(context.getFilesDir(), "virtual-usb");
        }

        if (!dir.exists()) {
            dir.mkdirs();
        }

        return dir;
    }

    // ---------------------------------------------------------------------
    // Screen
    // ---------------------------------------------------------------------

    private void build() {

        SettingsUi ui = new SettingsUi(this);

        ThemeManager.Palette palette = ui.palette();

        scroll = new ScrollView(this);

        scroll.setBackgroundColor(palette.bg);
        scroll.setFillViewport(true);
        scroll.setTag(ThemeManager.SKIP_TAG);

        LinearLayout page = ui.column();

        page.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(24));

        scroll.addView(page);

        ui.title(
            page,
            "Virtual USB drive",
            "Your phone acts as a USB flash drive on a PC. The drive is a " +
            "file on this phone."
        );

        LinearLayout about = ui.card(page);

        ui.info(
            about,
            "How it works",
            "Create a drive with Ventoy (copy ISO files to it from the PC and " +
            "boot from it) or as an empty exFAT drive. Then share it. " +
            "Requires root and kernel ConfigFS support, like USB Gadget."
        );

        UsbGadget.SessionInfo session = UsbGadget.getSession(this);

        String shared =
            session != null && session.active
            ? session.imagePath
            : "";

        if (shared.length() > 0) {

            LinearLayout live = ui.card(page);

            ui.info(
                live,
                "Sharing now",
                new File(shared).getName() +
                (session.readOnly ? " (read-only)" : " (read-write)")
            );

            ui.actionRow(
                live,
                "Stop sharing",
                "Eject the drive on the PC first.",
                new Runnable() {

                    @Override
                    public void run() {
                        stopSharing();
                    }
                }
            );
        }

        ui.section(page, "Your virtual drives");

        File[] files = directory.listFiles();

        if (files == null) {
            files = new File[0];
        }

        Arrays.sort(
            files,
            new Comparator<File>() {

                @Override
                public int compare(File a, File b) {
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            }
        );

        LinearLayout list = ui.card(page);

        boolean any = false;

        for (final File file : files) {

            if (!file.getName().endsWith(".img")) {
                continue;
            }

            any = true;

            boolean isShared = file.getAbsolutePath().equals(shared);

            ui.actionRow(
                list,
                file.getName(),
                UsbAnalyzer.format(file.length()) +
                (isShared ? "  \u2022  shared now" : ""),
                new Runnable() {

                    @Override
                    public void run() {
                        driveMenu(file);
                    }
                }
            );
        }

        if (!any) {

            ui.info(list, "No virtual drives yet", "Create one below.");
        }

        LinearLayout create = ui.card(page);

        ui.actionRow(
            create,
            "Create a new virtual drive",
            "Free space on the phone: " + UsbAnalyzer.format(freeBytes()),
            new Runnable() {

                @Override
                public void run() {
                    createDialog();
                }
            }
        );

        setContentView(scroll);
    }

    private long freeBytes() {

        try {

            return new StatFs(directory.getAbsolutePath()).getAvailableBytes();

        } catch (RuntimeException e) {

            return -1;
        }
    }

    // ---------------------------------------------------------------------
    // Create
    // ---------------------------------------------------------------------

    private void createDialog() {

        final SettingsUi ui = new SettingsUi(this);

        LinearLayout body = ui.column();

        body.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0);

        final EditText name = new EditText(this);

        name.setHint("Name");
        name.setText("virtual-usb");
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT);

        body.addView(name);

        TextView sizeLabel = new TextView(this);

        sizeLabel.setText("Size");
        sizeLabel.setPadding(0, ui.dp(12), 0, 0);

        body.addView(sizeLabel);

        final Spinner size = new Spinner(this);

        String[] labels = new String[SIZES_GIB.length];

        for (int i = 0; i < labels.length; i++) {
            labels[i] = SIZES_GIB[i] + " GiB";
        }

        size.setAdapter(
            new ArrayAdapter<String>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                labels
            )
        );

        size.setSelection(2);

        body.addView(size);

        TextView kindLabel = new TextView(this);

        kindLabel.setText("Content");
        kindLabel.setPadding(0, ui.dp(12), 0, 0);

        body.addView(kindLabel);

        final RadioGroup kinds = new RadioGroup(this);

        final VirtualDisk.Kind[] all = VirtualDisk.Kind.values();

        for (int i = 0; i < all.length; i++) {

            RadioButton radio = new RadioButton(this);

            radio.setId(1000 + i);
            radio.setText(all[i].label);

            kinds.addView(radio);
        }

        kinds.check(1000);

        body.addView(kinds);

        TextView note = new TextView(this);

        note.setTextSize(12);
        note.setPadding(0, ui.dp(8), 0, 0);
        note.setText(
            "The file only takes space as data is copied to the drive. " +
            "Ventoy needs at least 512 MiB."
        );

        body.addView(note);

        ScrollView holder = new ScrollView(this);

        holder.addView(
            body,
            new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        );

        new AlertDialog.Builder(this)
            .setTitle("New virtual drive")
            .setView(holder)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(
                "Create",
                new DialogInterface.OnClickListener() {

                    @Override
                    public void onClick(DialogInterface d, int which) {

                        int index = kinds.getCheckedRadioButtonId() - 1000;

                        startCreate(
                            name.getText().toString(),
                            SIZES_GIB[size.getSelectedItemPosition()] * GIB,
                            all[Math.max(0, index)]
                        );
                    }
                }
            )
            .show();
    }

    private void startCreate(
        String requestedName,
        final long sizeBytes,
        final VirtualDisk.Kind kind
    ) {

        String clean =
            requestedName
                .trim()
                .replaceAll("[^A-Za-z0-9 _.-]", "")
                .replaceAll("\\.img$", "");

        if (clean.length() == 0) {
            clean = "virtual-usb";
        }

        File target = new File(directory, clean + ".img");

        int n = 2;

        while (target.exists()) {
            target = new File(directory, clean + "-" + n++ + ".img");
        }

        final File image = target;

        final boolean[] cancelled = { false };

        final android.widget.ProgressBar bar =
            new android.widget.ProgressBar(
                this,
                null,
                android.R.attr.progressBarStyleHorizontal
            );

        bar.setMax(100);

        final TextView text = new TextView(this);

        text.setText("Starting...");

        LinearLayout body = new LinearLayout(this);

        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(48, 24, 48, 0);
        body.addView(bar);
        body.addView(text);

        final AlertDialog dialog =
            new AlertDialog.Builder(this)
                .setTitle("Creating " + image.getName())
                .setView(body)
                .setCancelable(false)
                .setNegativeButton(
                    "Cancel",
                    new DialogInterface.OnClickListener() {

                        @Override
                        public void onClick(DialogInterface d, int which) {
                            cancelled[0] = true;
                        }
                    }
                )
                .show();

        final VirtualDisk.Assets assets =
            new VirtualDisk.Assets() {

                @Override
                public InputStream open(String assetName) throws IOException {

                    return getAssets().open(assetName);
                }
            };

        Thread thread = new Thread(
            new Runnable() {

                @Override
                public void run() {

                    String error = "";

                    try {

                        VirtualDisk.create(
                            image,
                            sizeBytes,
                            kind,
                            assets,
                            new VirtualDisk.Progress() {

                                @Override
                                public void onProgress(
                                    final int percent,
                                    final String message
                                ) {

                                    runOnUiThread(
                                        new Runnable() {

                                            @Override
                                            public void run() {

                                                bar.setProgress(percent);
                                                text.setText(message);
                                            }
                                        }
                                    );
                                }

                                @Override
                                public boolean cancelled() {
                                    return cancelled[0];
                                }
                            }
                        );

                    } catch (IOException e) {

                        error = String.valueOf(e.getMessage());

                    } catch (RuntimeException e) {

                        error = "Unexpected error: " + e.getMessage();
                    }

                    final String finalError = error;

                    DebugLog.i(
                        DebugLog.Module.GADGET,
                        "Virtual drive " + kind + " " + sizeBytes +
                        " bytes: " + (finalError.length() == 0 ? "created" : finalError)
                    );

                    runOnUiThread(
                        new Runnable() {

                            @Override
                            public void run() {

                                dialog.dismiss();

                                if (isFinishing()) {
                                    return;
                                }

                                if (finalError.length() > 0) {

                                    Toast.makeText(
                                        VirtualUsbActivity.this,
                                        finalError,
                                        Toast.LENGTH_LONG
                                    ).show();
                                }

                                build();
                            }
                        }
                    );
                }
            },
            "VirtualDisk"
        );

        thread.setDaemon(true);
        thread.start();
    }

    // ---------------------------------------------------------------------
    // Share, delete
    // ---------------------------------------------------------------------

    private void driveMenu(final File file) {

        UsbGadget.SessionInfo session = UsbGadget.getSession(this);

        final boolean isShared =
            session != null &&
            session.active &&
            file.getAbsolutePath().equals(session.imagePath);

        String[] items =
            isShared
            ? new String[] { "Stop sharing" }
            : new String[] {
                "Share with a PC (read-write)",
                "Share with a PC (read-only)",
                "Delete"
            };

        new AlertDialog.Builder(this)
            .setTitle(file.getName())
            .setItems(
                items,
                new DialogInterface.OnClickListener() {

                    @Override
                    public void onClick(DialogInterface d, int which) {

                        if (isShared) {
                            stopSharing();
                        } else if (which == 0) {
                            share(file, false);
                        } else if (which == 1) {
                            share(file, true);
                        } else {
                            confirmDelete(file);
                        }
                    }
                }
            )
            .show();
    }

    private void share(final File file, final boolean readOnly) {

        if (UsbGadget.hasSavedActiveSession(this)) {

            new AlertDialog.Builder(this)
                .setTitle("USB Gadget is busy")
                .setMessage(
                    "Another USB Gadget session is running. Stop it first, " +
                    "then share the virtual drive."
                )
                .setNegativeButton("Close", null)
                .setPositiveButton(
                    "Open USB Gadget",
                    new DialogInterface.OnClickListener() {

                        @Override
                        public void onClick(DialogInterface d, int which) {

                            startActivity(
                                new Intent(
                                    VirtualUsbActivity.this,
                                    GadgetActivity.class
                                )
                            );
                        }
                    }
                )
                .show();

            return;
        }

        final Context app = getApplicationContext();

        ModeService.begin(
            app,
            ModeService.MODE_GADGET,
            "Sharing " + file.getName() + "...",
            GadgetActivity.stopHook(app)
        );

        Toast.makeText(this, "Starting...", Toast.LENGTH_SHORT).show();

        Thread thread = new Thread(
            new Runnable() {

                @Override
                public void run() {

                    final boolean ok =
                        UsbGadget.enableMassStorage(
                            app,
                            file.getAbsolutePath(),
                            false,
                            readOnly
                        );

                    final String error = UsbGadget.getLastError();

                    if (ok) {

                        ModeService.progress(
                            app,
                            ModeService.MODE_GADGET,
                            -1,
                            "Sharing " + file.getName() +
                            (readOnly ? " (read-only)" : " (read-write)")
                        );

                    } else {

                        ModeService.end(
                            app,
                            ModeService.MODE_GADGET,
                            false,
                            error.length() == 0
                                ? "Could not share the virtual drive."
                                : error
                        );
                    }

                    runOnUiThread(
                        new Runnable() {

                            @Override
                            public void run() {

                                if (isFinishing()) {
                                    return;
                                }

                                build();

                                new AlertDialog.Builder(VirtualUsbActivity.this)
                                    .setTitle(ok ? "Sharing" : "Could not share")
                                    .setMessage(
                                        ok
                                        ? "Connect the phone to a PC with a USB " +
                                          "cable. The virtual drive appears as a " +
                                          "USB flash drive.\n\nDo not use this " +
                                          "image on the phone while it is shared. " +
                                          "Eject the drive on the PC before you " +
                                          "stop sharing."
                                        : error
                                    )
                                    .setPositiveButton("OK", null)
                                    .show();
                            }
                        }
                    );
                }
            },
            "VirtualShare"
        );

        thread.setDaemon(true);
        thread.start();
    }

    private void stopSharing() {

        final Context app = getApplicationContext();

        Toast.makeText(this, "Stopping...", Toast.LENGTH_SHORT).show();

        Thread thread = new Thread(
            new Runnable() {

                @Override
                public void run() {

                    final boolean ok = UsbGadget.disableMassStorage(app);

                    final String error = UsbGadget.getLastError();

                    if (ok) {

                        ModeService.end(
                            app,
                            ModeService.MODE_GADGET,
                            true,
                            "USB Gadget disabled."
                        );
                    }

                    runOnUiThread(
                        new Runnable() {

                            @Override
                            public void run() {

                                if (isFinishing()) {
                                    return;
                                }

                                if (!ok) {

                                    Toast.makeText(
                                        VirtualUsbActivity.this,
                                        "Could not stop: " + error,
                                        Toast.LENGTH_LONG
                                    ).show();
                                }

                                build();
                            }
                        }
                    );
                }
            },
            "VirtualStop"
        );

        thread.setDaemon(true);
        thread.start();
    }

    private void confirmDelete(final File file) {

        new AlertDialog.Builder(this)
            .setTitle("Delete " + file.getName() + "?")
            .setMessage(
                "Everything on this virtual drive is deleted. This cannot be " +
                "undone."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton(
                "Delete",
                new DialogInterface.OnClickListener() {

                    @Override
                    public void onClick(DialogInterface d, int which) {

                        if (!file.delete()) {

                            Toast.makeText(
                                VirtualUsbActivity.this,
                                "Could not delete the file.",
                                Toast.LENGTH_LONG
                            ).show();
                        }

                        build();
                    }
                }
            )
            .show();
    }
}
