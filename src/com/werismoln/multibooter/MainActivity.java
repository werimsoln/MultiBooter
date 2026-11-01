/******************************************************************************
 * MainActivity.java
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

import com.werismoln.multibooter.R;

import android.app.Activity;
import android.app.NotificationManager;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;
import android.content.Intent;
import android.view.MotionEvent;
import android.widget.ViewFlipper;
import android.graphics.Color;
import android.content.res.ColorStateList;
import android.os.Environment;
import android.provider.Settings;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.Manifest;
import android.content.Context;
import android.os.PowerManager;
import android.view.animation.DecelerateInterpolator;

import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public class MainActivity extends BaseActivity {
    private static final int REQUEST_NOTIFICATION_PERMISSION = 101;

    /** Index of the last onboarding page (4 pages: 0..3). */
    private static final int LAST_PAGE = 3;

    private static final String STATE_FILE = "restored-data";
    private static final String FLAG_ROOT_GRANTED = "root-granted=true";
    private static final String FLAG_PERMISSIONS_GRANTED = "permissions-granted=true";

    private boolean button3_pushed = false;
    private boolean isRootGranted = false;
    private boolean isNotificationGranted = false;
    private boolean isNotificationRequested = false;
    private boolean isBatteryOptimizationDisabled = false;

    private NotificationManager notificationManager;

    private ViewFlipper viewflipper;

    private Button btnback;
    private Button btnnext;
    private Button btngrant;
    private Button btnnotify;
    private Button btnbattery;

    private RadioGroup radiogroup;
    private RadioButton radio1;
    private RadioButton radio2;
    private RadioButton radio3;
    private RadioButton radio4;

    private final DecelerateInterpolator decelerateInterpolator =
        new DecelerateInterpolator();

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        if (hasSavedFlag(FLAG_PERMISSIONS_GRANTED)) {
            openBootManager();
            return;
        }

        setContentView(R.layout.startup_page);

        bindViews();

        isRootGranted = hasSavedFlag(FLAG_ROOT_GRANTED);

        int savedPage = 0;

        if (savedInstanceState != null) {
            savedPage = savedInstanceState.getInt("SAVED_PAGE_INDEX", 0);
            button3_pushed = savedInstanceState.getBoolean("BUTTON3_PUSHED", false);
            isNotificationRequested =
                savedInstanceState.getBoolean("NOTIFICATION_REQUESTED", false);
        }

        if (savedPage < 0 || savedPage > 3) {
            savedPage = 0;
        }

        setupRadioButtons();
        setupClickListeners();
        setupTouchAnimations();

        refreshPermissionStates();
        updateGrantButtons();

        viewflipper.setDisplayedChild(savedPage);
        updateUI(savedPage, false);
    }

    private void bindViews() {

        btnback = (Button) findViewById(R.id.button);
        btnnext = (Button) findViewById(R.id.button2);
        btngrant = (Button) findViewById(R.id.button3);
        btnnotify = (Button) findViewById(R.id.button5);
        btnbattery = (Button) findViewById(R.id.button6);

        viewflipper = (ViewFlipper) findViewById(R.id.viewflipper);

        radiogroup = (RadioGroup) findViewById(R.id.radiogroup);
        radio1 = (RadioButton) findViewById(R.id.radio1);
        radio2 = (RadioButton) findViewById(R.id.radio2);
        radio3 = (RadioButton) findViewById(R.id.radio3);
        radio4 = (RadioButton) findViewById(R.id.radio4);
    }

    private void setupRadioButtons() {

        View.OnTouchListener touchblocker = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return true;
            }
        };

        radio1.setOnTouchListener(touchblocker);
        radio2.setOnTouchListener(touchblocker);
        radio3.setOnTouchListener(touchblocker);
        radio4.setOnTouchListener(touchblocker);
    }

    private void setupClickListeners() {

        btnback.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goBack();
            }
        });

        btnnext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goNext();
            }
        });

        btngrant.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestRoot();
            }
        });

        

        btnnotify.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!hasNotificationPermission()) {
                    requestNotificationPermission();
                }
            }
        });

        btnbattery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                disableBatteryOptimization();
            }
        });
    }

    private void setupTouchAnimations() {

        View.OnTouchListener buttonTouchListener = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {

                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    v.animate().cancel();
                    v.setAlpha(0.4f);
                } else if (
                    event.getAction() == MotionEvent.ACTION_UP ||
                    event.getAction() == MotionEvent.ACTION_CANCEL
                ) {
                    v.animate()
                        .alpha(1.0f)
                        .setDuration(300)
                        .start();
                }

                return false;
            }
        };

        btnback.setOnTouchListener(buttonTouchListener);
        btnnext.setOnTouchListener(buttonTouchListener);
        btngrant.setOnTouchListener(buttonTouchListener);
        btnnotify.setOnTouchListener(buttonTouchListener);
        btnbattery.setOnTouchListener(buttonTouchListener);
    }

    private void goNext() {

        int current = viewflipper.getDisplayedChild();

        /*
         * 0 = Welcome
         * 1 = Root                  (optional)
         * 2 = Notifications         (optional)
         * 3 = Battery optimization  (optional)
         *
         * No page blocks the user: every permission is optional and the
         * app explains what is lost if it is not granted.
         */
        if (current >= LAST_PAGE) {
            finishSetup();
            return;
        }

        viewflipper.setInAnimation(
            MainActivity.this,
            R.anim.slide_in_right
        );

        viewflipper.setOutAnimation(
            MainActivity.this,
            R.anim.slide_out_left
        );

        int next = current + 1;

        viewflipper.setDisplayedChild(next);

        updateUI(next, true);
    }

    private void goBack() {

        int current = viewflipper.getDisplayedChild();

        if (current <= 0) {
            return;
        }

        viewflipper.setInAnimation(
            MainActivity.this,
            R.anim.slide_in_left
        );

        viewflipper.setOutAnimation(
            MainActivity.this,
            R.anim.slide_out_right
        );

        int previous = current - 1;
        viewflipper.setDisplayedChild(previous);
        updateUI(previous, true);
    }

    private void updateUI(int position, boolean animate) {

        updateRadioIndicators(position, animate);

        if (position == 0) {
            btnback.setText("");
            btnback.setEnabled(false);
        } else {
            btnback.setText("< BACK");
            btnback.setEnabled(true);
        }

        btnnext.setEnabled(true);

        if (position >= LAST_PAGE) {
            btnnext.setText("FINISH");
        } else {
            btnnext.setText("NEXT >");
        }
    }

    private void updateRadioIndicators(int position, boolean animate) {

        RadioButton[] radios = {
            radio1,
            radio2,
            radio3,
            radio4
        };

        for (int i = 0; i < radios.length; i++) {

            RadioButton radio = radios[i];

            boolean selected = (i == position);

            radio.setChecked(selected);
            radio.animate().cancel();

            float scale = selected ? 0.5f : 0.4f;

            if (animate) {
                radio.animate()
                    .alpha(1.0f)
                    .scaleX(scale)
                    .scaleY(scale)
                    .setDuration(250)
                    .setInterpolator(decelerateInterpolator)
                    .start();
            } else {
                radio.setAlpha(1.0f);
                radio.setScaleX(scale);
                radio.setScaleY(scale);
            }
        }
    }

    private void refreshPermissionStates() {

        isRootGranted = hasSavedFlag(FLAG_ROOT_GRANTED);
        isNotificationGranted = hasNotificationPermission();
        isBatteryOptimizationDisabled =
            hasBatteryOptimizationBeenDisabled();
    }

    private void updateGrantButtons() {

        /*
         * Root butonu.
         * Mevcut renkler aynen korunmuştur.
         */
        if (isRootGranted) {

            btngrant.setEnabled(false);
            btngrant.setText("GRANTED");
            btngrant.setBackgroundTintList(
                ColorStateList.valueOf(
                    Color.parseColor("#4CAF50")
                )
            );

        } else if (button3_pushed) {

            btngrant.setEnabled(true);
            btngrant.setText("TRY AGAIN");
            btngrant.setBackgroundTintList(
                ColorStateList.valueOf(
                    Color.parseColor("#E53935")
                )
            );

        } else {

            btngrant.setEnabled(true);
            btngrant.setText("GRANT ROOT PERMISSION");
            btngrant.setBackgroundTintList(
                ColorStateList.valueOf(
                    Color.parseColor("#2563EB")
                )
            );
        }

        

        /*
         * Notification butonu.
         */
        if (isNotificationGranted) {

            btnnotify.setText("ALLOWED");
            btnnotify.setBackgroundTintList(
                ColorStateList.valueOf(
                    Color.parseColor("#4CAF50")
                )
            );
            btnnotify.setEnabled(false);

        } else {

            btnnotify.setText("ALLOW NOTIFICATIONS");
            btnnotify.setBackgroundTintList(
                ColorStateList.valueOf(
                    Color.parseColor("#2563EB")
                )
            );
            btnnotify.setEnabled(true);
        }

        /*
         * Battery optimization butonu.
         */
        if (isBatteryOptimizationDisabled) {

            btnbattery.setText("DISABLED");
            btnbattery.setBackgroundTintList(
                ColorStateList.valueOf(
                    Color.parseColor("#4CAF50")
                )
            );
            btnbattery.setEnabled(false);

        } else {

            btnbattery.setText("DISABLE");
            btnbattery.setBackgroundTintList(
                ColorStateList.valueOf(
                    Color.parseColor("#2563EB")
                )
            );
            btnbattery.setEnabled(true);
        }
    }

    private void requestRoot() {

        btngrant.setEnabled(false);
        button3_pushed = true;

        new Thread(new Runnable() {
            @Override
            public void run() {

                final boolean grantedResult =
                    requestRootPermission();

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {

                        isRootGranted = grantedResult;

                        if (isRootGranted) {

                            saveFlag(FLAG_ROOT_GRANTED);

                            btngrant.setEnabled(false);
                            btngrant.setText("GRANTED");
                            btngrant.setBackgroundTintList(
                                ColorStateList.valueOf(
                                    Color.parseColor("#4CAF50")
                                )
                            );

                        } else {

                            Toast.makeText(
                                MainActivity.this,
                                "Please check if this device is rooted and try again!",
                                Toast.LENGTH_LONG
                            ).show();

                            btngrant.setEnabled(true);
                            btngrant.setText("TRY AGAIN");
                            btngrant.setBackgroundTintList(
                                ColorStateList.valueOf(
                                    Color.parseColor("#E53935")
                                )
                            );
                        }
                    }
                });
            }
        }).start();
    }

    private boolean requestRootPermission() {

        Process process = null;
        DataOutputStream os = null;
        BufferedReader reader = null;

        try {

            process = Runtime.getRuntime().exec("su");

            os = new DataOutputStream(
                process.getOutputStream()
            );

            os.writeBytes("id\n");
            os.writeBytes("exit\n");
            os.flush();

            reader = new BufferedReader(
                new InputStreamReader(
                    process.getInputStream()
                )
            );

            StringBuilder output =
                new StringBuilder();

            String line;

            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }

            int exitCode = process.waitFor();

            return exitCode == 0 &&
                output.toString().contains("uid=0");

        } catch (Exception e) {

            e.printStackTrace();
            return false;

        } finally {

            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (Exception ignored) {
            }

            try {
                if (os != null) {
                    os.close();
                }
            } catch (Exception ignored) {
            }

            if (process != null) {
                process.destroy();
            }
        }
    }

    

    private void requestNotificationPermission() {

        if (Build.VERSION.SDK_INT < 33) {
            isNotificationGranted = true;
            updateGrantButtons();
            updateUI(viewflipper.getDisplayedChild(), false);
            return;
        }

        if (hasNotificationPermission()) {
            return;
        }

        /*
         * İlk istek.
         */
        if (!isNotificationRequested) {

            isNotificationRequested = true;

            requestPermissions(
                new String[] {
                    Manifest.permission.POST_NOTIFICATIONS
                },
                REQUEST_NOTIFICATION_PERMISSION
            );

            return;
        }

        /*
         * Android tekrar izin penceresi göstermeye izin veriyorsa
         * tekrar iste.
         */
        if (
            shouldShowRequestPermissionRationale(
                Manifest.permission.POST_NOTIFICATIONS
            )
        ) {

            requestPermissions(
                new String[] {
                    Manifest.permission.POST_NOTIFICATIONS
                },
                REQUEST_NOTIFICATION_PERMISSION
            );

            return;
        }

        /*
         * Kullanıcı izni kalıcı olarak reddettiyse ayarlar sayfasını aç.
         */
        Toast.makeText(
            this,
            "Please enable notifications from notification settings.",
            Toast.LENGTH_LONG
        ).show();

        Intent intent = new Intent(
            Settings.ACTION_APP_NOTIFICATION_SETTINGS
        );

        intent.putExtra(
            Settings.EXTRA_APP_PACKAGE,
            getPackageName()
        );

        startActivity(intent);
    }

    private boolean hasNotificationPermission() {

        /*
         * Android 12L ve altı için runtime POST_NOTIFICATIONS
         * izni bulunmaz.
         */
        if (Build.VERSION.SDK_INT < 33) {
            return true;
        }

        notificationManager =
            (NotificationManager)
            getSystemService(Context.NOTIFICATION_SERVICE);

        boolean hasRuntimePermission =
            checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED;

        boolean isSystemEnabled =
            notificationManager != null &&
            notificationManager.areNotificationsEnabled();

        return hasRuntimePermission && isSystemEnabled;
    }

    public void disableBatteryOptimization() {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }

        PowerManager pm =
            (PowerManager)
            getSystemService(Context.POWER_SERVICE);

        if (
            pm != null &&
            !pm.isIgnoringBatteryOptimizations(
                getPackageName()
            )
        ) {

            try {

                Intent intent = new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                );

                intent.setData(
                    Uri.parse(
                        "package:" + getPackageName()
                    )
                );

                startActivity(intent);

            } catch (Exception e) {

                Intent intent = new Intent(
                    Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                );

                startActivity(intent);
            }
        }
    }

    public boolean hasBatteryOptimizationBeenDisabled() {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }

        PowerManager pm =
            (PowerManager)
            getSystemService(Context.POWER_SERVICE);

        return pm != null &&
            pm.isIgnoringBatteryOptimizations(
                getPackageName()
            );
    }

    @Override
    protected void onResume() {

        super.onResume();

        /*
         * Kurulum tamamlanmışsa Activity bindViews() aşamasına
         * gelmeden kapanmış olabilir.
         */
        if (viewflipper == null) {
            return;
        }

        refreshPermissionStates();
        updateGrantButtons();
        updateUI(viewflipper.getDisplayedChild(), false);
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
            requestCode == REQUEST_NOTIFICATION_PERMISSION
        ) {

            refreshPermissionStates();
            updateGrantButtons();
            updateUI(
                viewflipper.getDisplayedChild(),
                false
            );
        }
    }

    private boolean hasSavedFlag(String flag) {

        File file = new File(
            getFilesDir(),
            STATE_FILE
        );

        if (!file.exists()) {
            return false;
        }

        BufferedReader br = null;

        try {

            br = new BufferedReader(
                new FileReader(file)
            );

            String line;

            while ((line = br.readLine()) != null) {

                if (flag.equals(line.trim())) {
                    return true;
                }
            }

        } catch (Exception e) {

            e.printStackTrace();

        } finally {

            try {
                if (br != null) {
                    br.close();
                }
            } catch (Exception ignored) {
            }
        }

        return false;
    }

    private void saveFlag(String flag) {

        /*
         * Aynı satırı dosyaya tekrar tekrar yazma.
         */
        if (hasSavedFlag(flag)) {
            return;
        }

        FileOutputStream fos = null;

        try {

            File file = new File(
                getFilesDir(),
                STATE_FILE
            );

            fos = new FileOutputStream(
                file,
                true
            );

            fos.write(
                (flag + "\n").getBytes("UTF-8")
            );

            fos.flush();

        } catch (Exception e) {

            e.printStackTrace();

        } finally {

            try {
                if (fos != null) {
                    fos.close();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void finishSetup() {

        /*
         * Nothing is mandatory. Root, notifications and the battery
         * optimization exemption can all be skipped; the related features
         * are simply limited and the app says why where it matters.
         */
        saveFlag(FLAG_PERMISSIONS_GRANTED);

        openBootManager();
    }

    private void openBootManager() {

        Intent intent = new Intent(
            MainActivity.this,
            BootManager.class
        );

        startActivity(intent);
        finish();
    }

    @Override
    protected void onSaveInstanceState(
        Bundle outState
    ) {

        super.onSaveInstanceState(outState);

        outState.putBoolean(
            "BUTTON3_PUSHED",
            button3_pushed
        );

        outState.putBoolean(
            "NOTIFICATION_REQUESTED",
            isNotificationRequested
        );

        if (viewflipper != null) {

            outState.putInt(
                "SAVED_PAGE_INDEX",
                viewflipper.getDisplayedChild()
            );
        }
    }

    @Override
    public void onBackPressed() {

        if (
            viewflipper != null &&
            viewflipper.getDisplayedChild() > 0
        ) {

            goBack();
            return;
        }

        super.onBackPressed();
    }
}
