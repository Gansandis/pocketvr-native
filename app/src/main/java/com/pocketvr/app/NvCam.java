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
    public volatile long resultCapT = 0;           // time (ms, System.nanoTime based) the picture behind `result` was taken
    public volatile boolean filt = true;           // steady the points: little shake when the hand is slow, almost no delay when it is quick
    private float[] fx = new float[127], fd = new float[127];
    private boolean fOk = false;
    private long fT = 0;
    private int fN = 0;
    private android.graphics.Bitmap detBmp;
    private byte[] yB = new byte[0], uB = new byte[0], vB = new byte[0];
    private int[] colX = new int[0], colU = new int[0], colV = new int[0];
    private int mapW = -1, mapOw = -1, mapUps = -1, mapVps = -1;
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
                                    try {
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
                    float[] res = hands.detect(bmp, ts);          // the picture is reused for the next look, so it is not recycled here
                    if (res != null) {
                        if (res[0] >= 1 && filt) smooth(res, ts);
                        long tn = System.nanoTime() / 1000000L;
                        if (res[0] >= 1) {
                            if (tn - lastHandT > 1000) { sessionT = tn; lastTwoT = tn; }       // a new time with hands in view
                            if (res[0] >= 2) lastTwoT = tn;
                            lastHandT = tn;
                            // a second hand has not shown up for a while: keep looking for one hand only (much faster)
                            if (handsN >= 2 && !autoDown && tn - lastTwoT > 5000) autoDown = true;
                        } else if (tn - lastHandT > 1000 && autoDown) autoDown = false;          // hands were lowered: look for two again next time
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

    // One Euro filter on every point: the cut-off frequency rises with the speed of the point, so a still hand is calm
    // and a moving hand is followed with almost no delay. dt comes from the real picture times, not from when we happened to look.
    private static final float F_MIN = 3.0f, F_BETA = 7.0f, F_D = 1.5f;
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
        for (int i = 1; i < len; i++) {
            float v = (res[i] - fx[i]) / dt;
            fd[i] += (v - fd[i]) * aD;
            float fc = F_MIN + F_BETA * Math.abs(fd[i]);
            fx[i] += (res[i] - fx[i]) * lpA(dt, fc);
            res[i] = fx[i];
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
        if (self.mapW != w || self.mapOw != ow || self.mapUps != ups || self.mapVps != vps || self.colX.length != ow) {
            self.colX = new int[ow]; self.colU = new int[ow]; self.colV = new int[ow];
            for (int ox = 0; ox < ow; ox++) {
                int sx = Math.min(w - 1, ox * w / ow);
                self.colX[ox] = sx; self.colU[ox] = (sx >> 1) * ups; self.colV[ox] = (sx >> 1) * vps;
            }
            self.mapW = w; self.mapOw = ow; self.mapUps = ups; self.mapVps = vps;
        }
        final byte[] Y = self.yB, U = self.uB, V = self.vB;
        final int[] cx = self.colX, cu = self.colU, cv = self.colV;
        int[] px = self.pxBuf;
        if (px == null || px.length != ow * oh) px = self.pxBuf = new int[ow * oh];
        for (int oy = 0; oy < oh; oy++) {
            int srcY = Math.min(hh - 1, oy * hh / oh);
            int yRow = srcY * yrs, uRow = (srcY >> 1) * urs, vRow = (srcY >> 1) * vrs, o = oy * ow;
            for (int ox = 0; ox < ow; ox++) {
                int y = Y[yRow + cx[ox]] & 0xFF;
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
