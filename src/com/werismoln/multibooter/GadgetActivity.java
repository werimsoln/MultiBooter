package com.werismoln.multibooter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;

public class GadgetActivity extends Activity {

    private static final int REQUEST_IMAGE =
        7301;

    private EditText imagePathEdit;
    private TextView imageInfo;
    private TextView supportStatus;
    private RadioGroup modeGroup;
    private RadioButton cdromMode;
    private RadioButton diskMode;
    private CheckBox readOnly;
    private ProgressBar setupProgress;
    private Button startButton;

    private TextView activeStatus;

    private boolean operationRunning =
        false;

    private boolean activePage =
        false;

    @Override
    protected void onCreate(
        Bundle savedInstanceState
    ) {

        super.onCreate(
            savedInstanceState
        );

        if (
            UsbGadget.hasSavedActiveSession(
                this
            )
        ) {

            showActivePage();

        } else {

            showSetupPage();
        }
    }

    private void showSetupPage() {

        activePage =
            false;

        setContentView(
            R.layout.gadget_setup
        );

        TextView backTop =
            (TextView)
            findViewById(
                R.id.gadget_back_top
            );

        imagePathEdit =
            (EditText)
            findViewById(
                R.id.gadget_image_path
            );

        imageInfo =
            (TextView)
            findViewById(
                R.id.gadget_image_info
            );

        supportStatus =
            (TextView)
            findViewById(
                R.id.gadget_support_status
            );

        modeGroup =
            (RadioGroup)
            findViewById(
                R.id.gadget_mode_group
            );

        cdromMode =
            (RadioButton)
            findViewById(
                R.id.gadget_mode_cdrom
            );

        diskMode =
            (RadioButton)
            findViewById(
                R.id.gadget_mode_disk
            );

        readOnly =
            (CheckBox)
            findViewById(
                R.id.gadget_read_only
            );

        setupProgress =
            (ProgressBar)
            findViewById(
                R.id.gadget_setup_progress
            );

        Button selectImage =
            (Button)
            findViewById(
                R.id.gadget_select_image
            );

        Button checkSupport =
            (Button)
            findViewById(
                R.id.gadget_check_support
            );

        Button cancel =
            (Button)
            findViewById(
                R.id.gadget_cancel
            );

        startButton =
            (Button)
            findViewById(
                R.id.gadget_start
            );

        addTouchFeedback(
            selectImage
        );

        addTouchFeedback(
            checkSupport
        );

        addTouchFeedback(
            cancel
        );

        addTouchFeedback(
            startButton
        );

        backTop.setOnClickListener(
            new View.OnClickListener() {

                @Override
                public void onClick(
                    View v
                ) {

                    if (!operationRunning) {
                        finish();
                    }
                }
            }
        );

        cancel.setOnClickListener(
            new View.OnClickListener() {

                @Override
                public void onClick(
                    View v
                ) {

                    if (!operationRunning) {
                        finish();
                    }
                }
            }
        );

        selectImage.setOnClickListener(
            new View.OnClickListener() {

                @Override
                public void onClick(
                    View v
                ) {

                    openImagePicker();
                }
            }
        );

        checkSupport.setOnClickListener(
            new View.OnClickListener() {

                @Override
                public void onClick(
                    View v
                ) {

                    probeSupport();
                }
            }
        );

        startButton.setOnClickListener(
            new View.OnClickListener() {

                @Override
                public void onClick(
                    View v
                ) {

                    confirmStart();
                }
            }
        );

        modeGroup.setOnCheckedChangeListener(
            new RadioGroup.OnCheckedChangeListener() {

                @Override
                public void onCheckedChanged(
                    RadioGroup group,
                    int checkedId
                ) {

                    updateModeUi();
                }
            }
        );

        cdromMode.setChecked(
            true
        );

        readOnly.setChecked(
            true
        );

        updateModeUi();

        probeSupport();
    }

    private void openImagePicker() {

        Intent intent =
            new Intent(
                Intent.ACTION_OPEN_DOCUMENT
            );

        intent.addCategory(
            Intent.CATEGORY_OPENABLE
        );

        intent.setType(
            "*/*"
        );

        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION |
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        );

