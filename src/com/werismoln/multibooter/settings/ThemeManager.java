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

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.werismoln.multibooter.R;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Theme engine for a code base without AndroidX or themed styles.
 *
 * The layouts use a fixed set of named colors (res/values/colors.xml). Each
 * of them stands for a ROLE: background, card, accent, text and so on. The
 * first time a view is seen, ThemeManager works out which role every color
 * of that view has and stores the answer in the view. After that a theme
 * change is only "paint every role again", which is instant and works as
 * often as you like, on every open screen, without re-creating anything.
 *
 * The role colors depend on the chosen theme (System, Light, Dark, AMOLED)
 * and on the accent color, which can be any color.
 */
public final class ThemeManager {

    // ---- Marker colors: must equal res/values/colors.xml (dark values). ----
    static final int M_BG = 0xFF0B0F19;
    static final int M_SURFACE = 0xFF141B2D;
    static final int M_SURFACE_HIGH = 0xFF1D2740;
    static final int M_ACCENT = 0xFF7C5CFF;
    static final int M_ACCENT_DEEP = 0xFF4B2FD0;
    static final int M_ACCENT_SOFT = 0x297C5CFF;
    static final int M_ON_ACCENT = 0xFFFFFFFE;
    static final int M_TEXT = 0xFFF4F6FF;
    static final int M_TEXT2 = 0xFFB6BFD8;
    static final int M_MUTED = 0xFF7C86A6;
    static final int M_DIVIDER = 0x1FFFFFFF;

    private static final int NONE = 0;
    private static final int BG = 1;
    private static final int SURFACE = 2;
    private static final int SURFACE_HIGH = 3;
    private static final int ACCENT = 4;
    private static final int ACCENT_DEEP = 5;
    private static final int ACCENT_SOFT = 6;
    private static final int ON_ACCENT = 7;
    private static final int TEXT = 8;
    private static final int TEXT2 = 9;
    private static final int MUTED = 10;
    private static final int DIVIDER = 11;

    /** White that is NOT on an accent surface (for example on a red button). */
    private static final int FIXED_WHITE = 12;
    private static final int LEGACY_WHITE = 13;

    /** Screens that use this tag are built from the palette and left alone. */
    public static final String SKIP_TAG = "multibooter.skip-theme";

    /** The colors of one theme. */
    public static final class Palette {

        public final int bg;
        public final int surface;
        public final int surfaceHigh;
        public final int accent;
        public final int accentDeep;
        public final int accentSoft;
        public final int onAccent;
        public final int text;
        public final int text2;
        public final int muted;
        public final int divider;
        public final boolean light;

        Palette(
            int bg,
            int surface,
            int surfaceHigh,
            int text,
            int text2,
            int muted,
            int divider,
            int accent,
            boolean light
        ) {
            this.bg = bg;
            this.surface = surface;
            this.surfaceHigh = surfaceHigh;
            this.text = text;
            this.text2 = text2;
            this.muted = muted;
            this.divider = divider;
            this.accent = accent;
            this.accentDeep = shade(accent, -0.35f);
            this.accentSoft = (accent & 0x00FFFFFF) | (light ? 0x2E000000 : 0x29000000);
            this.onAccent = luminance(accent) > 0.179f ? 0xFF0E1330 : 0xFFFFFFFF;
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
        new Accent("violet", "Violet", 0xFF7C5CFF),
        new Accent("blue", "Blue", 0xFF3B82F6),
        new Accent("teal", "Teal", 0xFF14B8A6),
        new Accent("green", "Green", 0xFF22C55E),
        new Accent("orange", "Orange", 0xFFF97316),
        new Accent("pink", "Pink", 0xFFEC4899),
        new Accent("red", "Red", 0xFFEF4444)
    };

    private static final List<WeakReference<Activity>> OPEN =
        new ArrayList<WeakReference<Activity>>();

    private ThemeManager() {
    }

    // ---------------------------------------------------------------------
    // Palette
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

            return custom != 0 ? custom : ACCENTS[0].color;
        }

        for (Accent accent : ACCENTS) {

            if (accent.id.equals(id)) {
                return accent.color;
            }
        }

