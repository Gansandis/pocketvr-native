package com.pocketvr.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The app's own camera (Camera2). One stream goes straight to the screen texture, a second small one is turned into a
 * little picture for the hand model. Nothing is read back from the graphics chip and no JPEG is made.
 */
public class NvCam {
    public interface Listener {
        void onImageSize(int w, int h, boolean rot180);
        void onError(String msg);
    }

    private final Activity act;
    private final NvHands hands;
    private final Listener lis;

    private HandlerThread ht;
    private Handler h;
    private CameraDevice dev;
    private CameraCaptureSession sess;
    private ImageReader reader;
    private Surface previewSurface;
    private ExecutorService detEx;
    private final AtomicBoolean detBusy = new AtomicBoolean(false);
    private volatile boolean running = false;
    private boolean handsOpened = false, handsFailed = false;

    public volatile float[] result = new float[]{0};
    public volatile boolean flowOn = true;         // follow the hand between the model's answers by looking at how the picture moved (NvFlow)
    public volatile boolean flowOk = false;
    public volatile boolean tipsOn = true;         // follow thumb / index / middle fingertips on their own (a pinch closing is seen at the camera rate)
    public volatile float[] tipNow = new float[NvFlow.NT * 3], resTip = new float[NvFlow.NT * 3];       // per fingertip: moves added up now / at the time of the model's picture, and whether they can be trusted
    public static final int[] TIPS = {4, 8, 12};
    private final float[] tmp2 = new float[2];
    // four followers (hand 0 / 1 x palm / fingers): their moves added up now / at the time of the picture behind `result` (x, y per follower), and which ones can be trusted (bit k = follower k)
    public volatile float[] flowNow = new float[NvFlow.NTRK * 2], resFl = new float[NvFlow.NTRK * 2];
    public volatile int flowMask = 0;
    private final float[] kfA = new float[21], hsx = new float[21], hsy = new float[21], shO = new float[2];
    public volatile long flowT = 0;
    private final NvFlow flow = new NvFlow();
    private final byte[] gray = new byte[NvFlow.GW * NvFlow.GH];
    private final float[] cat = new float[2];
    /** the colour of THIS person's skin in the light of the moment, learnt from points on the palm of the hand the model found:
     *  {Cb mean, Cr mean, Cb spread, Cr spread, time (ms) it was learnt}. The renderer uses it to tell skin from background (an adaptive skin model, not a fixed colour range). */
    public volatile float[] skin = new float[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    private static final int[][] SKIN_PTS = {{0, 0}, {1, 1}, {2, 2}, {5, 5}, {9, 9}, {13, 13}, {17, 17}, {0, 9}, {0, 5}, {0, 17}, {5, 9}, {9, 13}, {13, 17}, {2, 5}, {1, 5}, {5, 6}, {9, 10}, {13, 14}, {0, 2}};
    private void updateSkin(Bitmap bmp, float[] res) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        float[] cbs = new float[SKIN_PTS.length], crs = new float[SKIN_PTS.length], yys = new float[SKIN_PTS.length];
        int n = 0;
        for (int[] pp : SKIN_PTS) {
            float x = (res[1 + pp[0] * 3] + res[1 + pp[1] * 3]) * 0.5f * w, y = (res[1 + pp[0] * 3 + 1] + res[1 + pp[1] * 3 + 1]) * 0.5f * h;
            int xi = (int) x, yi = (int) y;
            if (xi < 2 || yi < 2 || xi >= w - 2 || yi >= h - 2) continue;
            float r = 0, g = 0, b = 0;
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) { int p = bmp.getPixel(xi + dx, yi + dy); r += (p >> 16) & 255; g += (p >> 8) & 255; b += p & 255; }
            r /= 9 * 255f; g /= 9 * 255f; b /= 9 * 255f;
            if (0.299f * r + 0.587f * g + 0.114f * b < 0.10f) continue;                 // too dark to tell
            yys[n] = 0.299f * r + 0.587f * g + 0.114f * b; cbs[n] = -0.169f * r - 0.331f * g + 0.5f * b + 0.5f; crs[n] = 0.5f * r - 0.419f * g - 0.081f * b + 0.5f; n++;
        }
        if (n < 5) return;
        float[] a = java.util.Arrays.copyOf(cbs, n), bb = java.util.Arrays.copyOf(crs, n);
        java.util.Arrays.sort(a); java.util.Arrays.sort(bb);
        float mcb = a[n / 2], mcr = bb[n / 2];
        float sc = 0, sr = 0; int k = 0;
        for (int i = 0; i < n; i++) if (Math.abs(cbs[i] - mcb) < 0.07f && Math.abs(crs[i] - mcr) < 0.07f) { sc += cbs[i]; sr += crs[i]; k++; }   // points on a ring, a nail or a background seen between the fingers are thrown away
        if (k < 4) return;
        float ec = sc / k, er = sr / k, vc = 0, vr = 0, ey = 0;
        for (int i = 0; i < n; i++) ey += yys[i];
        ey /= n;
        for (int i = 0; i < n; i++) if (Math.abs(cbs[i] - mcb) < 0.07f && Math.abs(crs[i] - mcr) < 0.07f) { vc += (cbs[i] - ec) * (cbs[i] - ec); vr += (crs[i] - er) * (crs[i] - er); }
        float dc = Math.max(0.022f, (float) Math.sqrt(vc / k)) + 0.012f, dr = Math.max(0.022f, (float) Math.sqrt(vr / k)) + 0.012f;      // some room for the shade over the hand
        // the BACKGROUND round the hand (what lies just beyond the fingertips and to the sides), learnt the same way: a pixel is skin only if it is closer to the hand's colour than to the background's.
        // (a wooden floor or table has nearly the colour of skin, but not the same as THIS hand and THIS floor)
        float bcb = 0, bcr = 0, bdc = 0, bdr = 0, bgOn = 0;
        {
            float sz = (float) Math.hypot((res[1 + 9 * 3] - res[1]) * w, (res[1 + 9 * 3 + 1] - res[2]) * h);
            float[] bx = new float[9], by = new float[9];
            int nb = 0;
            int[][] tips = {{4, 3}, {8, 7}, {12, 11}, {16, 15}, {20, 19}};
            for (int[] t : tips) {
                float dx = (res[1 + t[0] * 3] - res[1 + t[1] * 3]) * w, dy = (res[1 + t[0] * 3 + 1] - res[1 + t[1] * 3 + 1]) * h, l = (float) Math.hypot(dx, dy);
                if (l < 1e-3f) continue;
                bx[nb] = res[1 + t[0] * 3] * w + dx / l * sz * 0.45f; by[nb] = res[1 + t[0] * 3 + 1] * h + dy / l * sz * 0.45f; nb++;
            }
            {   // the side of the little finger and the side of the thumb, away from the palm's middle
                float mx = (res[1 + 0 * 3] + res[1 + 9 * 3]) * 0.5f * w, my = (res[1 + 0 * 3 + 1] + res[1 + 9 * 3 + 1]) * 0.5f * h;
                int[] sd = {17, 2};
                for (int q : sd) {
                    float dx = res[1 + q * 3] * w - mx, dy = res[1 + q * 3 + 1] * h - my, l = (float) Math.hypot(dx, dy);
                    if (l < 1e-3f) continue;
                    bx[nb] = mx + dx / l * sz * 1.05f; by[nb] = my + dy / l * sz * 1.05f; nb++;
                }
            }
            float[] bc = new float[nb], br2 = new float[nb]; int m = 0;
            for (int i = 0; i < nb; i++) {
                int xi = (int) bx[i], yi = (int) by[i];
                if (xi < 2 || yi < 2 || xi >= w - 2 || yi >= h - 2) continue;
                float r = 0, g = 0, b = 0;
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) { int p = bmp.getPixel(xi + dx, yi + dy); r += (p >> 16) & 255; g += (p >> 8) & 255; b += p & 255; }
                r /= 9 * 255f; g /= 9 * 255f; b /= 9 * 255f;
                float cb2 = -0.169f * r - 0.331f * g + 0.5f * b + 0.5f, cr2 = 0.5f * r - 0.419f * g - 0.081f * b + 0.5f;
                if (Math.abs(cb2 - ec) < dc * 1.2f && Math.abs(cr2 - er) < dr * 1.2f) continue;          // that is the hand itself (a bent finger): not background
                bc[m] = cb2; br2[m] = cr2; m++;
            }
            if (m >= 3) {
                float[] a2 = java.util.Arrays.copyOf(bc, m), b2 = java.util.Arrays.copyOf(br2, m);
                java.util.Arrays.sort(a2); java.util.Arrays.sort(b2);
                float mc = a2[m / 2], mr = b2[m / 2], s1 = 0, s2 = 0; int kk = 0;
                for (int i = 0; i < m; i++) if (Math.abs(bc[i] - mc) < 0.07f && Math.abs(br2[i] - mr) < 0.07f) { s1 += bc[i]; s2 += br2[i]; kk++; }
                if (kk >= 2) {
                    bcb = s1 / kk; bcr = s2 / kk; float v1 = 0, v2 = 0;
                    for (int i = 0; i < m; i++) if (Math.abs(bc[i] - mc) < 0.07f && Math.abs(br2[i] - mr) < 0.07f) { v1 += (bc[i] - bcb) * (bc[i] - bcb); v2 += (br2[i] - bcr) * (br2[i] - bcr); }
                    bdc = Math.max(0.022f, (float) Math.sqrt(v1 / kk)) + 0.012f; bdr = Math.max(0.022f, (float) Math.sqrt(v2 / kk)) + 0.012f;
                    bgOn = Math.hypot((bcb - ec) / dc, (bcr - er) / dr) > 0.9 ? 1f : 0f;                // the two colours must really differ, else it is skin only
                    float[] ov = skin;
                    if (bgOn > 0.5f && ov.length > 10 && ov[6] > 0.5f) { float t = 0.4f; bcb = ov[7] + (bcb - ov[7]) * t; bcr = ov[8] + (bcr - ov[8]) * t; bdc = ov[9] + (bdc - ov[9]) * t; bdr = ov[10] + (bdr - ov[10]) * t; }
                }
            }
        }
        float[] pv = skin; if (pv[2] > 0) ey = pv[5] + (ey - pv[5]) * 0.3f; long now = System.currentTimeMillis();
        if (pv[2] > 0 && Math.abs(pv[0] - ec) < 0.12f) { float t = 0.3f; ec = pv[0] + (ec - pv[0]) * t; er = pv[1] + (er - pv[1]) * t; dc = pv[2] + (dc - pv[2]) * t; dr = pv[3] + (dr - pv[3]) * t; }
        skin = new float[]{ec, er, dc, dr, 1f, ey, bgOn, bcb, bcr, bdc, bdr};
        skinT = now;
    }
    public volatile long skinT = 0;
    /** the colour table of the hand against the room: 32 x 32 over (Cb, Cr), a byte per colour = how likely that colour is the hand (0..255). It is learnt from the REAL pixels
     *  of the picture: those inside the bones of the hand are "hand", those far from the hand are "room" (histograms of both, then hand / (hand + room): the Bayes skin
     *  classifier of Jones and Rehg, trained again at every look, with the hand points of the model as the teacher). */
    public volatile byte[] lut = null;
    public volatile long lutT = 0;
    private final float[] hsH = new float[1024], hbH = new float[1024];
    private static final int[][] BONE = {{0, 1}, {1, 2}, {2, 3}, {3, 4}, {0, 5}, {5, 6}, {6, 7}, {7, 8}, {9, 10}, {10, 11}, {11, 12}, {13, 14}, {14, 15}, {15, 16}, {0, 17}, {17, 18}, {18, 19}, {19, 20}, {5, 9}, {9, 13}, {13, 17}};
    private boolean lutHas = false;
    private void updateLut(Bitmap bmp, float[] res) {
        int w = bmp.getWidth(), h = bmp.getHeight(), nh = Math.min(2, (int) res[0]);
        if (nh < 1 || res.length < 1 + nh * 63) return;
        float[][] px = new float[nh][42];
        float[] sz = new float[nh];
        for (int i = 0; i < nh; i++) {
            for (int k = 0; k < 21; k++) { px[i][k * 2] = res[1 + i * 63 + k * 3] * w; px[i][k * 2 + 1] = res[1 + i * 63 + k * 3 + 1] * h; }
            sz[i] = Math.max(8f, (float) Math.hypot(px[i][18] - px[i][0], px[i][19] - px[i][1]));
        }
        float[] nsH = new float[1024], nsB = new float[1024];
        int cH = 0, cB = 0;
        int[] row = new int[w];
        for (int y = 1; y < h - 1; y += 2) {
            bmp.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 1; x < w - 1; x += 2) {
                // nearest distance to a bone of each hand, in units of that hand's size
                float best = 1e9f;
                for (int i = 0; i < nh; i++) {
                    float[] P = px[i];
                    for (int[] b : BONE) {
                        float ax = P[b[0] * 2], ay = P[b[0] * 2 + 1], bx = P[b[1] * 2] - ax, by = P[b[1] * 2 + 1] - ay;
                        float l2 = bx * bx + by * by, t = l2 < 1e-3f ? 0f : ((x - ax) * bx + (y - ay) * by) / l2;
                        t = t < 0f ? 0f : (t > 1f ? 1f : t);
                        float dx = x - (ax + bx * t), dy = y - (ay + by * t);
                        float d = (float) Math.sqrt(dx * dx + dy * dy) / sz[i];
                        if (d < best) best = d;
                    }
                    // palm: the middle of the palm is a bone of its own, fat
                    float mx = (P[0] + P[18]) * 0.5f, my = (P[1] + P[19]) * 0.5f;
                    float dp = (float) Math.hypot(x - mx, y - my) / sz[i] - 0.15f;
                    if (dp < best) best = dp;
                }
                int p = row[x];
                float r = ((p >> 16) & 255) / 255f, g = ((p >> 8) & 255) / 255f, b2 = (p & 255) / 255f;
                float yy = 0.299f * r + 0.587f * g + 0.114f * b2;
                if (yy < 0.06f) continue;
                int ib = (int) ((-0.169f * r - 0.331f * g + 0.5f * b2 + 0.5f) * 32f), ir = (int) ((0.5f * r - 0.419f * g - 0.081f * b2 + 0.5f) * 32f);
                ib = ib < 0 ? 0 : (ib > 31 ? 31 : ib); ir = ir < 0 ? 0 : (ir > 31 ? 31 : ir);
                if (best < 0.055f) { nsH[ib * 32 + ir]++; cH++; }                  // inside the bones: surely hand (fingers are about 0.08-0.1 wide in these units, so this is their middle)
                else if (best > 0.42f) { nsB[ib * 32 + ir]++; cB++; }               // far from the hand: the room
            }
        }
        if (cH < 40 || cB < 200) return;
        float a = lutHas ? 0.5f : 0f;                                              // mix with what was learnt before (steady, but follows the light)
        for (int i = 0; i < 1024; i++) { hsH[i] = hsH[i] * a + nsH[i] / cH; hbH[i] = hbH[i] * a + nsB[i] / cB; }
        lutHas = true;
        float[] pr = new float[1024];
        for (int i = 0; i < 1024; i++) { float s = hsH[i], r = hbH[i]; pr[i] = s < 2e-4f ? 0f : s / (s + r * 1.0f + 1e-5f); }
        byte[] out = new byte[1024];
        for (int ib = 0; ib < 32; ib++) for (int ir = 0; ir < 32; ir++) {                 // a light blur, so a colour that was never seen exactly is judged by its neighbours
            float sum = 0, wt = 0;
            for (int db = -1; db <= 1; db++) for (int dr = -1; dr <= 1; dr++) {
                int jb = ib + db, jr = ir + dr; if (jb < 0 || jr < 0 || jb > 31 || jr > 31) continue;
                float k = (db == 0 && dr == 0) ? 2f : 1f; sum += pr[jb * 32 + jr] * k; wt += k;
            }
            out[ib * 32 + ir] = (byte) Math.round(255f * sum / wt);
        }
        lut = out; lutT = System.currentTimeMillis();
    }

    // ---- the hand cut out of the real pixels (see NvSeg) ----
    /** what the cutting made for one hand: a small probability picture of a square area (x0, y0, side in pixels of a W x H picture), and the 21 points it was made from */
    public static final class SegOut { public byte[] p; public float x0, y0, side; public int W, H; public float[] lm; public long t; public boolean arm; }
    public volatile SegOut[] segs = new SegOut[2];
    public volatile boolean segArm = false;                       // the forearm counts as hand too (set by the renderer)
    public volatile boolean segOn = true;
    public volatile String segErr = "";
    public volatile int segCount = 0;
    public volatile float segMs = 0f;
    private final java.util.concurrent.atomic.AtomicBoolean segBusy = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile ExecutorService segEx = null;
    private int[] segSrc = null;
    private void segStart(Bitmap bmp, float[] res) {
        if (!segOn) { segs = new SegOut[2]; return; }
        int w = bmp.getWidth(), h = bmp.getHeight(), nh = Math.min(2, (int) res[0]);
        if (nh < 1 || res.length < 1 + nh * 63 || segBusy.get()) return;
        if (segSrc == null || segSrc.length != w * h) segSrc = new int[w * h];
        bmp.getPixels(segSrc, 0, w, 0, 0, w, h);
        final boolean arm = segArm;
        final int S = NvSeg.S;
        final int[][] crops = new int[nh][S * S];
        final float[][] lms = new float[nh][42], rois = new float[nh][];
        final float[] szs = new float[nh];
        for (int i = 0; i < nh; i++) {
            float[] lm = lms[i];
            for (int k = 0; k < 21; k++) { lm[k * 2] = res[1 + i * 63 + k * 3] * w; lm[k * 2 + 1] = res[1 + i * 63 + k * 3 + 1] * h; }
            szs[i] = Math.max(8f, (float) Math.hypot(lm[18] - lm[0], lm[19] - lm[1]));
            rois[i] = NvSeg.roi(lm, szs[i], arm);
            NvSeg.crop(segSrc, w, h, rois[i][0], rois[i][1], rois[i][2], crops[i]);
        }
        if (!segBusy.compareAndSet(false, true)) return;
        ExecutorService ex = segEx;
        if (ex == null) { synchronized (this) { if (segEx == null) segEx = Executors.newSingleThreadExecutor(); ex = segEx; } }
        final int W = w, H = h; final long t = System.currentTimeMillis();
        ex.execute(() -> {
            try {
                long m0 = System.nanoTime();
                SegOut[] out = new SegOut[2];
                for (int i = 0; i < nh; i++) {
                    float sc = S / rois[i][2];
                    float[] q = new float[42];
                    for (int k = 0; k < 21; k++) { q[k * 2] = (lms[i][k * 2] - rois[i][0]) * sc; q[k * 2 + 1] = (lms[i][k * 2 + 1] - rois[i][1]) * sc; }
                    SegOut o = new SegOut();
                    o.p = segWorker.run(crops[i], q, szs[i] * sc, arm);
                    o.x0 = rois[i][0]; o.y0 = rois[i][1]; o.side = rois[i][2]; o.W = W; o.H = H; o.lm = lms[i]; o.t = t; o.arm = arm;
                    out[i] = o;
                }
                segs = out; segCount++; segMs += ((System.nanoTime() - m0) / 1e6f - segMs) * 0.3f; segErr = "";
            } catch (Throwable e) { segErr = String.valueOf(e);
            } finally { segBusy.set(false); }
        });
    }
    private final NvSeg segWorker = new NvSeg();
    public volatile long resultCapT = 0;           // time (ms, System.nanoTime based) the picture behind `result` was taken
    public volatile boolean filt = true;           // steady the points: little shake when the hand is slow, almost no delay when it is quick
    private float[] fx = new float[127], fd = new float[127];
    private boolean fOk = false;
    private long fT = 0;
    private int fN = 0;
    private android.graphics.Bitmap detBmp;
    private byte[] yB = new byte[0], uB = new byte[0], vB = new byte[0];
    private int[] colX = new int[0], colU = new int[0], colV = new int[0];
    private int mapW = -1, mapOw = -1, mapUps = -1, mapVps = -1, mapT = -1, mapH = -1, mapOh = -1;
    private int[] colXs = new int[0], rowYs = new int[0];
    // the phone can be turned over (landscape the other way) while the app runs: the camera picture must then be turned 180 degrees as well
    private volatile int sensorOri = -1, prevW = 0, prevH = 0;
    private volatile boolean curRot180 = false;
    private android.hardware.display.DisplayManager.DisplayListener dispLis;

    // look at how the picture inside the hand's box moved since the last frame (see NvFlow)
    private void feedFlow(Image img, long now) {
        float[] rr = result;
        float[] pts = null, tips = null;
        int n = (rr != null && rr.length > 0 && rr[0] >= 1) ? Math.min(2, Math.min((int) rr[0], (rr.length - 1) / 63)) : 0;
        if (n > 0) {
            pts = new float[n * 42];
            if (tipsOn) { tips = new float[NvFlow.NT * 2]; java.util.Arrays.fill(tips, Float.NaN); }
            float[] tn = tipNow, rt = resTip, rs = resFl;
            for (int hnd = 0; hnd < n; hnd++) {
                int base = 1 + hnd * 63, t0 = hnd * 2;
                boolean pok = flow.valid(t0), fok = flow.valid(t0 + 1);
                float pdx = 0f, pdy = 0f, fdx = 0f, fdy = 0f;                              // what each follower has seen since the model's picture
                if (pok) { pdx = flow.cumX(t0) - rs[t0 * 2]; pdy = flow.cumY(t0) - rs[t0 * 2 + 1]; }
                if (fok) { fdx = flow.cumX(t0 + 1) - rs[(t0 + 1) * 2]; fdy = flow.cumY(t0 + 1) - rs[(t0 + 1) * 2 + 1]; }
                NvFlow.kFactors(rr, base, kfA);
                for (int k = 0; k < 21; k++) {
                    boolean has = NvFlow.shiftOf(kfA[k], pok, pdx, pdy, fok, fdx, fdy, shO);     // each point is where the model saw it plus what moved since
                    hsx[k] = has ? shO[0] : 0f; hsy[k] = has ? shO[1] : 0f;
                    pts[hnd * 42 + k * 2] = rr[base + k * 3] + hsx[k];
                    pts[hnd * 42 + k * 2 + 1] = rr[base + k * 3 + 1] + hsy[k];
                }
                if (tips != null) for (int j = 0; j < 3; j++) {
                    int slot = hnd * 3 + j, lm = TIPS[j], tb = base + lm * 3;
                    float sx = hsx[lm], sy = hsy[lm];
                    float tx = rr[tb] + sx, ty = rr[tb + 1] + sy;                      // where this fingertip was in the last frame: the model's point + what has moved since
                    if (tn != null && rt != null && tn[slot * 3 + 2] > 0.5f && rt[slot * 3 + 2] > 0.5f) {
                        float ex = tn[slot * 3] - rt[slot * 3], ey = tn[slot * 3 + 1] - rt[slot * 3 + 1];
                        if (Math.abs(ex - sx) < 0.08f && Math.abs(ey - sy) < 0.08f) { tx = rr[tb] + ex; ty = rr[tb + 1] + ey; }
                    }
                    tips[slot * 2] = tx; tips[slot * 2 + 1] = ty;
                }
            }
        }
        Image.Plane yp = img.getPlanes()[0];
        ByteBuffer yb = yp.getBuffer();
        int w = img.getWidth(), h = img.getHeight(), rs = yp.getRowStride();
        for (int gy = 0; gy < NvFlow.GH; gy++) {
            int row = Math.min(h - 1, (2 * gy + 1) * h / (2 * NvFlow.GH)) * rs;
            for (int gx = 0; gx < NvFlow.GW; gx++) gray[gy * NvFlow.GW + gx] = yb.get(row + Math.min(w - 1, (2 * gx + 1) * w / (2 * NvFlow.GW)));
        }
        flow.feed(gray, now, pts, tips);
        float[] tnw = new float[NvFlow.NT * 3];
        flow.tipNow(tnw);
        tipNow = tnw;
        float[] fn = new float[NvFlow.NTRK * 2];
        for (int k = 0; k < NvFlow.NTRK; k++) { fn[k * 2] = flow.cumX(k); fn[k * 2 + 1] = flow.cumY(k); }
        flowNow = fn;
        flowMask = flow.validMask();
        flowT = now;
        flowOk = n > 0 && flowMask != 0;
    }

    private void applyRotation() {
        try {
            if (sensorOri < 0 || prevW <= 0) return;
            int disp = act.getWindowManager().getDefaultDisplay().getRotation() * 90;
            boolean r = ((sensorOri - disp + 360) % 360) == 180;
            if (r != curRot180) {
                curRot180 = r;
                NvMotion mo = motion; if (mo != null) mo.rot180 = r;
                lis.onImageSize(prevW, prevH, r);
            }
        } catch (Throwable ignored) { }
    }
    public volatile float camFps = 0, detHz = 0, convMs = 0;
    public volatile float hFov = 1.15f;           // horizontal field of view of the camera, radians
    public volatile NvMotion motion;
    public volatile long detPaceMs = 0;           // shortest time between two looks for hands
    private volatile boolean autoDown = false;       // two hands were asked for but only one is in use: look for one (faster) until the hands are lowered
    private volatile long lastTwoT = 0, sessionT = 0;
    public volatile boolean handsGpu = true;      // the hand model runs on the graphics chip (true) or on the processor (false)
    private volatile boolean openedGpu = true;
    public volatile int handsN = 1;               // how many hands to look for (1 or 2; two is slower)
    private int openedN = 1;
    public volatile int detW = 320;               // width of the picture given to the hand model (bigger = steadier points, slower conversion)
    private long lastSeenHand = 0, lastHandT = 0;
    private int[] pxBuf;
    private long camCount = 0, camT0 = 0, detCount = 0, detT0 = 0, lastDet = 0;

    public float leftness(int i) { float[] l = hands.leftness; return i >= 0 && i < l.length ? l[i] : -1f; }
    // ---- seeing in the dark: a longer exposure (lower frame rate allowed) and a brighter exposure target; the picture is also lifted a little when drawn (VRRenderer.lift)
    public volatile int night = 0;                          // 0 off, 1 medium, 2 high
    private volatile CaptureRequest.Builder reqB;
    private volatile Range<Integer> fpsNight, fpsDay;
    private volatile int aeMax = 0;

    private void applyNightOn(CaptureRequest.Builder b) {
        int lv = night;
        try {
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, lv == 0 ? 0 : Math.round(aeMax * (lv == 1 ? 0.6f : 1.0f)));
            Range<Integer> r = lv > 0 && fpsNight != null ? fpsNight : fpsDay;
            if (r != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, r);
        } catch (Throwable ignored) { }
    }

    /** change the night setting while the camera runs */
    public void setNight(int level) {
        night = Math.max(0, Math.min(2, level));
        CaptureRequest.Builder b = reqB; CameraCaptureSession s = sess;
        if (b == null || s == null) return;
        try {
            applyNightOn(b);
            s.setRepeatingRequest(b.build(), null, h);
        } catch (Throwable ignored) { }
    }

    public NvCam(Activity a, NvHands hh, Listener l) { act = a; hands = hh; lis = l; }

    public void start(final SurfaceTexture st) {
        stop();
        running = true;
        detEx = Executors.newSingleThreadExecutor();
        ht = new HandlerThread("nv-cam");
        ht.start();
        h = new Handler(ht.getLooper());
        h.post(() -> open(st));
        try {
            final android.hardware.display.DisplayManager dm = (android.hardware.display.DisplayManager) act.getSystemService(Context.DISPLAY_SERVICE);
            dispLis = new android.hardware.display.DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int id) { }
                @Override public void onDisplayRemoved(int id) { }
                @Override public void onDisplayChanged(int id) { applyRotation(); }
            };
            dm.registerDisplayListener(dispLis, h);
        } catch (Throwable ignored) { dispLis = null; }
    }

    public void stop() {
        running = false;
        try {
            if (dispLis != null) ((android.hardware.display.DisplayManager) act.getSystemService(Context.DISPLAY_SERVICE)).unregisterDisplayListener(dispLis);
        } catch (Throwable ignored) { }
        dispLis = null;
        try { if (sess != null) sess.close(); } catch (Throwable ignored) { }
        try { if (dev != null) dev.close(); } catch (Throwable ignored) { }
        try { if (reader != null) reader.close(); } catch (Throwable ignored) { }
        try { if (previewSurface != null) previewSurface.release(); } catch (Throwable ignored) { }
        sess = null; dev = null; reader = null; previewSurface = null;
        if (ht != null) { ht.quitSafely(); ht = null; }
        if (detEx != null) {
            final NvHands hh = hands;
            detEx.execute(() -> { hh.close(); });         // the model is closed on the thread that made it
            detEx.shutdown();
            detEx = null;
        }
        { ExecutorService se = segEx; if (se != null) { se.shutdown(); segEx = null; } segs = new SegOut[2]; segBusy.set(false); }
        handsOpened = false;
        detBusy.set(false);
        detBmp = null; fOk = false; flow.reset(); flowOk = false; flowMask = 0;
    }

    private static Size pick(Size[] list, int tw, int th, double aspect) {
        Size best = null;
        long bestScore = Long.MAX_VALUE;
        for (Size s : list) {
            double a = (double) s.getWidth() / s.getHeight();
            if (aspect > 0 && Math.abs(a - aspect) > 0.02) continue;
            if (aspect <= 0 && Math.abs(a - (double) tw / th) > 0.02) continue;
            long score = Math.abs((long) s.getWidth() * s.getHeight() - (long) tw * th);
            if (score < bestScore) { bestScore = score; best = s; }
        }
        if (best == null && list.length > 0) best = list[0];
        return best;
    }

    private void open(SurfaceTexture st) {
        try {
            CameraManager cm = (CameraManager) act.getSystemService(Context.CAMERA_SERVICE);
            String id = null;
            CameraCharacteristics cc = null;
            for (String s : cm.getCameraIdList()) {
                CameraCharacteristics c = cm.getCameraCharacteristics(s);
                Integer f = c.get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == CameraCharacteristics.LENS_FACING_BACK) { id = s; cc = c; break; }
            }
            if (id == null) { lis.onError("no back camera"); return; }
            try {
                float[] fl = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                android.util.SizeF ps = cc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                if (fl != null && fl.length > 0 && ps != null && fl[0] > 0) hFov = (float) (2.0 * Math.atan(ps.getWidth() / (2.0 * fl[0])));
            } catch (Throwable ignored) { }
            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size prev = pick(map.getOutputSizes(SurfaceTexture.class), 1280, 720, 0);
            double asp = (double) prev.getWidth() / prev.getHeight();
            Size an = pick(map.getOutputSizes(ImageFormat.YUV_420_888), 640, (int) Math.round(640 / asp), asp);
            if (prev == null || an == null) { lis.onError("no camera size"); return; }

            Integer so = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
            int disp = act.getWindowManager().getDefaultDisplay().getRotation() * 90;
            boolean rot180 = so != null && ((so - disp + 360) % 360) == 180;
            NvMotion mo = motion; if (mo != null) { mo.rot180 = rot180; Integer tsrc = cc.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE); mo.tsReal = tsrc != null && tsrc == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME; }

            st.setDefaultBufferSize(prev.getWidth(), prev.getHeight());
            previewSurface = new Surface(st);
            reader = ImageReader.newInstance(an.getWidth(), an.getHeight(), ImageFormat.YUV_420_888, 3);
            reader.setOnImageAvailableListener(r -> onImage(r), h);
            sensorOri = so != null ? so : -1; prevW = prev.getWidth(); prevH = prev.getHeight(); curRot180 = rot180;
            lis.onImageSize(prev.getWidth(), prev.getHeight(), rot180);

            Range<Integer> aer = cc.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            aeMax = aer != null ? aer.getUpper() : 0;
            Range<Integer> lowFps = null;                                     // the range that lets the camera slow down most (longer exposure in the dark)
            Range<Integer>[] rs0 = cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (rs0 != null) for (Range<Integer> r : rs0) if (r.getUpper() >= 24 && r.getUpper() <= 30 && (lowFps == null || r.getLower() < lowFps.getLower())) lowFps = r;
            fpsNight = lowFps;
            Range<Integer> fps = null, fps30 = null;
            Range<Integer>[] rs = cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (rs != null) {
                for (Range<Integer> r : rs) {
                    if (r.getUpper() == 30 && (fps30 == null || r.getLower() > fps30.getLower())) fps30 = r;
                    // a range that reaches 60 (and may drop to 30 in dim light): the camera then gives up to 60 pictures a second, so the hand and the passthrough are fresher
                    if (r.getUpper() == 60 && r.getLower() <= 30 && (fps == null || r.getLower() < fps.getLower())) fps = r;
                }
            }
            if (fps == null) fps = fps30;
            final Range<Integer> fpsF = fps, fps30F = fps30;
            fpsDay = fpsF;
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    if (!running) { camera.close(); return; }
                    dev = camera;
                    try {
                        dev.createCaptureSession(Arrays.asList(previewSurface, reader.getSurface()), new CameraCaptureSession.StateCallback() {
                            @Override public void onConfigured(CameraCaptureSession s) {
                                if (!running) return;
                                sess = s;
                                try {
                                    CaptureRequest.Builder b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                    b.addTarget(previewSurface);
                                    b.addTarget(reader.getSurface());
                                    b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                                    if (fpsF != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsF);
                                    reqB = b;
                                    try {
                                        applyNightOn(b);
                                        s.setRepeatingRequest(b.build(), null, h);
                                    } catch (Throwable hi) {                  // the 60 range was refused: go back to 30
                                        if (fps30F == null || fps30F == fpsF) throw hi;
                                        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps30F);
                                        s.setRepeatingRequest(b.build(), null, h);
                                    }
                                } catch (Throwable t) { lis.onError("session: " + t); }
                            }
                            @Override public void onConfigureFailed(CameraCaptureSession s) { lis.onError("camera session failed"); }
                        }, h);
                    } catch (Throwable t) { lis.onError("session: " + t); }
                }
                @Override public void onDisconnected(CameraDevice camera) { camera.close(); }
                @Override public void onError(CameraDevice camera, int error) { camera.close(); lis.onError("camera error " + error); }
            }, h);
        } catch (Throwable t) {
            lis.onError("open: " + t);
        }
    }

    private void onImage(ImageReader r) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return;
            long now = System.nanoTime() / 1000000L;
            camCount++;
            if ((camCount & 15) == 0) applyRotation();                 // also checked now and then, in case the system did not tell us
            if (camT0 == 0) camT0 = now;
            if (now - camT0 >= 1000) { camFps = camCount * 1000f / (now - camT0); camCount = 0; camT0 = now; }
            NvMotion mo = motion;
            if (mo != null && mo.enabled) { try { mo.hands = result; mo.feed(img, hFov); } catch (Throwable t) { mo.err = String.valueOf(t); } }
            if (flowOn) { try { feedFlow(img, now); } catch (Throwable t) { flowOk = false; } } else flowOk = false;
            float[] rr = result;
            if (rr != null && rr.length > 0 && rr[0] >= 1) lastSeenHand = now;
            long pace = detPaceMs;
            float im = hands.inferMs;                                      // the phone slows the processor down as it heats up (the model then takes 130 -> 190 ms), and a model that never rests heats it more:
            if (im > 110f) pace = Math.max(pace, (long) (im * 0.4f));       // so once it is slow, give it a rest between two looks (about 60 % of the time busy). The hand is followed by the picture's motion in between.
            if (now - lastSeenHand > 3000) pace = Math.max(pace, 80);     // no hand in sight for a while: look less often (saves heat), wake up fast when one appears
            if (handsFailed || now - lastDet < pace || !detBusy.compareAndSet(false, true)) return;
            lastDet = now;
            long c0 = System.nanoTime();
            final Bitmap bmp = yuvToBitmap(img, this);
            convMs += ((System.nanoTime() - c0) / 1e6f - convMs) * 0.2f;
            final long ts = now;
            ExecutorService ex = detEx;
            if (ex == null) { detBusy.set(false); return; }
            ex.execute(() -> {
                try {
                    int want = (handsN >= 2 && !autoDown) ? 2 : 1;
                    if (handsOpened && (openedN != want || openedGpu != handsGpu)) { hands.close(); handsOpened = false; }      // the number of hands (or where it runs) was changed
                    if (!handsOpened) {
                        openedN = want;
                        openedGpu = handsGpu;
                        handsOpened = hands.open(act.getApplicationContext(), openedGpu, openedN);
                        if (!handsOpened) { handsFailed = true; lis.onError("hands: " + hands.error); return; }
                    }
                    // the hand model closes the picture it is given (and that recycles the bitmap), so a copy is kept for the colour learning and the cutting of the hand
                    Bitmap keep = null;
                    try { keep = bmp.copy(Bitmap.Config.ARGB_8888, false); } catch (Throwable e) { segErr = "copy:" + e; }
                    float[] res = hands.detect(bmp, ts);
                    if (keep != null) {
                        try {
                            if (res != null && res[0] >= 1 && res.length >= 64) {
                                try { updateSkin(keep, res); } catch (Throwable e) { segErr = "skin:" + e; }
                                try { updateLut(keep, res); } catch (Throwable e) { segErr = "lut:" + e; }
                                try { segStart(keep, res); } catch (Throwable e) { segErr = "start:" + e; }
                            }
                        } finally { try { keep.recycle(); } catch (Throwable ignored) { } }
                    }
                    if (res != null) {
                        heldFlag = false;
                        res = holdSecond(res, result, System.nanoTime() / 1000000L);
                        if (res[0] >= 1 && filt) smooth(res, ts);
                        long tn = System.nanoTime() / 1000000L;
                        if (res[0] >= 1) {
                            if (tn - lastHandT > 1000) { sessionT = tn; lastTwoT = tn; }       // a new time with hands in view
                            if (res[0] >= 2 && !heldFlag) lastTwoT = tn;        // (a hand that is only being held for a moment does not count as seen)
                            lastHandT = tn;
                            // (it used to drop to one-hand looking after 5 s with a single hand in view. That made the second hand impossible to find
                            // until both hands were lowered, and switching needed the model to be opened again, which loses the hands for a moment. Not any more:
                            // when two hands are chosen in the settings, two are looked for all the time.)
                        } else if (tn - lastHandT > 1000 && autoDown) autoDown = false;
                        // one or two empty answers in a row (a blink of the model) must not drop the hand: keep the last good points for a moment
                        if (res[0] < 1 && result.length > 0 && result[0] >= 1 && tn - lastHandT < 260) res = result;
                        else {
                            resultCapT = ts;
                            float[] rf = new float[NvFlow.NTRK * 2];                       // where the moves of every follower stood when that picture was taken (set before the points)
                            for (int k = 0; k < NvFlow.NTRK; k++) { flow.cumAt(k, ts, cat); rf[k * 2] = cat[0]; rf[k * 2 + 1] = cat[1]; }
                            resFl = rf;
                            float[] rt = new float[NvFlow.NT * 3];
                            for (int k = 0; k < NvFlow.NT; k++) { flow.tipAt(k, ts, tmp2); rt[k * 3] = tmp2[0]; rt[k * 3 + 1] = tmp2[1]; rt[k * 3 + 2] = flow.tipValid(k) ? 1f : 0f; }
                            resTip = rt;
                        }                       // when the picture the points belong to was taken (written before the points, so a reader of the new points sees this time)
                        result = res;
                        long t = System.nanoTime() / 1000000L;
                        detCount++;
                        if (detT0 == 0) detT0 = t;
                        if (t - detT0 >= 1000) { detHz = detCount * 1000f / (t - detT0); detCount = 0; detT0 = t; }
                    }
                } catch (Throwable t) {
                    lis.onError("detect: " + t);
                } finally {
                    detBusy.set(false);
                }
            });
        } catch (Throwable t) {
            detBusy.set(false);
        } finally {
            if (img != null) img.close();
        }
    }

    /**
     * With two hands, the model now and then sees only one of them for a look or two. The other hand must not vanish (and the two hands must not swap
     * places in the list): the missing one is kept at its last points for a short moment. The hands stay ordered left to right in the picture.
     */
    private volatile boolean heldFlag = false;
    private float[] holdSecond(float[] res, float[] prev, long tn) {
        if (res == null || res[0] != 1f || res.length < 64) return res;
        if (prev == null || prev.length < 127 || prev[0] < 2f || tn - lastTwoT > 230) return res;
        // which of the two earlier hands is the one that was seen again (nearest wrist)?
        float nx = res[1], ny = res[2];
        float d0 = (prev[1] - nx) * (prev[1] - nx) + (prev[2] - ny) * (prev[2] - ny);
        float d1 = (prev[64] - nx) * (prev[64] - nx) + (prev[65] - ny) * (prev[65] - ny);
        int seen = d0 <= d1 ? 0 : 1, gone = 1 - seen;
        float[] out = new float[127];
        out[0] = 2f;
        System.arraycopy(res, 1, out, 1 + seen * 63, 63);                 // the hand that is seen (fresh points)
        System.arraycopy(prev, 1 + gone * 63, out, 1 + gone * 63, 63);   // the hand that is missing (its last points)
        heldFlag = true;
        return out;
    }

    // One Euro filter on every point: the cut-off frequency rises with the speed of the point, so a still hand is calm
    // and a moving hand is followed with almost no delay. dt comes from the real picture times, not from when we happened to look.
    // The speed is measured against the size of the hand (a palm 12 % of the picture is the reference): a far, small hand moves few picture units but just as fast
    // for its size, so it gets the same calm when still and the same quickness when moving as a near one.
    private static final float F_MIN = 2.6f, F_BETA = 7.0f, F_D = 1.5f, PALM_REF = 0.12f;
    private static float lpA(float dt, float fc) { float tau = 1f / (6.2831853f * fc); return 1f / (1f + tau / dt); }

    private void smooth(float[] res, long ts) {
        int n = (int) res[0];
        int len = 1 + n * 63;
        float dt = (ts - fT) / 1000f;
        if (!fOk || n != fN || dt <= 0f || dt > 0.25f || res.length < len || len > fx.length) {      // a new hand, or a long gap: start from the raw points
            for (int i = 1; i < len && i < fx.length; i++) { fx[i] = res[i]; fd[i] = 0f; }
            fOk = true; fN = n; fT = ts;
            return;
        }
        dt = Math.max(0.004f, dt);
        float aD = lpA(dt, F_D);
        for (int h = 0; h < n; h++) {
            int b = 1 + h * 63;
            float px = res[b + 27] - res[b], py = res[b + 28] - res[b + 1];          // wrist (point 0) to the base of the middle finger (point 9)
            float palm = (float) Math.sqrt(px * px + py * py);
            float sc = Math.max(0.5f, Math.min(2.5f, PALM_REF / Math.max(0.02f, palm)));
            for (int i = b; i < b + 63 && i < len; i++) {
                float v = (res[i] - fx[i]) / dt;
                fd[i] += (v - fd[i]) * aD;
                float fc = F_MIN + F_BETA * sc * Math.abs(fd[i]);
                fx[i] += (res[i] - fx[i]) * lpA(dt, fc);
                res[i] = fx[i];
            }
        }
        fT = ts;
    }

    // the camera gives YUV; the hand model wants colour pixels. The planes are copied in one piece and the column positions are worked out once,
    // so the loop only does array reads (much faster than reading the buffers pixel by pixel). The picture object is reused every time.
    private static Bitmap yuvToBitmap(Image img, NvCam self) {
        int w = img.getWidth(), hh = img.getHeight();
        Image.Plane[] p = img.getPlanes();
        ByteBuffer yb = p[0].getBuffer(), ub = p[1].getBuffer(), vb = p[2].getBuffer();
        int yrs = p[0].getRowStride(), urs = p[1].getRowStride(), ups = p[1].getPixelStride(), vrs = p[2].getRowStride(), vps = p[2].getPixelStride();
        int ow = Math.max(64, Math.min(w, self.detW)), oh = Math.max(36, ow * hh / w);
        int yn = yb.remaining(), un = ub.remaining(), vn = vb.remaining();
        if (self.yB.length < yn) self.yB = new byte[yn];
        if (self.uB.length < un) self.uB = new byte[un];
        if (self.vB.length < vn) self.vB = new byte[vn];
        yb.get(self.yB, 0, yn); ub.get(self.uB, 0, un); vb.get(self.vB, 0, vn);
        // Shrinking the picture by taking one pixel out of every 4 x 4 makes thin edges flicker from picture to picture (the hand model then moves its points for no
        // reason). Each small pixel is now the average of a few pixels spread over its patch of the big picture: calmer, sharper points. (up to 3 x 3 reads per pixel)
        final int t = Math.max(1, Math.min(3, Math.min(w / ow, hh / oh)));
        if (self.mapW != w || self.mapOw != ow || self.mapUps != ups || self.mapVps != vps || self.colX.length != ow || self.mapT != t || self.mapH != hh || self.mapOh != oh) {
            self.colX = new int[ow]; self.colU = new int[ow]; self.colV = new int[ow];
            self.colXs = new int[ow * t];
            self.rowYs = new int[oh * t];
            for (int ox = 0; ox < ow; ox++) {
                int sx = Math.min(w - 1, (int) ((ox + 0.5f) * w / ow));
                self.colX[ox] = sx; self.colU[ox] = (sx >> 1) * ups; self.colV[ox] = (sx >> 1) * vps;
                for (int k = 0; k < t; k++) self.colXs[ox * t + k] = Math.min(w - 1, (int) ((ox + (k + 0.5f) / t) * w / ow));
            }
            for (int oy = 0; oy < oh; oy++) for (int k = 0; k < t; k++) self.rowYs[oy * t + k] = Math.min(hh - 1, (int) ((oy + (k + 0.5f) / t) * hh / oh));
            self.mapW = w; self.mapOw = ow; self.mapUps = ups; self.mapVps = vps; self.mapT = t; self.mapH = hh; self.mapOh = oh;
        }
        final byte[] Y = self.yB, U = self.uB, V = self.vB;
        final int[] cx = self.colX, cu = self.colU, cv = self.colV, cxs = self.colXs, rys = self.rowYs;
        final int inv = 65536 / (t * t);
        int[] px = self.pxBuf;
        if (px == null || px.length != ow * oh) px = self.pxBuf = new int[ow * oh];
        for (int oy = 0; oy < oh; oy++) {
            int srcY = Math.min(hh - 1, (int) ((oy + 0.5f) * hh / oh));
            int uRow = (srcY >> 1) * urs, vRow = (srcY >> 1) * vrs, o = oy * ow;
            for (int ox = 0; ox < ow; ox++) {
                int sum = 0;
                for (int ky = 0; ky < t; ky++) {
                    int yRow = rys[oy * t + ky] * yrs;
                    for (int kx = 0; kx < t; kx++) sum += Y[yRow + cxs[ox * t + kx]] & 0xFF;
                }
                int y = (sum * inv) >> 16;
                int u = (U[uRow + cu[ox]] & 0xFF) - 128;
                int v = (V[vRow + cv[ox]] & 0xFF) - 128;
                int r = y + ((1436 * v) >> 10);
                int g = y - ((352 * u + 731 * v) >> 10);
                int b = y + ((1815 * u) >> 10);
                r = r < 0 ? 0 : (r > 255 ? 255 : r);
                g = g < 0 ? 0 : (g > 255 ? 255 : g);
                b = b < 0 ? 0 : (b > 255 ? 255 : b);
                px[o + ox] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        Bitmap bm = self.detBmp;
        if (bm == null || bm.getWidth() != ow || bm.getHeight() != oh || bm.isRecycled()) {
            bm = self.detBmp = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888);
        }
        bm.setPixels(px, 0, ow, 0, 0, ow, oh);
        return bm;
    }
}
