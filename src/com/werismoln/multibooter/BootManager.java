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
import android.widget.LinearLayout;
import android.widget.TextView;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

public class BootManager extends Activity {

  private LinearLayout cardtftp;
  private LinearLayout cardusb;
  private LinearLayout cardgadget;
  private LinearLayout cardventoy;
  private LinearLayout sideDrawer;
  private View drawerOverlay;
  private boolean isDrawerOpen = false;
  private int selectedBoot = 0;

  @Override
  protected void onCreate(Bundle savedInstanceState) {

    super.onCreate(savedInstanceState);
    setContentView(R.layout.boot_manager);

    if (savedInstanceState != null) {
      selectedBoot = savedInstanceState.getInt("selected_boot", 0);
      isDrawerOpen = savedInstanceState.getBoolean("drawer_open", false);
    }

    sideDrawer = (LinearLayout) findViewById(R.id.side_drawer);
    drawerOverlay = findViewById(R.id.drawer_overlay);
    ImageView btnMenu = (ImageView) findViewById(R.id.btn_menu);
    cardusb = findViewById(R.id.card_usb);
    cardventoy = findViewById(R.id.card_usb2);
    cardgadget = findViewById(R.id.card_gadget);
    cardtftp = findViewById(R.id.card_tftp);

    addTouchAnimation(cardventoy, 1);
    addTouchAnimation(cardusb, 2);
    addTouchAnimation(cardgadget, 3);
    addTouchAnimation(cardtftp, 4);

    cardgadget.setEnabled(false);
    cardgadget.setAlpha(0.5 f);
    cardtftp.setEnabled(false);
    cardtftp.setAlpha(0.5 f);

    resetCard(cardusb);
    resetCard(cardgadget);
    resetCard(cardtftp);
    resetCard(cardventoy);

    if (isRootGrantedSaved()) {
      cardgadget.setEnabled(true);
      cardgadget.setAlpha(1.0 f);
      cardtftp.setEnabled(true);
      cardtftp.setAlpha(1.0 f);
    }

    restorestate();

    btnMenu.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        if (isDrawerOpen) {
          closeDrawer();
        } else {
          openDrawer();
        }
      }
    });

    drawerOverlay.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        closeDrawer();
      }
    });

    TextView menuUsb = (TextView) findViewById(R.id.menu_item_usb);
    menuUsb.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        closeDrawer();
      }
    });

  }

  private void selectBoot(int boot) {

    selectedBoot = boot;

    resetCard(cardusb);
    resetCard(cardgadget);
    resetCard(cardtftp);
    resetCard(cardventoy);

    if (boot == 1) {
      cardventoy.setSelected(true);
      scaleSelected(cardventoy);
    } else if (boot == 2) {
      cardusb.setSelected(true);
      scaleSelected(cardusb);
    } else if (boot == 3) {
      cardgadget.setSelected(true);
      scaleSelected(cardgadget);
    } else if (boot == 4) {
      cardtftp.setSelected(true);
      scaleSelected(cardtftp);
    }
  }

  private void addTouchAnimation(final View view, final int boot) {

    view.setOnTouchListener(new View.OnTouchListener() {

      @Override
      public boolean onTouch(View v, MotionEvent event) {

        switch (event.getAction()) {

        case MotionEvent.ACTION_DOWN:

          view.animate()
            .scaleX(0.95 f)
            .scaleY(0.95 f)
            .setDuration(70)
            .start();

          return true;

        case MotionEvent.ACTION_UP:

          view.animate()
            .scaleX(1.00 f)
            .scaleY(1.00 f)
            .setDuration(120)
            .start();

          selectBoot(boot);

          return true;

        case MotionEvent.ACTION_CANCEL:

          view.animate()
            .scaleX(0.97 f)
            .scaleY(0.97 f)
            .setDuration(100)
            .start();

          return true;
        }

        return true;
      }
    });
  }

  private void resetCard(View view) {

    view.setSelected(false);

    view.animate()
      .scaleX(0.97 f)
      .scaleY(0.97 f)
      .setDuration(100)
      .start();
  }

  private void scaleSelected(View view) {

    view.animate()
      .scaleX(1.00 f)
      .scaleY(1.00 f)
      .setDuration(100)
      .start();
  }

  private void scaleSelectedFaster(View view) {

    view.animate()
      .scaleX(1.00 f)
      .scaleY(1.00 f)
      .setDuration(0)
      .start();
  }

  private boolean isRootGrantedSaved() {
    File file = new File(getFilesDir(), "restored-data");

    if (!file.exists()) {
      return false;
    }

    try (BufferedReader br = new BufferedReader(new FileReader(file))) {
      String line;
      while ((line = br.readLine()) != null) {
        if (line.trim().equals("root-granted=true")) {
          return true;
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
    }

    return false;
  }

  private void openDrawer() {
    sideDrawer.setVisibility(View.VISIBLE);
    drawerOverlay.setVisibility(View.VISIBLE);

    sideDrawer.animate()
      .translationX(0)
      .setDuration(300)
      .start();

    drawerOverlay.animate()
      .alpha(1.0 f)
      .setDuration(300)
      .start();

    isDrawerOpen = true;
  }

  private void openDrawerFaster() {
    sideDrawer.setVisibility(View.VISIBLE);
    drawerOverlay.setVisibility(View.VISIBLE);

    sideDrawer.animate()
      .translationX(0)
      .setDuration(0)
      .start();

    drawerOverlay.animate()
      .alpha(1.0 f)
      .setDuration(0)
      .start();

    isDrawerOpen = true;
  }

  private void closeDrawer() {

    sideDrawer.animate()
      .translationX(-sideDrawer.getWidth())
      .setDuration(250)
      .start();

    drawerOverlay.animate()
      .alpha(0.0 f)
      .setDuration(250)
      .setListener(new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
          if (!isDrawerOpen) {
            sideDrawer.setVisibility(View.GONE);
            drawerOverlay.setVisibility(View.GONE);
          }
          drawerOverlay.animate().setListener(null);
        }
      })
      .start();

    isDrawerOpen = false;
  }

  @Override
  public void onBackPressed() {
    if (sideDrawer != null && sideDrawer.getVisibility() == View.VISIBLE) {
      closeDrawer();
    } else {
      super.onBackPressed();
    }
  }

  private void restorestate() {

    if (isDrawerOpen) {
      openDrawerFaster();
    }
    if (selectedBoot == 1) {
      cardventoy.setSelected(true);
      scaleSelectedFaster(cardventoy);
    } else if (selectedBoot == 2) {
      cardusb.setSelected(true);
      scaleSelectedFaster(cardusb);
    } else if (selectedBoot == 3) {
      cardgadget.setSelected(true);
      scaleSelectedFaster(cardgadget);
    } else if (selectedBoot == 4) {
      cardtftp.setSelected(true);
      scaleSelectedFaster(cardtftp);
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);

    outState.putInt("selected_boot", selectedBoot);
    outState.putBoolean("drawer_open", isDrawerOpen);
  }

}