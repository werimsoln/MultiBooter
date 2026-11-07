/******************************************************************************
 * ThemeManager.java
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

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * Theme engine for a code base that has no AndroidX and no themed styles.
 *
 * The XML layouts use a small fixed palette (navy background, slightly
 * lighter cards, blue buttons, white and light-blue text). Instead of
 * rewriting every layout, apply() walks the view tree once after the layout
 * was inflated and maps those original colors to the colors of the selected
 * theme and accent:
 *
 *   - backgrounds and shapes in the screen / card colors,
 *   - buttons tinted in the original accent color,
 *   - text on screen and card backgrounds (text on colored cards and
 *     colored buttons is left alone, so it stays readable).
 *
 * The default setup (dark theme, blue accent, solid background) is exactly
 * what the layouts already contain, so nothing is touched in that case.
 */
public final class ThemeManager {

    // Colors that appear in the layouts.
    private static final int ORIG_BG = 0xFF0B305A;
    private static final int ORIG_SURFACE = 0xFF123A63;
    private static final int ORIG_ACCENT = 0xFF2563EB;
    private static final int ORIG_TEXT = 0xFFFFFFFF;
    private static final int ORIG_MUTED = 0xFF7FB2D9;

    private static final int[] ORIG_TEXT2 = {
        0xFFD7EEFF, 0xFFBAE6FD, 0xFFD0E8FF,
        0xFFD9E8F5, 0xFFD8E8F0, 0xFFD5F5FA
    };

    /** The colors of one theme. */
    public static final class Palette {

        public final int bg;
        public final int surface;
        public final int text;
        public final int text2;
        public final int muted;
        public final int accent;
        public final int onAccent = 0xFFFFFFFF;
        public final boolean light;

        Palette(
            int bg,
            int surface,
            int text,
            int text2,
            int muted,
            int accent,
            boolean light
        ) {
            this.bg = bg;
            this.surface = surface;
            this.text = text;
            this.text2 = text2;
            this.muted = muted;
            this.accent = accent;
            this.light = light;
        }
    }

    /** A selectable accent color. */
    public static final class Accent {

        public final String id;
        public final String label;
        public final int color;

        Accent(String id, String label, int color) {
            this.id = id;
            this.label = label;
            this.color = color;
        }
    }

    public static final Accent[] ACCENTS = {
        new Accent("blue", "Blue", 0xFF2563EB),
        new Accent("purple", "Purple", 0xFF7C3AED),
        new Accent("green", "Green", 0xFF16A34A),
        new Accent("orange", "Orange", 0xFFEA580C),
        new Accent("red", "Red", 0xFFDC2626)
    };

    /**
     * Views tagged with this are skipped by apply(). The settings screens are
     * built from the palette directly and must not be mapped a second time.
     */
    public static final String SKIP_TAG = "multibooter.skip-theme";

    private ThemeManager() {
    }

    // ---------------------------------------------------------------------

    public static boolean isLight(Context context) {

        return resolveTheme(context).equals(SettingsDefaults.THEME_LIGHT);
    }

    private static String resolveTheme(Context context) {

        String theme =
            SettingsManager.get(context).getString(SettingsDefaults.THEME);

        if (!SettingsDefaults.THEME_SYSTEM.equals(theme)) {
            return theme;
        }

        int night =
            context.getResources().getConfiguration().uiMode &
            Configuration.UI_MODE_NIGHT_MASK;

        return
            night == Configuration.UI_MODE_NIGHT_YES
            ? SettingsDefaults.THEME_DARK
            : SettingsDefaults.THEME_LIGHT;
    }

    public static int accentColor(Context context) {

        SettingsManager settings = SettingsManager.get(context);

        String id = settings.getString(SettingsDefaults.ACCENT);

        if ("custom".equals(id)) {

            int custom =
                parseHex(settings.getString(SettingsDefaults.ACCENT_CUSTOM));

            return custom != 0 ? custom : ORIG_ACCENT;
        }

        for (Accent accent : ACCENTS) {

            if (accent.id.equals(id)) {
                return accent.color;
            }
        }

        return ORIG_ACCENT;
    }

