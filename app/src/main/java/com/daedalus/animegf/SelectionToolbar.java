package com.daedalus.animegf;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The app's own text-selection toolbar: a small floating glass pill that replaces the system
 * one. It is a plain overlay view (never a window or dialog), so the WebView keeps focus and
 * its selection while buttons are tapped. Positioning is done by {@link #show}.
 */
final class SelectionToolbar {

    static final int CUT        = 1;
    static final int COPY       = 1 << 1;
    static final int PASTE      = 1 << 2;
    static final int SELECT_ALL = 1 << 3;
    static final int SEARCH     = 1 << 4;
    static final int TRANSLATE  = 1 << 5;

    interface Listener {
        void onAction(int action);
    }

    private static final int[]    ACTIONS = {CUT, COPY, PASTE, SELECT_ALL, SEARCH, TRANSLATE};
    private static final int[]    ICONS   = {
        R.drawable.ic_cut, R.drawable.ic_copy, R.drawable.ic_paste,
        R.drawable.ic_select_all, R.drawable.ic_search, R.drawable.ic_translate
    };
    private static final String[] LABELS  = {"Cut", "Copy", "Paste", "Select all", "Search", "Translate"};
    private static final int[]    TINTS   = {Ui.TEXT, Ui.TEXT, Ui.TEXT, Ui.TEXT, Ui.CYAN, Ui.GREEN};

    private final Context ctx;
    private final Listener listener;
    private final FrameLayout container;
    private final LinearLayout row;

    private final int margin;
    private final int gapAbove;
    private final int gapBelow;

    private int builtActions;
    private boolean visible;

    /** Adds the (initially hidden) toolbar to {@code host} at child position {@code insertIndex}. */
    SelectionToolbar(Context ctx, FrameLayout host, int insertIndex, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
        this.margin = Ui.dp(ctx, 8);
        this.gapAbove = Ui.dp(ctx, 12);
        this.gapBelow = Ui.dp(ctx, 38);   // leaves room for the selection drag handles

        final float radius = Ui.dp(ctx, 22);
        container = new FrameLayout(ctx);
        container.setBackground(Ui.glassPanel(ctx, 22));
        container.setElevation(Ui.dp(ctx, 14));
        container.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        container.setClipToOutline(true);
        container.setVisibility(View.GONE);

        HorizontalScrollView scroller = new HorizontalScrollView(ctx);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.setFocusable(false);
        scroller.setDescendantFocusability(HorizontalScrollView.FOCUS_BLOCK_DESCENDANTS);

        row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4));
        scroller.addView(row, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        container.addView(scroller, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        host.addView(container, insertIndex, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.START));
    }

    boolean isShowing() {
        return visible;
    }

    /**
     * Shows the toolbar with the given actions next to {@code anchor}. Both rectangles are in
     * the host's coordinates: {@code anchor} is the selection, {@code bounds} the area the
     * toolbar may use (the WebView). It goes above the selection, below it when there is no
     * room above, and is always kept inside {@code bounds}.
     */
    void show(RectF anchor, RectF bounds, int actions) {
        if (actions == 0) {
            hide();
            return;
        }
        if (actions != builtActions) rebuild(actions);

        int maxW = Math.max(0, Math.round(bounds.width()) - 2 * margin);
        int maxH = Math.max(0, Math.round(bounds.height()) - 2 * margin);
        container.measure(
            View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST));
        int w = container.getMeasuredWidth();
        int h = container.getMeasuredHeight();

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) container.getLayoutParams();
        if (lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            container.setLayoutParams(lp);
        }

        float x = clamp(anchor.centerX() - w / 2f, bounds.left + margin, bounds.right - margin - w);
        float y = anchor.top - h - gapAbove;
        if (y < bounds.top + margin) {
            float below = anchor.bottom + gapBelow;
            y = below + h <= bounds.bottom - margin
                ? below
                : clamp(anchor.top, bounds.top + margin, bounds.bottom - margin - h);
        }
        container.setTranslationX(x);
        container.setTranslationY(y);

        if (visible) return;
        visible = true;
        container.animate().cancel();
        container.setPivotX(w / 2f);
        container.setPivotY(h);
        container.setAlpha(0f);
        container.setScaleX(0.92f);
        container.setScaleY(0.92f);
        container.setVisibility(View.VISIBLE);
        container.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(140)
            .setInterpolator(new DecelerateInterpolator(1.5f))
            .start();
    }

    void hide() {
        if (!visible) return;
        visible = false;
        container.animate().cancel();
        container.animate()
            .alpha(0f).scaleX(0.96f).scaleY(0.96f)
            .setDuration(90)
            .withEndAction(() -> {
                if (!visible) container.setVisibility(View.GONE);
            })
            .start();
    }

    // ─── BUILD ────────────────────────────────────────────────────────────────

    private void rebuild(int actions) {
        builtActions = actions;
        row.removeAllViews();
        for (int i = 0; i < ACTIONS.length; i++) {
            if ((actions & ACTIONS[i]) == 0) continue;
            row.addView(buildItem(ACTIONS[i], ICONS[i], LABELS[i], TINTS[i]));
        }
    }

    private View buildItem(final int action, int iconRes, String label, int tint) {
        LinearLayout item = new LinearLayout(ctx);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setMinimumWidth(Ui.dp(ctx, 54));
        item.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 7), Ui.dp(ctx, 8), Ui.dp(ctx, 6));
        float r = Ui.dp(ctx, 16);
        item.setBackground(Ui.ripple(Ui.rounded(Color.TRANSPARENT, r, 0, 0), r, 0x33FFFFFF));
        item.setClickable(true);
        item.setContentDescription(label);
        item.setOnClickListener(v -> listener.onAction(action));

        ImageView icon = Ui.icon(ctx, iconRes, tint, 20);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(ctx, 20), Ui.dp(ctx, 20));
        ip.bottomMargin = Ui.dp(ctx, 3);
        item.addView(icon, ip);

        TextView text = Ui.text(ctx, label, 10.5f, Ui.TEXT_DIM, true);
        text.setSingleLine(true);
        item.addView(text, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return item;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(value, max));
    }
}
