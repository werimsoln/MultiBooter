/******************************************************************************
 * MaxWidthLayout.java
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
import android.util.AttributeSet;
import android.widget.LinearLayout;

/**
 * A LinearLayout that never gets wider than 560 dp. On tablets and in
 * landscape the content stays a comfortable reading width and, with
 * layout_gravity="center_horizontal", sits in the middle of the screen.
 */
public class MaxWidthLayout extends LinearLayout {

    private static final int MAX_WIDTH_DP = 560;

    private final int maxWidthPx;

    public MaxWidthLayout(Context context, AttributeSet attrs) {

        super(context, attrs);

        maxWidthPx =
            Math.round(
                MAX_WIDTH_DP *
                context.getResources().getDisplayMetrics().density
            );

        if (
            attrs == null ||
            attrs.getAttributeValue(
                "http://schemas.android.com/apk/res/android",
                "orientation"
            ) == null
        ) {
            setOrientation(VERTICAL);
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {

        int width = MeasureSpec.getSize(widthSpec);

        if (width > maxWidthPx) {

            int mode =
                MeasureSpec.getMode(widthSpec) == MeasureSpec.EXACTLY
                ? MeasureSpec.EXACTLY
                : MeasureSpec.AT_MOST;

            widthSpec = MeasureSpec.makeMeasureSpec(maxWidthPx, mode);
        }

        super.onMeasure(widthSpec, heightSpec);
    }
}
