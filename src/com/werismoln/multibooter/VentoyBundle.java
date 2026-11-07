/******************************************************************************
 * VentoyBundle.java
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/** Information about the Ventoy release that is packaged in this app. */
public final class VentoyBundle {

    private static final String ASSET_VERSION = "ventoy.version";

    private static String cached;

    private VentoyBundle() {
    }

    /**
     * Version text such as "1.1.18" (written into the assets by build.sh),
     * or "" if the asset is missing.
     */
    public static synchronized String version(Context context) {

        if (cached != null) {
            return cached;
        }

        String value = "";

        try {

            BufferedReader reader =
                new BufferedReader(
                    new InputStreamReader(
                        context.getAssets().open(ASSET_VERSION),
                        "UTF-8"
                    )
                );

            try {

                String line = reader.readLine();

                if (line != null && VentoyVersion.isValid(line)) {
                    value = line.trim();
                }

            } finally {

                reader.close();
            }

        } catch (IOException ignored) {
            // Older builds have no version asset.
        }

        cached = value;

        return cached;
    }
}
