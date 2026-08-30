package com.werismoln.multibooter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;

public class FunctionFsActivity extends Activity {

    private static final int REQUEST_ISO =
        7401;

    private static final long STATUS_INTERVAL_MS =
        2000L;

    private final Handler handler =
        new Handler(
            Looper.getMainLooper()
        );

    private EditText isoPathEdit;
    private TextView isoInfo;
    private TextView supportStatus;
    private ProgressBar setupProgress;
    private Button startButton;

    private TextView activeStatus;
    private TextView nativeStatus;

    private boolean operationRunning =
        false;

    private boolean activePage =
        false;

    private boolean supportReady =
        false;

    private final Runnable statusRunnable =
        new Runnable() {

            @Override
            public void run() {

                if (
                    activePage &&
                    !isFinishing()
                ) {

                    verifyActiveState();

                    handler.postDelayed(
                        this,
                        STATUS_INTERVAL_MS
                    );
                }
            }
        };

    @Override
    protected void onCreate(
        Bundle savedInstanceState
    ) {

        super.onCreate(
            savedInstanceState
        );

        if (
            FunctionFileSystem.hasSavedActiveSession(
                this
            )
        ) {

            showActivePage();

        } else {

            showSetupPage();
        }
    }

    @Override
    protected void onDestroy() {

        handler.removeCallbacks(
            statusRunnable
        );

        super.onDestroy();
    }

