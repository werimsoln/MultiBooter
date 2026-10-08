package com.werismoln.multibooter;

import android.app.Activity;
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

    @Override
    public void setContentView(int layoutResID) {
        super.setContentView(layoutResID);
        applyWindowInsets();
    }

    @Override
    public void setContentView(View view) {
        super.setContentView(view);
        applyWindowInsets();
    }

    @Override
    public void setContentView(View view, ViewGroup.LayoutParams params) {
        super.setContentView(view, params);
        applyWindowInsets();
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
