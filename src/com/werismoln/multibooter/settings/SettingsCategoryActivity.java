/******************************************************************************
 * SettingsCategoryActivity.java
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

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import com.werismoln.multibooter.BaseActivity;

import java.io.IOException;
import java.io.OutputStream;

/**
 * One settings category as its own page. The page is built in code from the
 * current palette, so rebuild() shows theme and accent changes at once.
 */
public class SettingsCategoryActivity extends BaseActivity
    implements SettingsPage.Host {

    public static final String EXTRA_CATEGORY = "category";

    private static final int REQUEST_EXPORT = 7001;

    private String category = "appearance";

    private String pendingExportText = "";

    private ScrollView scroll;

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        String requested = getIntent().getStringExtra(EXTRA_CATEGORY);

        if (requested != null) {
            category = requested;
        }

        build();
    }

    @Override
    public void rebuild() {

        int scrollY = scroll != null ? scroll.getScrollY() : 0;

        build();

        final int restore = scrollY;

        scroll.post(
            new Runnable() {

                @Override
                public void run() {
                    scroll.scrollTo(0, restore);
                }
            }
        );
    }

    @Override
    public void recreateScreen() {

        recreate();
    }

    @Override
    public void exportText(String fileName, String text) {

        pendingExportText = text;

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);

        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, fileName);

        startActivityForResult(intent, REQUEST_EXPORT);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {

        super.onActivityResult(request, result, data);

        if (
            request != REQUEST_EXPORT ||
            result != Activity.RESULT_OK ||
            data == null ||
            data.getData() == null
        ) {
            return;
        }

        Uri uri = data.getData();

        try {

            OutputStream out = getContentResolver().openOutputStream(uri);

            if (out == null) {
                throw new IOException("Could not open the file.");
            }

            try {

                out.write(pendingExportText.getBytes("UTF-8"));

            } finally {

                out.close();
            }

            Toast.makeText(this, "Saved.", Toast.LENGTH_SHORT).show();

        } catch (IOException e) {

            Toast.makeText(
                this,
                "Could not save the file: " + e.getMessage(),
                Toast.LENGTH_LONG
            ).show();
        }

        pendingExportText = "";
    }

    private void build() {

        SettingsUi ui = new SettingsUi(this);

        ThemeManager.Palette palette = ui.palette();

        scroll = new ScrollView(this);

        scroll.setBackgroundColor(palette.bg);
        scroll.setFillViewport(true);
        scroll.setTag(ThemeManager.SKIP_TAG);

        LinearLayout content = ui.column();

        content.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(24));

        scroll.addView(content);

        SettingsPage page = new SettingsPage(this, ui, content, this);

        if ("appearance".equals(category)) {
            AppearanceSettings.build(page);
        } else if ("usb".equals(category)) {
            UsbSettings.build(page);
        } else if ("ventoy".equals(category)) {
            VentoySettings.build(page);
        } else if ("notifications".equals(category)) {
            NotificationSettings.build(page);
        } else if ("network".equals(category)) {
            NetworkSettings.build(page);
        } else if ("debug".equals(category)) {
            DebugSettings.build(page);
        } else {
            AdvancedSettings.build(page);
        }

        setContentView(scroll);
    }
}
