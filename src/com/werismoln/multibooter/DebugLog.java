/******************************************************************************
 * DebugLog.java
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

import android.content.Context;
import android.util.Log;

import com.werismoln.multibooter.settings.SettingsDefaults;
import com.werismoln.multibooter.settings.SettingsManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Debug log with levels, per-module switches, rotation and masking.
 *
 * Nothing detailed is written to disk while Debug Mode is off. Warnings and
 * errors still go to logcat only. When Debug Mode is on, lines are appended
 * to files/logs/log.0.txt and rotated (3 files of up to 256 KiB), and
 * sensitive values are masked before they are written.
 *
 * The native C libraries keep using logcat; they are not routed here.
 */
public final class DebugLog {

    public enum Module {

        JAVA("java", "Java"),
        USB("usb", "USB Host / SCSI"),
        GADGET("gadget", "USB Gadget / ConfigFS"),
        FUNCTIONFS("functionfs", "FunctionFS"),
        TFTP("tftp", "TFTP / PXE"),
        VENTOY("ventoy", "Ventoy installation");

        public final String id;
        public final String label;

        Module(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public String settingsKey() {

            return SettingsDefaults.LOG_MODULE_PREFIX + id;
        }
    }

    public static final int VERBOSE = 0;
    public static final int DEBUG = 1;
    public static final int INFO = 2;
    public static final int WARN = 3;
    public static final int ERROR = 4;

    private static final String TAG = "MultiBooter";

    private static final int FILES = 3;
    private static final long MAX_FILE_BYTES = 256L * 1024L;
    private static final long MAX_READ_BYTES = 768L * 1024L;

    private static final Object LOCK = new Object();

    private static Context appContext;

    private static final String[] LEVEL_CHARS = { "V", "D", "I", "W", "E" };

    private DebugLog() {
    }

    /** Call once from the first activity; safe to call repeatedly. */
    public static void init(Context context) {

        if (appContext == null) {
            appContext = context.getApplicationContext();
        }
    }

    public static boolean isEnabled() {

        return
            appContext != null &&
            SettingsManager.get(appContext).getBoolean(
                SettingsDefaults.DEBUG_MODE
            );
    }

    public static void v(Module m, String msg) { log(VERBOSE, m, msg, null); }
    public static void d(Module m, String msg) { log(DEBUG, m, msg, null); }
    public static void i(Module m, String msg) { log(INFO, m, msg, null); }
    public static void w(Module m, String msg) { log(WARN, m, msg, null); }
    public static void e(Module m, String msg) { log(ERROR, m, msg, null); }

    public static void e(Module m, String msg, Throwable t) {

        log(ERROR, m, msg, t);
    }

    public static void log(int level, Module module, String msg, Throwable t) {

        // Always visible in logcat for warnings and errors.
        if (level >= WARN) {
            Log.println(
                level == ERROR ? Log.ERROR : Log.WARN,
                TAG + "/" + module.id,
                msg
            );
        }

        if (!isEnabled()) {
            return;
        }

        SettingsManager settings = SettingsManager.get(appContext);

        if (!settings.getBoolean(module.settingsKey())) {
            return;
        }

        if (level < configuredLevel(settings)) {
            return;
        }

        StringBuilder line = new StringBuilder();

        line.append(
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date())
        );

        line.append(' ')
            .append(LEVEL_CHARS[level])
            .append('/')
            .append(module.id)
            .append(": ")
            .append(mask(msg));

        if (t != null) {
            line.append(" | ")
                .append(t.getClass().getSimpleName())
                .append(": ")
                .append(mask(String.valueOf(t.getMessage())));
        }

        line.append('\n');

        append(line.toString());
    }

    private static int configuredLevel(SettingsManager settings) {

        String level = settings.getString(SettingsDefaults.LOG_LEVEL);

        if (SettingsDefaults.LEVEL_VERBOSE.equals(level)) return VERBOSE;
        if (SettingsDefaults.LEVEL_DEBUG.equals(level)) return DEBUG;
        if (SettingsDefaults.LEVEL_WARN.equals(level)) return WARN;

        return INFO;
    }

    // ---------------------------------------------------------------------
    // Storage and rotation
    // ---------------------------------------------------------------------

