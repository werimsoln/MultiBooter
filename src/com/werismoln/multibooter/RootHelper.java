/******************************************************************************
 * RootHelper.java
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Shared root detection.
 *
 * The result of the last successful check is stored as the "root-granted=true"
 * line of the "restored-data" file, the same file and format that MainActivity
 * and BootManager already use. Unlike before, the line is also REMOVED when
 * root is no longer available, so BootManager never keeps showing the root
 * modes after root was revoked or the device was un-rooted.
 */
final class RootHelper {

    static final String STATE_FILE = "restored-data";
    static final String FLAG_ROOT_GRANTED = "root-granted=true";

    /** Time given to the user to answer the superuser prompt. */
    private static final long SU_TIMEOUT_SECONDS = 25L;

    static final int RESULT_GRANTED = 1;
    static final int RESULT_DENIED = 0;

    /**
     * The su prompt was not answered in time or the check could not finish.
     * The stored state is left unchanged in that case.
     */
    static final int RESULT_UNKNOWN = -1;

    interface Callback {

        /**
         * Called on the UI thread.
         *
         * @param result RESULT_GRANTED, RESULT_DENIED or RESULT_UNKNOWN
         */
        void onRootChecked(int result);
    }

    private static final Object FILE_LOCK = new Object();

    private RootHelper() {
    }

    static boolean isRootSaved(Context context) {

        synchronized (FILE_LOCK) {

            return readLines(context).contains(FLAG_ROOT_GRANTED);
        }
    }

    /**
     * Adds or removes only the root line. Every other line of the state
     * file (for example "permissions-granted=true") is preserved.
     */
    static void saveRoot(Context context, boolean granted) {

        synchronized (FILE_LOCK) {

            List<String> lines = readLines(context);
            boolean present = lines.contains(FLAG_ROOT_GRANTED);

            if (granted == present) {
                return;
            }

            if (granted) {

                lines.add(FLAG_ROOT_GRANTED);

            } else {

                while (lines.remove(FLAG_ROOT_GRANTED)) {
                    // remove every duplicate
                }
            }

            writeLines(context, lines);
        }
    }

    /**
     * Runs the root check on a background thread, stores the result and
     * reports it on the UI thread.
     */
    static void check(final Activity activity, final Callback callback) {

        final Context appContext = activity.getApplicationContext();

        Thread thread = new Thread(
            new Runnable() {

                @Override
                public void run() {

                    final int result = probe();

                    if (result == RESULT_GRANTED) {
                        saveRoot(appContext, true);
                    } else if (result == RESULT_DENIED) {
                        saveRoot(appContext, false);
                    }

                    if (callback == null) {
                        return;
                    }

                    activity.runOnUiThread(
                        new Runnable() {

                            @Override
                            public void run() {

                                if (activity.isFinishing()) {
                                    return;
                                }

                                callback.onRootChecked(result);
                            }
                        }
                    );
                }
            },
            "RootCheck"
        );

        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Blocking check. Must not be called on the UI thread.
     */
    static int probe() {

        Process process = null;

        try {

            // Same way the setup screen asked for root: open "su" and feed
            // it commands on stdin. This works with every su implementation.
            process = new ProcessBuilder("su")
                .redirectErrorStream(true)
                .start();

            OutputStream stdin = process.getOutputStream();

            stdin.write("id\nexit\n".getBytes("UTF-8"));
            stdin.flush();
            stdin.close();

            if (!process.waitFor(SU_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return RESULT_UNKNOWN;
            }

            String output = readAll(process.getInputStream());

            if (
                process.exitValue() == 0 &&
                output.contains("uid=0")
            ) {
                return RESULT_GRANTED;
            }

            return RESULT_DENIED;

        } catch (IOException e) {

            // The su binary does not exist: the device is not rooted.
            return RESULT_DENIED;

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();
            return RESULT_UNKNOWN;

        } catch (RuntimeException e) {

            return RESULT_UNKNOWN;

        } finally {

            if (process != null) {
                process.destroy();
            }
        }
    }

    private static String readAll(InputStream in) throws IOException {

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[256];
        int read;

        while (
            (read = in.read(buffer)) > 0 &&
            out.size() < 4096
        ) {
            out.write(buffer, 0, read);
        }

        return out.toString("UTF-8");
    }

    private static List<String> readLines(Context context) {

        List<String> lines = new ArrayList<String>();
        File file = new File(context.getFilesDir(), STATE_FILE);

        if (!file.exists()) {
            return lines;
        }

        InputStream in = null;

        try {

            in = new FileInputStream(file);
            String text = readAll(in);

            for (String line : text.split("\n")) {

                String trimmed = line.trim();

                if (trimmed.length() > 0) {
                    lines.add(trimmed);
                }
            }

        } catch (IOException e) {

            e.printStackTrace();

        } finally {

            closeQuietly(in);
        }

        return lines;
    }

    private static void writeLines(Context context, List<String> lines) {

        File dir = context.getFilesDir();
        File file = new File(dir, STATE_FILE);
        File temp = new File(dir, STATE_FILE + ".tmp");

        StringBuilder text = new StringBuilder();

        for (String line : lines) {
            text.append(line).append('\n');
        }

        OutputStream out = null;

        try {

            out = new FileOutputStream(temp);
            out.write(text.toString().getBytes("UTF-8"));
            out.flush();
            out.close();
            out = null;

            // renameTo replaces the target on Android (Linux rename()).
            if (!temp.renameTo(file)) {
                temp.delete();
            }

        } catch (IOException e) {

            e.printStackTrace();
            temp.delete();

        } finally {

            closeQuietly(out);
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {

        if (closeable == null) {
            return;
        }

        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }
}
