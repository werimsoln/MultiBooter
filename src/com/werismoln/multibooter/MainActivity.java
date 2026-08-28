package com.werismoln.multibooter;

import com.werismoln.multibooter.R;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.RadioButton;
import android.view.ViewGroup;
import android.view.LayoutInflater;
import android.widget.RadioGroup;
import android.widget.Toast;
import android.view.View.OnClickListener;
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
import android.widget.ImageView;
import android.os.Handler;
import android.os.Looper;
import android.content.res.Configuration;
import android.app.NotificationManager;
import android.content.Context;
import android.os.PowerManager;

import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileReader;
import java.io.BufferedReader;

public class MainActivity extends Activity {
  private boolean button3_pushed = false;
  private boolean isRootGranted = false;
  private boolean isStorageGranted = false;
  private boolean isNotificationGranted = false;
  private boolean isNotificationRequested = false;
  private boolean isBatteryOptimizationDisabled = false;
  private NotificationManager notificationManager;

  @Override
  protected void onCreate(Bundle savedInstanceState) {

    super.onCreate(savedInstanceState);
    requestWindowFeature(Window.FEATURE_NO_TITLE);

    String data = "";

    File file = new File(getFilesDir(), "restored-data");

    Intent intent = new Intent(MainActivity.this, BootManager.class);

    try (BufferedReader br = new BufferedReader(new FileReader(file))) {
      String line;
      while ((line = br.readLine()) != null) {
        if (line.trim().equals("permissions-granted=true")) {
          startActivity(intent);
          finish();
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
    }

    setContentView(R.layout.startup_page);

    Button btnback = findViewById(R.id.button);
    Button btnnext = findViewById(R.id.button2);
    Button btngrant = findViewById(R.id.button3);
    Button btngrant2 = findViewById(R.id.button4);
    Button btngrant3 = findViewById(R.id.button5);
    Button btngrant4 = findViewById(R.id.button6);

    ViewFlipper viewflipper = findViewById(R.id.viewflipper);

    RadioGroup radiogroup = findViewById(R.id.radiogroup);
    RadioButton radio1 = findViewById(R.id.radio1);
    RadioButton radio2 = findViewById(R.id.radio2);
    RadioButton radio3 = findViewById(R.id.radio3);
    RadioButton radio4 = findViewById(R.id.radio4);
    RadioButton radio5 = findViewById(R.id.radio5);

    viewflipper.setInAnimation(MainActivity.this, R.anim.slide_in_right);
    viewflipper.setOutAnimation(MainActivity.this, R.anim.slide_out_left);
    radio1.setChecked(true);
    radio1.animate().alpha(1.0f).scaleX(0.5f).scaleY(0.5f).setDuration(250).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    btnback.setText("");
    btnback.setEnabled(false);

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
    radio5.setOnTouchListener(touchblocker);

    if (hasStoragePermission()) {
      btngrant2.setText("GRANTED");
      btngrant2.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#4CAF50")));
      btngrant2.setEnabled(false);
      isStorageGranted = true;
    }

    if (hasNotificationPermission()) {
      btngrant3.setText("ALLOWED");
      btngrant3.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#4CAF50")));
      btngrant3.setEnabled(false);
      isNotificationGranted = true;
    }

    if (hasBatteryOptimizationBeenDisabled()) {
      btngrant4.setText("DISABLED");
      btngrant4.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#4CAF50")));
      btngrant4.setEnabled(false);
      isBatteryOptimizationDisabled = true;
    }

    btnback.setOnClickListener(new View.OnClickListener() {
      @Override

      public void onClick(View v) {

        viewflipper.setInAnimation(MainActivity.this, R.anim.slide_in_left);
        viewflipper.setOutAnimation(MainActivity.this, R.anim.slide_out_right);

        if (radio1.isChecked()) {
          radio1.setChecked(true);
        } else if (radio2.isChecked()) {
          radio2.animate().alpha(1.0f).scaleX(0.4f).scaleY(0.4f).setDuration(250).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
          radio1.animate().alpha(1.0f).scaleX(0.5f).scaleY(0.5f).setDuration(250).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
          int current = viewflipper.getDisplayedChild();
          viewflipper.setDisplayedChild(current - 1);
          radio1.setChecked(true);
          radio2.setChecked(false);
          btnback.setText("");
          btnback.setEnabled(false);
        } else if (radio3.isChecked()) {
          radio3.animate().alpha(1.0f).scaleX(0.4f).scaleY(0.4f).setDuration(250).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
          radio2.animate().alpha(1.0f).scaleX(0.5f).scaleY(0.5f).setDuration(250).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
          int current = viewflipper.getDisplayedChild();
          viewflipper.setDisplayedChild(current - 1);
          radio2.setChecked(true);
          radio3.setChecked(false);
          if (!btnnext.isEnabled() || btnnext.getText().toString().isEmpty()) {
            btnnext.setText("NEXT >");
            btnnext.setEnabled(true);
          }
        } else if (radio4.isChecked()) {
          radio4.animate().alpha(1.0f).scaleX(0.4f).scaleY(0.4f).setDuration(250).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
          radio3.animate().alpha(1.0f).scaleX(0.5f).scaleY(0.5f).setDuration(250).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
          int current = viewflipper.getDisplayedChild();
          viewflipper.setDisplayedChild(current - 1);
          radio3.setChecked(true);
          radio4.setChecked(false);
          if (!btnnext.isEnabled() || btnnext.getText().toString().isEmpty()) {
            btnnext.setText("NEXT >");
            btnnext.setEnabled(true);
          }
        } else if (radio5.isChecked()) {
          radio5.animate().alpha(1.0f).scaleX(0.4f).scaleY(0.4f).setDuration(250).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
          radio4.animate().alpha(1.0f).scaleX(0.5f).scaleY(0.5f).setDuration(250).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
          int current = viewflipper.getDisplayedChild();
          viewflipper.setDisplayedChild(current - 1);
          radio4.setChecked(true);
          radio5.setChecked(false);
          if (btnnext.getText().toString() == "FINISH" || !btnnext.isEnabled() || btnnext.getText().toString().isEmpty()) {
            btnnext.setText("NEXT >");
            btnnext.setEnabled(true);
          }
        }

      }

    });

    btnnext.setOnClickListener(new View.OnClickListener() {

      @Override
      public void onClick(View v) {

        viewflipper.setInAnimation(MainActivity.this, R.anim.slide_in_right);
        viewflipper.setOutAnimation(MainActivity.this, R.anim.slide_out_left);

        if (radio1.isChecked()) {
          radio1.animate().alpha(1.0f).scaleX(0.4f).scaleY(0.4f).setDuration(250).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
          radio2.animate().alpha(1.0f).scaleX(0.5f).scaleY(0.5f).setDuration(250).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
          viewflipper.showNext();
          radio1.setChecked(false);
          radio2.setChecked(true);
          btnback.setText("< BACK");
          btnback.setEnabled(true);
        } else if (radio2.isChecked()) {
          radio2.animate().alpha(1.0f).scaleX(0.4f).scaleY(0.4f).setDuration(250).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
          radio3.animate().alpha(1.0f).scaleX(0.5f).scaleY(0.5f).setDuration(250).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
          viewflipper.showNext();
          radio2.setChecked(false);
          radio3.setChecked(true);
          if (!isStorageGranted) {
            btnnext.setText("");
            btnnext.setEnabled(false);
          }
        } else if (radio3.isChecked()) {
          radio3.animate().alpha(1.0f).scaleX(0.4f).scaleY(0.4f).setDuration(250).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
          radio4.animate().alpha(1.0f).scaleX(0.5f).scaleY(0.5f).setDuration(250).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
          viewflipper.showNext();
          radio3.setChecked(false);
          radio4.setChecked(true);
          if (!isNotificationGranted) {
            btnnext.setText("");
            btnnext.setEnabled(false);
          }
        } else if (radio4.isChecked()) {
          radio4.animate().alpha(1.0f).scaleX(0.4f).scaleY(0.4f).setDuration(250).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
          radio5.animate().alpha(1.0f).scaleX(0.5f).scaleY(0.5f).setDuration(250).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
          viewflipper.showNext();
          radio4.setChecked(false);
          radio5.setChecked(true);
          btnnext.setText("FINISH");
          if (!isBatteryOptimizationDisabled) {
            btnnext.setText("");
            btnnext.setEnabled(false);
          }
        } else if (radio5.isChecked()) {
          try {
            String data = "permissions-granted=true\n";
            FileOutputStream fos2 = new FileOutputStream(new File(getFilesDir(), "restored-data"), true);
            fos2.write(data.getBytes("UTF-8"));
            fos2.close();
          } catch (Exception e) {
            e.printStackTrace();
          }
          startActivity(intent);
          finish();
        }

      }

    });

    btngrant.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        btngrant.setEnabled(false);
        button3_pushed = true;
        new Thread(new Runnable() {
          @Override
          public void run() {
            boolean grantedResult = requestRootPermission();

            runOnUiThread(new Runnable() {
              @Override
              public void run() {

                isRootGranted = grantedResult;

                if (isRootGranted) {
                  btngrant.setEnabled(false);
                  btngrant.setText("GRANTED");
                  btngrant.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#4CAF50")));
                  try {
                    DataOutputStream os2 = null;
                    Process process2 = Runtime.getRuntime().exec("sh");
                    os2 = new DataOutputStream(process2.getOutputStream());
                    os2.writeBytes("echo \"root-granted=true\" > /data/data/com.werismoln.multibooter/files/restored-data\n");
                    os2.writeBytes("exit\n");
                    os2.flush();
                  } catch (Exception e) {
                    e.printStackTrace();
                  }
                } else {
                  Toast.makeText(MainActivity.this, "Please check if this device is rooted and try again!", Toast.LENGTH_LONG).show();
                  btngrant.setEnabled(true);
                  btngrant.setText("TRY AGAIN");
                  btngrant.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#E53935")));
                }
              }
            });
          }
        }).start();
      }
    });