        return ACCENTS[0].color;
    }

    public static Palette palette(Context context) {

        int accent = accentColor(context);

        String theme = resolveTheme(context);

        if (SettingsDefaults.THEME_LIGHT.equals(theme)) {

            return new Palette(
                0xFFF4F5FB, 0xFFFFFFFF, 0xFFECEEF8,
                0xFF0E1330, 0xFF3D4568, 0xFF6B7494, 0x1F0E1330,
                accent, true
            );
        }

        if (SettingsDefaults.THEME_AMOLED.equals(theme)) {

            return new Palette(
                0xFF000000, 0xFF0D1220, 0xFF161E33,
                0xFFF4F6FF, 0xFFB6BFD8, 0xFF7C86A6, 0x1FFFFFFF,
                accent, false
            );
        }

        return new Palette(
            M_BG, M_SURFACE, M_SURFACE_HIGH,
            M_TEXT, M_TEXT2, M_MUTED, M_DIVIDER,
            accent, false
        );
    }

    private static int colorOf(int role, Palette p) {

        switch (role) {

            case BG: return p.bg;
            case SURFACE: return p.surface;
            case SURFACE_HIGH: return p.surfaceHigh;
            case ACCENT: return p.accent;
            case ACCENT_DEEP: return p.accentDeep;
            case ACCENT_SOFT: return p.accentSoft;
            case ON_ACCENT: return p.onAccent;
            case TEXT: return p.text;
            case TEXT2: return p.text2;
            case MUTED: return p.muted;
            case DIVIDER: return p.divider;
            case FIXED_WHITE: return 0xFFFFFFFF;
            default: return 0;
        }
    }

    private static int roleOfColor(int color) {

        switch (color) {

            case M_BG: return BG;
            case M_SURFACE: return SURFACE;
            case M_SURFACE_HIGH: return SURFACE_HIGH;
            case M_ACCENT: return ACCENT;
            case M_ACCENT_DEEP: return ACCENT_DEEP;
            case M_ACCENT_SOFT: return ACCENT_SOFT;
            case M_ON_ACCENT: return ON_ACCENT;
            case M_TEXT: return TEXT;
            case M_TEXT2: return TEXT2;
            case M_MUTED: return MUTED;
            case M_DIVIDER: return DIVIDER;
            case 0xFFFFFFFF: return LEGACY_WHITE;
            default: return NONE;
        }
    }

    // ---------------------------------------------------------------------
    // Live updates
    // ---------------------------------------------------------------------

    /** Every screen registers itself so a change can reach all of them. */
    public static void register(Activity activity) {

        synchronized (OPEN) {

            for (int i = OPEN.size() - 1; i >= 0; i--) {

                Activity known = OPEN.get(i).get();

                if (known == null || known == activity) {
                    OPEN.remove(i);
                }
            }

            OPEN.add(new WeakReference<Activity>(activity));
        }
    }

    public static void unregister(Activity activity) {

        synchronized (OPEN) {

            for (int i = OPEN.size() - 1; i >= 0; i--) {

                Activity known = OPEN.get(i).get();

                if (known == null || known == activity) {
                    OPEN.remove(i);
                }
            }
        }
    }

    /** Re-paints every open screen. Safe to call from any thread. */
    public static void refreshAll() {

        List<Activity> screens = new ArrayList<Activity>();

        synchronized (OPEN) {

            for (WeakReference<Activity> ref : OPEN) {

                Activity activity = ref.get();

                if (activity != null && !activity.isFinishing()) {
                    screens.add(activity);
                }
            }
        }

        for (final Activity activity : screens) {

            activity.runOnUiThread(
                new Runnable() {

                    @Override
                    public void run() {

                        repaint(activity);
                    }
                }
            );
        }
    }

    /** Re-paints one screen (the window and everything in it). */
    public static void repaint(Activity activity) {

        if (activity.isFinishing()) {
            return;
        }

        if (activity instanceof ThemeAware) {

            ((ThemeAware) activity).onThemeChanged();

        } else {

            paintWindow(activity);

            apply(activity, activity.findViewById(android.R.id.content));
        }
    }

    /** Window background, system bars and the light/dark bar icons. */
    @SuppressWarnings("deprecation")
    public static void paintWindow(Activity activity) {

        Palette p = palette(activity);

        Window window = activity.getWindow();

        window.setBackgroundDrawable(new ColorDrawable(p.bg));
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        View decor = window.getDecorView();

        int flags = decor.getSystemUiVisibility();

        int light =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR |
            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;

        decor.setSystemUiVisibility(
            p.light ? (flags | light) : (flags & ~light)
        );
    }

    // ---------------------------------------------------------------------
    // Applying roles to a view tree
    // ---------------------------------------------------------------------

    /** What the engine remembers about one view. */
    private static final class Roles {

        int[] background = new int[0];
        boolean screenBackground;
        int tint;
        int imageTint;
        int drawableTint;
        int buttonTint;
        int progressTint;
        int progressBackgroundTint;
        int indeterminateTint;
        int text;
        int hint;
        int ownBackgroundRole;
    }

    public static void apply(Context context, View root) {

        if (root == null) {
            return;
        }

        Palette palette = palette(context);

        boolean gradient =
            SettingsDefaults.BACKGROUND_GRADIENT.equals(
                SettingsManager.get(context).getString(
                    SettingsDefaults.BACKGROUND
                )
            );

        walk(root, NONE, palette, gradient);
    }

    private static void walk(
        View view,
        int inheritedBackground,
        Palette palette,
        boolean gradient
    ) {

        if (SKIP_TAG.equals(view.getTag())) {
            return;
        }

        Roles roles = (Roles) view.getTag(R.id.mb_roles);

        if (roles == null) {

            roles = analyze(view, inheritedBackground);

            view.setTag(R.id.mb_roles, roles);
        }

        paint(view, roles, palette, gradient);

        if (view instanceof ViewGroup) {

            ViewGroup group = (ViewGroup) view;

            int forChildren =
                roles.ownBackgroundRole != NONE
                ? roles.ownBackgroundRole
                : inheritedBackground;

            for (int i = 0; i < group.getChildCount(); i++) {

                walk(group.getChildAt(i), forChildren, palette, gradient);
            }
        }
    }

    // ---- first sight: find out the roles ----

    private static Roles analyze(View view, int inheritedBackground) {

        Roles roles = new Roles();

        List<Integer> leaves = new ArrayList<Integer>();

        collect(view.getBackground(), leaves);

        roles.background = new int[leaves.size()];

        for (int i = 0; i < roles.background.length; i++) {
            roles.background[i] = leaves.get(i);
        }

        roles.ownBackgroundRole =
            roles.background.length > 0 ? roles.background[0] : NONE;

        roles.screenBackground =
            roles.ownBackgroundRole == BG &&
            inheritedBackground == NONE;

        roles.tint = roleOfTint(view.getBackgroundTintList());

        int effectiveBackground =
            roles.tint != NONE
            ? roles.tint
            : (roles.ownBackgroundRole != NONE ? roles.ownBackgroundRole : inheritedBackground);

        if (roles.tint != NONE && roles.ownBackgroundRole == NONE) {
            roles.ownBackgroundRole = roles.tint;
        }

        if (view instanceof ImageView) {

            roles.imageTint =
                fixWhite(
                    roleOfTint(((ImageView) view).getImageTintList()),
                    effectiveBackground
                );
        }

        if (view instanceof TextView) {

            TextView text = (TextView) view;

            roles.text =
                fixWhite(roleOfColor(text.getCurrentTextColor()), effectiveBackground);

            ColorStateList hint = text.getHintTextColors();

            roles.hint =
                hint != null
                ? fixWhite(roleOfColor(hint.getDefaultColor()), effectiveBackground)
                : NONE;

            roles.drawableTint =
                fixWhite(
                    roleOfTint(text.getCompoundDrawableTintList()),
                    effectiveBackground
                );
        }

        if (view instanceof CompoundButton) {

            roles.buttonTint =
                fixWhite(
                    roleOfTint(((CompoundButton) view).getButtonTintList()),
                    effectiveBackground
                );
        }

        if (view instanceof ProgressBar) {

            ProgressBar bar = (ProgressBar) view;

            roles.progressTint =
                fixWhite(roleOfTint(bar.getProgressTintList()), effectiveBackground);

            roles.progressBackgroundTint =
                fixWhite(roleOfTint(bar.getProgressBackgroundTintList()), effectiveBackground);

            roles.indeterminateTint =
                fixWhite(roleOfTint(bar.getIndeterminateTintList()), effectiveBackground);
        }

        return roles;
    }

    /** Resolve legacy white according to the background it is displayed on. */
    private static int fixWhite(int role, int effectiveBackground) {

        if (role == LEGACY_WHITE) {

            if (
                effectiveBackground == ACCENT ||
                effectiveBackground == ACCENT_DEEP
            ) {
                return ON_ACCENT;
            }

            if (
                effectiveBackground == BG ||
                effectiveBackground == SURFACE ||
                effectiveBackground == SURFACE_HIGH ||
                effectiveBackground == ACCENT_SOFT
            ) {
                return TEXT;
            }

            return FIXED_WHITE;
        }

        if (
            role == ON_ACCENT &&
            effectiveBackground != ACCENT &&
            effectiveBackground != ACCENT_DEEP
        ) {
            return FIXED_WHITE;
        }

        return role;
    }

    private static int roleOfTint(ColorStateList list) {

        return list == null ? NONE : roleOfColor(list.getDefaultColor());
    }

    private static void collect(Drawable drawable, List<Integer> out) {

        if (drawable == null) {
            return;
        }

        if (drawable instanceof ColorDrawable) {

            out.add(roleOfColor(((ColorDrawable) drawable).getColor()));

        } else if (drawable instanceof GradientDrawable) {

            GradientDrawable shape = (GradientDrawable) drawable;

            int[] colors = shape.getColors();

            if (colors != null && colors.length >= 2) {

                out.add(roleOfColor(colors[0]));
                out.add(roleOfColor(colors[colors.length - 1]));

            } else {

                ColorStateList fill = shape.getColor();

                out.add(fill == null ? NONE : roleOfColor(fill.getDefaultColor()));
            }

        } else if (drawable instanceof LayerDrawable) {

            LayerDrawable layers = (LayerDrawable) drawable;

            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                collect(layers.getDrawable(i), out);
            }

        } else if (drawable instanceof StateListDrawable) {

            StateListDrawable states = (StateListDrawable) drawable;

            for (int i = 0; i < states.getStateCount(); i++) {
                collect(states.getStateDrawable(i), out);
            }
        }
    }

    // ---- every time: paint the roles ----

    private static void paint(
        View view,
        Roles roles,
        Palette palette,
        boolean gradient
    ) {

        // background
        if (roles.screenBackground) {

            view.setBackground(
                gradient
                ? new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] {
                        palette.bg,
                        shade(palette.bg, palette.light ? -0.05f : 0.12f)
                    }
                )
                : new ColorDrawable(palette.bg)
            );

        } else if (roles.background.length > 0) {

            Drawable background = view.getBackground();

            if (background != null) {

                background = background.mutate();

                int[] index = { 0 };

                recolor(background, roles.background, index, palette);
            }
        }

        // tints
        if (roles.tint != NONE) {

            view.setBackgroundTintList(
                ColorStateList.valueOf(colorOf(roles.tint, palette))
            );
        }

        if (view instanceof ImageView && roles.imageTint != NONE) {

            ((ImageView) view).setImageTintList(
                ColorStateList.valueOf(colorOf(roles.imageTint, palette))
            );
        }

        if (view instanceof TextView) {

            TextView text = (TextView) view;

            if (roles.text != NONE) {
                text.setTextColor(colorOf(roles.text, palette));
            }

            if (roles.hint != NONE) {
                text.setHintTextColor(colorOf(roles.hint, palette));
            }

            if (roles.drawableTint != NONE) {

                text.setCompoundDrawableTintList(
                    ColorStateList.valueOf(colorOf(roles.drawableTint, palette))
                );
            }
        }

        if (view instanceof CompoundButton && roles.buttonTint != NONE) {

            ((CompoundButton) view).setButtonTintList(
                ColorStateList.valueOf(colorOf(roles.buttonTint, palette))
            );
        }

        if (view instanceof ProgressBar) {

            ProgressBar bar = (ProgressBar) view;

            if (roles.progressTint != NONE) {
                bar.setProgressTintList(
                    ColorStateList.valueOf(colorOf(roles.progressTint, palette))
                );
            }

            if (roles.progressBackgroundTint != NONE) {
                bar.setProgressBackgroundTintList(
                    ColorStateList.valueOf(
                        colorOf(roles.progressBackgroundTint, palette)
                    )
                );
            }

            if (roles.indeterminateTint != NONE) {
                bar.setIndeterminateTintList(
                    ColorStateList.valueOf(
                        colorOf(roles.indeterminateTint, palette)
                    )
                );
            }
        }
    }

    private static void recolor(
        Drawable drawable,
        int[] roles,
        int[] index,
        Palette palette
    ) {

        if (drawable == null) {
            return;
        }

        if (drawable instanceof ColorDrawable) {

            int role = roles[index[0]++];

            if (role != NONE) {
                ((ColorDrawable) drawable).setColor(colorOf(role, palette));
            }

        } else if (drawable instanceof GradientDrawable) {

            GradientDrawable shape = (GradientDrawable) drawable;

            int[] colors = shape.getColors();

            if (colors != null && colors.length >= 2) {

                int first = roles[index[0]++];
                int last = roles[index[0]++];

                if (first != NONE && last != NONE) {

                    shape.setColors(
                        new int[] {
                            colorOf(first, palette),
                            colorOf(last, palette)
                        }
                    );
                }

            } else {

                int role = roles[index[0]++];

                if (role != NONE) {
                    shape.setColor(colorOf(role, palette));
                }
            }

        } else if (drawable instanceof LayerDrawable) {

            LayerDrawable layers = (LayerDrawable) drawable;

            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                recolor(layers.getDrawable(i), roles, index, palette);
            }

        } else if (drawable instanceof StateListDrawable) {

            StateListDrawable states = (StateListDrawable) drawable;

            for (int i = 0; i < states.getStateCount(); i++) {
                recolor(states.getStateDrawable(i), roles, index, palette);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Other appearance options
    // ---------------------------------------------------------------------

    private static float linearize(int channel) {

        float value = channel / 255f;

        return value <= 0.04045f
            ? value / 12.92f
            : (float) Math.pow((value + 0.055f) / 1.055f, 2.4f);
    }

    static float luminance(int color) {

        return
            0.2126f * linearize(Color.red(color)) +
            0.7152f * linearize(Color.green(color)) +
            0.0722f * linearize(Color.blue(color));
    }

    static int shade(int color, float amount) {

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
