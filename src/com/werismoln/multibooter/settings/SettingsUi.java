/******************************************************************************
 * SettingsUi.java
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
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Builds the rows of the settings screens in code, using the colors of the
 * selected theme directly. Because every row is created from the current
 * palette, a change of theme or accent is visible immediately when the page
 * is rebuilt.
 */
public final class SettingsUi {

    /** Called after a setting was changed by one of the rows. */
    public interface OnChanged {

        void onChanged();
    }

    private final Activity activity;
    private final SettingsManager settings;
    private final ThemeManager.Palette palette;
    private final float density;

    public SettingsUi(Activity activity) {

        this.activity = activity;
        this.settings = SettingsManager.get(activity);
        this.palette = ThemeManager.palette(activity);
        this.density = activity.getResources().getDisplayMetrics().density;
    }

    public ThemeManager.Palette palette() {

        return palette;
    }

    public int dp(int value) {

        return Math.round(value * density);
    }

    // ---------------------------------------------------------------------
    // Containers
    // ---------------------------------------------------------------------

    public LinearLayout column() {

        LinearLayout column = new LinearLayout(activity);

        column.setOrientation(LinearLayout.VERTICAL);
        column.setLayoutParams(
            new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        );

        return column;
    }

    /** Rounded card on the surface color. */
    public LinearLayout card(LinearLayout parent) {

        LinearLayout card = column();

        GradientDrawable shape = new GradientDrawable();

        shape.setColor(palette.surface);
        shape.setCornerRadius(dp(16));

        card.setBackground(shape);
        card.setPadding(dp(16), dp(8), dp(16), dp(8));

        LinearLayout.LayoutParams params =
            new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );

        params.topMargin = dp(12);

        parent.addView(card, params);

