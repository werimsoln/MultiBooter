/******************************************************************************
 * VentoyVersion.java
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

/** Compares Ventoy version strings such as "1.0.99" and "1.1.18". */
public final class VentoyVersion {

    private VentoyVersion() {
    }

    /**
     * @return negative if a is older than b, 0 if equal, positive if newer.
     *         A version that cannot be read counts as 0.0.0.
     */
    public static int compare(String a, String b) {

        int[] x = parse(a);
        int[] y = parse(b);

        for (int i = 0; i < 3; i++) {

            if (x[i] != y[i]) {
                return x[i] < y[i] ? -1 : 1;
            }
        }

        return 0;
    }

    public static boolean isValid(String version) {

        if (version == null) {
            return false;
        }

        return version.trim().matches("\\d+(\\.\\d+){1,2}.*");
    }

    private static int[] parse(String text) {

        int[] parts = new int[3];

        if (text == null) {
            return parts;
        }

        String[] pieces = text.trim().split("[^0-9]+");

        int index = 0;

        for (String piece : pieces) {

            if (piece.length() == 0) {
                continue;
            }

            if (index >= 3) {
                break;
            }

            try {

                parts[index++] = Integer.parseInt(piece);

            } catch (NumberFormatException e) {

                parts[index++] = 0;
            }
        }

        return parts;
    }
}
