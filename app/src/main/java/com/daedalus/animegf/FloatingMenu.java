package com.daedalus.animegf;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * Draggable round floating button that expands into a small icon-only radial menu:
 * Reload · Snippets · Desktop mode.
 *
 * The items fan out on an arc around the button, in whichever direction keeps all of them
 * on screen. Long-press an item for its name. The button's position is persisted.
 * All views live directly in the host FrameLayout, below {@code insertIndex}'s view
 * (the splash), so the splash covers them while it is showing.
 */
final class FloatingMenu {

    interface Listener {
        void onReload();
        void onSnippets();
        void onDesktop();
    }

    private static final String PREFS_NAME = "animegf_prefs";
    private static final String PREF_FAB_X = "fab_x_v2";
    private static final String PREF_FAB_Y = "fab_y_v2";

    private static final int ITEM_RELOAD   = 0;
    private static final int ITEM_SNIPPETS = 1;
    private static final int ITEM_DESKTOP  = 2;
    private static final int ITEM_COUNT    = 3;

    private static final int[] ITEM_ICONS  = {R.drawable.ic_refresh, R.drawable.ic_snippets, R.drawable.ic_desktop};
    private static final int[] ITEM_COLORS = {Ui.BLUE, Ui.GREEN, Ui.CYAN};
    private static final String[] ITEM_DESCRIPTIONS = {"Reload page", "Snippets", "Desktop mode"};

    private final Activity activity;
    private final FrameLayout host;
    private final Listener listener;

    private final int mainSize;
    private final int miniSize;
    private final int itemGap;
    private final int dragSlop;
    private final int badgeSize;

    private final View scrim;
    private final ImageView mainButton;
    private final View badge;
    private final ImageView[] minis = new ImageView[ITEM_COUNT];
    // final (expanded) top-left position of each item, and the main button's top-left
    // (where items start from / return to). X/Y are absolute, so animate them directly.
    private final float[] itemX = new float[ITEM_COUNT];
    private final float[] itemY = new float[ITEM_COUNT];
    private float originX, originY;

    private int insetL, insetT, insetR, insetB;

    private boolean open;
    private boolean positioned;
    private boolean dragging;
    private float downRawX, downRawY, downX, downY;
    private ObjectAnimator rotator;

