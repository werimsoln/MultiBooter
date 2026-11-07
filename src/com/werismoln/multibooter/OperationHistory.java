/******************************************************************************
 * OperationHistory.java
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
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The last operations (ISO writes, Ventoy installs, gadget / FunctionFS /
 * TFTP sessions) with their result. Kept in SharedPreferences, newest first,
 * at most MAX entries. Messages are masked before they are stored.
 */
public final class OperationHistory {

    public static final class Entry {

        public final long time;
        public final int mode;
        public final boolean success;
        public final String message;

        Entry(long time, int mode, boolean success, String message) {
            this.time = time;
            this.mode = mode;
            this.success = success;
            this.message = message;
        }
    }

    private static final String FILE = "multibooter_history";
    private static final String KEY = "entries";
    private static final int MAX = 30;

    private OperationHistory() {
    }

    public static synchronized void add(
        Context context,
        int mode,
        boolean success,
        String message
    ) {

        try {

            JSONArray old = load(context);
            JSONArray next = new JSONArray();

            JSONObject entry = new JSONObject();

            entry.put("t", System.currentTimeMillis());
            entry.put("m", mode);
            entry.put("ok", success);
            entry.put("msg", DebugLog.mask(message));

            next.put(entry);

            for (int i = 0; i < old.length() && next.length() < MAX; i++) {
                next.put(old.get(i));
            }

            prefs(context).edit().putString(KEY, next.toString()).apply();

        } catch (JSONException ignored) {
            // History is a convenience; never fail an operation because of it.
        }
    }

    public static synchronized List<Entry> list(Context context) {

        List<Entry> out = new ArrayList<Entry>();

        JSONArray array = load(context);

        for (int i = 0; i < array.length(); i++) {

            JSONObject o = array.optJSONObject(i);

            if (o != null) {

                out.add(
                    new Entry(
                        o.optLong("t"),
                        o.optInt("m"),
                        o.optBoolean("ok"),
                        o.optString("msg")
                    )
                );
            }
        }

        return out;
    }

    public static synchronized void clear(Context context) {

        prefs(context).edit().remove(KEY).apply();
    }

    private static JSONArray load(Context context) {

        try {

            return new JSONArray(prefs(context).getString(KEY, "[]"));

        } catch (JSONException e) {

            return new JSONArray();
        }
    }

    private static SharedPreferences prefs(Context context) {

        return context
            .getApplicationContext()
            .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
