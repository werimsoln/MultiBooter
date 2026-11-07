/******************************************************************************
 * ControlCenterActivity.java
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

package com.werismoln.multibooter.settings;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.werismoln.multibooter.BaseActivity;
import com.werismoln.multibooter.SettingsActivity;

/**
 * Home of the settings: one card per category. Each category opens its own
 * page, so the list stays short however many options are added.
 */
public class ControlCenterActivity extends BaseActivity {

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        build();
    }

    @Override
    protected void onResume() {

        super.onResume();
    }

    private void build() {

        SettingsUi ui = new SettingsUi(this);

        ThemeManager.Palette palette = ui.palette();

        ScrollView scroll = new ScrollView(this);

        scroll.setBackgroundColor(palette.bg);
        scroll.setFillViewport(true);
        scroll.setTag(ThemeManager.SKIP_TAG);

        LinearLayout page = ui.column();

        page.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(24));

        scroll.addView(page);

        ui.title(page, "Settings", "MultiBooter Control Center");

        category(
            ui,
            page,
            "Appearance",
            "Themes, colors, typography and animations",
            "appearance"
        );

        category(
            ui,
            page,
            "USB Configuration",
            "Write verification and device behavior",
            "usb"
        );

        category(
            ui,
            page,
            "Ventoy",
            "Partition style and installer defaults",
            "ventoy"
        );

        category(
            ui,
            page,
            "Notifications",
            "Progress and operation notifications",
            "notifications"
        );

        category(
            ui,
            page,
            "Network & PXE",
            "TFTP and PXE options",
            "network"
        );

        category(
            ui,
            page,
            "Developer & Debug",
            "Logs, diagnostics and debugging tools",
            "debug"
        );

        category(
            ui,
            page,
            "Advanced",
            "History, cache and resetting settings",
            "advanced"
        );

        ui.section(page, "System");

        permissionsCard(ui, page);

        setContentView(scroll);
    }

    private void category(
        SettingsUi ui,
        LinearLayout page,
        String title,
        String subtitle,
        final String id
    ) {

        addCard(
            ui,
            page,
            title,
            subtitle,
            new View.OnClickListener() {

                @Override
                public void onClick(View v) {

                    Intent intent =
                        new Intent(
                            ControlCenterActivity.this,
                            SettingsCategoryActivity.class
                        );

                    intent.putExtra(SettingsCategoryActivity.EXTRA_CATEGORY, id);

                    startActivity(intent);
                }
            }
        );
    }

    private void permissionsCard(SettingsUi ui, LinearLayout page) {

        addCard(
            ui,
            page,
            "Permissions",
            "Root, notifications and battery optimization (all optional)",
            new View.OnClickListener() {

                @Override
                public void onClick(View v) {

                    startActivity(
                        new Intent(
                            ControlCenterActivity.this,
                            SettingsActivity.class
                        )
                    );
                }
            }
        );
    }

    private void addCard(
        SettingsUi ui,
        LinearLayout page,
        String title,
        String subtitle,
        View.OnClickListener click
    ) {

        ThemeManager.Palette palette = ui.palette();

        LinearLayout card = ui.card(page);

        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16));
        card.setOnClickListener(click);

        LinearLayout labels = ui.column();

        labels.setLayoutParams(
            new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        );

        labels.addView(ui.text(title, 17, palette.text, true));

        TextView sub = ui.text(subtitle, 13, palette.muted, false);

        sub.setPadding(0, ui.dp(2), 0, 0);

        labels.addView(sub);

        card.addView(labels);

        TextView chevron = ui.text(">", 20, palette.accent, true);

        card.addView(chevron);
    }
}
