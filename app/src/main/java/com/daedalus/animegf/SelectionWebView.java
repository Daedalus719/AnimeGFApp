package com.daedalus.animegf;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebView;

/**
 * WebView that never shows the system text-selection toolbar (Select all / Copy / Cut /
 * Paste / Web search / Translate). Native selection and its drag handles keep working; the
 * app draws its own toolbar instead (see {@link SelectionToolbar}).
 *
 * It also reports when a finger goes down / comes up, so that toolbar can stay out of the
 * way while the user is still touching the page.
 */
public final class SelectionWebView extends WebView {

    interface TouchStateListener {
        void onTouchStateChanged(boolean touching);
    }

    private TouchStateListener touchStateListener;

    public SelectionWebView(Context context) {
        super(context);
    }

    public SelectionWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setTouchStateListener(TouchStateListener listener) {
        this.touchStateListener = listener;
    }

    // ─── SYSTEM SELECTION TOOLBAR ─────────────────────────────────────────────

    @Override
    public ActionMode startActionMode(ActionMode.Callback callback, int type) {
        return super.startActionMode(new HiddenCallback(callback), type);
    }

    /**
     * Lets the WebView run its normal action-mode lifecycle (so the selection behaves as it
     * always did) but guarantees nothing is drawn: the menu is emptied and the toolbar is
     * anchored far off screen, which the framework treats as "selection scrolled out of view".
     */
    private static final class HiddenCallback extends ActionMode.Callback2 {

        private static final int OFF_SCREEN = -100_000;

        private final ActionMode.Callback wrapped;

        HiddenCallback(ActionMode.Callback wrapped) {
            this.wrapped = wrapped;
        }

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            boolean created = wrapped.onCreateActionMode(mode, menu);
            menu.clear();
            return created;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            boolean prepared = wrapped.onPrepareActionMode(mode, menu);
            menu.clear();
            return prepared;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            return wrapped.onActionItemClicked(mode, item);
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            wrapped.onDestroyActionMode(mode);
        }

        @Override
        public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
            outRect.set(OFF_SCREEN, OFF_SCREEN, OFF_SCREEN + 1, OFF_SCREEN + 1);
        }
    }

    // ─── TOUCH STATE ──────────────────────────────────────────────────────────

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) notifyTouchState(true);
        boolean handled = super.dispatchTouchEvent(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            notifyTouchState(false);
        }
        return handled;
    }

    private void notifyTouchState(boolean touching) {
        if (touchStateListener != null) touchStateListener.onTouchStateChanged(touching);
    }
}