    FloatingMenu(Activity activity, FrameLayout host, int insertIndex, Listener listener) {
        this.activity = activity;
        this.host = host;
        this.listener = listener;

        mainSize  = Ui.dp(activity, 56);
        miniSize  = Ui.dp(activity, 48);
        itemGap   = Ui.dp(activity, 12);
        dragSlop  = Ui.dp(activity, 8);
        badgeSize = Ui.dp(activity, 14);

        int idx = insertIndex;

        // click-away layer behind the open menu
        scrim = new View(activity);
        scrim.setBackgroundColor(0x66050A14);
        scrim.setVisibility(View.GONE);
        scrim.setOnClickListener(v -> collapse(true));
        host.addView(scrim, idx++, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // mini buttons (icon only)
        for (int i = 0; i < ITEM_COUNT; i++) {
            final int item = i;
            ImageView mini = new ImageView(activity);
            minis[i] = mini;
            mini.setContentDescription(ITEM_DESCRIPTIONS[i]);
            mini.setTooltipText(ITEM_DESCRIPTIONS[i]);
            mini.setScaleType(ImageView.ScaleType.FIT_CENTER);
            mini.setElevation(Ui.dp(activity, 6));
            mini.setVisibility(View.GONE);
            mini.setOnClickListener(v -> onItemClicked(item));
            styleMini(i, false);
            host.addView(mini, idx++, new FrameLayout.LayoutParams(miniSize, miniSize));
        }

        // main button
        mainButton = new ImageView(activity);
        mainButton.setContentDescription("Menu");
        mainButton.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = Ui.dp(activity, 16);
        mainButton.setPadding(pad, pad, pad, pad);
        mainButton.setElevation(Ui.dp(activity, 8));
        applyMainStyle(false);
        mainButton.setOnTouchListener(this::onMainTouch);
        host.addView(mainButton, idx++, new FrameLayout.LayoutParams(mainSize, mainSize));

        // "desktop mode is on" badge on the main button
        badge = new View(activity);
        badge.setBackground(Ui.circle(Ui.GREEN, Ui.dp(activity, 2), 0xFF0B1220));
        badge.setElevation(Ui.dp(activity, 9));
        badge.setVisibility(View.GONE);
        host.addView(badge, idx, new FrameLayout.LayoutParams(badgeSize, badgeSize));

        host.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l <= 0 || b - t <= 0) return;
            if (!positioned) {
                restorePosition();
                return;
            }
            // Layout passes also happen when menu items change visibility; only a real
            // size change (rotation, split-screen) should re-clamp and close the menu.
            boolean sizeChanged = (r - l) != (or - ol) || (b - t) != (ob - ot);
            if (sizeChanged) {
                if (open) collapse(false);
                setMainPosition(mainButton.getX(), mainButton.getY());
            }
        });
        host.post(() -> {
            if (!positioned && host.getWidth() > 0 && host.getHeight() > 0) restorePosition();
        });
    }

    // ─── PUBLIC API ───────────────────────────────────────────────────────────

    boolean isOpen() {
        return open;
    }

    /** Keep the button inside the visible area (outside status/navigation bars). */
    void setSafeInsets(int left, int top, int right, int bottom) {
        insetL = left;
        insetT = top;
        insetR = right;
        insetB = bottom;
        if (positioned) setMainPosition(mainButton.getX(), mainButton.getY());
    }

    void setDesktopState(boolean active, String description) {
        minis[ITEM_DESKTOP].setContentDescription(description);
        minis[ITEM_DESKTOP].setTooltipText(description);
        styleMini(ITEM_DESKTOP, active);
        badge.setVisibility(active ? View.VISIBLE : View.GONE);
        positionBadge();
    }

    void savePosition() {
        if (!positioned) return;
        activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putFloat(PREF_FAB_X, mainButton.getX())
            .putFloat(PREF_FAB_Y, mainButton.getY())
            .apply();
    }

    void collapse(boolean animate) {
        if (!open) return;
        open = false;
        animateMainIcon(false);
        applyMainStyle(false);

        scrim.animate().cancel();
        if (animate) {
            scrim.animate().alpha(0f).setDuration(150).withEndAction(() -> {
                if (!open) scrim.setVisibility(View.GONE);
            }).start();
        } else {
            scrim.setAlpha(0f);
            scrim.setVisibility(View.GONE);
        }

        for (int i = 0; i < ITEM_COUNT; i++) {
            final ImageView m = minis[i];
            m.animate().cancel();
            if (!animate) {
                m.setVisibility(View.GONE);
                continue;
            }
            m.animate()
                .alpha(0f).scaleX(0.4f).scaleY(0.4f)
                .x(originX).y(originY)
                .setStartDelay((ITEM_COUNT - 1 - i) * 25L)
                .setDuration(150)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> { if (!open) m.setVisibility(View.GONE); })
                .start();
        }
    }

    // ─── EXPAND / COLLAPSE ────────────────────────────────────────────────────

    private void toggle() {
        if (open) collapse(true);
        else expand();
    }

    private void expand() {
        if (open) return;
        open = true;
        layoutMinis();
        animateMainIcon(true);
        applyMainStyle(true);

        scrim.animate().cancel();
        scrim.setAlpha(0f);
        scrim.setVisibility(View.VISIBLE);
        scrim.animate().alpha(1f).setDuration(180).setStartDelay(0).start();

        for (int i = 0; i < ITEM_COUNT; i++) {
            ImageView m = minis[i];
            m.animate().cancel();
            m.setVisibility(View.VISIBLE);
            m.setAlpha(0f);
            m.setScaleX(0.4f);
            m.setScaleY(0.4f);
            m.setX(originX);
            m.setY(originY);
            m.animate()
                .alpha(1f).scaleX(1f).scaleY(1f).x(itemX[i]).y(itemY[i])
                .setStartDelay(i * 45L)
                .setDuration(240)
                .setInterpolator(new OvershootInterpolator(1.1f))
                .start();
        }
    }

    /**
     * Place the items on an arc around the main button. Of 72 candidate directions, pick the
     * one that keeps every item fully on screen and points closest to the screen centre.
     */
    private void layoutMinis() {
        final float mx = mainButton.getX();
        final float my = mainButton.getY();
        final float cx = mx + mainSize / 2f;
        final float cy = my + mainSize / 2f;
        originX = cx - miniSize / 2f;
        originY = cy - miniSize / 2f;

        final float radius = Ui.dp(activity, 84);
        // angle between neighbours so that items are one item-size + gap apart
        final double stepRad = 2 * Math.asin(Math.min(1.0, (miniSize + itemGap) / (2.0 * radius)));
        final double stepDeg = Math.toDegrees(stepRad);

        final float minX = insetL, maxX = host.getWidth() - insetR - miniSize;
        final float minY = insetT, maxY = host.getHeight() - insetB - miniSize;

        double toCenter = Math.toDegrees(Math.atan2(
            host.getHeight() / 2.0 - cy, host.getWidth() / 2.0 - cx));

        double bestBase = toCenter;
        double bestDist = Double.MAX_VALUE;
        boolean found = false;
        for (int k = 0; k < 72; k++) {
            double base = k * 5.0;
            boolean fits = true;
            for (int i = 0; i < ITEM_COUNT && fits; i++) {
                double a = Math.toRadians(base + (i - (ITEM_COUNT - 1) / 2.0) * stepDeg);
                float x = cx + (float) (radius * Math.cos(a)) - miniSize / 2f;
                float y = cy + (float) (radius * Math.sin(a)) - miniSize / 2f;
                fits = x >= minX && x <= maxX && y >= minY && y <= maxY;
            }
            if (!fits) continue;
            double d = Math.abs(((base - toCenter + 540.0) % 360.0) - 180.0);
            if (!found || d < bestDist) {
                found = true;
                bestDist = d;
                bestBase = base;
            }
        }
        if (!found) bestBase = toCenter; // tiny window: positions are clamped below

        for (int i = 0; i < ITEM_COUNT; i++) {
            double a = Math.toRadians(bestBase + (i - (ITEM_COUNT - 1) / 2.0) * stepDeg);
            float x = cx + (float) (radius * Math.cos(a)) - miniSize / 2f;
            float y = cy + (float) (radius * Math.sin(a)) - miniSize / 2f;
            x = Math.max(minX, Math.min(x, Math.max(minX, maxX)));
            y = Math.max(minY, Math.min(y, Math.max(minY, maxY)));
            itemX[i] = x;
            itemY[i] = y;
        }
    }

    private void onItemClicked(int item) {
        collapse(true);
        switch (item) {
            case ITEM_RELOAD:   listener.onReload();   break;
            case ITEM_SNIPPETS: listener.onSnippets(); break;
            case ITEM_DESKTOP:  listener.onDesktop();  break;
            default: break;
        }
    }

    // ─── MAIN BUTTON: TOUCH / DRAG ────────────────────────────────────────────

    private boolean onMainTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                downX = v.getX();
                downY = v.getY();
                dragging = false;
                v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start();
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = e.getRawX() - downRawX;
                float dy = e.getRawY() - downRawY;
                if (!dragging && (Math.abs(dx) > dragSlop || Math.abs(dy) > dragSlop)) {
                    dragging = true;
                    if (open) collapse(true);
                }
                if (dragging) setMainPosition(downX + dx, downY + dy);
                return true;
            }

            case MotionEvent.ACTION_UP:
                v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                if (dragging) savePosition();
                else toggle();
                dragging = false;
                return true;

            case MotionEvent.ACTION_CANCEL:
                v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                if (dragging) savePosition();
                dragging = false;
                return true;

            default:
                return false;
        }
    }

    // ─── POSITION ─────────────────────────────────────────────────────────────

    private float clampX(float x) {
        float min = insetL;
        float max = Math.max(min, host.getWidth() - insetR - mainSize);
        return Math.max(min, Math.min(x, max));
    }

    private float clampY(float y) {
        float min = insetT;
        float max = Math.max(min, host.getHeight() - insetB - mainSize);
        return Math.max(min, Math.min(y, max));
    }

    private void setMainPosition(float x, float y) {
        mainButton.setX(clampX(x));
        mainButton.setY(clampY(y));
        positionBadge();
    }

    private void positionBadge() {
        badge.setX(mainButton.getX() + mainSize - badgeSize * 0.9f);
        badge.setY(mainButton.getY() - badgeSize * 0.1f);
    }

    private void restorePosition() {
        SharedPreferences p = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        float x = p.getFloat(PREF_FAB_X, -1f);
        float y = p.getFloat(PREF_FAB_Y, -1f);
        if (x < 0f || y < 0f) {
            // default: bottom-right, lifted above a typical page footer / input bar
            x = host.getWidth() - insetR - mainSize - Ui.dp(activity, 16);
            y = host.getHeight() - insetB - mainSize - Ui.dp(activity, 96);
        }
        positioned = true;
        setMainPosition(x, y);
    }

    // ─── STYLING ──────────────────────────────────────────────────────────────

    private void applyMainStyle(boolean opened) {
        if (opened) {
            mainButton.setBackground(Ui.accentCircle());
            mainButton.setImageDrawable(Ui.tinted(activity, R.drawable.ic_close, Ui.NAVY_DEEP));
        } else {
            mainButton.setBackground(Ui.circle(Ui.SURFACE, Ui.dp(activity, 2), Ui.BLUE));
            mainButton.setImageDrawable(Ui.tinted(activity, R.drawable.ic_menu, Ui.TEXT));
        }
    }

    private void animateMainIcon(boolean toOpen) {
        if (rotator != null) rotator.cancel();
        mainButton.setRotation(toOpen ? -90f : 90f);
        rotator = ObjectAnimator.ofFloat(mainButton, View.ROTATION, toOpen ? -90f : 90f, 0f);
        rotator.setDuration(220);
        rotator.setInterpolator(new DecelerateInterpolator());
        rotator.start();
    }

    private void styleMini(int i, boolean active) {
        ImageView m = minis[i];
        int pad = Ui.dp(activity, 12);
        m.setPadding(pad, pad, pad, pad);
        if (active) {
            m.setBackground(Ui.accentCircle());
            m.setImageDrawable(Ui.tinted(activity, ITEM_ICONS[i], Ui.NAVY_DEEP));
        } else {
            m.setBackground(Ui.circle(Ui.SURFACE, Ui.dp(activity, 1), 0x55FFFFFF));
            m.setImageDrawable(Ui.tinted(activity, ITEM_ICONS[i], ITEM_COLORS[i]));
        }
    }
}