    btngrant2.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        if (!hasStoragePermission()) {
          requestStoragePermission();
        }
      }
    });

    btngrant3.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        if (!hasNotificationPermission() && Build.VERSION.SDK_INT >= 33) {
          requestNotificationPermission();
        }
      }
    });

    btngrant4.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        disableBatteryOptimization();
      }
    });

    btnback.setOnTouchListener((v, event) -> {
      if (event.getAction() == MotionEvent.ACTION_DOWN) {
        v.setAlpha(0.4f);
      } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
        v.animate().alpha(1.0f).setDuration(300).start();
      }
      return false;
    });

    btnnext.setOnTouchListener((v, event) -> {
      if (event.getAction() == MotionEvent.ACTION_DOWN) {
        v.setAlpha(0.4f);
      } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
        v.animate().alpha(1.0f).setDuration(300).start();
      }
      return false;

    });

    btngrant.setOnTouchListener((v, event) -> {
      if (event.getAction() == MotionEvent.ACTION_DOWN) {
        v.setAlpha(0.4f);
      } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
        v.animate().alpha(1.0f).setDuration(300).start();
      }
      return false;

    });

    btngrant2.setOnTouchListener((v, event) -> {
      if (event.getAction() == MotionEvent.ACTION_DOWN) {
        v.setAlpha(0.4f);
      } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
        v.animate().alpha(1.0f).setDuration(300).start();
      }
      return false;

    });

    btngrant3.setOnTouchListener((v, event) -> {
      if (event.getAction() == MotionEvent.ACTION_DOWN) {
        v.setAlpha(0.4f);
      } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
        v.animate().alpha(1.0f).setDuration(300).start();
      }
      return false;

    });

    btngrant4.setOnTouchListener((v, event) -> {
      if (event.getAction() == MotionEvent.ACTION_DOWN) {
        v.setAlpha(0.4f);
      } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
        v.animate().alpha(1.0f).setDuration(300).start();
      }
      return false;

    });

    if (savedInstanceState == null) {
      viewflipper.setDisplayedChild(0);
      updateUI(0);
    }

  }

  @Override
  protected void onResume() {
    super.onResume();

    Button btngrant4 = findViewById(R.id.button6);
    Button btngrant3 = findViewById(R.id.button5);
    Button btngrant2 = findViewById(R.id.button4);
    Button btnnext = findViewById(R.id.button2);
    RadioButton radio5 = findViewById(R.id.radio5);
    RadioButton radio4 = findViewById(R.id.radio4);
    RadioButton radio3 = findViewById(R.id.radio3);

    if (radio3.isChecked() && btngrant2 != null && hasStoragePermission()) {

      btngrant2.setEnabled(false);
      btngrant2.setText("GRANTED");
      btngrant2.setBackgroundTintList(
        ColorStateList.valueOf(
          Color.parseColor("#4CAF50")
        )
      );
      btnnext.setEnabled(true);
      btnnext.setText("NEXT >");
      isStorageGranted = true;
    }
    if (radio4.isChecked() && btngrant3 != null && hasNotificationPermission()) {

      btngrant3.setEnabled(false);
      btngrant3.setText("ALLOWED");
      btngrant3.setBackgroundTintList(
        ColorStateList.valueOf(
          Color.parseColor("#4CAF50")
        )
      );
      btnnext.setEnabled(true);
      btnnext.setText("NEXT >");
      isNotificationGranted = true;
    }

    if (radio5.isChecked() && btngrant4 != null && hasNotificationPermission()) {

      btngrant4.setEnabled(false);
      btngrant4.setText("DISABLED");
      btngrant4.setBackgroundTintList(
        ColorStateList.valueOf(
          Color.parseColor("#4CAF50")
        )
      );
      btnnext.setEnabled(true);
      btnnext.setText("FINISH");
      isBatteryOptimizationDisabled = true;
    }

  }

  private void requestStoragePermission() {

    try {
      Intent intent = new Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
      );

      intent.setData(
        Uri.parse("package:" + getPackageName())
      );

      startActivity(intent);

    } catch (Exception e) {

      Intent intent = new Intent(
        Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
      );

      startActivity(intent);
    }
  }

  private void requestNotificationPermission() {
    if (!isNotificationRequested && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
      requestPermissions(new String[] {
        Manifest.permission.POST_NOTIFICATIONS
      }, 101);
      isNotificationRequested = true;
    } else {
      Toast.makeText(this, "Please enable notifications from notification settings.", Toast.LENGTH_LONG).show();
      Intent intent2 = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
      intent2.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
      startActivity(intent2);
    }
  }

  private boolean hasStoragePermission() {

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {

      return Environment.isExternalStorageManager();

    } else {

      return checkSelfPermission(
        Manifest.permission.WRITE_EXTERNAL_STORAGE
      ) == PackageManager.PERMISSION_GRANTED;
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    ViewFlipper viewflipper = findViewById(R.id.viewflipper);
    super.onSaveInstanceState(outState);

    outState.putBoolean("IS_ROOT_GRANTED", isRootGranted);
    outState.putBoolean("BUTTON3_PUSHED", button3_pushed);

    if (viewflipper != null) {

      outState.putInt("SAVED_PAGE_INDEX", viewflipper.getDisplayedChild());

    }

  }

  @Override
  protected void onRestoreInstanceState(Bundle savedInstanceState) {

    ViewFlipper viewflipper = findViewById(R.id.viewflipper);

    super.onRestoreInstanceState(savedInstanceState);

    if (savedInstanceState != null) {

      isRootGranted = savedInstanceState.getBoolean(
        "IS_ROOT_GRANTED",
        false
      );

      button3_pushed = savedInstanceState.getBoolean(
        "BUTTON3_PUSHED",
        false
      );

      Button btngrant = findViewById(R.id.button3);

      if (btngrant != null) {

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
      }

      int savedIndex = savedInstanceState.getInt(
        "SAVED_PAGE_INDEX",
        0
      );

      if (viewflipper != null) {
        viewflipper.setDisplayedChild(savedIndex);
        updateUI(savedIndex);
      }
    }
  }

  private void updateUI(int position) {

    Button btnback = findViewById(R.id.button);
    Button btnnext = findViewById(R.id.button2);
    Button btngrant = findViewById(R.id.button3);
    Button btngrant2 = findViewById(R.id.button4);
    Button btngrant3 = findViewById(R.id.button5);

    ViewFlipper viewflipper = findViewById(R.id.viewflipper);
    Intent intent = new Intent(MainActivity.this, BootManager.class);

    RadioGroup radiogroup = findViewById(R.id.radiogroup);
    RadioButton radio1 = findViewById(R.id.radio1);
    RadioButton radio2 = findViewById(R.id.radio2);
    RadioButton radio3 = findViewById(R.id.radio3);
    RadioButton radio4 = findViewById(R.id.radio4);
    RadioButton radio5 = findViewById(R.id.radio5);
    RadioButton[] radios = {
      radio1,
      radio2,
      radio3,
      radio4,
      radio5
    };

    for (int i = 0; i < radios.length; i++) {
      if (radios[i] == null) continue;
      if (i == position) {
        radios[i].setChecked(true);
        radios[i].animate().alpha(1.0f).scaleX(0.5f).scaleY(0.5f).setDuration(250).start();
      } else {
        radios[i].setChecked(false);
        radios[i].animate().alpha(1.0f).scaleX(0.4f).scaleY(0.4f).setDuration(250).start();
      }
    }

    boolean isFirstPage = (position == 0);
    btnback.setEnabled(!isFirstPage);
    btnback.setText(isFirstPage ? "" : "< BACK");

    boolean isLastPage = (position == radios.length - 1);
    btnnext.setText(isLastPage ? "FINISH" : "NEXT >");

  }

  private boolean requestRootPermission() {
    Process process = null;
    DataOutputStream os = null;
    try {
      process = Runtime.getRuntime().exec("su");
      os = new DataOutputStream(process.getOutputStream());
      os.writeBytes("id\n");
      os.writeBytes("exit\n");
      os.flush();

      int exitCode = process.waitFor();
      return (exitCode == 0);
    } catch (Exception e) {
      e.printStackTrace();
      return false;
    } finally {
      try {
        if (os != null) os.close();
        if (process != null) process.destroy();
      } catch (Exception ignored) {}
    }
  }

  private boolean hasNotificationPermission() {
    notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    if (android.os.Build.VERSION.SDK_INT < 33) {
      return true;
    } else {
      boolean hasRuntimePermission = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
      boolean isSystemEnabled = notificationManager != null && notificationManager.areNotificationsEnabled();
      return hasRuntimePermission && isSystemEnabled;
    }
  }

  public void disableBatteryOptimization() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);

      if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
        try {
          Intent intent3 = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
          intent3.setData(Uri.parse("package:" + getPackageName()));
          startActivity(intent3);
        } catch (Exception e) {
          Intent intent3 = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
          startActivity(intent3);
        }
      }
    }
  }
  public boolean hasBatteryOptimizationBeenDisabled() {
    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
    return pm.isIgnoringBatteryOptimizations(getPackageName());
  }
}
