package com.daedalus.animegf;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Lets the user pick how the site should see this device. Choices are staged and only
 * applied (with a page reload) when "Apply" is pressed.
 */
final class DesktopDialog extends GlassDialog {

    interface Callback {
        void onApply(int mode, int width);
    }

    private static final int[]    MODES = {DesktopMode.OFF, DesktopMode.IDENTITY, DesktopMode.VIEWPORT};
    private static final String[] NAMES = {"Off", "Desktop identity", "Desktop view"};
    private static final String[] TAGS  = {null, "Experimental", "Recommended"};
    private static final String[] DESCS = {
        "Normal mobile layout.",
        "Keeps the mobile layout, but tells the site you're on a desktop browser (user agent, "
            + "hover/pointer, window width). Only helps when the site decides this in JavaScript.",
        "Renders the real wide desktop layout so everything the site hides on phones is "
            + "reachable. Pinch to zoom; the zoom is kept steady."
    };
    private static final String[] WIDTH_NAMES = {"Tablet", "Laptop", "Desktop"};

    private final DesktopMode current;
    private final Callback callback;

    private int stagedMode;
    private int stagedWidth;

    private final LinearLayout[] optionCards = new LinearLayout[MODES.length];
    private final View[]         optionDots  = new View[MODES.length];
    private final LinearLayout[] widthChips  = new LinearLayout[DesktopMode.WIDTHS.length];
    private LinearLayout widthSection;

    DesktopDialog(Activity activity, DesktopMode current, Callback callback) {
        super(activity, 420, 640, false);
        this.current = current;
        this.callback = callback;
        this.stagedMode = current.getMode();
        this.stagedWidth = current.getWidth();
        build();
        refresh();
    }

