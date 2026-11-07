/******************************************************************************
 * BaseActivity.java
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
import android.content.Context;
import android.content.Intent;

import com.werismoln.multibooter.settings.SettingsManager;
import com.werismoln.multibooter.settings.ThemeManager;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;

/**
 * Base class for all MultiBooter activities.
 *
 * Apps that target Android 15 (API 35) or newer are drawn edge-to-edge, so
 * the system bars (status bar, navigation bar) and the on-screen keyboard
 * overlap the app content. This class pads the content area by the size of
 * those system areas so that buttons at the bottom of a screen stay above the
 * navigation bar and the last items of a scrolling screen can be reached.
 */
public class BaseActivity extends Activity {

    /** Appearance settings version this screen was built with. */
    private int appearanceVersion;

    @Override
    protected void attachBaseContext(Context newBase) {
        // Font scale setting.
        super.attachBaseContext(ThemeManager.withFontScale(newBase));
    }

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DebugLog.init(this);
        appearanceVersion = SettingsManager.appearanceVersion();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // The theme, accent or font scale was changed on another screen.
        if (appearanceVersion != SettingsManager.appearanceVersion()) {
            appearanceVersion = SettingsManager.appearanceVersion();
            recreate();
        }
    }

    @Override
    public void setContentView(int layoutResID) {
        super.setContentView(layoutResID);
        applyWindowInsets();
        applyTheme();
    }

    @Override
    public void setContentView(View view) {
        super.setContentView(view);
        applyWindowInsets();
        applyTheme();
    }

    @Override
    public void setContentView(View view, ViewGroup.LayoutParams params) {
        super.setContentView(view, params);
        applyWindowInsets();
        applyTheme();
    }

    private void applyTheme() {
        ThemeManager.apply(this, findViewById(android.R.id.content));
    }

    @Override
    public void startActivity(Intent intent) {
        super.startActivity(intent);

        if (!ThemeManager.animationsEnabled(this)) {
            overridePendingTransition(0, 0);
        }
    }

    @Override
    public void finish() {
        super.finish();

        if (!ThemeManager.animationsEnabled(this)) {
            overridePendingTransition(0, 0);
        }
    }


    private void applyWindowInsets() {
        final View content = findViewById(android.R.id.content);
        if (content == null) {
            return;
        }

        content.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int left;
                int top;
                int right;
                int bottom;

                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets bars = insets.getInsets(
                            WindowInsets.Type.systemBars()
                                    | WindowInsets.Type.displayCutout()
                                    | WindowInsets.Type.ime());
                    left = bars.left;
                    top = bars.top;
                    right = bars.right;
                    bottom = bars.bottom;
                    v.setPadding(left, top, right, bottom);
                    return WindowInsets.CONSUMED;
                }

                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
                v.setPadding(left, top, right, bottom);
                return insets.consumeSystemWindowInsets();
            }
        });

        content.requestApplyInsets();
    }
}
