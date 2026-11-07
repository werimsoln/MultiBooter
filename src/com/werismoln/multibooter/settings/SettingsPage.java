/******************************************************************************
 * SettingsPage.java
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
import android.widget.LinearLayout;

/**
 * What a category builder gets: the activity, the row builder and the
 * content column to add cards to, plus callbacks to the hosting screen.
 */
public final class SettingsPage {

    /** Implemented by the screen that hosts a category page. */
    public interface Host {

        /** Rebuilds the whole page with the current settings (live preview). */
        void rebuild();

        /** Re-creates the activity (needed for the font scale). */
        void recreateScreen();

        /** Lets the user choose where to save the text as a file. */
        void exportText(String fileName, String text);
    }

    public final Activity activity;
    public final SettingsManager settings;
    public final SettingsUi ui;
    public final LinearLayout content;
    public final Host host;

    public SettingsPage(
        Activity activity,
        SettingsUi ui,
        LinearLayout content,
        Host host
    ) {
        this.activity = activity;
        this.settings = SettingsManager.get(activity);
        this.ui = ui;
        this.content = content;
        this.host = host;
    }

    /** Standard listener: rebuild the page after a change. */
    public SettingsUi.OnChanged rebuildOnChange() {

        return new SettingsUi.OnChanged() {

            @Override
            public void onChanged() {
                host.rebuild();
            }
        };
    }
}
