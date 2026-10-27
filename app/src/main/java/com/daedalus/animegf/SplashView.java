package com.daedalus.animegf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateInterpolator;

/**
 * Full-screen launch splash: the app icon in the centre with a circular progress ring
 * around it. The ring is driven by the real page-load progress (see {@link #setProgress}),
 * with a gentle "trickle" so it never looks frozen before the first progress callback.
 *
 * Lifecycle: shown from the first frame → call {@link #finish(Runnable)} when the page is
 * ready → the ring completes, holds for a beat, then the whole view fades out and the
 * callback fires so the host can remove it.
 */
public final class SplashView extends View {

    /** The splash is never visible for less than this, so it can't flash on fast loads. */
    private static final long MIN_VISIBLE_MS = 1100L;
    private static final long HOLD_AFTER_FINISH_MS = 140L;
    private static final long FADE_IN_MS = 260L;
    private static final long EXIT_MS = 380L;

    private static final int BG = 0xFF0B1220; // == @color/splash_bg

    private final Paint glowPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tipPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint  = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint rimPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect    = new RectF();
    private final Matrix matrix    = new Matrix();

    private float cx, cy, iconR, ringR, strokePx, textY;
    private Bitmap iconBitmap;

    private float shown;          // what the ring currently displays (0..1)
    private float target;         // real progress reported by the WebView (0..1)
    private long  startTime;
    private long  lastFrame;
    private long  finishAllowedAt;
    private boolean finishRequested;
    private boolean exiting;
    private boolean done;
    private Runnable onDone;

    public SplashView(Context context) {
        super(context);
        init();
    }

    public SplashView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setClickable(true); // swallow touches while the splash is up
        setBackgroundColor(BG);

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setColor(0x24FFFFFF);

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setColor(0x33FFFFFF);

        tipPaint.setStyle(Paint.Style.FILL);

