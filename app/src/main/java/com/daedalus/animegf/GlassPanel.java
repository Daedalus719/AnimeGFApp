package com.daedalus.animegf;

import android.content.Context;
import android.widget.LinearLayout;

/**
 * Vertical LinearLayout that clamps its own size: never wider than {@code maxWidthPx},
 * never taller than {@code maxHeightPx} (and never larger than what its parent offers).
 *
 * fillHeight = true  → always as tall as allowed (stable "tool window", children may use weights)
 * fillHeight = false → wraps its content, up to the maximum
 */
final class GlassPanel extends LinearLayout {

    private final int maxWidthPx;
    private final int maxHeightPx;
    private final boolean fillHeight;

    GlassPanel(Context context, int maxWidthPx, int maxHeightPx, boolean fillHeight) {
        super(context);
        setOrientation(VERTICAL);
        this.maxWidthPx = maxWidthPx;
        this.maxHeightPx = maxHeightPx;
        this.fillHeight = fillHeight;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int parentW = MeasureSpec.getSize(widthSpec);
        int parentH = MeasureSpec.getSize(heightSpec);
        int w = maxWidthPx > 0 ? Math.min(parentW, maxWidthPx) : parentW;
        int h = maxHeightPx > 0 ? Math.min(parentH, maxHeightPx) : parentH;
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, fillHeight ? MeasureSpec.EXACTLY : MeasureSpec.AT_MOST));
    }
}
