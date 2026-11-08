package com.pocketvr.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;

/**
 * The start-up animation: a ring draws itself, the name comes up inside it, a light sweeps under it, and everything fades out.
 * It is a see-through window (the pointer goes straight through it) that the activity shows when the screen opens and hides again at {@link #TOTAL_MS}.
 */
public class NvSplash implements NvContent {
    public static final long TOTAL_MS = 3400;
    private final int W = 900, H = 520;
    private final Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    private final Canvas cv = new Canvas(bmp);
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long t0 = 0;
    private boolean done = false;
    private final RectF rr = new RectF();

    /** start again (when the window is shown) */
    public void restart() { t0 = 0; done = false; }

    @Override public Bitmap bitmap() { return bmp; }
    @Override public void markDirty() { }
    @Override public void update(boolean valid, float x, float y, boolean pinch) { }
    @Override public boolean handleRecent() { return false; }
    @Override public void setDragging(boolean d) { }
    @Override public boolean covers(float x, float y) { return false; }

    private static float ease(float x) { x = Math.max(0f, Math.min(1f, x)); return 1f - (1f - x) * (1f - x) * (1f - x); }
    private static float seg(float t, float a, float b) { return (t - a) / (b - a); }

    @Override public boolean takeDirty() {
        long now = System.currentTimeMillis();
        if (t0 == 0) t0 = now;
        long el = now - t0;
        if (el > TOTAL_MS) {
            if (done) return false;
            done = true;
            cv.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
            return true;
        }
        draw(el / 1000f);
        return true;
    }

    private void draw(float t) {
        cv.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
        float fadeOut = 1f - ease(seg(t, 2.7f, 3.3f));                        // everything fades at the end
        float cx = W / 2f, cy = H / 2f - 20, R = 118f;
        // soft light behind the ring
        float glow = ease(seg(t, 0f, 0.9f)) * fadeOut;
        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(cx, cy, R * 2.3f, Color.argb((int) (120 * glow), 90, 170, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, R * 2.3f, p);
        p.setShader(null);
        // the ring draws itself
        float sweep = 360f * ease(seg(t, 0.1f, 1.5f));
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(10f);
        rr.set(cx - R, cy - R, cx + R, cy + R);
        SweepGradient sg = new SweepGradient(cx, cy, new int[]{0xFF4DA3FF, 0xFF7FE3FF, 0xFFFFFFFF, 0xFF4DA3FF}, null);
        cv.save();
        cv.rotate(-90f + 40f * t, cx, cy);
        p.setShader(sg);
        p.setAlpha((int) (255 * fadeOut));
        cv.drawArc(rr, 0f, sweep, false, p);
        cv.restore();
        p.setShader(null);
        // the name
        float a = ease(seg(t, 0.9f, 1.7f)) * fadeOut;
        float sc = 0.86f + 0.14f * ease(seg(t, 0.9f, 1.9f));
        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextSize(64f * sc);
        p.setColor(Color.argb((int) (255 * a), 255, 255, 255));
        cv.drawText("PocketVR", cx, cy + 22f * sc, p);
        // a light that sweeps under the ring
        float ls = seg(t, 1.3f, 2.4f);
        if (ls > 0f && ls < 1f) {
            float lx = cx - 210f + 420f * ease(ls), la = (float) Math.sin(Math.PI * ls) * fadeOut;
            p.setShader(new RadialGradient(lx, cy + R + 52f, 90f, Color.argb((int) (230 * la), 160, 220, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            cv.drawRect(lx - 90f, cy + R + 44f, lx + 90f, cy + R + 60f, p);
            p.setShader(null);
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3f);
        p.setColor(Color.argb((int) (90 * ease(seg(t, 1.2f, 1.9f)) * fadeOut), 255, 255, 255));
        cv.drawLine(cx - 210f, cy + R + 52f, cx + 210f, cy + R + 52f, p);
        // the small line of words
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(26f);
        p.setColor(Color.argb((int) (200 * ease(seg(t, 1.6f, 2.3f)) * fadeOut), 220, 235, 255));
        cv.drawText("Mixed reality on your phone", cx, cy + R + 100f, p);
    }
}