    private void build() {
        ImageView close = Ui.iconButton(ctx, R.drawable.ic_close, Ui.TEXT_DIM, 38, "Close");
        close.setOnClickListener(v -> dismissAnimated());
        panel.addView(buildHeader(R.drawable.ic_desktop, title("Desktop mode"),
            subtitle("Choose how anime.gf sees this device"), close));

        ScrollView scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 4), Ui.dp(ctx, 16), Ui.dp(ctx, 16));
        scroll.addView(body, new ScrollView.LayoutParams(
            ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < MODES.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Ui.dp(ctx, 10);
            body.addView(buildOption(i), lp);
        }

        // width chooser (hidden while Off)
        widthSection = new LinearLayout(ctx);
        widthSection.setOrientation(LinearLayout.VERTICAL);
        TextView wl = Ui.text(ctx, "VIRTUAL SCREEN WIDTH", 11, Ui.TEXT_FAINT, true);
        wl.setLetterSpacing(0.1f);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = Ui.dp(ctx, 6);
        wlp.bottomMargin = Ui.dp(ctx, 8);
        widthSection.addView(wl, wlp);

        LinearLayout chips = new LinearLayout(ctx);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < DesktopMode.WIDTHS.length; i++) {
            final int width = DesktopMode.WIDTHS[i];
            LinearLayout chip = new LinearLayout(ctx);
            chip.setOrientation(LinearLayout.VERTICAL);
            chip.setGravity(Gravity.CENTER);
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setPadding(0, Ui.dp(ctx, 10), 0, Ui.dp(ctx, 10));
            chip.addView(Ui.text(ctx, String.valueOf(width), 15, Ui.TEXT, true));
            TextView sub = Ui.text(ctx, WIDTH_NAMES[i], 11.5f, Ui.TEXT_DIM, false);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            sp.topMargin = Ui.dp(ctx, 3);
            chip.addView(sub, sp);
            chip.setOnClickListener(v -> {
                stagedWidth = width;
                refresh();
            });
            widthChips[i] = chip;
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) cp.leftMargin = Ui.dp(ctx, 8);
            chips.addView(chip, cp);
        }
        widthSection.addView(chips);
        TextView hint = Ui.text(ctx,
            "Not sure? 1024 matches most “large screen” layouts. Go wider if something still "
                + "stays hidden.", 12, Ui.TEXT_FAINT, false);
        hint.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hp.topMargin = Ui.dp(ctx, 8);
        widthSection.addView(hint, hp);
        body.addView(widthSection);

        // actions
        LinearLayout actions = new LinearLayout(ctx);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout cancel = Ui.ghostButton(ctx, "Cancel");
        cancel.setOnClickListener(v -> dismissAnimated());
        LinearLayout apply = Ui.gradientButton(ctx, R.drawable.ic_refresh, "Apply & reload");
        apply.setOnClickListener(v -> apply());
        actions.addView(cancel, new LinearLayout.LayoutParams(0, Ui.dp(ctx, 50), 1f));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(0, Ui.dp(ctx, 50), 1.6f);
        ap.leftMargin = Ui.dp(ctx, 10);
        actions.addView(apply, ap);
        LinearLayout.LayoutParams actp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        actp.topMargin = Ui.dp(ctx, 18);
        body.addView(actions, actp);
    }

    private View buildOption(final int index) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 14), Ui.dp(ctx, 14), Ui.dp(ctx, 14));
        card.setClickable(true);
        card.setFocusable(true);

        View dot = new View(ctx);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(Ui.dp(ctx, 20), Ui.dp(ctx, 20));
        dp.topMargin = Ui.dp(ctx, 1);
        dp.rightMargin = Ui.dp(ctx, 12);
        card.addView(dot, dp);
        optionDots[index] = dot;

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout titleRow = new LinearLayout(ctx);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(Ui.text(ctx, NAMES[index], 15, Ui.TEXT, true));
        if (TAGS[index] != null) {
            boolean recommended = MODES[index] == DesktopMode.VIEWPORT;
            int c = recommended ? Ui.GREEN : Ui.CYAN;
            TextView tag = Ui.text(ctx, TAGS[index], 10.5f, c, true);
            float r = Ui.dp(ctx, 8);
            tag.setBackground(Ui.rounded(c & 0x00FFFFFF | 0x26000000, r, 0, 0));
            tag.setPadding(Ui.dp(ctx, 7), Ui.dp(ctx, 3), Ui.dp(ctx, 7), Ui.dp(ctx, 3));
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            tp.leftMargin = Ui.dp(ctx, 8);
            titleRow.addView(tag, tp);
        }
        col.addView(titleRow);

        TextView desc = Ui.text(ctx, DESCS[index], 12.5f, Ui.TEXT_DIM, false);
        desc.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = Ui.dp(ctx, 4);
        col.addView(desc, dlp);

        card.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card.setOnClickListener(v -> {
            stagedMode = MODES[index];
            refresh();
        });
        optionCards[index] = card;
        return card;
    }

    /** Re-style everything from the staged selection. */
    private void refresh() {
        float r = Ui.dp(ctx, 16);
        for (int i = 0; i < MODES.length; i++) {
            boolean sel = MODES[i] == stagedMode;
            optionCards[i].setBackground(Ui.ripple(
                Ui.rounded(sel ? 0x1F4C9AFF : Ui.CARD_FILL, r, Ui.dp(ctx, sel ? 2 : 1),
                    sel ? Ui.BLUE : Ui.CARD_STROKE), r, 0x26FFFFFF));
            if (sel) {
                GradientDrawable d = Ui.circle(Ui.BLUE, 0, 0);
                optionDots[i].setBackground(new LayerDrawable(
                    new Drawable[]{
                        Ui.circle(0x00000000, Ui.dp(ctx, 2), Ui.BLUE),
                        new InsetDrawable(d, Ui.dp(ctx, 4))}));
            } else {
                optionDots[i].setBackground(Ui.circle(0x00000000, Ui.dp(ctx, 2), 0x66FFFFFF));
            }
        }

        widthSection.setVisibility(stagedMode == DesktopMode.OFF ? View.GONE : View.VISIBLE);
        float cr = Ui.dp(ctx, 14);
        for (int i = 0; i < widthChips.length; i++) {
            boolean sel = DesktopMode.WIDTHS[i] == stagedWidth;
            widthChips[i].setBackground(Ui.ripple(
                Ui.rounded(sel ? 0x264C9AFF : Ui.CARD_FILL, cr, Ui.dp(ctx, sel ? 2 : 1),
                    sel ? Ui.BLUE : Ui.CARD_STROKE), cr, 0x26FFFFFF));
        }
    }

    private void apply() {
        boolean changed = stagedMode != current.getMode()
            || (stagedMode != DesktopMode.OFF && stagedWidth != current.getWidth());
        dismissAnimated();
        if (changed) callback.onApply(stagedMode, stagedWidth);
    }
}
