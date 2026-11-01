/******************************************************************************
 * ModeStatusView.java
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
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Simple live progress page.
 *
 * The ISO writer and the Ventoy installer keep running when their screen is
 * destroyed (for example after the app was swiped away). When the user taps
 * the notification a new Activity instance is created; this page shows the
 * current progress of the operation that is still running, with a Stop
 * button, and the final result once it ends.
 */
final class ModeStatusView {

    private static final long POLL_MS = 500L;

    private ModeStatusView() {
    }

    /** true if a status page should be shown for this mode right now. */
    static boolean shouldShow(int mode) {

        return ModeService.isActive(mode);
    }

    static void show(final Activity activity, final int mode) {

        LinearLayout root = new LinearLayout(activity);

        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0B305A"));
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        int pad = dp(activity, 24);

        root.setPadding(pad, pad * 2, pad, pad);

        final TextView title = new TextView(activity);

        title.setText(ModeService.titleOf(mode));
        title.setTextColor(Color.WHITE);
        title.setTextSize(22f);
        title.setGravity(Gravity.CENTER);

        final ProgressBar bar =
            new ProgressBar(
                activity,
                null,
                android.R.attr.progressBarStyleHorizontal
            );

        bar.setMax(100);

        LinearLayout.LayoutParams barParams =
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );

        barParams.topMargin = dp(activity, 28);

        final TextView text = new TextView(activity);

        text.setTextColor(Color.parseColor("#D7EEFF"));
        text.setTextSize(15f);
        text.setGravity(Gravity.CENTER);

        LinearLayout.LayoutParams textParams =
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );

        textParams.topMargin = dp(activity, 14);

        final Button stop = new Button(activity);

        stop.setText("STOP");

        final Button close = new Button(activity);

        close.setText("CLOSE");
        close.setVisibility(View.GONE);

        LinearLayout.LayoutParams buttonParams =
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );

        buttonParams.topMargin = dp(activity, 28);

        root.addView(title);
        root.addView(bar, barParams);
        root.addView(text, textParams);
        root.addView(stop, buttonParams);
        root.addView(close, buttonParams);

        activity.setContentView(root);

        stop.setOnClickListener(
            new View.OnClickListener() {

                @Override
                public void onClick(View v) {

                    stop.setEnabled(false);

                    ModeService.requestStop(mode);
                }
            }
        );

        close.setOnClickListener(
            new View.OnClickListener() {

                @Override
                public void onClick(View v) {

                    activity.finish();
                }
            }
        );

        final Handler handler = new Handler(Looper.getMainLooper());

        handler.post(
            new Runnable() {

                @Override
                public void run() {

                    if (activity.isFinishing()) {
                        return;
                    }

                    ModeService.State state = ModeService.snapshot(mode);

                    if (state != null) {

                        text.setText(state.text);

                        if (state.percent >= 0) {

                            bar.setIndeterminate(false);
                            bar.setProgress(state.percent);

                        } else {

                            bar.setIndeterminate(true);
                        }

                        stop.setEnabled(!state.stopping);

                        handler.postDelayed(this, POLL_MS);
                        return;
                    }

                    ModeService.Result result = ModeService.lastResult(mode);

                    bar.setIndeterminate(false);
                    bar.setProgress(
                        result != null && result.success ? 100 : 0
                    );

                    text.setText(
                        result == null
                        ? "Finished."
                        : result.message
                    );

                    stop.setVisibility(View.GONE);
                    close.setVisibility(View.VISIBLE);
                }
            }
        );
    }

    private static int dp(Activity activity, int value) {

        return Math.round(
            value *
            activity.getResources().getDisplayMetrics().density
        );
    }
}
