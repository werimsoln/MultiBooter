/******************************************************************************
 * TftpActivity.java
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
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Locale;

public class TftpActivity extends BaseActivity {

    private static final long STATUS_INTERVAL_MS = 2000L;
    private static final int REQUEST_TFTP_TREE = 7302;
    private static final String TREE_URI_KEY = "tftp_tree_uri";

    private final Handler handler =
        new Handler(Looper.getMainLooper());

    private final List<InterfaceItem> interfaces =
        new ArrayList<InterfaceItem>();

    private Spinner interfaceSpinner;
    private EditText rootEdit;
    private Uri selectedTreeUri;
    private String selectedTreeLabel = "";
    private String treeCopyError = "";
    private EditText bootEdit;
    private EditText serverIpEdit;
    private EditText dhcpStartEdit;
    private EditText dhcpEndEdit;
    private ProgressBar progress;
    private Button startButton;

    private TextView runningStatus;
    private boolean runningPage = false;
    private boolean operationRunning = false;

    private static final class InterfaceItem {
        final String name;
        final String label;

        InterfaceItem(String name, String label) {
            this.name = name;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final Runnable statusCheck = new Runnable() {
        @Override
        public void run() {
            if (!runningPage || isFinishing()) return;
            updateRunningStatus();
            handler.postDelayed(this, STATUS_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (TftpMode.isPxeRunning(this)) {
            if (!ModeService.isActive(ModeService.MODE_TFTP)) {
                ModeService.begin(
                    getApplicationContext(),
                    ModeService.MODE_TFTP,
                    "PXE/TFTP server is running",
                    stopHook(getApplicationContext())
                );
            }
            showRunningPage();
        } else {
            showSetupPage();
        }
    }

        /** Stops dnsmasq. Used by the notification "Stop" action. */
    private static ModeService.StopHook stopHook(final android.content.Context app) {
        return new ModeService.StopHook() {
            @Override
            public void stop() {
                boolean ok = TftpMode.stopPxe(app);
                String error = TftpMode.getLastError();
                if (ok) {
                    ModeService.end(
                        app,
                        ModeService.MODE_TFTP,
                        true,
                        "PXE/TFTP server stopped."
                    );
                } else {
                    ModeService.begin(
                        app,
                        ModeService.MODE_TFTP,
                        "Could not stop: " + (error.length() == 0 ? "unknown error" : error),
                        stopHook(app)
                    );
                }
            }
        };
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(statusCheck);
        super.onDestroy();
    }

    private void showSetupPage() {
        runningPage = false;
        handler.removeCallbacks(statusCheck);

        setContentView(R.layout.tftp_setup);

        TextView backTop =
            (TextView) findViewById(R.id.tftp_back_top);

        interfaceSpinner =
            (Spinner) findViewById(R.id.tftp_interface);

        rootEdit =
            (EditText) findViewById(R.id.tftp_root);

        bootEdit =
            (EditText) findViewById(R.id.tftp_boot_file);

        serverIpEdit =
            (EditText) findViewById(R.id.tftp_server_ip);

        dhcpStartEdit =
            (EditText) findViewById(R.id.tftp_dhcp_start);

        dhcpEndEdit =
            (EditText) findViewById(R.id.tftp_dhcp_end);

        TextView abiView =
            (TextView) findViewById(R.id.tftp_dnsmasq_abi);

        progress =
            (ProgressBar) findViewById(R.id.tftp_setup_progress);

        Button createRoot =
            (Button) findViewById(R.id.tftp_create_root);

        createRoot.setText("SELECT TFTP FOLDER");

        Button refresh =
            (Button) findViewById(R.id.tftp_refresh_interfaces);

        Button cancel =
            (Button) findViewById(R.id.tftp_cancel);

        startButton =
            (Button) findViewById(R.id.tftp_start);

        addTouchFeedback(createRoot);
        addTouchFeedback(refresh);
        addTouchFeedback(cancel);
        addTouchFeedback(startButton);

        backTop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!operationRunning) finish();
            }
        });

        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!operationRunning) finish();
            }
        });

        createRoot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openTftpTreePicker();
            }
        });

        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshInterfaces();
            }
        });

        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmStart();
            }
        });

        TftpMode.SessionInfo old = TftpMode.getLastSession(this);

        File defaultRoot = getExternalFilesDir("tftp");
        if (defaultRoot == null) {
            defaultRoot = new File(getFilesDir(), "tftp-root");
        }

        if (!defaultRoot.exists()) {
            defaultRoot.mkdirs();
        }

        rootEdit.setText(
            old.tftpRootPath.length() > 0
                ? old.tftpRootPath
                : defaultRoot.getAbsolutePath()
        );

        String savedTree =
            getPreferences(MODE_PRIVATE).getString(TREE_URI_KEY, "");

        if (savedTree.length() > 0) {
            try {
                selectedTreeUri = Uri.parse(savedTree);
                selectedTreeLabel =
                    "SAF folder: " + getTreeDisplayName(selectedTreeUri);
                rootEdit.setText(selectedTreeLabel);
            } catch (Throwable ignored) {
                selectedTreeUri = null;
                selectedTreeLabel = "";
            }
        }

        bootEdit.setText(
            old.bootFile.length() > 0
                ? old.bootFile
                : "pxelinux.0"
        );

        serverIpEdit.setText(
            old.serverIp.length() > 0
                ? old.serverIp
                : "192.168.2.1"
        );

        dhcpStartEdit.setText(
            old.dhcpStart.length() > 0
                ? old.dhcpStart
                : "192.168.2.10"
        );

        dhcpEndEdit.setText(
            old.dhcpEnd.length() > 0
                ? old.dhcpEnd
                : "192.168.2.100"
        );

        abiView.setText(
            "Host ABI: " +
            TftpMode.getSelectedDnsmasqAbi() +
            "\nAsset: " +
            TftpMode.getSelectedDnsmasqAsset()
        );

        refreshInterfaces();
        selectInterface(old.interfaceName);
    }

    private void refreshInterfaces() {
        if (operationRunning) return;

        interfaces.clear();

        try {
            Enumeration<NetworkInterface> all =
                NetworkInterface.getNetworkInterfaces();

            while (all != null && all.hasMoreElements()) {
                NetworkInterface ni = all.nextElement();
                String name = ni.getName();

                if (name == null || name.length() == 0 || "lo".equals(name))
                    continue;

                boolean up = false;
                try { up = ni.isUp(); } catch (Throwable ignored) {}
                if (!up) continue;

                String ip = firstIpv4(ni);
                String label = name;

                if (ip.length() > 0)
                    label += "  •  " + ip;

                interfaces.add(new InterfaceItem(name, label));
            }
        } catch (Throwable e) {
            Toast.makeText(
                this,
                "Could not enumerate interfaces: " + e.getMessage(),
                Toast.LENGTH_LONG
            ).show();
        }

                if (interfaces.isEmpty()) {
            loadInterfacesViaRoot();
        }

        applyInterfaces();
    }

    /**
     * Fallback for devices where NetworkInterface returns nothing (for
     * example when the app has no INTERNET permission): ask root.
     */
    private void loadInterfacesViaRoot() {

        new Thread(
            new Runnable() {

                @Override
                public void run() {

                    final java.util.List<String[]> found =
                        TftpMode.listInterfacesViaRoot();

                    runOnUiThread(
                        new Runnable() {

                            @Override
                            public void run() {

                                if (isFinishing() || found.isEmpty()) {
                                    return;
                                }

                                interfaces.clear();

                                for (String[] item : found) {

                                    interfaces.add(
                                        new InterfaceItem(
                                            item[0],
                                            item[1].length() > 0
                                                ? item[0] + "  \u2022  " + item[1]
                                                : item[0]
                                        )
                                    );
                                }

                                applyInterfaces();

                                selectInterface(
                                    TftpMode.getLastSession(
                                        getApplicationContext()
                                    ).interfaceName
                                );
                            }
                        }
                    );
                }
            },
            "TftpInterfaces"
        ).start();
    }

    private void applyInterfaces() {

        Collections.sort(
            interfaces,
            new Comparator<InterfaceItem>() {
                @Override
                public int compare(InterfaceItem a, InterfaceItem b) {
                    int sa = score(a.name);
                    int sb = score(b.name);
                    if (sa != sb) return sb - sa;
                    return a.name.compareToIgnoreCase(b.name);
                }
            }
        );

        if (interfaces.isEmpty())
            interfaces.add(new InterfaceItem("", "No active interface found"));

        ArrayAdapter<InterfaceItem> adapter =
            new ArrayAdapter<InterfaceItem>(
                this,
                android.R.layout.simple_spinner_item,
                interfaces
            );

        adapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        );

        interfaceSpinner.setAdapter(adapter);
    }

    private void selectInterface(String wanted) {
        if (wanted == null || wanted.length() == 0) return;

        for (int i = 0; i < interfaces.size(); i++) {
            if (wanted.equals(interfaces.get(i).name)) {
                interfaceSpinner.setSelection(i);
                return;
            }
        }
    }

    private void openTftpTreePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION |
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        );

        try {
            startActivityForResult(intent, REQUEST_TFTP_TREE);
        } catch (Throwable e) {
            Toast.makeText(
                this,
                "Could not open Android folder picker: " + e.getMessage(),
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
        super.onActivityResult(requestCode, resultCode, data);

        if (
            requestCode != REQUEST_TFTP_TREE ||
            resultCode != RESULT_OK ||
            data == null ||
            data.getData() == null
        ) {
            return;
        }

        Uri tree = data.getData();
        int flags = data.getFlags() &
            (
                Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            );

        try {
            if (
                flags != 0 &&
                (data.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0
            ) {
                getContentResolver().takePersistableUriPermission(tree, flags);
            }
        } catch (Throwable e) {
            Toast.makeText(
                this,
                "Folder access may need to be granted again: " + e.getMessage(),
                Toast.LENGTH_LONG
            ).show();
        }

        selectedTreeUri = tree;
        selectedTreeLabel = "SAF folder: " + getTreeDisplayName(tree);

        getPreferences(MODE_PRIVATE)
            .edit()
            .putString(TREE_URI_KEY, tree.toString())
            .apply();

        rootEdit.setText(selectedTreeLabel);
    }

    private String getTreeDisplayName(Uri tree) {
        if (tree == null) {
            return "selected folder";
        }

        Cursor cursor = null;

        try {
            String documentId = DocumentsContract.getTreeDocumentId(tree);
            Uri documentUri =
                DocumentsContract.buildDocumentUriUsingTree(tree, documentId);

            cursor = getContentResolver().query(
                documentUri,
                new String[] { Document.COLUMN_DISPLAY_NAME },
                null,
                null,
                null
            );

            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(Document.COLUMN_DISPLAY_NAME);

                if (index >= 0 && !cursor.isNull(index)) {
                    String name = cursor.getString(index);
                    if (name != null && name.length() > 0) {
                        return name;
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

        String segment = tree.getLastPathSegment();
        return segment == null || segment.length() == 0
            ? "selected folder"
            : segment;
    }

    private File stageSelectedTree(Uri tree) {
        treeCopyError = "";

        File directory = getExternalFilesDir("tftp");
        if (directory == null) {
            directory = new File(getFilesDir(), "tftp");
        }

        File target = new File(directory, "tftp-root-staged");

        try {
            deleteRecursively(target);

            if (!target.mkdirs() && !target.isDirectory()) {
                throw new IOException("Could not create the app-specific TFTP directory.");
            }

            String rootId = DocumentsContract.getTreeDocumentId(tree);
            Set<String> visited = new HashSet<String>();
            visited.add(rootId);
            int[] copiedEntries = new int[] { 0 };

            if (!copyTreeChildren(tree, rootId, target, visited, copiedEntries, 0)) {
                throw new IOException(
                    treeCopyError.length() == 0
                        ? "Could not copy the selected TFTP folder."
                        : treeCopyError
                );
            }

            return target;

        } catch (Throwable e) {
            treeCopyError = "Could not stage selected SAF folder: " + e.getMessage();
            deleteRecursively(target);
            return null;
        }
    }

    private boolean copyTreeChildren(
        Uri tree,
        String parentId,
        File parent,
        Set<String> visited,
        int[] copiedEntries,
        int depth
    ) {
        if (depth > 64 || copiedEntries[0] > 100000) {
            treeCopyError = "The selected TFTP tree is too deep or contains too many entries.";
            return false;
        }

        Cursor cursor = null;

        try {
            Uri childrenUri =
                DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);

            cursor = getContentResolver().query(
                childrenUri,
                new String[] {
                    Document.COLUMN_DOCUMENT_ID,
                    Document.COLUMN_DISPLAY_NAME,
                    Document.COLUMN_MIME_TYPE
                },
                null,
                null,
                null
            );

            if (cursor == null) {
                treeCopyError = "The document provider returned no folder listing.";
                return false;
            }

            int idColumn = cursor.getColumnIndex(Document.COLUMN_DOCUMENT_ID);
            int nameColumn = cursor.getColumnIndex(Document.COLUMN_DISPLAY_NAME);
            int mimeColumn = cursor.getColumnIndex(Document.COLUMN_MIME_TYPE);

            if (idColumn < 0 || nameColumn < 0 || mimeColumn < 0) {
                treeCopyError = "The document provider returned an incomplete folder listing.";
                return false;
            }

            while (cursor.moveToNext()) {
                String documentId = cursor.getString(idColumn);
                String name = cursor.getString(nameColumn);
                String mimeType = cursor.getString(mimeColumn);

                if (documentId == null || !visited.add(documentId)) {
                    continue;
                }

                if (name == null || name.length() == 0) {
                    name = "document-" + copiedEntries[0];
                }

                name = name.replace('/', '_').replace('\\', '_');
                if (".".equals(name) || "..".equals(name)) {
                    name = "document-" + copiedEntries[0];
                }

                File destination =
                    new File(parent, name).getCanonicalFile();

                String parentPath = parent.getCanonicalPath();
                if (!destination.getPath().startsWith(parentPath + File.separator)) {
                    treeCopyError = "A document name escaped the destination directory.";
                    return false;
                }

                if (destination.exists()) {
                    treeCopyError = "Duplicate destination name: " + name;
                    return false;
                }

                copiedEntries[0]++;

                if (Document.MIME_TYPE_DIR.equals(mimeType)) {
                    if (!destination.mkdirs() && !destination.isDirectory()) {
                        treeCopyError = "Could not create directory: " + name;
                        return false;
                    }

                    if (!copyTreeChildren(
                        tree,
                        documentId,
                        destination,
                        visited,
                        copiedEntries,
                        depth + 1
                    )) {
                        return false;
                    }
                } else {
                    InputStream input = null;
                    FileOutputStream output = null;

                    try {
                        Uri documentUri =
                            DocumentsContract.buildDocumentUriUsingTree(tree, documentId);
                        input = getContentResolver().openInputStream(documentUri);

                        if (input == null) {
                            throw new IOException("No input stream for " + name);
                        }

                        output = new FileOutputStream(destination, false);
                        byte[] buffer = new byte[65536];
                        int count;

                        while ((count = input.read(buffer)) != -1) {
                            output.write(buffer, 0, count);
                        }

                        output.flush();

                    } catch (Throwable e) {
                        treeCopyError =
                            "Could not copy '" + name + "': " + e.getMessage();
                        return false;

                    } finally {
                        if (input != null) {
                            try {
                                input.close();
                            } catch (Throwable ignored) {
                            }
                        }

                        if (output != null) {
                            try {
                                output.close();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            }

            return true;

        } catch (Throwable e) {
            treeCopyError = "Could not read selected folder: " + e.getMessage();
            return false;

        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }

        if (file.isDirectory()) {
            File[] children = file.listFiles();

            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }

        file.delete();
    }

    private void confirmStart() {
        if (operationRunning) return;

        Object raw = interfaceSpinner.getSelectedItem();
        if (!(raw instanceof InterfaceItem)) return;

        final InterfaceItem selected = (InterfaceItem) raw;

        if (selected.name.length() == 0) {
            Toast.makeText(
                this,
                "Select an active Ethernet or USB network interface.",
                Toast.LENGTH_LONG
            ).show();
            return;
        }

        final String root = text(rootEdit);

        final Uri treeUri =
            selectedTreeUri != null && root.equals(selectedTreeLabel)
                ? selectedTreeUri
                : null;

        final String boot = text(bootEdit);
        final String server = text(serverIpEdit);
        final String start = text(dhcpStartEdit);
        final String end = text(dhcpEndEdit);

        new AlertDialog.Builder(this)
            .setTitle("Start PXE/TFTP server?")
            .setMessage(
                "Interface: " + selected.name +
                "\nServer: " + server + "/24" +
                "\nDHCP: " + start + " - " + end +
                "\n\nTFTP root:\n" + root +
                "\n\nBoot file:\n" + boot +
                "\n\nUse a dedicated network segment. Another DHCP server on the same LAN will conflict."
            )
            .setNegativeButton("CANCEL", null)
            .setPositiveButton(
                "START",
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        performStart(
                            selected.name,
                            root,
                            treeUri,
                            boot,
                            server,
                            start,
                            end
                        );
                    }
                }
            )
            .show();
    }

    private void performStart(
        final String iface,
        final String root,
        final Uri treeUri,
        final String boot,
        final String server,
        final String start,
        final String end
    ) {
        operationRunning = true;
        setBusy(true);

        ModeService.begin(
            getApplicationContext(),
            ModeService.MODE_TFTP,
            "Starting PXE/TFTP server...",
            stopHook(getApplicationContext())
        );

        new Thread(
            new Runnable() {
                @Override
                public void run() {
                    String effectiveRoot = root;
                    String preparationError = "";

                    if (treeUri != null) {
                        File stagedRoot = stageSelectedTree(treeUri);

                        if (stagedRoot == null) {
                            preparationError = treeCopyError;
                        } else {
                            effectiveRoot = stagedRoot.getAbsolutePath();
                        }
                    }

                    boolean ok = preparationError.length() == 0 &&
                        TftpMode.hasRootAccess() &&
                        TftpMode.startPxe(
                            TftpActivity.this,
                            iface,
                            effectiveRoot,
                            boot,
                            server,
                            start,
                            end
                        );

                    final boolean success = ok;
                    final String error = preparationError.length() > 0
                        ? preparationError
                        : TftpMode.getLastError();

                    if (success) {
                        ModeService.progress(
                            getApplicationContext(),
                            ModeService.MODE_TFTP,
                            -1,
                            "Serving " + boot + " on " + iface
                        );
                    } else {
                        ModeService.end(
                            getApplicationContext(),
                            ModeService.MODE_TFTP,
                            false,
                            error.length() == 0
                                ? "PXE/TFTP could not be started."
                                : error
                        );
                    }

                    runOnUiThread(
                        new Runnable() {
                            @Override
                            public void run() {
                                operationRunning = false;
                                setBusy(false);

                                if (success) {
                                    showRunningPage();
                                    Toast.makeText(
                                        TftpActivity.this,
                                        "PXE/TFTP server started.",
                                        Toast.LENGTH_LONG
                                    ).show();
                                } else {
                                    Toast.makeText(
                                        TftpActivity.this,
                                        error.length() == 0
                                            ? "PXE/TFTP could not be started."
                                            : error,
                                        Toast.LENGTH_LONG
                                    ).show();
                                }
                            }
                        }
                    );
                }
            },
            "TftpStart"
        ).start();
    }

    private void showRunningPage() {
        runningPage = true;
        handler.removeCallbacks(statusCheck);

        setContentView(R.layout.tftp_running);

        TextView backTop =
            (TextView) findViewById(R.id.tftp_running_back_top);

        runningStatus =
            (TextView) findViewById(R.id.tftp_running_status);

        TextView abi =
            (TextView) findViewById(R.id.tftp_running_abi);

        TextView iface =
            (TextView) findViewById(R.id.tftp_running_interface);

        TextView address =
            (TextView) findViewById(R.id.tftp_running_address);

        TextView root =
            (TextView) findViewById(R.id.tftp_running_root);

        TextView boot =
            (TextView) findViewById(R.id.tftp_running_boot);

        Button leave =
            (Button) findViewById(R.id.tftp_leave_running);

        Button stop =
            (Button) findViewById(R.id.tftp_stop);

        addTouchFeedback(leave);
        addTouchFeedback(stop);

        View.OnClickListener leaveListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!operationRunning) confirmLeave();
            }
        };

        backTop.setOnClickListener(leaveListener);
        leave.setOnClickListener(leaveListener);

        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!operationRunning) stopServer();
            }
        });

        TftpMode.SessionInfo s = TftpMode.getLastSession(this);

        abi.setText(
            s.dnsmasqAbi +
            "  •  " +
            TftpMode.getSelectedDnsmasqAsset()
        );

        iface.setText(s.interfaceName);

        address.setText(
            s.serverIp + "/24\nDHCP: " +
            s.dhcpStart + " – " + s.dhcpEnd
        );

        root.setText(s.tftpRootPath);
        boot.setText(s.bootFile);

        updateRunningStatus();
        handler.postDelayed(statusCheck, STATUS_INTERVAL_MS);
    }

    private void updateRunningStatus() {
        if (runningStatus == null) return;

        if (TftpMode.isPxeRunning(this)) {
            runningStatus.setText("RUNNING");
            runningStatus.setAlpha(1.0f);
        } else {
            runningStatus.setText("STOPPED / PROCESS LOST");
            runningStatus.setAlpha(0.7f);
        }
    }

    private void stopServer() {
        operationRunning = true;

        new Thread(
            new Runnable() {
                @Override
                public void run() {
                    final boolean ok = TftpMode.stopPxe(TftpActivity.this);
                    final String error = TftpMode.getLastError();

                    if (ok) {
                        ModeService.end(
                            getApplicationContext(),
                            ModeService.MODE_TFTP,
                            true,
                            "PXE/TFTP server stopped."
                        );
                    }

                    runOnUiThread(
                        new Runnable() {
                            @Override
                            public void run() {
                                operationRunning = false;

                                if (ok) {
                                    Toast.makeText(
                                        TftpActivity.this,
                                        "PXE/TFTP server stopped.",
                                        Toast.LENGTH_SHORT
                                    ).show();

                                    showSetupPage();
                                } else {
                                    Toast.makeText(
                                        TftpActivity.this,
                                        error.length() == 0
                                            ? "Could not stop server."
                                            : error,
                                        Toast.LENGTH_LONG
                                    ).show();
                                    updateRunningStatus();
                                }
                            }
                        }
                    );
                }
            },
            "TftpStop"
        ).start();
    }

    private void confirmLeave() {
        if (!TftpMode.isPxeRunning(this)) {
            finish();
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle("Leave server running?")
            .setMessage(
                "dnsmasq is a separate root process. It can keep serving PXE/TFTP after this Activity closes."
            )
            .setNegativeButton("CANCEL", null)
            .setNeutralButton(
                "STOP SERVER",
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        stopServer();
                    }
                }
            )
            .setPositiveButton(
                "LEAVE RUNNING",
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                }
            )
            .show();
    }

    private void setBusy(boolean busy) {
        if (progress != null)
            progress.setVisibility(busy ? View.VISIBLE : View.GONE);

        if (startButton != null) {
            startButton.setEnabled(!busy);
            startButton.setAlpha(busy ? 0.45f : 1.0f);
        }

        if (interfaceSpinner != null)
            interfaceSpinner.setEnabled(!busy);
    }

    private static String firstIpv4(NetworkInterface ni) {
        try {
            Enumeration<InetAddress> addresses = ni.getInetAddresses();

            while (addresses.hasMoreElements()) {
                InetAddress a = addresses.nextElement();

                if (a instanceof Inet4Address && !a.isLoopbackAddress())
                    return a.getHostAddress();
            }
        } catch (Throwable ignored) {}

        return "";
    }

    private static int score(String name) {
        String s = name == null ? "" : name.toLowerCase(Locale.US);

        if (s.startsWith("eth") || s.startsWith("en")) return 100;
        if (s.startsWith("usb") || s.contains("rndis")) return 90;
        if (s.startsWith("wlan")) return 40;
        return 10;
    }

    private static String text(EditText e) {
        return e == null || e.getText() == null
            ? ""
            : e.getText().toString().trim();
    }

    private void addTouchFeedback(final View view) {
        if (view == null) return;

        view.setOnTouchListener(
            new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (!v.isEnabled()) return false;

                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            v.animate().alpha(0.55f).setDuration(60).start();
                            break;

                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            v.animate().alpha(1.0f).setDuration(180).start();
                            break;
                    }

                    return false;
                }
            }
        );
    }

    @Override
    public void onBackPressed() {
        if (operationRunning) return;

        if (runningPage) {
            confirmLeave();
        } else {
            super.onBackPressed();
        }
    }
}
