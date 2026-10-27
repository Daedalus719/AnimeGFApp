package com.daedalus.animegf;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Palette + small view/drawable factories shared by the floating menu and the
 * glassmorphism dialogs. Everything is built in code (like the rest of the app).
 */
final class Ui {

    private Ui() {}

    // ─── PALETTE: deep navy surfaces, bright blue/green accents, white text ───
    static final int NAVY_DEEP    = 0xFF0A1020;
    static final int PANEL_TOP    = 0xF0192A4B;   // translucent navy (top)
    static final int PANEL_BOTTOM = 0xF50A1428;   // translucent navy (bottom)
    static final int SURFACE      = 0xEB101B33;   // floating buttons / pills
    static final int BLUE         = 0xFF4C9AFF;
    static final int GREEN        = 0xFF34D399;
    static final int CYAN         = 0xFF38BDF8;
    static final int TEXT         = 0xFFFFFFFF;
    static final int TEXT_DIM     = 0xFFB4C0D8;
    static final int TEXT_FAINT   = 0xFF7F8CA8;
    static final int DANGER       = 0xFFFF6B7A;
    static final int HAIRLINE     = 0x26FFFFFF;
    static final int CARD_FILL    = 0x14FFFFFF;
    static final int CARD_STROKE  = 0x29FFFFFF;

    static int dp(Context c, float dp) {
        return Math.round(dp * c.getResources().getDisplayMetrics().density);
    }

    // ─── DRAWABLES ────────────────────────────────────────────────────────────

    static GradientDrawable rounded(int fill, float radiusPx, int strokePx, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(radiusPx);
        if (strokePx > 0) g.setStroke(strokePx, strokeColor);
        return g;
    }

    static GradientDrawable circle(int fill, int strokePx, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fill);
        if (strokePx > 0) g.setStroke(strokePx, strokeColor);
        return g;
    }

    static GradientDrawable accentGradient(float radiusPx) {
        GradientDrawable g = new GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT, new int[]{BLUE, GREEN});
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static GradientDrawable accentCircle() {
        GradientDrawable g = new GradientDrawable(
            GradientDrawable.Orientation.TL_BR, new int[]{BLUE, GREEN});
        g.setShape(GradientDrawable.OVAL);
        return g;
    }

    /** The big translucent navy panel with soft blue/green glows in opposite corners. */
    static Drawable glassPanel(Context c, float radiusDp) {
        float r = dp(c, radiusDp);
        GradientDrawable base = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, new int[]{PANEL_TOP, PANEL_BOTTOM});
        base.setShape(GradientDrawable.RECTANGLE);
        base.setCornerRadius(r);
        base.setStroke(Math.max(1, dp(c, 1)), 0x3DFFFFFF);
        return new LayerDrawable(new Drawable[]{
            base,
            radialGlow(0x3A4C9AFF, dp(c, 300), 0.05f, 0.0f, r),
            radialGlow(0x2434D399, dp(c, 260), 0.95f, 1.0f, r)
        });
    }

    private static GradientDrawable radialGlow(int color, float radiusPx,
                                               float cx, float cy, float cornerPx) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(radiusPx);
        g.setGradientCenter(cx, cy);
        g.setColors(new int[]{color, color & 0x00FFFFFF});
        g.setCornerRadius(cornerPx);
        return g;
    }

    /** Wrap {@code content} in a ripple that is clipped to a rounded rect. */
    static Drawable ripple(Drawable content, float radiusPx, int rippleColor) {
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(radiusPx);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    static Drawable tinted(Context c, int res, int color) {
        Drawable d = c.getDrawable(res).mutate();
        d.setTint(color);
        return d;
    }

    // ─── VIEWS ────────────────────────────────────────────────────────────────

    static TextView text(Context c, CharSequence s, float sp, int color, boolean medium) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        if (medium) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    static ImageView icon(Context c, int res, int tint, int sizeDp) {
        ImageView v = new ImageView(c);
        v.setImageDrawable(tinted(c, res, tint));
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return v;
    }

    /** Round, clickable icon-only button (header close / back). */
    static ImageView iconButton(Context c, int res, int tint, int sizeDp, String description) {
        ImageView v = new ImageView(c);
        int pad = dp(c, 9);
        v.setPadding(pad, pad, pad, pad);
        v.setImageDrawable(tinted(c, res, tint));
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setBackground(ripple(circle(0x14FFFFFF, 0, 0), dp(c, sizeDp), 0x33FFFFFF));
        v.setContentDescription(description);
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    /**
     * Small rounded "icon + label" button. Child 0 is the ImageView, child 1 the TextView
     * (see {@link #setPill}).
     */
    static LinearLayout pill(Context c, int iconRes, CharSequence label, int fg, int fill, int stroke) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 12), dp(c, 8), dp(c, 14), dp(c, 8));
        float r = dp(c, 12);
        b.setBackground(ripple(rounded(fill, r, dp(c, 1), stroke), r, 0x33FFFFFF));
        b.setClickable(true);
        b.setFocusable(true);

        ImageView iv = icon(c, iconRes, fg, 16);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(c, 16), dp(c, 16));
        ip.rightMargin = dp(c, 6);
        iv.setLayoutParams(ip);
        b.addView(iv);

        TextView tv = text(c, label, 13, fg, true);
        b.addView(tv);
        return b;
    }

    static void setPill(Context c, LinearLayout pill, int iconRes, CharSequence label, int fg) {
        ((ImageView) pill.getChildAt(0)).setImageDrawable(tinted(c, iconRes, fg));
        TextView tv = (TextView) pill.getChildAt(1);
        tv.setText(label);
        tv.setTextColor(fg);
    }

    /** Full-width blue→green call-to-action button. */
    static LinearLayout gradientButton(Context c, int iconRes, CharSequence label) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER);
        float r = dp(c, 14);
        b.setBackground(ripple(accentGradient(r), r, 0x40FFFFFF));
        b.setClickable(true);
        b.setFocusable(true);
        if (iconRes != 0) {
            ImageView iv = icon(c, iconRes, NAVY_DEEP, 18);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(c, 18), dp(c, 18));
            ip.rightMargin = dp(c, 8);
            iv.setLayoutParams(ip);
            b.addView(iv);
        }
        TextView tv = text(c, label, 15, NAVY_DEEP, true);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        b.addView(tv);
        return b;
    }

    /** Neutral glass button used for "Cancel" style actions. */
    static LinearLayout ghostButton(Context c, CharSequence label) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER);
        float r = dp(c, 14);
        b.setBackground(ripple(rounded(CARD_FILL, r, dp(c, 1), CARD_STROKE), r, 0x33FFFFFF));
        b.setClickable(true);
        b.setFocusable(true);
        b.addView(text(c, label, 15, TEXT, true));
        return b;
    }
}