        try {

            startActivityForResult(
                intent,
                REQUEST_IMAGE
            );

        } catch (Throwable e) {

            Toast.makeText(
                this,
                "Could not open Android file picker.",
                Toast.LENGTH_LONG
            ).show();
        }
    }

    @Override
    protected void onActivityResult(
        int requestCode,
        int resultCode,
        Intent data
    ) {

        super.onActivityResult(
            requestCode,
            resultCode,
            data
        );

        if (
            requestCode !=
                REQUEST_IMAGE ||
            resultCode !=
                RESULT_OK ||
            data == null ||
            data.getData() == null
        ) {

            return;
        }

        Uri uri =
            data.getData();

        try {

            int flags =
                data.getFlags() &
                Intent.FLAG_GRANT_READ_URI_PERMISSION;

            getContentResolver()
                .takePersistableUriPermission(
                    uri,
                    flags
                );

        } catch (Throwable ignored) {
        }

        String path =
            resolveLocalPath(
                uri
            );

        if (
            path.length() == 0
        ) {

            new AlertDialog.Builder(
                this
            )
                .setTitle(
                    "A real file path is required"
                )
                .setMessage(
                    "ConfigFS Mass Storage cannot use a content:// URI as its backing file. Select an ISO/IMG stored in shared/internal storage, or enter its absolute path manually."
                )
                .setPositiveButton(
                    "OK",
                    null
                )
                .show();

            return;
        }

        imagePathEdit.setText(
            path
        );

        updateImageInfo(
            path
        );
    }

    private void updateImageInfo(
        String path
    ) {

        if (
            imageInfo == null
        ) {

            return;
        }

        if (
            path == null ||
            path.trim().length() == 0
        ) {

            imageInfo.setText(
                "No backing image selected."
            );

            return;
        }

        File file =
            new File(
                path.trim()
            );

        if (
            !file.isFile()
        ) {

            imageInfo.setText(
                "File not found at this path."
            );

            return;
        }

        imageInfo.setText(
            file.getName() +
            "\n" +
            formatBytes(
                file.length()
            )
        );
    }

    private void updateModeUi() {

        if (
            cdromMode == null ||
            readOnly == null
        ) {

            return;
        }

        boolean cdrom =
            cdromMode.isChecked();

        if (cdrom) {

            readOnly.setChecked(
                true
            );

            readOnly.setEnabled(
                false
            );

            readOnly.setAlpha(
                0.65f
            );

        } else {

            readOnly.setEnabled(
                true
            );

            readOnly.setAlpha(
                1.0f
            );
        }
    }

    private void probeSupport() {

        if (operationRunning) {
            return;
        }

        operationRunning =
            true;

        setSetupBusy(
            true
        );

        supportStatus.setText(
            "Checking root and ConfigFS..."
        );

        new Thread(
            new Runnable() {

                @Override
                public void run() {

                    final UsbGadget.ProbeInfo probe =
                        UsbGadget.probe();

                    runOnUiThread(
                        new Runnable() {

                            @Override
                            public void run() {

                                operationRunning =
                                    false;

                                setSetupBusy(
                                    false
                                );

                                StringBuilder status =
                                    new StringBuilder();

                                status.append(
                                    probe.rootGranted
                                        ? "Root: OK"
                                        : "Root: unavailable"
                                );

                                status.append(
                                    "\nConfigFS: "
                                );

                                status.append(
                                    probe.configFsFound
                                        ? "found"
                                        : "not found"
                                );

                                status.append(
                                    "\nMass Storage function: "
                                );

                                status.append(
                                    probe.massStorageSupported
                                        ? "supported"
                                        : "not available"
                                );

                                if (
                                    probe.gadgetRoot.length() >
                                    0
                                ) {

                                    status.append(
                                        "\nGadget: "
                                    );

                                    status.append(
                                        probe.gadgetRoot
                                    );
                                }

                                if (
                                    probe.currentUdc.length() >
                                    0
                                ) {

                                    status.append(
                                        "\nUDC: "
                                    );

                                    status.append(
                                        probe.currentUdc
                                    );
                                }

                                if (
                                    probe.message.length() >
                                    0
                                ) {

                                    status.append(
                                        "\n"
                                    );

                                    status.append(
                                        probe.message
                                    );
                                }

                                supportStatus.setText(
                                    status.toString()
                                );

                                boolean ready =
                                    probe.rootGranted &&
                                    probe.configFsFound &&
                                    probe.massStorageSupported;

                                startButton.setEnabled(
                                    ready
                                );

                                startButton.setAlpha(
                                    ready
                                        ? 1.0f
                                        : 0.45f
                                );
                            }
                        }
                    );
                }
            },
            "GadgetProbe"
        ).start();
    }

    private void confirmStart() {

        if (operationRunning) {
            return;
        }

        final String image =
            imagePathEdit.getText()
                .toString()
                .trim();

        updateImageInfo(
            image
        );

        File file =
            new File(
                image
            );

        if (
            !file.isFile()
        ) {

            Toast.makeText(
                this,
                "Select a readable ISO/IMG backing file first.",
                Toast.LENGTH_LONG
            ).show();

            return;
        }

        final boolean cdrom =
            cdromMode.isChecked();

        final boolean ro =
            cdrom ||
            readOnly.isChecked();

        String mode =
            cdrom
                ? "Virtual CD-ROM"
                : "USB Mass Storage disk";

        new AlertDialog.Builder(
            this
        )
            .setTitle(
                "Enable USB Gadget?"
            )
            .setMessage(
                "Backing file:\n" +
                file.getAbsolutePath() +
                "\n\nMode: " +
                mode +
                "\nRead-only: " +
                (
                    ro
                        ? "yes"
                        : "no"
                ) +
                "\n\nThe phone's USB device controller will be briefly unbound and rebound. Keep the phone connected to the target PC."
            )
            .setNegativeButton(
                "CANCEL",
                null
            )
            .setPositiveButton(
                "ENABLE",
                new DialogInterface.OnClickListener() {

                    @Override
                    public void onClick(
                        DialogInterface dialog,
                        int which
                    ) {

                        performStart(
                            image,
                            cdrom,
                            ro
                        );
                    }
                }
            )
            .show();
    }

    private void performStart(
        final String image,
        final boolean cdrom,
        final boolean ro
    ) {

        operationRunning =
            true;

        setSetupBusy(
            true
        );

        new Thread(
            new Runnable() {

                @Override
                public void run() {

                    final boolean success =
                        UsbGadget.enableMassStorage(
                            GadgetActivity.this,
                            image,
                            cdrom,
                            ro
                        );

                    final String error =
                        UsbGadget.getLastError();

                    runOnUiThread(
                        new Runnable() {

                            @Override
                            public void run() {

                                operationRunning =
                                    false;

                                setSetupBusy(
                                    false
                                );

                                if (success) {

                                    showActivePage();

                                    Toast.makeText(
                                        GadgetActivity.this,
                                        "USB Mass Storage gadget enabled.",
                                        Toast.LENGTH_LONG
                                    ).show();

                                } else {

                                    Toast.makeText(
                                        GadgetActivity.this,
                                        error.length() == 0
                                            ? "Could not enable USB Gadget."
                                            : error,
                                        Toast.LENGTH_LONG
                                    ).show();

                                    probeSupport();
                                }
                            }
                        }
                    );
                }
            },
            "GadgetEnable"
        ).start();
    }

    private void showActivePage() {

        activePage =
            true;

        setContentView(
            R.layout.gadget_active
        );

        TextView backTop =
            (TextView)
            findViewById(
                R.id.gadget_active_back_top
            );

        activeStatus =
            (TextView)
            findViewById(
                R.id.gadget_active_status
            );

        TextView image =
            (TextView)
            findViewById(
                R.id.gadget_active_image
            );

        TextView mode =
            (TextView)
            findViewById(
                R.id.gadget_active_mode
            );

        TextView gadget =
            (TextView)
            findViewById(
                R.id.gadget_active_root
            );

        TextView config =
            (TextView)
            findViewById(
                R.id.gadget_active_config
            );

        TextView udc =
            (TextView)
            findViewById(
                R.id.gadget_active_udc
            );

        Button leave =
            (Button)
            findViewById(
                R.id.gadget_leave_active
            );

        Button stop =
            (Button)
            findViewById(
                R.id.gadget_stop
            );

        addTouchFeedback(
            leave
        );

        addTouchFeedback(
            stop
        );

        final UsbGadget.SessionInfo session =
            UsbGadget.getSession(
                this
            );

        image.setText(
            session.imagePath
        );

        mode.setText(
            (
                session.cdRom
                    ? "Virtual CD-ROM"
                    : "USB Mass Storage disk"
            ) +
            "\nRead-only: " +
            (
                session.readOnly
                    ? "yes"
                    : "no"
            )
        );

        gadget.setText(
            session.gadgetRoot
        );

        config.setText(
            session.configPath
        );

        udc.setText(
            session.boundUdc
        );

        View.OnClickListener leaveListener =
            new View.OnClickListener() {

                @Override
                public void onClick(
                    View v
                ) {

                    if (!operationRunning) {
                        confirmLeave();
                    }
                }
            };

        backTop.setOnClickListener(
            leaveListener
        );

        leave.setOnClickListener(
            leaveListener
        );

        stop.setOnClickListener(
            new View.OnClickListener() {

                @Override
                public void onClick(
                    View v
                ) {

                    if (!operationRunning) {
                        stopGadget();
                    }
                }
            }
        );

        verifyActiveState();
    }

    private void verifyActiveState() {

        if (
            activeStatus == null
        ) {

            return;
        }

        activeStatus.setText(
            "CHECKING..."
        );

        new Thread(
            new Runnable() {

                @Override
                public void run() {

                    final boolean active =
                        UsbGadget.isMassStorageActive(
                            GadgetActivity.this
                        );

                    runOnUiThread(
                        new Runnable() {

                            @Override
                            public void run() {

                                if (
                                    activeStatus ==
                                    null
                                ) {

                                    return;
                                }

                                activeStatus.setText(
                                    active
                                        ? "ACTIVE"
                                        : "INACTIVE / CONFIG LOST"
                                );

                                activeStatus.setAlpha(
                                    active
                                        ? 1.0f
                                        : 0.7f
                                );
                            }
                        }
                    );
                }
            },
            "GadgetState"
        ).start();
    }

    private void stopGadget() {

        operationRunning =
            true;

        new Thread(
            new Runnable() {

                @Override
                public void run() {

                    final boolean success =
                        UsbGadget.disableMassStorage(
                            GadgetActivity.this
                        );

                    final String error =
                        UsbGadget.getLastError();

                    runOnUiThread(
                        new Runnable() {

                            @Override
                            public void run() {

                                operationRunning =
                                    false;

                                if (success) {

                                    Toast.makeText(
                                        GadgetActivity.this,
                                        "USB Gadget disabled and previous UDC restored.",
                                        Toast.LENGTH_LONG
                                    ).show();

                                    showSetupPage();

                                } else {

                                    Toast.makeText(
                                        GadgetActivity.this,
                                        error.length() == 0
                                            ? "Could not disable USB Gadget."
                                            : error,
                                        Toast.LENGTH_LONG
                                    ).show();

                                    verifyActiveState();
                                }
                            }
                        }
                    );
                }
            },
            "GadgetDisable"
        ).start();
    }

    private void confirmLeave() {

        new AlertDialog.Builder(
            this
        )
            .setTitle(
                "Leave Gadget active?"
            )
            .setMessage(
                "The ConfigFS Mass Storage function can remain active after this Activity closes. Use STOP GADGET later to restore the previous USB configuration."
            )
            .setNegativeButton(
                "CANCEL",
                null
            )
            .setNeutralButton(
                "STOP GADGET",
                new DialogInterface.OnClickListener() {

                    @Override
                    public void onClick(
                        DialogInterface dialog,
                        int which
                    ) {

                        stopGadget();
                    }
                }
            )
            .setPositiveButton(
                "LEAVE ACTIVE",
                new DialogInterface.OnClickListener() {

                    @Override
                    public void onClick(
                        DialogInterface dialog,
                        int which
                    ) {

                        finish();
                    }
                }
            )
            .show();
    }

    private void setSetupBusy(
        boolean busy
    ) {

        if (
            setupProgress !=
            null
        ) {

            setupProgress.setVisibility(
                busy
                    ? View.VISIBLE
                    : View.GONE
            );
        }

        if (
            startButton !=
            null
        ) {

            startButton.setEnabled(
                !busy
            );

            startButton.setAlpha(
                busy
                    ? 0.45f
                    : 1.0f
            );
        }
    }

    private String resolveLocalPath(
        Uri uri
    ) {

        if (uri == null) {
            return "";
        }

        if (
            "file".equalsIgnoreCase(
                uri.getScheme()
            )
        ) {

            String path =
                uri.getPath();

            return
                path == null
                    ? ""
                    : path;
        }

        if (
            "com.android.externalstorage.documents".equals(
                uri.getAuthority()
            )
        ) {

            try {

                String documentId =
                    DocumentsContract.getDocumentId(
                        uri
                    );

                String[] split =
                    documentId.split(
                        ":",
                        2
                    );

                if (
                    split.length ==
                    2
                ) {

                    String volume =
                        split[0];

                    String relative =
                        split[1];

                    if (
                        "primary".equalsIgnoreCase(
                            volume
                        )
                    ) {

                        return new File(
                            Environment.getExternalStorageDirectory(),
                            relative
                        ).getAbsolutePath();
                    }

                    return new File(
                        "/storage/" +
                        volume,
                        relative
                    ).getAbsolutePath();
                }

            } catch (Throwable ignored) {
            }
        }

        Cursor cursor =
            null;

        try {

            cursor =
                getContentResolver()
                    .query(
                        uri,
                        new String[] {
                            MediaStore.MediaColumns.DATA
                        },
                        null,
                        null,
                        null
                    );

            if (
                cursor != null &&
                cursor.moveToFirst()
            ) {

                int index =
                    cursor.getColumnIndex(
                        MediaStore.MediaColumns.DATA
                    );

                if (
                    index >= 0 &&
                    !cursor.isNull(
                        index
                    )
                ) {

                    String path =
                        cursor.getString(
                            index
                        );

                    if (
                        path != null
                    ) {

                        return path;
                    }
                }
            }

        } catch (Throwable ignored) {

        } finally {

            if (cursor != null) {

                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }

        return "";
    }

    private static String formatBytes(
        long bytes
    ) {

        double value =
            bytes;

        String[] units =
            new String[] {
                "B",
                "KiB",
                "MiB",
                "GiB",
                "TiB"
            };

        int unit =
            0;

        while (
            value >= 1024.0 &&
            unit <
                units.length - 1
        ) {

            value /=
                1024.0;

            unit++;
        }

        if (unit == 0) {

            return
                Long.toString(
                    bytes
                ) +
                " B";
        }

        return String.format(
            Locale.US,
            "%.2f %s",
            value,
            units[unit]
        );
    }

    private void addTouchFeedback(
        final View view
    ) {

        if (view == null) {
            return;
        }

        view.setOnTouchListener(
            new View.OnTouchListener() {

                @Override
                public boolean onTouch(
                    View v,
                    MotionEvent event
                ) {

                    if (!v.isEnabled()) {
                        return false;
                    }

                    switch (
                        event.getAction()
                    ) {

                        case MotionEvent.ACTION_DOWN:

                            v.animate()
                                .alpha(0.55f)
                                .setDuration(60)
                                .start();

                            break;

                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:

                            v.animate()
                                .alpha(1.0f)
                                .setDuration(180)
                                .start();

                            break;
                    }

                    return false;
                }
            }
        );
    }

    @Override
    public void onBackPressed() {

        if (operationRunning) {
            return;
        }

        if (activePage) {

            confirmLeave();

        } else {

            super.onBackPressed();
        }
    }
}