    public static Palette palette(Context context) {

        int accent = accentColor(context);

        String theme = resolveTheme(context);

        if (SettingsDefaults.THEME_LIGHT.equals(theme)) {

            return new Palette(
                0xFFF1F5F9,
                0xFFFFFFFF,
                0xFF0F172A,
                0xFF334155,
                0xFF64748B,
                accent,
                true
            );
        }

        if (SettingsDefaults.THEME_AMOLED.equals(theme)) {

            return new Palette(
                0xFF000000,
                0xFF101A2A,
                ORIG_TEXT,
                ORIG_TEXT2[0],
                ORIG_MUTED,
                accent,
                false
            );
        }

        return new Palette(
            ORIG_BG,
            ORIG_SURFACE,
            ORIG_TEXT,
            ORIG_TEXT2[0],
            ORIG_MUTED,
            accent,
            false
        );
    }

    /** true when the layouts already look exactly like the chosen setup. */
    private static boolean isIdentity(Context context) {

        SettingsManager settings = SettingsManager.get(context);

        return
            SettingsDefaults.THEME_DARK.equals(resolveTheme(context)) &&
            accentColor(context) == ORIG_ACCENT &&
            !SettingsDefaults.BACKGROUND_GRADIENT.equals(
                settings.getString(SettingsDefaults.BACKGROUND)
            );
    }

    // ---------------------------------------------------------------------
    // Applying a theme to an inflated layout
    // ---------------------------------------------------------------------

    public static void apply(Context context, View root) {

        if (root == null || isIdentity(context)) {
            return;
        }

        Palette palette = palette(context);

        boolean gradient =
            SettingsDefaults.BACKGROUND_GRADIENT.equals(
                SettingsManager.get(context).getString(
                    SettingsDefaults.BACKGROUND
                )
            );

        walk(root, 0, palette, gradient, new boolean[] { false });
    }

    private static void walk(
        View view,
        int inheritedBg,
        Palette palette,
        boolean gradient,
        boolean[] gradientUsed
    ) {

        if (SKIP_TAG.equals(view.getTag())) {
            return;
        }

        int ownBg = backgroundColor(view.getBackground());

        ColorStateList tint = view.getBackgroundTintList();

        int tintColor =
            tint != null
            ? tint.getDefaultColor() | 0xFF000000
            : 0;

        // What the text of this view sits on, using ORIGINAL colors.
        int effectiveBg =
            tintColor != 0
            ? tintColor
            : (ownBg != 0 ? ownBg : inheritedBg);

        boolean themedBackground =
            effectiveBg == 0 ||
            effectiveBg == ORIG_BG ||
            effectiveBg == ORIG_SURFACE;

        // ---- background ----
        if (ownBg == ORIG_BG) {

            if (gradient && !gradientUsed[0]) {

                gradientUsed[0] = true;

                view.setBackground(
                    new GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[] {
                            palette.bg,
                            shade(palette.bg, palette.light ? -0.06f : 0.10f)
                        }
                    )
                );

            } else {

                setBackgroundColorKeepShape(view, palette.bg);
            }

        } else if (ownBg == ORIG_SURFACE) {

            setBackgroundColorKeepShape(view, palette.surface);

        } else if (ownBg == ORIG_ACCENT) {

            setBackgroundColorKeepShape(view, palette.accent);
        }

        if (tintColor == ORIG_ACCENT) {

            view.setBackgroundTintList(
                ColorStateList.valueOf(palette.accent)
            );
        }