    private void showSetupPage() {

        activePage =
            false;

        handler.removeCallbacks(
            statusRunnable
        );

        setContentView(
            R.layout.functionfs_setup
        );

        TextView backTop =
            (TextView)
            findViewById(
                R.id.functionfs_back_top
            );

        isoPathEdit =
            (EditText)
            findViewById(
                R.id.functionfs_iso_path
            );

        isoInfo =
            (TextView)
            findViewById(
                R.id.functionfs_iso_info
            );

        supportStatus =
            (TextView)
            findViewById(
                R.id.functionfs_support_status
            );

        setupProgress =
            (ProgressBar)
            findViewById(
                R.id.functionfs_setup_progress
            );

        Button selectIso =
            (Button)
            findViewById(
                R.id.functionfs_select_iso
            );

        Button checkSupport =
            (Button)
            findViewById(
                R.id.functionfs_check_support
            );

        Button cancel =
            (Button)
            findViewById(
                R.id.functionfs_cancel
            );

        startButton =
            (Button)
            findViewById(
                R.id.functionfs_start
            );

        addTouchFeedback(
            selectIso
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

        selectIso.setOnClickListener(
            new View.OnClickListener() {

                @Override
                public void onClick(
                    View v
                ) {

                    openIsoPicker();
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

        FunctionFileSystem.SessionInfo old =
            FunctionFileSystem.getSession(
                this
            );

        if (
            old.isoPath.length() >
            0
        ) {

            isoPathEdit.setText(
                old.isoPath
            );

            updateIsoInfo(
                old.isoPath
            );
        }

        probeSupport();
    }

    private void openIsoPicker() {

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
                REQUEST_ISO
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
                REQUEST_ISO ||
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
                    "A real filesystem path is required"
                )
                .setMessage(
                    "The native FunctionFS SCSI backend opens the ISO with fopen(), so it cannot use a content:// URI directly. Select an ISO stored in normal shared/internal storage or enter its absolute path manually."
                )
                .setPositiveButton(
                    "OK",
                    null
                )
                .show();

            return;
        }

        isoPathEdit.setText(
            path
        );

        updateIsoInfo(
            path
        );
    }

    private void updateIsoInfo(
        String path
    ) {

        if (
            isoInfo == null
        ) {

            return;
        }

        if (
            path == null ||
            path.trim().length() == 0
        ) {

            isoInfo.setText(
                "No ISO selected."
            );

            return;
        }

        File iso =
            new File(
                path.trim()
            );

        if (
            !iso.isFile()
        ) {

            isoInfo.setText(
                "ISO file not found at this path."
            );

            return;
        }

        isoInfo.setText(
            iso.getName() +
            "\n" +
            formatBytes(
                iso.length()
            ) +
            "\nRead-only virtual CD-ROM • 2048-byte SCSI logical blocks"
        );
    }

    private void probeSupport() {

        if (operationRunning) {
            return;
        }

        operationRunning =
            true;

        supportReady =
            false;

        setSetupBusy(
            true
        );

        supportStatus.setText(
            "Checking root, ConfigFS and FunctionFS..."
        );

        new Thread(
            new Runnable() {

                @Override
                public void run() {

                    final FunctionFileSystem.ProbeInfo probe =
                        FunctionFileSystem.probe();

                    runOnUiThread(
                        new Runnable() {

                            @Override
                            public void run() {

                                operationRunning =
                                    false;

                                supportReady =
                                    probe.isReady();

                                setSetupBusy(
                                    false
                                );

                                StringBuilder status =
                                    new StringBuilder();

                                status.append(
                                    "Native library: "
                                );

                                status.append(
                                    probe.libraryLoaded
                                        ? "OK"
                                        : "not loaded"
                                );

                                status.append(
                                    "\nRoot: "
                                );

                                status.append(
                                    probe.rootGranted
                                        ? "OK"
                                        : "unavailable"
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
                                    "\nFunctionFS function: "
                                );

                                status.append(
                                    probe.functionFsSupported
                                        ? "supported"
                                        : "not available"
                                );

                                status.append(
                                    "\nFunctionFS mount: "
                                );

                                status.append(
                                    probe.functionFsMounted
                                        ? "already mounted"
                                        : "not mounted yet"
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
                                    probe.configPath.length() >
                                    0
                                ) {

                                    status.append(
                                        "\nConfig: "
                                    );

                                    status.append(
                                        probe.configPath
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

                                updateStartButton();
                            }
                        }
                    );
                }
            },
            "FunctionFsProbe"
        ).start();
    }

    private void confirmStart() {

        if (operationRunning) {
            return;
        }

        final String iso =
            isoPathEdit.getText()
                .toString()
                .trim();

        updateIsoInfo(
            iso
        );

        File file =
            new File(
                iso
            );

        if (
            !supportReady
        ) {

            Toast.makeText(
                this,
                "FunctionFS support check is not ready.",
                Toast.LENGTH_LONG
            ).show();

            return;
        }

        if (
            !file.isFile() ||
            !file.canRead()
        ) {

            Toast.makeText(
                this,
                "Select a readable ISO file first.",
                Toast.LENGTH_LONG
            ).show();

            return;
        }

        new AlertDialog.Builder(
            this
        )
            .setTitle(
                "Start FunctionFS Virtual CD-ROM?"
            )
            .setMessage(
                "ISO:\n" +
                file.getAbsolutePath() +
                "\n\nMultiBooter will create ffs.multiboot, mount FunctionFS at " +
                FunctionFileSystem.getMountPath() +
                ", start the native USB Mass Storage BOT/SCSI backend and reconnect the UDC.\n\nThe ISO is exposed read-only as a 2048-byte-block virtual optical device."
            )
            .setNegativeButton(
                "CANCEL",
                null
            )
            .setPositiveButton(
                "START",
                new DialogInterface.OnClickListener() {

                    @Override
                    public void onClick(
                        DialogInterface dialog,
                        int which
                    ) {

                        performStart(
                            iso
                        );
                    }
                }
            )
            .show();
    }

    private void performStart(
        final String iso
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
                        FunctionFileSystem.start(
                            FunctionFsActivity.this,
                            iso
                        );

                    final String error =
                        FunctionFileSystem.getLastError();

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
                                        FunctionFsActivity.this,
                                        "FunctionFS virtual CD-ROM started.",
                                        Toast.LENGTH_LONG
                                    ).show();

                                } else {

                                    Toast.makeText(
                                        FunctionFsActivity.this,
                                        error.length() == 0
                                            ? "FunctionFS could not be started."
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
            "FunctionFsStart"
        ).start();
    }

    private void showActivePage() {

        activePage =
            true;

        handler.removeCallbacks(
            statusRunnable
        );

        setContentView(
            R.layout.functionfs_active
        );

        TextView backTop =
            (TextView)
            findViewById(
                R.id.functionfs_active_back_top
            );

        activeStatus =
            (TextView)
            findViewById(
                R.id.functionfs_active_status
            );

        nativeStatus =
            (TextView)
            findViewById(
                R.id.functionfs_active_native
            );

        TextView iso =
            (TextView)
            findViewById(
                R.id.functionfs_active_iso
            );

        TextView mount =
            (TextView)
            findViewById(
                R.id.functionfs_active_mount
            );

        TextView function =
            (TextView)
            findViewById(
                R.id.functionfs_active_function
            );

        TextView config =
            (TextView)
            findViewById(
                R.id.functionfs_active_config
            );

        TextView udc =
            (TextView)
            findViewById(
                R.id.functionfs_active_udc
            );

        Button leave =
            (Button)
            findViewById(
                R.id.functionfs_leave_active
            );

        Button stop =
            (Button)
            findViewById(
                R.id.functionfs_stop
            );

        addTouchFeedback(
            leave
        );

        addTouchFeedback(
            stop
        );

        FunctionFileSystem.SessionInfo session =
            FunctionFileSystem.getSession(
                this
            );

        iso.setText(
            session.isoPath
        );

        mount.setText(
            FunctionFileSystem.getMountPath() +
            (
                session.mountCreatedByUs
                    ? "\nMounted by MultiBooter"
                    : "\nPre-existing FunctionFS mount"
            )
        );

        function.setText(
            session.functionPath
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
                        stopFunctionFs();
                    }
                }
            }
        );

        verifyActiveState();

        handler.postDelayed(
            statusRunnable,
            STATUS_INTERVAL_MS
        );
    }

