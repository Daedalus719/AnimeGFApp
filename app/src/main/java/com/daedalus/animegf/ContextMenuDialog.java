package com.daedalus.animegf;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Outline;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Long-press menu for images and links, built on the shared glass dialog shell and anchored
 * to the bottom of the screen. The header shows a preview of the image (when there is one)
 * with its host and address; below it come the actions.
 */
final class ContextMenuDialog extends GlassDialog {

    private final LinearLayout rows;

    /**
     * @param title      first header line (usually the host)
     * @param subtitle   second header line (the full address)
     * @param chipIcon   icon for the header when there is no image preview
     * @param thumbnail  image to preview in the header, or null
     */
    ContextMenuDialog(Activity activity, String title, String subtitle, int chipIcon,
                      ImageTools.Request thumbnail) {
        super(activity, 420, 0, false);

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.getLayoutParams();
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        panel.setLayoutParams(lp);

        panel.addView(buildHeaderRow(title, subtitle, chipIcon, thumbnail));

        View hairline = new View(ctx);
        hairline.setBackgroundColor(Ui.HAIRLINE);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(ctx, 1)));
        hp.leftMargin = Ui.dp(ctx, 16);
        hp.rightMargin = Ui.dp(ctx, 16);
        panel.addView(hairline, hp);

        rows = new LinearLayout(ctx);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 10));

        ScrollView scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(rows, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    /** Adds an action row. The dialog closes first, then {@code action} runs. */
    ContextMenuDialog addAction(int iconRes, CharSequence label, int accent, Runnable action) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 9), Ui.dp(ctx, 12), Ui.dp(ctx, 9));
        float r = Ui.dp(ctx, 16);
        row.setBackground(Ui.ripple(Ui.rounded(Color.TRANSPARENT, r, 0, 0), r, 0x26FFFFFF));
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription(label);

        FrameLayout chip = new FrameLayout(ctx);
        chip.setBackground(Ui.circle((accent & 0x00FFFFFF) | 0x29000000, 0, 0));
        chip.addView(Ui.icon(ctx, iconRes, accent, 18),
            new FrameLayout.LayoutParams(Ui.dp(ctx, 18), Ui.dp(ctx, 18), Gravity.CENTER));
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(Ui.dp(ctx, 38), Ui.dp(ctx, 38));
        chipLp.rightMargin = Ui.dp(ctx, 14);
        row.addView(chip, chipLp);

        row.addView(Ui.text(ctx, label, 15, Ui.TEXT, true), new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(v -> {
            dismissAnimated();
            action.run();
        });
        rows.addView(row, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return this;
    }

    /** Thin separator between groups of actions. */
    ContextMenuDialog addDivider() {
        View line = new View(ctx);
        line.setBackgroundColor(Ui.HAIRLINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(ctx, 1)));
        lp.setMargins(Ui.dp(ctx, 10), Ui.dp(ctx, 6), Ui.dp(ctx, 10), Ui.dp(ctx, 6));
        rows.addView(line, lp);
        return this;
    }

    // ─── HEADER ───────────────────────────────────────────────────────────────

    private LinearLayout buildHeaderRow(String title, String subtitle, int chipIcon,
                                        ImageTools.Request thumbnail) {
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(ctx, 18), Ui.dp(ctx, 16), Ui.dp(ctx, 18), Ui.dp(ctx, 14));

        int size = Ui.dp(ctx, 52);
        LinearLayout.LayoutParams leadLp = new LinearLayout.LayoutParams(size, size);
        leadLp.rightMargin = Ui.dp(ctx, 14);
        header.addView(thumbnail != null ? buildThumbnail(thumbnail, size) : buildChip(chipIcon), leadLp);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView titleView = Ui.text(ctx, title, 16, Ui.TEXT, true);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(titleView);

        TextView subtitleView = Ui.text(ctx, subtitle, 12, Ui.TEXT_FAINT, false);
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.topMargin = Ui.dp(ctx, 4);
        col.addView(subtitleView, sp);

        header.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return header;
    }

    private View buildChip(int iconRes) {
        FrameLayout chip = new FrameLayout(ctx);
        chip.setBackground(Ui.accentGradient(Ui.dp(ctx, 16)));
        chip.addView(Ui.icon(ctx, iconRes, Ui.NAVY_DEEP, 24),
            new FrameLayout.LayoutParams(Ui.dp(ctx, 24), Ui.dp(ctx, 24), Gravity.CENTER));
        return chip;
    }

    private View buildThumbnail(ImageTools.Request request, int sizePx) {
        final float radius = Ui.dp(ctx, 16);
        ImageView thumb = new ImageView(ctx);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackground(Ui.rounded(Ui.CARD_FILL, radius, Math.max(1, Ui.dp(ctx, 1)), Ui.CARD_STROKE));
        thumb.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        thumb.setClipToOutline(true);

        ImageTools.loadThumbnail(activity, request, sizePx * 2, bitmap -> {
            if (bitmap != null && dialog.isShowing()) thumb.setImageBitmap(bitmap);
        });
        return thumb;
    }
}
