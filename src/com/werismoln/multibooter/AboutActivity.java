/******************************************************************************
 * AboutActivity.java
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

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class AboutActivity extends Activity {

    private static final String SOURCE_URL =
        "https://github.com/werimsoln/MultiBooter";

    private static final String ISSUES_URL =
        "https://github.com/werimsoln/MultiBooter/issues";

    private static final String LICENSE_URL =
        "https://github.com/werimsoln/MultiBooter/blob/main/LICENSE";

    private static final String DONATION_URL =
        "https://www.buymeacoffee.com/werismoln";

    private TextView versionView;
    private TextView packageView;

    private Button sourceButton;
    private Button issuesButton;
    private Button licenseButton;
    private Button donationButton;

    @Override
    protected void onCreate(
        Bundle savedInstanceState
    ) {

        super.onCreate(
            savedInstanceState
        );

        setContentView(
            R.layout.about
        );

        bindViews();
        setupListeners();
        setupTouchFeedback();
        showPackageInformation();
    }

    private void bindViews() {

        versionView =
            (TextView)
            findViewById(
                R.id.about_version
            );

        packageView =
            (TextView)
            findViewById(
                R.id.about_package
            );

        sourceButton =
            (Button)
            findViewById(
                R.id.about_source
            );

        issuesButton =
            (Button)
            findViewById(
                R.id.about_issues
            );

        licenseButton =
            (Button)
            findViewById(
                R.id.about_license
            );

        donationButton =
            (Button)
            findViewById(
                R.id.about_donate
            );
    }

    private void setupListeners() {

        TextView back =
            (TextView)
            findViewById(
                R.id.about_back_top
            );

        back.setOnClickListener(
            new View.OnClickListener() {

                @Override
                public void onClick(
                    View view
                ) {
                    finish();
                }
            }
        );

        sourceButton.setOnClickListener(
            new View.OnClickListener() {

                @Override
                public void onClick(
                    View view
                ) {
                    openUrl(
                        SOURCE_URL,
                        "Could not open the source-code page."
                    );
                }
            }
        );

        issuesButton.setOnClickListener(
            new View.OnClickListener() {

                @Override
                public void onClick(
                    View view
                ) {
                    openUrl(
                        ISSUES_URL,
                        "Could not open the issue tracker."
                    );
                }
            }
        );

        licenseButton.setOnClickListener(
            new View.OnClickListener() {

                @Override
                public void onClick(
                    View view
                ) {
                    openUrl(
                        LICENSE_URL,
                        "Could not open the license page."
                    );
                }
            }
        );

        donationButton.setOnClickListener(
            new View.OnClickListener() {

                @Override
                public void onClick(
                    View view
                ) {
                    openUrl(
                        DONATION_URL,
                        "Could not open Buy Me a Coffee."
                    );
                }
            }
        );
    }

    private void setupTouchFeedback() {

        View.OnTouchListener listener =
            new View.OnTouchListener() {

                @Override
                public boolean onTouch(
                    View view,
                    MotionEvent event
                ) {

                    if (!view.isEnabled()) {
                        return false;
                    }

                    if (
                        event.getAction() ==
                        MotionEvent.ACTION_DOWN
                    ) {

                        view.animate().cancel();
                        view.setAlpha(0.4f);

                    } else if (
                        event.getAction() ==
                            MotionEvent.ACTION_UP ||
                        event.getAction() ==
                            MotionEvent.ACTION_CANCEL
                    ) {

                        view.animate()
                            .alpha(1.0f)
                            .setDuration(250)
                            .start();
                    }

                    return false;
                }
            };

        sourceButton.setOnTouchListener(
            listener
        );

        issuesButton.setOnTouchListener(
            listener
        );

        licenseButton.setOnTouchListener(
            listener
        );

        donationButton.setOnTouchListener(
            listener
        );
    }

    private void showPackageInformation() {

        String versionName =
            "Unknown";

        long versionCode =
            -1L;

        try {

            PackageManager manager =
                getPackageManager();

            PackageInfo info =
                manager.getPackageInfo(
                    getPackageName(),
                    0
                );

            if (
                info.versionName != null &&
                info.versionName.length() > 0
            ) {

                versionName =
                    info.versionName;
            }

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.P
            ) {

                versionCode =
                    info.getLongVersionCode();

            } else {

                versionCode =
                    info.versionCode;
            }

        } catch (
            Throwable ignored
        ) {
        }

        String versionText =
            "Version: " +
            versionName;

        if (
            versionCode >= 0
        ) {

            versionText +=
                " (" +
                versionCode +
                ")";
        }

        versionView.setText(
            versionText
        );

        packageView.setText(
            "Package: " +
            getPackageName()
        );
    }

    private void openUrl(
        String url,
        String errorMessage
    ) {

        try {

            Intent intent =
                new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(
                        url
                    )
                );

            startActivity(
                intent
            );

        } catch (
            Throwable error
        ) {

            Toast.makeText(
                this,
                errorMessage,
                Toast.LENGTH_LONG
            ).show();
        }
    }
}