        // ---- text ----
        if (view instanceof TextView) {

            TextView text = (TextView) view;

            int color = text.getCurrentTextColor() | 0xFF000000;

            if (color == ORIG_ACCENT) {

                text.setTextColor(palette.accent);

            } else if (themedBackground) {

                int mapped = mapText(color, palette);

                if (mapped != 0) {
                    text.setTextColor(mapped);
                }
            }
        }

        // ---- children ----
        if (view instanceof ViewGroup) {

            ViewGroup group = (ViewGroup) view;

            int forChildren =
                ownBg != 0
                ? ownBg
                : inheritedBg;

            for (int i = 0; i < group.getChildCount(); i++) {

                walk(
                    group.getChildAt(i),
                    forChildren,
                    palette,
                    gradient,
                    gradientUsed
                );
            }
        }
    }

    private static int mapText(int original, Palette palette) {

        if (original == ORIG_TEXT) {
            return palette.text;
        }

        if (original == ORIG_MUTED) {
            return palette.muted;
        }

        for (int color : ORIG_TEXT2) {

            if (original == color) {
                return palette.text2;
            }
        }

        return 0;
    }

    private static int backgroundColor(Drawable drawable) {

        if (drawable instanceof ColorDrawable) {

            ColorDrawable color = (ColorDrawable) drawable;

            // Only fully opaque colors take part in theming.
            return
                (color.getColor() >>> 24) == 0xFF
                ? color.getColor()
                : 0;
        }

        if (drawable instanceof GradientDrawable) {

            ColorStateList list = ((GradientDrawable) drawable).getColor();

            if (list != null) {

                int color = list.getDefaultColor();

                return (color >>> 24) == 0xFF ? color : 0;
            }
        }

        return 0;
    }

    private static void setBackgroundColorKeepShape(View view, int color) {

        Drawable drawable = view.getBackground();

        if (drawable instanceof GradientDrawable) {

            // Rounded card: keep the shape and corners, change only the fill.
            ((GradientDrawable) drawable.mutate()).setColor(color);

        } else {

            view.setBackgroundColor(color);
        }
    }

    private static int shade(int color, float amount) {

        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);

        if (amount >= 0f) {

            r += (int) ((255 - r) * amount);
            g += (int) ((255 - g) * amount);
            b += (int) ((255 - b) * amount);

        } else {

            r += (int) (r * amount);
            g += (int) (g * amount);
            b += (int) (b * amount);
        }

        return Color.rgb(r, g, b);
    }

    // ---------------------------------------------------------------------
    // Other appearance options
    // ---------------------------------------------------------------------

    /** Wraps a base context so every Activity uses the chosen font scale. */
    public static Context withFontScale(Context base) {

        int percent =
            SettingsManager.get(base).getInt(SettingsDefaults.FONT_SCALE);

        percent =
            Math.max(
                SettingsDefaults.FONT_SCALE_MIN,
                Math.min(SettingsDefaults.FONT_SCALE_MAX, percent)
            );

        if (percent == 100) {
            return base;
        }

        Configuration configuration =
            new Configuration(base.getResources().getConfiguration());

        configuration.fontScale = percent / 100f;

        return base.createConfigurationContext(configuration);
    }

    public static boolean animationsEnabled(Context context) {

        return SettingsManager.get(context).getBoolean(
            SettingsDefaults.ANIMATIONS
        );
    }

    public static boolean hapticsEnabled(Context context) {

        return SettingsManager.get(context).getBoolean(
            SettingsDefaults.HAPTICS
        );
    }

    // ---------------------------------------------------------------------
    // HEX colors
    // ---------------------------------------------------------------------

    /** @return the opaque color, or 0 if text is not #RRGGBB */
    public static int parseHex(String text) {

        if (text == null) {
            return 0;
        }

        String value = text.trim();

        if (!value.matches("#[0-9a-fA-F]{6}")) {
            return 0;
        }

        return Color.parseColor(value);
    }

    public static String toHex(int color) {

        return String.format("#%06X", color & 0xFFFFFF);
    }
}
