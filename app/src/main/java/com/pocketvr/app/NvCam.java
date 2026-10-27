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
    public volatile float camFps = 0, detHz = 0, convMs = 0;
    public volatile float hFov = 1.15f;           // horizontal field of view of the camera, radians
    public volatile long detPaceMs = 0;           // shortest time between two looks for hands
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
    }

    public void stop() {
        running = false;
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

            st.setDefaultBufferSize(prev.getWidth(), prev.getHeight());
            previewSurface = new Surface(st);
            reader = ImageReader.newInstance(an.getWidth(), an.getHeight(), ImageFormat.YUV_420_888, 3);
            reader.setOnImageAvailableListener(r -> onImage(r), h);
            lis.onImageSize(prev.getWidth(), prev.getHeight(), rot180);

            Range<Integer> fps = null;
            Range<Integer>[] rs = cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (rs != null) {
                for (Range<Integer> r : rs) {
                    if (r.getUpper() == 30 && (fps == null || r.getLower() > fps.getLower())) fps = r;
                }
            }
            final Range<Integer> fpsF = fps;
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
                                    s.setRepeatingRequest(b.build(), null, h);
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
            if (camT0 == 0) camT0 = now;
            if (now - camT0 >= 1000) { camFps = camCount * 1000f / (now - camT0); camCount = 0; camT0 = now; }
            if (handsFailed || now - lastDet < detPaceMs || !detBusy.compareAndSet(false, true)) return;
            lastDet = now;
            long c0 = System.nanoTime();
            final Bitmap bmp = yuvToBitmap(img);
            convMs += ((System.nanoTime() - c0) / 1e6f - convMs) * 0.2f;
            final long ts = now;
            ExecutorService ex = detEx;
            if (ex == null) { detBusy.set(false); return; }
            ex.execute(() -> {
                try {
                    if (!handsOpened) {
                        handsOpened = hands.open(act.getApplicationContext(), true, 1);
                        if (!handsOpened) { handsFailed = true; lis.onError("hands: " + hands.error); return; }
                    }
                    float[] res = hands.detect(bmp, ts);
                    bmp.recycle();
                    if (res != null) {
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

    // the camera gives YUV; the hand model wants colour pixels. Every second pixel is used (half size is plenty for the model).
    private static Bitmap yuvToBitmap(Image img) {
        int w = img.getWidth(), hh = img.getHeight();
        Image.Plane[] p = img.getPlanes();
        ByteBuffer yb = p[0].getBuffer(), ub = p[1].getBuffer(), vb = p[2].getBuffer();
        int yrs = p[0].getRowStride(), urs = p[1].getRowStride(), ups = p[1].getPixelStride(), vrs = p[2].getRowStride(), vps = p[2].getPixelStride();
        int ow = w / 2, oh = hh / 2;
        int[] px = new int[ow * oh];
        for (int oy = 0; oy < oh; oy++) {
            int yRow = oy * 2 * yrs, uRow = oy * urs, vRow = oy * vrs;
            for (int ox = 0; ox < ow; ox++) {
                int y = yb.get(yRow + ox * 2) & 0xFF;
                int u = (ub.get(uRow + ox * ups) & 0xFF) - 128;
                int v = (vb.get(vRow + ox * vps) & 0xFF) - 128;
                int r = y + ((1436 * v) >> 10);
                int g = y - ((352 * u + 731 * v) >> 10);
                int b = y + ((1815 * u) >> 10);
                r = r < 0 ? 0 : (r > 255 ? 255 : r);
                g = g < 0 ? 0 : (g > 255 ? 255 : g);
                b = b < 0 ? 0 : (b > 255 ? 255 : b);
                px[oy * ow + ox] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return Bitmap.createBitmap(px, ow, oh, Bitmap.Config.ARGB_8888);
    }
}
