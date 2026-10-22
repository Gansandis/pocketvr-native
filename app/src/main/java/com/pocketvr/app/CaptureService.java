package com.pocketvr.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Creates a private virtual display that other apps can be launched onto, and turns whatever is drawn on it
 * into JPEG frames the web page can show inside VR. The real screen is never captured.
 */
public class CaptureService extends Service {
    public interface Ready { void done(boolean ok, String message); }

    public static volatile int displayId = -1;
    public static volatile byte[] jpeg = null;
    public static volatile boolean running = false;
    public static volatile Ready ready = null;

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler handler;
    private Bitmap bmp;
    private long lastFrame = 0;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat();
        try {
            int code = intent.getIntExtra("code", 0);
            Intent data;
            if (Build.VERSION.SDK_INT >= 33) data = intent.getParcelableExtra("data", Intent.class);
            else data = (Intent) intent.getParcelableExtra("data");
            int w = intent.getIntExtra("w", 1280), h = intent.getIntExtra("h", 720);

            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(code, data);
            thread = new HandlerThread("pvr-capture");
            thread.start();
            handler = new Handler(thread.getLooper());
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() { cleanup(); }
            }, handler);

            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(this::onFrame, handler);
            int f = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
            display = projection.createVirtualDisplay("PocketVR", w, h, 240, f, reader.getSurface(), null, handler);
            if (display == null) throw new IllegalStateException("createVirtualDisplay returned null");
            displayId = display.getDisplay().getDisplayId();
            running = true;
            Ready r = ready;
            if (r != null) r.done(true, String.valueOf(displayId));
        } catch (Throwable t) {
            Ready r = ready;
            if (r != null) r.done(false, String.valueOf(t));
            cleanup();
        }
        return START_NOT_STICKY;
    }

    private void onFrame(ImageReader r) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return;
            long now = SystemClock.uptimeMillis();
            if (now - lastFrame < 60) return;          // about 15 frames per second is plenty for a window
            lastFrame = now;
            Image.Plane p = img.getPlanes()[0];
            ByteBuffer buf = p.getBuffer();
            int w = img.getWidth(), h = img.getHeight();
            int bw = p.getRowStride() / p.getPixelStride();
            if (bmp == null || bmp.getWidth() != bw || bmp.getHeight() != h) bmp = Bitmap.createBitmap(bw, h, Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(buf);
            Bitmap src = bw == w ? bmp : Bitmap.createBitmap(bmp, 0, 0, w, h);
            ByteArrayOutputStream out = new ByteArrayOutputStream(80000);
            src.compress(Bitmap.CompressFormat.JPEG, 70, out);
            jpeg = out.toByteArray();
        } catch (Throwable ignored) {
        } finally {
            if (img != null) img.close();
        }
    }

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("pvr_capture", "PocketVR", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, "pvr_capture");
        } else {
            b = new Notification.Builder(this);
        }
        Notification n = b.setContentTitle("PocketVR").setContentText("กำลังแสดงหน้าจอแอปใน VR")
                .setSmallIcon(android.R.drawable.ic_menu_view).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(7, n);
    }

    private void cleanup() {
        running = false;
        displayId = -1;
        jpeg = null;
        try { if (display != null) display.release(); } catch (Throwable ignored) { }
        try { if (reader != null) reader.close(); } catch (Throwable ignored) { }
        try { if (projection != null) projection.stop(); } catch (Throwable ignored) { }
        try { if (thread != null) thread.quitSafely(); } catch (Throwable ignored) { }
        display = null; reader = null; projection = null; thread = null;
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (running) cleanup();
        super.onDestroy();
    }
}