    private static File dir() {

        File dir = new File(appContext.getFilesDir(), "logs");

        if (!dir.exists()) {
            dir.mkdirs();
        }

        return dir;
    }

    private static File file(int index) {

        return new File(dir(), "log." + index + ".txt");
    }

    private static void append(String text) {

        synchronized (LOCK) {

            try {

                File current = file(0);

                if (current.length() + text.length() > MAX_FILE_BYTES) {
                    rotate();
                }

                FileOutputStream out = new FileOutputStream(file(0), true);

                try {
                    out.write(text.getBytes("UTF-8"));
                } finally {
                    out.close();
                }

            } catch (IOException e) {

                Log.w(TAG, "Could not write the debug log.", e);
            }
        }
    }

    private static void rotate() {

        file(FILES - 1).delete();

        for (int i = FILES - 2; i >= 0; i--) {

            File from = file(i);

            if (from.exists()) {
                from.renameTo(file(i + 1));
            }
        }
    }

    /** Oldest to newest, at most about 768 KiB. */
    public static String readAll(Context context) {

        init(context);

        synchronized (LOCK) {

            StringBuilder text = new StringBuilder();

            for (int i = FILES - 1; i >= 0; i--) {

                File f = file(i);

                if (!f.exists()) {
                    continue;
                }

                try {

                    BufferedReader reader =
                        new BufferedReader(
                            new InputStreamReader(
                                new FileInputStream(f),
                                "UTF-8"
                            )
                        );

                    try {

                        String line;

                        while ((line = reader.readLine()) != null) {
                            text.append(line).append('\n');
                        }

                    } finally {
                        reader.close();
                    }

                } catch (IOException e) {

                    text.append("[could not read ")
                        .append(f.getName())
                        .append("]\n");
                }
            }

            if (text.length() > MAX_READ_BYTES) {
                return text.substring(text.length() - (int) MAX_READ_BYTES);
            }

            return text.toString();
        }
    }

    public static List<String> tail(Context context, int lines) {

        String[] all = readAll(context).split("\n");
        List<String> out = new ArrayList<String>();

        for (int i = Math.max(0, all.length - lines); i < all.length; i++) {

            if (all[i].length() > 0) {
                out.add(all[i]);
            }
        }

        return out;
    }

    public static void clear(Context context) {

        init(context);

        synchronized (LOCK) {

            for (int i = 0; i < FILES; i++) {
                file(i).delete();
            }
        }
    }

    public static long sizeBytes(Context context) {

        init(context);

        long size = 0;

        for (int i = 0; i < FILES; i++) {
            size += file(i).length();
        }

        return size;
    }

    // ---------------------------------------------------------------------
    // Masking
    // ---------------------------------------------------------------------

    private static final Pattern MAC =
        Pattern.compile(
            "\\b([0-9A-Fa-f]{2}[:-]){2}[0-9A-Fa-f]{2}([:-][0-9A-Fa-f]{2}){3}\\b"
        );

    private static final Pattern IPV4 =
        Pattern.compile("\\b(\\d{1,3})\\.(\\d{1,3})\\.\\d{1,3}\\.\\d{1,3}\\b");

    private static final Pattern SERIAL =
        Pattern.compile(
            "(?i)\\b(serial(?:number|no)?|android_id|imei)\\b\\s*[=:]\\s*\\S+"
        );

    private static final Pattern CONTENT_URI =
        Pattern.compile("content://\\S+");

    private static final Pattern STORAGE_PATH =
        Pattern.compile("(/storage/[^/\\s]+/|/sdcard/)(?:[^\\s]*/)?([^/\\s]+)");

    private static final Pattern EMAIL =
        Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /** Hides values that identify the user, device or files. */
    public static String mask(String text) {

        if (text == null) {
            return "";
        }

        String out = text;

        out = CONTENT_URI.matcher(out).replaceAll("content://***");
        out = SERIAL.matcher(out).replaceAll("$1=***");
        out = EMAIL.matcher(out).replaceAll("***@***");
        out = MAC.matcher(out).replaceAll("xx:xx:xx:xx:xx:xx");
        out = IPV4.matcher(out).replaceAll("$1.$2.x.x");
        out = STORAGE_PATH.matcher(out).replaceAll("$1.../$2");

        return out;
    }
}
