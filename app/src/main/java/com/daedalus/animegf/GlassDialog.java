package com.daedalus.animegf;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Shared shell for the app's modal windows: a full-screen translucent window that dims and
 * (on Android 12+) blurs the page behind it, with one centred, rounded, translucent-navy
 * "glass" panel. Handles system-bar / keyboard insets, enter/exit animation, outside-tap
 * and back-button dismissal.
 */
abstract class GlassDialog {

    protected final Activity activity;
    protected final Dialog dialog;
    protected final FrameLayout root;
    protected final GlassPanel panel;
    protected final Context ctx;

    private boolean closing;

    protected GlassDialog(Activity activity, int maxWidthDp, int maxHeightDp, boolean fillHeight) {
        this.activity = activity;

        dialog = new Dialog(activity, R.style.GlassDialog) {
            @Override
            public void onBackPressed() {
                GlassDialog.this.onBackRequested();
            }
        };
        ctx = dialog.getContext();

        root = new FrameLayout(ctx);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        root.setOnClickListener(v -> onOutsideTap());

        panel = new GlassPanel(ctx,
            Ui.dp(ctx, maxWidthDp),
            maxHeightDp > 0 ? Ui.dp(ctx, maxHeightDp) : 0,
            fillHeight);
        panel.setBackground(Ui.glassPanel(ctx, 26));
        panel.setClickable(true); // swallow taps so they don't hit the scrim
        panel.setElevation(Ui.dp(ctx, 18));
        final float radius = Ui.dp(ctx, 26);
        panel.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        panel.setClipToOutline(true);

        root.addView(panel, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.CENTER));

        dialog.setContentView(root);
        configureWindow();
    }

    // ─── WINDOW ───────────────────────────────────────────────────────────────

    private void configureWindow() {
        Window w = dialog.getWindow();
        if (w == null) return;

        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setDimAmount(0.5f);

        // Edge-to-edge like the main activity; we apply bar + keyboard insets ourselves.
        WindowCompat.setDecorFitsSystemWindows(w, false);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);

        // Real background blur (Android 12+). Older devices just get the dim.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.setBlurBehindRadius(Ui.dp(ctx, 20));
            w.setAttributes(lp);
        }

        final int margin = Ui.dp(ctx, 16);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(
                bars.left + margin,
                bars.top + margin,
                bars.right + margin,
                Math.max(bars.bottom, ime.bottom) + margin);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    // ─── SHOW / DISMISS ───────────────────────────────────────────────────────

    void show() {
        panel.setAlpha(0f);
        panel.setScaleX(0.94f);
        panel.setScaleY(0.94f);
        panel.setTranslationY(Ui.dp(ctx, 14));
        dialog.show();
        ViewCompat.requestApplyInsets(root);
        panel.animate()
            .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(230)
            .setInterpolator(new DecelerateInterpolator(1.6f))
            .start();
    }

    protected void dismissAnimated() {
        if (closing) return;
        closing = true;
        hideKeyboard();
        final Runnable finish = () -> {
            try {
                if (dialog.isShowing()) dialog.dismiss();
            } catch (Exception ignored) { }
        };
        panel.animate()
            .alpha(0f).scaleX(0.96f).scaleY(0.96f).translationY(Ui.dp(ctx, 10))
            .setDuration(150)
            .withEndAction(finish)
            .start();
        root.postDelayed(finish, 320); // safety net if the animation is cancelled
    }

    protected void onBackRequested() {
        dismissAnimated();
    }

    protected void onOutsideTap() {
        dismissAnimated();
    }

    protected void hideKeyboard() {
        InputMethodManager imm =
            (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
    }

    // ─── SHARED PIECES ────────────────────────────────────────────────────────

    /** Header row: gradient icon chip, title + optional subtitle, and an optional trailing button. */
    protected LinearLayout buildHeader(int chipIcon, TextView titleView, TextView subtitleView,
                                       ImageView trailing) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 18), Ui.dp(ctx, 14), Ui.dp(ctx, 12));

        FrameLayout chip = new FrameLayout(ctx);
        chip.setBackground(Ui.accentGradient(Ui.dp(ctx, 12)));
        ImageView chipIv = Ui.icon(ctx, chipIcon, Ui.NAVY_DEEP, 20);
        chip.addView(chipIv, new FrameLayout.LayoutParams(Ui.dp(ctx, 20), Ui.dp(ctx, 20), Gravity.CENTER));
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(Ui.dp(ctx, 38), Ui.dp(ctx, 38));
        chipLp.rightMargin = Ui.dp(ctx, 12);
        row.addView(chip, chipLp);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(titleView);
        if (subtitleView != null) {
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            sp.topMargin = Ui.dp(ctx, 3);
            col.addView(subtitleView, sp);
        }
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (trailing != null) {
            row.addView(trailing, new LinearLayout.LayoutParams(Ui.dp(ctx, 38), Ui.dp(ctx, 38)));
        }
        return row;
    }

    protected TextView title(CharSequence s) {
        return Ui.text(ctx, s, 18, Ui.TEXT, true);
    }

    protected TextView subtitle(CharSequence s) {
        return Ui.text(ctx, s, 12.5f, Ui.TEXT_DIM, false);
    }
}
