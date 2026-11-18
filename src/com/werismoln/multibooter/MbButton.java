/******************************************************************************
 * MbButton.java
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
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.widget.Button;

/**
 * The app's button: no forced capital letters, no elevation shadow, bold
 * text, and a clearly dimmed look while disabled.
 */
public class MbButton extends Button {

    public MbButton(Context context) {

        this(context, null);
    }

    public MbButton(Context context, AttributeSet attrs) {

        super(context, attrs);

        setAllCaps(false);
        setStateListAnimator(null);
        setMinWidth(0);
        setMinHeight(0);
        setTypeface(getTypeface(), Typeface.BOLD);
        setGravity(android.view.Gravity.CENTER);
    }

    @Override
    public void setEnabled(boolean enabled) {

        super.setEnabled(enabled);

        setAlpha(enabled ? 1.0f : 0.45f);
    }
}