    private void verifyActiveState() {

        if (
            !activePage ||
            activeStatus == null
        ) {

            return;
        }

        new Thread(
            new Runnable() {

                @Override
                public void run() {

                    final boolean nativeRunning =
                        FunctionFileSystem.isRunning();

                    final boolean active =
                        FunctionFileSystem.isActive(
                            FunctionFsActivity.this
                        );

                    final String error =
                        FunctionFileSystem.getLastError();

                    runOnUiThread(
                        new Runnable() {

                            @Override
                            public void run() {

                                if (
                                    !activePage ||
                                    activeStatus == null
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

                                if (
                                    nativeStatus !=
                                    null
                                ) {

                                    String text =
                                        nativeRunning
                                            ? "Native event/SCSI backend: RUNNING"
                                            : "Native event/SCSI backend: STOPPED";

                                    if (
                                        !nativeRunning &&
                                        error.length() > 0
                                    ) {

                                        text +=
                                            "\n" +
                                            error;
                                    }

                                    nativeStatus.setText(
                                        text
                                    );
                                }
                            }
                        }
                    );
                }
            },
            "FunctionFsStatus"
        ).start();
    }

    private void stopFunctionFs() {

        operationRunning =
            true;

        handler.removeCallbacks(
            statusRunnable
        );

        new Thread(
            new Runnable() {

                @Override
                public void run() {

                    final boolean success =
                        FunctionFileSystem.stop(
                            FunctionFsActivity.this
                        );

                    final String error =
                        FunctionFileSystem.getLastError();

                    runOnUiThread(
                        new Runnable() {

                            @Override
                            public void run() {

                                operationRunning =
                                    false;

                                if (success) {

                                    Toast.makeText(
                                        FunctionFsActivity.this,
                                        "FunctionFS stopped and previous UDC restored.",
                                        Toast.LENGTH_LONG
                                    ).show();

                                    showSetupPage();

                                } else {

                                    Toast.makeText(
                                        FunctionFsActivity.this,
                                        error.length() == 0
                                            ? "Could not stop FunctionFS."
                                            : error,
                                        Toast.LENGTH_LONG
                                    ).show();

                                    verifyActiveState();

                                    handler.postDelayed(
                                        statusRunnable,
                                        STATUS_INTERVAL_MS
                                    );
                                }
                            }
                        }
                    );
                }
            },
            "FunctionFsStop"
        ).start();
    }

    private void confirmLeave() {

        new AlertDialog.Builder(
            this
        )
            .setTitle(
                "Leave FunctionFS active?"
            )
            .setMessage(
                "The native FunctionFS threads run inside the MultiBooter process. Leaving this Activity is safe while the app process remains alive, but force-stopping/killing the app terminates the native backend."
            )
            .setNegativeButton(
                "CANCEL",
                null
            )
            .setNeutralButton(
                "STOP FUNCTIONFS",
                new DialogInterface.OnClickListener() {

                    @Override
                    public void onClick(
                        DialogInterface dialog,
                        int which
                    ) {

                        stopFunctionFs();
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
                !busy &&
                supportReady
            );

            startButton.setAlpha(
                (
                    !busy &&
                    supportReady
                )
                    ? 1.0f
                    : 0.45f
            );
        }
    }

    private void updateStartButton() {

        if (
            startButton ==
            null
        ) {

            return;
        }

        boolean enabled =
            supportReady &&
            !operationRunning;

        startButton.setEnabled(
            enabled
        );

        startButton.setAlpha(
            enabled
                ? 1.0f
                : 0.45f
        );
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