        return card;
    }

    public TextView title(LinearLayout parent, String title, String subtitle) {

        TextView t = text(title, 26, palette.text, true);

        t.setPadding(dp(4), dp(8), dp(4), 0);

        parent.addView(t);

        if (subtitle != null) {

            TextView s = text(subtitle, 14, palette.muted, false);

            s.setPadding(dp(4), dp(4), dp(4), dp(4));

            parent.addView(s);
        }

        return t;
    }

    public void section(LinearLayout parent, String label) {

        TextView t = text(label.toUpperCase(), 12, palette.accent, true);

        t.setPadding(dp(8), dp(20), dp(8), 0);

        parent.addView(t);
    }

    public TextView text(String value, int sp, int color, boolean bold) {

        TextView t = new TextView(activity);

        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);

        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }

        return t;
    }

    private LinearLayout row(LinearLayout card) {

        LinearLayout row = new LinearLayout(activity);

        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));

        card.addView(
            row,
            new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        );

        return row;
    }

    private void divider(LinearLayout card) {

        View line = new View(activity);

        line.setBackgroundColor(
            (palette.text & 0x00FFFFFF) | 0x22000000
        );

        card.addView(
            line,
            new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(1) / 2)
            )
        );
    }

    private void head(LinearLayout row, String title, String description) {

        row.addView(text(title, 16, palette.text, false));

        if (description != null && description.length() > 0) {

            TextView d = text(description, 13, palette.muted, false);

            d.setPadding(0, dp(2), 0, 0);

            row.addView(d);
        }
    }

    private void tick(View view) {

        if (ThemeManager.hapticsEnabled(activity)) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    // ---------------------------------------------------------------------
    // Rows
    // ---------------------------------------------------------------------

    public void info(LinearLayout card, String title, String value) {

        LinearLayout row = row(card);

        head(row, title, null);

        TextView v = text(value, 14, palette.text2, false);

        v.setPadding(0, dp(2), 0, 0);

        row.addView(v);
    }

    public void switchRow(
        LinearLayout card,
        String title,
        String description,
        final String key,
        boolean enabled,
        final OnChanged listener
    ) {

        LinearLayout row = row(card);

        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout labels = column();

        labels.setLayoutParams(
            new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        );

        head(labels, title, description);

        row.addView(labels);

        final Switch toggle = new Switch(activity);

        toggle.setChecked(settings.getBoolean(key));
        toggle.setEnabled(enabled);

        int[][] states = {
            new int[] { android.R.attr.state_checked },
            new int[] {}
        };

        toggle.setThumbTintList(
            new ColorStateList(
                states,
                new int[] { palette.accent, palette.muted }
            )
        );

        toggle.setTrackTintList(
            new ColorStateList(
                states,
                new int[] {
                    (palette.accent & 0x00FFFFFF) | 0x66000000,
                    (palette.muted & 0x00FFFFFF) | 0x44000000
                }
            )
        );

        toggle.setOnCheckedChangeListener(
            new CompoundButton.OnCheckedChangeListener() {

                @Override
                public void onCheckedChanged(CompoundButton b, boolean checked) {

                    tick(b);

                    settings.setBoolean(key, checked);

                    if (listener != null) {
                        listener.onChanged();
                    }
                }
            }
        );

        row.addView(toggle);

        if (enabled) {

            row.setOnClickListener(
                new View.OnClickListener() {

                    @Override
                    public void onClick(View v) {
                        toggle.toggle();
                    }
                }
            );
        } else {

            row.setAlpha(0.5f);
        }

        divider(card);
    }

    /**
     * A row of selectable chips (segmented choice). Selecting a chip stores
     * the value and calls the listener.
     */
    public void chipRow(
        LinearLayout card,
        String title,
        String description,
        final String key,
        final String[] values,
        final String[] labels,
        final OnChanged listener
    ) {

        LinearLayout row = row(card);

        head(row, title, description);

        LinearLayout chips = new LinearLayout(activity);

        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, dp(8), 0, 0);

        String current = settings.getString(key);

        for (int i = 0; i < values.length; i++) {

            final String value = values[i];

            TextView chip = chip(labels[i], value.equals(current));

            chip.setOnClickListener(
                new View.OnClickListener() {

                    @Override
                    public void onClick(View v) {

                        tick(v);

                        settings.setString(key, value);

                        if (listener != null) {
                            listener.onChanged();
                        }
                    }
                }
            );

            chips.addView(chip, chipParams());
        }

        row.addView(chips);

        divider(card);
    }

    private LinearLayout.LayoutParams chipParams() {

        LinearLayout.LayoutParams params =
            new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            );

        params.rightMargin = dp(6);

        return params;
    }

    private TextView chip(String label, boolean selected) {

        TextView chip = text(
            label,
            13,
            selected ? palette.onAccent : palette.text2,
            selected
        );

        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(6), dp(10), dp(6), dp(10));

        GradientDrawable shape = new GradientDrawable();

        shape.setCornerRadius(dp(20));

        if (selected) {

            shape.setColor(palette.accent);

        } else {

            shape.setColor((palette.text & 0x00FFFFFF) | 0x14000000);
        }

        chip.setBackground(shape);

        return chip;
    }

    /** Round color swatches for the accent colors plus a custom entry. */
    public void accentRow(
        LinearLayout card,
        final OnChanged listener
    ) {

        LinearLayout row = row(card);

        head(
            row,
            "Accent color",
            "Pick a color. Every screen updates immediately."
        );

        LinearLayout swatches = new LinearLayout(activity);

        swatches.setOrientation(LinearLayout.HORIZONTAL);
        swatches.setPadding(0, dp(10), 0, 0);

        String current = settings.getString(SettingsDefaults.ACCENT);

        for (final ThemeManager.Accent accent : ThemeManager.ACCENTS) {

            swatches.addView(
                swatch(
                    accent.color,
                    accent.id.equals(current),
                    accent.label,
                    new View.OnClickListener() {

                        @Override
                        public void onClick(View v) {

                            tick(v);

                            settings.setString(
                                SettingsDefaults.ACCENT,
                                accent.id
                            );

                            listener.onChanged();
                        }
                    }
                ),
                swatchParams()
            );
        }

        final boolean custom = "custom".equals(current);

        swatches.addView(
            swatch(
                ThemeManager.accentColor(activity),
                custom,
                "Custom",
                new View.OnClickListener() {

                    @Override
                    public void onClick(View v) {

                        tick(v);

                        settings.setString(SettingsDefaults.ACCENT, "custom");

                        listener.onChanged();
                    }
                }
            ),
            swatchParams()
        );

        row.addView(swatches);

        if (custom) {

            final EditText hex = new EditText(activity);

            hex.setText(settings.getString(SettingsDefaults.ACCENT_CUSTOM));
            hex.setHint("#RRGGBB");
            hex.setTextColor(palette.text);
            hex.setHintTextColor(palette.muted);
            hex.setSingleLine(true);
            hex.setFilters(new InputFilter[] { new InputFilter.LengthFilter(7) });
            hex.setBackgroundTintList(ColorStateList.valueOf(palette.accent));

            hex.addTextChangedListener(
                new TextWatcher() {

                    @Override
                    public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                    }

                    @Override
                    public void onTextChanged(CharSequence s, int a, int b, int c) {
                    }

                    @Override
                    public void afterTextChanged(Editable s) {

                        String value = s.toString();

                        int color = ThemeManager.parseHex(value);

                        if (
                            color != 0 &&
                            !value.equalsIgnoreCase(
                                settings.getString(
                                    SettingsDefaults.ACCENT_CUSTOM
                                )
                            )
                        ) {

                            settings.setString(
                                SettingsDefaults.ACCENT_CUSTOM,
                                ThemeManager.toHex(color)
                            );

                            listener.onChanged();
                        }
                    }
                }
            );

            row.addView(hex);

            TextView hint =
                text(
                    "Type a color such as #7C3AED. It is applied as soon as it is valid.",
                    12,
                    palette.muted,
                    false
                );

            row.addView(hint);
        }

        divider(card);
    }

    private LinearLayout.LayoutParams swatchParams() {

        LinearLayout.LayoutParams params =
            new LinearLayout.LayoutParams(dp(40), dp(40));

        params.rightMargin = dp(10);

        return params;
    }

    private View swatch(
        int color,
        boolean selected,
        String description,
        View.OnClickListener click
    ) {

        View view = new View(activity);

        GradientDrawable shape = new GradientDrawable();

        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(color);

        if (selected) {
            shape.setStroke(dp(3), palette.text);
        } else {
            shape.setStroke(dp(1), (palette.text & 0x00FFFFFF) | 0x33000000);
        }

        view.setBackground(shape);
        view.setContentDescription(description);
        view.setOnClickListener(click);

        return view;
    }

    public void sliderRow(
        LinearLayout card,
        String title,
        String description,
        final String key,
        final int min,
        final int max,
        final int step,
        final String suffix,
        final OnChanged listener
    ) {

        LinearLayout row = row(card);

        head(row, title, description);

        final TextView value =
            text(settings.getInt(key) + suffix, 14, palette.text2, true);

        value.setPadding(0, dp(6), 0, 0);

        row.addView(value);

        SeekBar bar = new SeekBar(activity);

        bar.setMax((max - min) / step);
        bar.setProgress((settings.getInt(key) - min) / step);
        bar.setThumbTintList(ColorStateList.valueOf(palette.accent));
        bar.setProgressTintList(ColorStateList.valueOf(palette.accent));

        bar.setOnSeekBarChangeListener(
            new SeekBar.OnSeekBarChangeListener() {

                @Override
                public void onProgressChanged(SeekBar b, int progress, boolean user) {

                    value.setText((min + progress * step) + suffix);
                }

                @Override
                public void onStartTrackingTouch(SeekBar b) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar b) {

                    settings.setInt(key, min + b.getProgress() * step);

                    if (listener != null) {
                        listener.onChanged();
                    }
                }
            }
        );

        row.addView(bar);

        divider(card);
    }

    public void actionRow(
        LinearLayout card,
        String title,
        String description,
        final Runnable action
    ) {

        LinearLayout row = row(card);

        head(row, title, description);

        row.setOnClickListener(
            new View.OnClickListener() {

                @Override
                public void onClick(View v) {

                    tick(v);

                    action.run();
                }
            }
        );

        divider(card);
    }

    // ---------------------------------------------------------------------
    // Dialogs
    // ---------------------------------------------------------------------

    public void confirm(
        String title,
        String message,
        String positive,
        final Runnable onConfirm
    ) {

        new AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(
                positive,
                new android.content.DialogInterface.OnClickListener() {

                    @Override
                    public void onClick(
                        android.content.DialogInterface d,
                        int which
                    ) {
                        onConfirm.run();
                    }
                }
            )
            .show();
    }

    /** A scrollable monospace text dialog. */
    public AlertDialog.Builder textDialog(String title, String body) {

        android.widget.ScrollView scroll = new android.widget.ScrollView(activity);

        TextView view = new TextView(activity);

        view.setText(body);
        view.setTextSize(11);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        view.setPadding(dp(16), dp(8), dp(16), dp(8));

        scroll.addView(view);

        return new AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(scroll);
    }

    public Context context() {

        return activity;
    }
}
