/******************************************************************************
 * TftpActivity.java
 *
 * Copyright (c) 2026, werismoln <vlkanblek@gmail.com>
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
import android.os.Bundle;
import android.os.Environment;
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
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

public class TftpActivity extends Activity {

    private static final long STATUS_INTERVAL_MS = 2000L;

    private final Handler handler =
        new Handler(Looper.getMainLooper());

    private final List<InterfaceItem> interfaces =
        new ArrayList<InterfaceItem>();

    private Spinner interfaceSpinner;
    private EditText rootEdit;
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
            showRunningPage();
        } else {
            showSetupPage();
        }
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
                createRootDirectory();
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

        File defaultRoot = new File(
            Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            ),
            "tftp"
        );

        rootEdit.setText(
            old.tftpRootPath.length() > 0
                ? old.tftpRootPath
                : defaultRoot.getAbsolutePath()
        );

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

    private void createRootDirectory() {
        String path = text(rootEdit);

        if (path.length() == 0) {
            Toast.makeText(this, "TFTP root path is empty.", Toast.LENGTH_SHORT).show();
            return;
        }

        File dir = new File(path);

        if (dir.isDirectory()) {
            Toast.makeText(
                this,
                "TFTP root already exists:\n" + dir.getAbsolutePath(),
                Toast.LENGTH_LONG
            ).show();
            return;
        }

        if (dir.exists()) {
            Toast.makeText(this, "Path exists but is not a directory.", Toast.LENGTH_LONG).show();
            return;
        }

        if (dir.mkdirs()) {
            Toast.makeText(
                this,
                "Created:\n" + dir.getAbsolutePath() +
                "\n\nCopy the PXE boot tree into this folder.",
                Toast.LENGTH_LONG
            ).show();
        } else {
            Toast.makeText(this, "Could not create TFTP root.", Toast.LENGTH_LONG).show();
        }
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
        final String boot,
        final String server,
        final String start,
        final String end
    ) {
        operationRunning = true;
        setBusy(true);

        new Thread(
            new Runnable() {
                @Override
                public void run() {
                    boolean ok =
                        TftpMode.hasRootAccess() &&
                        TftpMode.startPxe(
                            TftpActivity.this,
                            iface,
                            root,
                            boot,
                            server,
                            start,
                            end
                        );

                    final boolean success = ok;
                    final String error = TftpMode.getLastError();

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