        textPaint.setColor(0xE6FFFFFF);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textPaint.setLetterSpacing(0.08f);
    }

    // ─── PUBLIC API ───────────────────────────────────────────────────────────

    /** Real load progress, 0..1. Safe to call from onProgressChanged. */
    public void setProgress(float progress) {
        float p = Math.max(0f, Math.min(1f, progress)) * 0.97f; // last 3% belongs to finish()
        if (p > target) target = p;
    }

    /** Page is ready: complete the ring, then fade out and invoke {@code callback}. */
    public void finish(Runnable callback) {
        if (finishRequested || done) return;
        finishRequested = true;
        onDone = callback;
        finishAllowedAt = SystemClock.uptimeMillis() + HOLD_AFTER_FINISH_MS;
        invalidate();
    }

    // ─── LAYOUT ───────────────────────────────────────────────────────────────

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startTime = SystemClock.uptimeMillis();
        lastFrame = 0L;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float d = getResources().getDisplayMetrics().density;
        float minSide = Math.min(w, h);

        cx = w / 2f;
        cy = h * 0.45f;
        iconR = Math.min(54f * d, minSide * 0.17f);
        ringR = iconR + 16f * d;
        strokePx = Math.max(3f * d, ringR * 0.055f);
        textY = cy + ringR + 54f * d;

        trackPaint.setStrokeWidth(strokePx);
        arcPaint.setStrokeWidth(strokePx);
        rimPaint.setStrokeWidth(1.5f * d);
        textPaint.setTextSize(22f * d);
        arcRect.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR);

        // ring gradient: blue → green → blue, so a completed ring has no visible seam
        SweepGradient sweep = new SweepGradient(cx, cy,
            new int[]{Ui.BLUE, Ui.GREEN, Ui.BLUE}, new float[]{0f, 0.5f, 1f});
        matrix.setRotate(-90f, cx, cy);
        sweep.setLocalMatrix(matrix);
        arcPaint.setShader(sweep);

        glowPaint.setShader(new RadialGradient(cx, cy, ringR * 2.8f,
            new int[]{0x404C9AFF, 0x00000000}, null, Shader.TileMode.CLAMP));

        buildIconBitmap((int) (iconR * 2f));
    }

    private void buildIconBitmap(int size) {
        if (size <= 0) return;
        try {
            Drawable icon = getContext().getDrawable(R.mipmap.ic_launcher_round);
            if (icon == null) return;
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            icon.setBounds(0, 0, size, size);
            icon.draw(c);
            iconBitmap = bmp;
            BitmapShader shader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setTranslate(cx - iconR, cy - iconR);
            shader.setLocalMatrix(m);
            iconPaint.setShader(shader);
        } catch (Throwable t) {
            iconBitmap = null; // splash still works, just without the picture
        }
    }

    // ─── DRAWING ──────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        final long now = SystemClock.uptimeMillis();
        step(now);

        final float appear = Math.min(1f, (now - startTime) / (float) FADE_IN_MS);

        // soft glow behind everything
        glowPaint.setAlpha((int) (255 * appear));
        canvas.drawCircle(cx, cy, ringR * 2.8f, glowPaint);

        // everything else fades in over the flat navy (which matches the window background,
        // so the system → in-app splash hand-over is invisible)
        float pad = 48f * getResources().getDisplayMetrics().density;
        int layer = canvas.saveLayerAlpha(cx - ringR - pad, cy - ringR - pad,
            cx + ringR + pad, textY + pad, (int) (255 * appear));

        // breathing icon
        float breathe = 1f + 0.012f * (float) Math.sin((now - startTime) / 520.0);
        canvas.save();
        canvas.scale(breathe, breathe, cx, cy);
        if (iconBitmap != null) {
            canvas.drawCircle(cx, cy, iconR, iconPaint);
        } else {
            tipPaint.setColor(Ui.BLUE);
            canvas.drawCircle(cx, cy, iconR, tipPaint);
        }
        canvas.restore();
        canvas.drawCircle(cx, cy, iconR + strokePx * 0.9f, rimPaint);

        // ring: track + progress arc
        canvas.drawCircle(cx, cy, ringR, trackPaint);
        if (shown > 0.003f) {
            float sweep = 360f * shown;
            canvas.drawArc(arcRect, -90f, sweep, false, arcPaint);
            if (shown < 0.999f) {
                double a = Math.toRadians(-90f + sweep);
                float tx = cx + ringR * (float) Math.cos(a);
                float ty = cy + ringR * (float) Math.sin(a);
                tipPaint.setColor(0x33FFFFFF);
                canvas.drawCircle(tx, ty, strokePx * 1.25f, tipPaint);
                tipPaint.setColor(Color.WHITE);
                canvas.drawCircle(tx, ty, strokePx * 0.55f, tipPaint);
            }
        }

        canvas.drawText("anime.gf", cx, textY, textPaint);
        canvas.restoreToCount(layer);

        if (!done) postInvalidateOnAnimation();
    }

    private void step(long now) {
        if (lastFrame == 0L) lastFrame = now;
        float dt = Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;

        float elapsed = (now - startTime) / 1000f;
        float trickle = 0.28f * (1f - (float) Math.exp(-elapsed / 1.1f));
        float goal = finishRequested ? 1f : Math.max(target, trickle);

        float speed = finishRequested ? 7f : 3.2f;
        shown += (goal - shown) * (1f - (float) Math.exp(-speed * dt));
        if (finishRequested && shown > 0.9985f) shown = 1f;

        if (finishRequested && !exiting && shown >= 0.995f
            && now - startTime >= MIN_VISIBLE_MS && now >= finishAllowedAt) {
            exiting = true;
            post(this::runExit); // never start animators from inside onDraw
        }
    }

    private void runExit() {
        animate()
            .alpha(0f)
            .scaleX(1.05f)
            .scaleY(1.05f)
            .setDuration(EXIT_MS)
            .setInterpolator(new AccelerateInterpolator(1.2f))
            .withEndAction(() -> {
                done = true;
                setVisibility(View.GONE);
                if (onDone != null) onDone.run();
            })
            .start();
    }
}
