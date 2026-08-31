/******************************************************************************
 * SettingsActivity.java
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

import com.werismoln.multibooter.R;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends Activity {

    private static final int REQUEST_STORAGE_PERMISSION = 5100;
    private static final int REQUEST_NOTIFICATION_PERMISSION = 5101;

    private static final String STATE_FILE = "restored-data";
    /*
     * Must remain identical to MainActivity and BootManager.
     * Stored in getFilesDir()/restored-data as:
     *
     *   root-granted=true
     */
    private static final String FLAG_ROOT_GRANTED = "root-granted=true";

    private TextView rootStatus;
    private TextView storageStatus;
    private TextView notificationStatus;
    private TextView batteryStatus;

    private Button rootAction;
    private Button storageAction;
    private Button notificationAction;
    private Button batteryAction;

    private boolean rootCheckRunning = false;
    private boolean notificationRequestedThisSession = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings);

        bindViews();
        setupListeners();
        setupTouchFeedback();
        refreshPermissionStates();
    }

    private void bindViews() {
        rootStatus = (TextView) findViewById(R.id.settings_root_status);
        storageStatus = (TextView) findViewById(R.id.settings_storage_status);
        notificationStatus = (TextView) findViewById(R.id.settings_notifications_status);
        batteryStatus = (TextView) findViewById(R.id.settings_battery_status);

        rootAction = (Button) findViewById(R.id.settings_root_action);
        storageAction = (Button) findViewById(R.id.settings_storage_action);
        notificationAction = (Button) findViewById(R.id.settings_notifications_action);
        batteryAction = (Button) findViewById(R.id.settings_battery_action);
    }

    private void setupListeners() {
        TextView back = (TextView) findViewById(R.id.settings_back_top);

        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });

        rootAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                checkOrRequestRoot();
            }
        });

        storageAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openStoragePermission();
            }
        });

        notificationAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openNotificationPermission();
            }
        });

        batteryAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openBatteryOptimization();
            }
        });
    }

    private void setupTouchFeedback() {
        View.OnTouchListener listener = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if (!view.isEnabled()) {
                    return false;
                }

                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    view.animate().cancel();
                    view.setAlpha(0.4f);
                } else if (
                    event.getAction() == MotionEvent.ACTION_UP ||
                    event.getAction() == MotionEvent.ACTION_CANCEL
                ) {
                    view.animate()
                        .alpha(1.0f)
                        .setDuration(250)
                        .start();
                }

                return false;
            }
        };

        rootAction.setOnTouchListener(listener);
        storageAction.setOnTouchListener(listener);
        notificationAction.setOnTouchListener(listener);
        batteryAction.setOnTouchListener(listener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissionStates();
    }

    private void refreshPermissionStates() {
        updateRootUi(hasSavedFlag(FLAG_ROOT_GRANTED));
        updateStorageUi(hasStoragePermission());
        updateNotificationUi(hasNotificationPermission());
        updateBatteryUi(isBatteryOptimizationDisabled());
    }

    private void updateRootUi(boolean granted) {
        if (rootCheckRunning) {
            setStatus(rootStatus, "CHECKING...", false);
            rootAction.setText("CHECKING ROOT ACCESS...");
            rootAction.setEnabled(false);
            return;
        }

        rootAction.setEnabled(true);
        setActionColor(rootAction);

        if (granted) {
            setStatus(rootStatus, "GRANTED", true);
            rootAction.setText("CHECK ROOT ACCESS");
        } else {
            setStatus(rootStatus, "NOT GRANTED", false);
            rootAction.setText("GRANT ROOT ACCESS");
        }
    }

    private void updateStorageUi(boolean granted) {
        setStatus(storageStatus, granted ? "GRANTED" : "NOT GRANTED", granted);
        storageAction.setText(granted ? "MANAGE STORAGE ACCESS" : "ALLOW STORAGE ACCESS");
        storageAction.setEnabled(true);
        setActionColor(storageAction);
    }

    private void updateNotificationUi(boolean granted) {
        setStatus(notificationStatus, granted ? "ALLOWED" : "NOT ALLOWED", granted);
        notificationAction.setText(granted ? "NOTIFICATION SETTINGS" : "ALLOW NOTIFICATIONS");
        notificationAction.setEnabled(true);
        setActionColor(notificationAction);
    }

    private void updateBatteryUi(boolean disabled) {
        setStatus(
            batteryStatus,
            disabled ? "OPTIMIZATION DISABLED" : "OPTIMIZATION ENABLED",
            disabled
        );

        batteryAction.setText(disabled ? "BATTERY SETTINGS" : "DISABLE OPTIMIZATION");
        batteryAction.setEnabled(true);
        setActionColor(batteryAction);
    }

    private void setStatus(TextView view, String text, boolean granted) {
        view.setText(text);
        view.setTextColor(
            Color.parseColor(
                granted ? "#4CAF50" : "#E53935"
            )
        );
    }

    private void setActionColor(Button button) {
        button.setBackgroundTintList(
            ColorStateList.valueOf(
                Color.parseColor("#2563EB")
            )
        );
        button.setTextColor(Color.WHITE);
    }

    private void checkOrRequestRoot() {
        if (rootCheckRunning) {
            return;
        }

        rootCheckRunning = true;
        updateRootUi(false);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean granted = requestRootPermission();

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }

                        rootCheckRunning = false;
                        /*
                         * Keep Settings in sync with the onboarding flow.
                         * A successful root check writes exactly:
                         *
                         *   root-granted=true
                         *
                         * into the existing restored-data file.
                         */
                        setFlag(FLAG_ROOT_GRANTED, granted);
                        updateRootUi(granted);

                        if (!granted) {
                            Toast.makeText(
                                SettingsActivity.this,
                                "Root access was not granted.",
                                Toast.LENGTH_LONG
                            ).show();
                        }
                    }
                });
            }
        }, "SettingsRootCheck").start();
    }

    private boolean requestRootPermission() {
        Process process = null;
        DataOutputStream output = null;
        BufferedReader reader = null;

        try {
            process = Runtime.getRuntime().exec("su");
            output = new DataOutputStream(process.getOutputStream());
            output.writeBytes("id\n");
            output.writeBytes("exit\n");
            output.flush();

            reader = new BufferedReader(
                new InputStreamReader(process.getInputStream())
            );

            StringBuilder result = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                result.append(line).append('\n');
            }

            int exitCode = process.waitFor();

            return exitCode == 0 &&
                result.toString().contains("uid=0");

        } catch (Throwable error) {
            return false;

        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (Throwable ignored) {
            }

            try {
                if (output != null) {
                    output.close();
                }
            } catch (Throwable ignored) {
            }

            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }

        return checkSelfPermission(
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED;
    }

    private void openStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                );
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Throwable ignored) {
                try {
                    startActivity(
                        new Intent(
                            Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                        )
                    );
                } catch (Throwable error) {
                    showSettingsError();
                }
            }
            return;
        }

        if (!hasStoragePermission()) {
            requestPermissions(
                new String[] {
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                },
                REQUEST_STORAGE_PERMISSION
            );
            return;
        }

        openAppDetailsSettings();
    }

    private boolean hasNotificationPermission() {
        NotificationManager manager =
            (NotificationManager)
            getSystemService(Context.NOTIFICATION_SERVICE);

        boolean systemEnabled =
            manager != null &&
            manager.areNotificationsEnabled();

        if (Build.VERSION.SDK_INT < 33) {
            return systemEnabled;
        }

        boolean runtimeGranted =
            checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED;

        return runtimeGranted && systemEnabled;
    }

    private void openNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            if (
                !notificationRequestedThisSession ||
                shouldShowRequestPermissionRationale(
                    Manifest.permission.POST_NOTIFICATIONS
                )
            ) {
                notificationRequestedThisSession = true;

                requestPermissions(
                    new String[] {
                        Manifest.permission.POST_NOTIFICATIONS
                    },
                    REQUEST_NOTIFICATION_PERMISSION
                );
                return;
            }
        }

        try {
            Intent intent = new Intent(
                Settings.ACTION_APP_NOTIFICATION_SETTINGS
            );
            intent.putExtra(
                Settings.EXTRA_APP_PACKAGE,
                getPackageName()
            );
            startActivity(intent);
        } catch (Throwable error) {
            openAppDetailsSettings();
        }
    }

    private boolean isBatteryOptimizationDisabled() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }

        PowerManager manager =
            (PowerManager)
            getSystemService(Context.POWER_SERVICE);

        return manager != null &&
            manager.isIgnoringBatteryOptimizations(
                getPackageName()
            );
    }

    private void openBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(
                this,
                "Battery optimization is not used on this Android version.",
                Toast.LENGTH_SHORT
            ).show();
            return;
        }

        if (!isBatteryOptimizationDisabled()) {
            try {
                Intent intent = new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                );
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                return;
            } catch (Throwable ignored) {
            }
        }

        try {
            startActivity(
                new Intent(
                    Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                )
            );
        } catch (Throwable error) {
            openAppDetailsSettings();
        }
    }

    @Override
    public void onRequestPermissionsResult(
        int requestCode,
        String[] permissions,
        int[] grantResults
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        );

        if (
            requestCode == REQUEST_STORAGE_PERMISSION ||
            requestCode == REQUEST_NOTIFICATION_PERMISSION
        ) {
            refreshPermissionStates();
        }
    }

    private void openAppDetailsSettings() {
        try {
            Intent intent = new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            );
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Throwable error) {
            showSettingsError();
        }
    }

    private void showSettingsError() {
        Toast.makeText(
            this,
            "Could not open Android settings.",
            Toast.LENGTH_LONG
        ).show();
    }

    private boolean hasSavedFlag(String flag) {
        File file = new File(getFilesDir(), STATE_FILE);

        if (!file.exists()) {
            return false;
        }

        BufferedReader reader = null;

        try {
            reader = new BufferedReader(new FileReader(file));
            String line;

            while ((line = reader.readLine()) != null) {
                if (flag.equals(line.trim())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (Throwable ignored) {
            }
        }

        return false;
    }

    private void setFlag(String flag, boolean enabled) {
        File file = new File(getFilesDir(), STATE_FILE);
        List<String> lines = new ArrayList<String>();

        if (file.exists()) {
            BufferedReader reader = null;

            try {
                reader = new BufferedReader(new FileReader(file));
                String line;

                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();

                    if (
                        trimmed.length() > 0 &&
                        !flag.equals(trimmed)
                    ) {
                        lines.add(trimmed);
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                try {
                    if (reader != null) {
                        reader.close();
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        if (enabled) {
            lines.add(flag);
        }

        FileOutputStream output = null;

        try {
            output = new FileOutputStream(file, false);

            for (String line : lines) {
                output.write((line + "\n").getBytes("UTF-8"));
            }

            output.flush();

        } catch (Throwable error) {
            Toast.makeText(
                this,
                "Could not update permission state file.",
                Toast.LENGTH_LONG
            ).show();

        } finally {
            try {
                if (output != null) {
                    output.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
