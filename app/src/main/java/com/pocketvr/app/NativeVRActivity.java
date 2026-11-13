package com.pocketvr.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Separate native VR screen: own camera, own hand model, own drawing. Tap top-left = split/full, top-right = lens strength, back = return. */
public class NativeVRActivity extends Activity implements VRRenderer.Host, NvCam.Listener {
    private GLSurfaceView gl;
    private VRRenderer rend;
    private NvCam cam;
    private NvHands hands;
    private TextView hud;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile String err = "";
    private boolean hudOn = true;
    private android.hardware.SensorManager sm;
    private android.hardware.Sensor rotSensor;
    private android.hardware.Sensor magSensor;
    public volatile boolean compass = true;                  // the compass sensor slowly corrects the turn sensor's sideways drift
    private final android.hardware.SensorEventListener sl = new android.hardware.SensorEventListener() {
        private final float[] r = new float[16], o = new float[16], gm = new float[16], out = new float[16];
        private boolean haveG = false, haveD = false;
        private double delta = 0;                            // radians: how far the turn sensor has drifted from the compass
        private long lastMagT = 0, prevT = 0;
        private double tgtF = 0, turnRate = 0, slow = 0, adj = 0, hRef = 0;
        private boolean lock = false, havePrev2 = false;
        private long prevT2 = 0;
        private final float[] prev2 = new float[16];
        private final float[] prev = new float[16];
        private boolean havePrev = false;
        private float[] mapped(float[] values) {
            android.hardware.SensorManager.getRotationMatrixFromVector(r, values);
            int rot = getWindowManager().getDefaultDisplay().getRotation();
            if (rot == android.view.Surface.ROTATION_90) { android.hardware.SensorManager.remapCoordinateSystem(r, android.hardware.SensorManager.AXIS_Y, android.hardware.SensorManager.AXIS_MINUS_X, o); return o; }
            if (rot == android.view.Surface.ROTATION_270) { android.hardware.SensorManager.remapCoordinateSystem(r, android.hardware.SensorManager.AXIS_MINUS_Y, android.hardware.SensorManager.AXIS_X, o); return o; }
            return r;
        }
        private double heading(float[] m) { return Math.atan2(-m[6], -m[2]); }   // where the back camera points, turned about "up"
        @Override public void onSensorChanged(android.hardware.SensorEvent e) {
            try {
                boolean isMag = magSensor != null && e.sensor.getType() == android.hardware.Sensor.TYPE_ROTATION_VECTOR && rotSensor != null && rotSensor.getType() == android.hardware.Sensor.TYPE_GAME_ROTATION_VECTOR;
                float[] m = mapped(e.values);
                if (isMag) {
                    if (!haveG || e.accuracy < 2) return;
                    if (Math.abs(m[10]) > 0.8f || Math.abs(gm[10]) > 0.8f) return;       // looking straight up/down: the turn angle is undefined
                    double tgt = heading(m) - heading(gm);
                    while (tgt > Math.PI) tgt -= 2 * Math.PI;
                    while (tgt < -Math.PI) tgt += 2 * Math.PI;
                    long now = e.timestamp;
                    double dt = lastMagT == 0 ? 0 : Math.min(0.5, (now - lastMagT) / 1e9);
                    lastMagT = now;
                    if (!haveD) { delta = tgt; tgtF = tgt; haveD = true; }
                    else if (compass) {
                        // the compass inside a VR box is disturbed by its magnets and the phone's own parts, so its reading wanders: it is smoothed over ~4 s, and the
                        // view is only nudged while you are really turning your head (the nudge hides in the motion), never while you look at a still screen
                        double d = tgt - tgtF;
                        while (d > Math.PI) d -= 2 * Math.PI;
                        while (d < -Math.PI) d += 2 * Math.PI;
                        tgtF += d * Math.min(1.0, dt / 4.0);
                        double err = tgtF - delta;
                        while (err > Math.PI) err -= 2 * Math.PI;
                        while (err < -Math.PI) err += 2 * Math.PI;
                        if (turnRate > 0.35 && Math.abs(err) > Math.toRadians(1.5)) {
                            double step = err * dt / 8.0;
                            double mx = Math.toRadians(0.4) * dt;
                            delta += Math.max(-mx, Math.min(mx, step));
                        }
                    }
                    return;
                }
                if (havePrev) {                                                 // how fast the head turns now (rad/s), smoothed
                    float tr = 0f;
                    for (int a = 0; a < 3; a++) for (int j = 0; j < 3; j++) tr += m[j * 4 + a] * prev[j * 4 + a];
                    double ang = Math.acos(Math.max(-1f, Math.min(1f, (tr - 1f) * 0.5f)));
                    double dts = (e.timestamp - prevT) / 1e9;
                    if (dts > 0 && dts < 0.1) turnRate = turnRate * 0.9 + (ang / dts) * 0.1;
                }
                System.arraycopy(m, 0, prev, 0, 16); prevT = e.timestamp; havePrev = true;
                System.arraycopy(m, 0, gm, 0, 16);
                haveG = true;
                // when the head is still (under ~1 degree a second for a while) the picture must not creep: whatever slow turn the sensor still reports is its drift,
                // so the facing is held where it was and the creep is taken out (it is the same sideways drift for every window, whatever its cause)
                {
                    double dts2 = prevT2 == 0 ? 0 : Math.min(0.2, (e.timestamp - prevT2) / 1e9);
                    prevT2 = e.timestamp;
                    if (havePrev2 && dts2 > 0) {
                        float tr2 = 0f;
                        for (int a = 0; a < 3; a++) for (int j = 0; j < 3; j++) tr2 += m[j * 4 + a] * prev2[j * 4 + a];
                        double ang2 = Math.acos(Math.max(-1f, Math.min(1f, (tr2 - 1f) * 0.5f)));
                        double al = 1.0 - Math.exp(-dts2 / 0.5);
                        slow += (ang2 / dts2 - slow) * al;
                    }
                    System.arraycopy(m, 0, prev2, 0, 16); havePrev2 = true;
                    boolean flat = Math.abs(m[10]) <= 0.8f;                               // the turn angle is undefined looking straight up or down
                    if (slow < (lock ? 0.035 : 0.02) && flat) {
                        double hh = heading(m);
                        double base = compass && haveD ? delta : 0.0;
                        if (!lock) { lock = true; hRef = hh + base + adj; }
                        adj = hRef - hh - base;
                        while (adj > Math.PI) adj -= 2 * Math.PI;
                        while (adj < -Math.PI) adj += 2 * Math.PI;
                    } else lock = false;
                }
                double yawT = (compass && haveD ? delta : 0.0) + adj;
                if (yawT != 0.0) {
                    float c = (float) Math.cos(yawT), s = (float) Math.sin(yawT);
                    System.arraycopy(m, 0, out, 0, 16);
                    for (int k = 0; k < 4; k++) { out[k] = c * m[k] - s * m[4 + k]; out[4 + k] = s * m[k] + c * m[4 + k]; }
                    rend.setRotation(out);
                } else rend.setRotation(m);
                if (motion != null && motion.enabled) motion.pushRot(e.timestamp, rend.rotCopy());
            } catch (Throwable ignored) { }
        }
        @Override public void onAccuracyChanged(android.hardware.Sensor s, int acc) { }
    };
    private final NvUi menu = new NvUi();
    private VRRenderer.Win wMenu, wClock, wStats, wKeys, wFruit, wSword, wSword3;
    private NvKeyboard kbd;
    private NvMedia media;
    private NvSettings settings;
    private VRRenderer.Win wSet;
    private NvVideo video;
    private VRRenderer.Win wMedia, wVideo;
    private final java.util.concurrent.ExecutorService bgIo = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final NvBrowser[] brs = new NvBrowser[3];             // up to three browser windows at once
    private final VRRenderer.Win[] bws = new VRRenderer.Win[3];
    private static final float[] BYAW = {0f, 52f, -52f};
    private NvMotion motion;
    private final long t0 = System.currentTimeMillis();
    private boolean swRunning = false;
    private long swStart = 0, swAcc = 0;
    // ---- heat and battery: read the battery temperature and the phone's own heat level; when it gets warm, draw / look for hands a bit less often ----
    private int eco = 1;                                    // 0 off, 1 automatic, 2 always light (45 fps), 3 always lighter (30 fps)
    private int ecoLevel = 0;                               // what is applied now: 0 none, 1 = 45 fps, 2 = 30 fps
    private float tempC = 0f;
    private int thermal = 0;
    private long ecoHotT = 0;

    private void updateEco(long nowMs) {
        try {
            android.content.Intent bi = registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            if (bi != null) tempC = bi.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
        } catch (Throwable ignored) { }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) thermal = ((android.os.PowerManager) getSystemService(POWER_SERVICE)).getCurrentThermalStatus();
        } catch (Throwable ignored) { }
        int want = (thermal >= 3 || tempC >= 44f) ? 2 : ((thermal >= 2 || tempC >= 41f) ? 1 : 0);
        int old = ecoLevel;
        if (eco == 0) ecoLevel = 0;
        else if (eco == 2) ecoLevel = 1;
        else if (eco == 3) ecoLevel = 2;
        else {
            if (want >= ecoLevel) ecoHotT = nowMs;
            if (want > ecoLevel) ecoLevel = want;
            else if (want < ecoLevel && nowMs - ecoHotT > 40000) ecoLevel = want;       // only after it has been cooler for 40 s
        }
        if (ecoLevel != old && menu != null) menu.toast(ecoLevel > old ? "เครื่องอุ่น: ลดความเร็วเพื่อประหยัดความร้อน" : "กลับเป็นความเร็วปกติ");
        rend.fpsCap = ecoLevel == 0 ? 0 : (ecoLevel == 1 ? 45 : 30);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (cam != null && rend != null) {
                float f = rend.fps;
                long p = cam.detPaceMs;
                if (f >= 40) p = Math.max(0, p - 20); else if (f > 0 && f < 28) p = Math.min(100, p + 15);
                updateEco(System.currentTimeMillis());
                updateStatus();
                if (ecoLevel == 1) p = Math.max(p, 25); else if (ecoLevel == 2) p = Math.max(p, 60);
                cam.detPaceMs = p;
                for (NvBrowser b : brs) if (b != null) b.poll();
                savePrefsIfChanged();
                if (motion != null && motion.enabled) menu.markDirty();      // so the numbers on the walk-around button stay fresh
                if (hudOn) {
                    hud.setText(Tr.t((System.currentTimeMillis() - t0 < 15000 ? "จีบนิ้วโป้ง+นิ้วกลางค้างไว้ = เปิด/ปิดเมนู\n" : "") + String.format("native v3.193 | วาด %.0f fps | กล้อง %.0f | ตรวจ %.0f Hz | โมเดล %.0f ms | แปลง %.0f ms | เว้น %d ms | นำหน้า %.0f ms | ตามมือ %s | 🌡 %.0f°C%s | ภาพ %d%%%s",
                            f, cam.camFps, cam.detHz, hands.inferMs, cam.convMs, p, rend.predMs, cam.flowOn ? (cam.flowOk ? "✓" : "–") : "ปิด", tempC, ecoLevel > 0 ? " (ประหยัด " + (ecoLevel == 1 ? "45" : "30") + ")" : "", Math.round(rend.sceneScale * 100f), err.isEmpty() ? "" : "\n! " + err) + (rend.placeDbg.isEmpty() ? "" : "\n" + rend.placeDbg) + (rend.skel >= 2 ? "\n" + rend.segDbg : "") + (voice != null && voice.isOn() ? "\n🎙 " + voiceMsg + (heard.isEmpty() ? "" : "\nได้ยิน: " + heard) : "")));
                } else hud.setText("");
            }
            ui.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hands = new NvHands();
        cam = new NvCam(this, hands, this);
        cam.detPaceMs = 0;
        rend = new VRRenderer(this, cam);
        rend.onKeysBtn = w -> {                                              // the keyboard button of the bar under every window: it types into THAT window
            if (wKeys.visible) { kbd.sink = null; rend.setVisible(wKeys, false, 0f, 0f); return; }
            if (w.c instanceof NvBrowser) { NvBrowser nb = (NvBrowser) w.c; if (nb.kbHost != null) { nb.kbHost.showKeyboard(nb.pageSink); return; } }
            if (w.c instanceof NvAppWin) { NvAppWin na = (NvAppWin) w.c; if (na.kbHost != null) { na.kbHost.showKeyboard(na.sink); return; } }
            rend.toggle(wKeys, 0f, -24f);
        };
        motion = new NvMotion();
        motion.rot = () -> rend.rotCopy();
        cam.motion = motion;
        rend.motion = motion;
        loadPrefs();
        try { NvShizuku.init(); } catch (Throwable ignored) { }
        rend.ui = menu;
        wMenu = rend.addWindow(menu, 0.95f);      // starts hidden: pinch thumb + middle finger and hold to bring it up
        wMenu.noGhost = true; wMenu.anchor = true;                      // new windows prefer to sit right next to the main menu
        buildWindows();
        buildMenu();
        final NvSplash splash = new NvSplash();                          // the start-up animation: a window that shows for a few seconds and goes
        final VRRenderer.Win wSplash = rend.addWindow(splash, 0.9f); wSplash.noGhost = true; wSplash.noBar = true;
        rend.toggle(wSplash, 0f, 0f);
        ui.postDelayed(() -> { if (wSplash.visible) rend.toggle(wSplash, 0f, 0f); }, NvSplash.TOTAL_MS + 150);
        for (int i = 0; i < 7; i++) winApply(i);
        gl = new GLSurfaceView(this);
        gl.setEGLContextClientVersion(2);
        gl.setPreserveEGLContextOnPause(false);
        gl.setRenderer(rend);
        gl.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        FrameLayout root = new FrameLayout(this);
        root.addView(gl);
        hud = new TextView(this);
        hud.setTextColor(Color.WHITE);
        hud.setTextSize(11);
        hud.setBackgroundColor(0x66000000);
        hud.setPadding(12, 4, 12, 4);
        root.addView(hud, new FrameLayout.LayoutParams(-2, -2));
        setContentView(root);
        root.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                float x = e.getX() / v.getWidth(), y = e.getY() / v.getHeight();
                if (y < 0.25f) {
                    if (x < 0.33f) rend.stereo = !rend.stereo;
                    else if (x > 0.67f) rend.lens = (rend.lens + 1) % 4;
                    else hudOn = !hudOn;
                }
            }
            return true;
        });
        initVoice();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 7);
    }

    // small floating windows (start hidden; opened from the menu)
    private void buildWindows() {
        final java.text.SimpleDateFormat tf = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US);
        final java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("EEE d MMM", new java.util.Locale("th"));
        final NvPanel clock = new NvPanel(600, 300, "นาฬิกา / จับเวลา");
        clock.refreshMs = 200;
        clock.body = (cv, p, w, h) -> {
            float u = h / 300f;
            p.setShader(null);
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setTextAlign(android.graphics.Paint.Align.CENTER);
            java.util.Date now = new java.util.Date();
            p.setColor(0xFFFFFFFF);
            p.setTextSize(66 * u);
            cv.drawText(tf.format(now), w / 2f, 112 * u, p);
            p.setColor(0xFFB8BEC8);
            p.setTextSize(20 * u);
            cv.drawText(df.format(now), w / 2f, 142 * u, p);
            long e = swAcc + (swRunning ? System.currentTimeMillis() - swStart : 0);
            p.setColor(0xFF9EE7F2);
            p.setTextSize(32 * u);
            cv.drawText(String.format(java.util.Locale.US, "%02d:%02d.%d", e / 60000, (e / 1000) % 60, (e / 100) % 10), w / 2f, 182 * u, p);
        };
        clock.add(new NvUi.Btn("", () -> swRunning ? "⏸ หยุด" : "▶ เริ่ม", null, () -> swRunning, 0, 0, () -> {
            long n = System.currentTimeMillis();
            if (swRunning) { swAcc += n - swStart; swRunning = false; } else { swStart = n; swRunning = true; }
        }), 40, 198, 250, 52);
        clock.add(new NvUi.Btn("", () -> "↺ รีเซ็ต", null, null, 0, 0, () -> { swRunning = false; swAcc = 0; }), 310, 198, 250, 52);
        wClock = rend.addWindow(clock, 0.55f);
        clock.onClose = () -> rend.setVisible(wClock, false, 0f, 0f);
        wClock.title = "นาฬิกา"; wClock.onClose = clock.onClose;

        final NvPanel stats = new NvPanel(560, 300, "สถานะระบบ");
        stats.refreshMs = 500;
        stats.body = (cv, p, w, h) -> {
            float u = h / 300f;
            p.setShader(null);
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setTextAlign(android.graphics.Paint.Align.LEFT);
            p.setColor(0xFFFFFFFF);
            p.setTextSize(21 * u);
            String[] ln = {
                String.format("วาด %.0f fps   กล้อง %.0f fps", rend.fps, cam.camFps),
                String.format("ตรวจมือ %.0f ครั้ง/วิ   โมเดล %.0f ms", cam.detHz, hands.inferMs),
                String.format("แปลงภาพ %.0f ms   เว้น %d ms", cam.convMs, cam.detPaceMs),
                String.format("คาดการณ์นำหน้า %.0f ms", rend.predMs),
                err.isEmpty() ? "ไม่มีข้อผิดพลาด" : "! " + err};
            for (int i = 0; i < ln.length; i++) cv.drawText(ln[i], 30 * u, (86 + i * 36) * u, p);
        };
        wStats = rend.addWindow(stats, 0.55f);
        stats.onClose = () -> rend.setVisible(wStats, false, 0f, 0f);
        wStats.title = "สถานะ"; wStats.onClose = stats.onClose;

        final NvFruit fruitGame = new NvFruit();
        wFruit = rend.addWindow(fruitGame, 1.0f);
        fruitGame.onClose = () -> rend.setVisible(wFruit, false, 0f, 0f);
        wFruit.title = "เกมฟันผลไม้"; wFruit.onClose = fruitGame.onClose;
        wFruit.game = true;

        final NvSword3D sword3 = new NvSword3D();
        wSword3 = rend.addWindow(sword3, 0.42f);
        sword3.onClose = () -> rend.setVisible(wSword3, false, 0f, 0f);
        wSword3.title = "เกมฟันดาบ 3D"; wSword3.onClose = sword3.onClose;
        rend.world3 = sword3; rend.world3Win = wSword3; wSword3.game = true;

        final NvKeyboard kb = new NvKeyboard();
        kb.copier = s -> runOnUiThread(() -> {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("pocketvr", s));
            } catch (Throwable ignored) { }
        });
        wKeys = rend.addWindow(kb, 0.80f); wKeys.noGhost = true;
        kbd = kb;
        kb.onClose = () -> { kb.sink = null; rend.setVisible(wKeys, false, 0f, 0f); };
        wKeys.noBar = true; wKeys.lowDock = true;

        settings = new NvSettings();
        wSet = rend.addWindow(settings, 1.5f);
        rend.onPalmSettings = () -> rend.toggle(wSet, 0f, 5f);
        wSet.showInCinema = true;
        settings.onClose = () -> rend.setVisible(wSet, false, 0f, 0f);
        wSet.title = "ตั้งค่า"; wSet.onClose = settings.onClose;

        // gallery, 360 viewer and cinema
        video = new NvVideo(this);
        wVideo = rend.addWindow(video, 1.7f); wVideo.noGhost = true;
        wVideo.showInCinema = true; wKeys.showInCinema = true;
        video.onClose = () -> { video.release(); rend.setVisible(wVideo, false, 0f, 0f); rend.cinema = false; };
        wVideo.noBar = true;
        media = new NvMedia(this);
        wMedia = rend.addWindow(media, 1.3f);
        wMedia.showInCinema = true;
        media.onClose = () -> { rend.setPano(null); media.panoOn = false; rend.setVisible(wMedia, false, 0f, 0f); };
        wMedia.title = "แกลเลอรี"; wMedia.onClose = media.onClose;
        media.host = new NvMedia.Host() {
            @Override public void onPano(final android.net.Uri u) {
                if (rend.panoActive()) { rend.setPano(null); media.panoOn = false; return; }
                media.panoOn = true;                                       // button shows "exit" while it loads
                bgIo.execute(() -> {
                    android.graphics.Bitmap b = media.decode(u, 4096);
                    if (b == null) { media.panoOn = false; return; }
                    rend.setPano(b);
                });
            }
            @Override public void onVideo(android.net.Uri u) {
                rend.setPano(null); media.panoOn = false;
                video.open(u);
                rend.cinema = true;                                       // the room goes dark: only the screen (and the menu) stay
                rend.setVisible(wVideo, true, 0f, 4f);
                rend.recenter = true;
                rend.setVisible(wMedia, false, 0f, 0f);                   // the cinema takes the place of the gallery
            }
        };
    }

    // ---- permission to read the phone's pictures and videos (asked once, with the phone in the hand) ----
    private String[] mediaPerms() {
        return android.os.Build.VERSION.SDK_INT >= 33
                ? new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO}
                : new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    private boolean hasMediaPerm() {
        for (String s : mediaPerms()) if (checkSelfPermission(s) == PackageManager.PERMISSION_GRANTED) return true;   // "some photos only" on newer Android still counts
        return false;
    }

    private void openGallery() {
        if (!hasMediaPerm()) {
            menu.toast("ถอดแว่นแล้วกดอนุญาตบนหน้าจอ");
            runOnUiThread(() -> requestPermissions(mediaPerms(), 77));
            return;
        }
        if (!media.isLoaded()) media.reload();
        rend.setVisible(wMedia, true, 0f, 0f);
    }

    private NvBrowser browserSlot(final int i) {
        if (brs[i] != null) return brs[i];
        final NvBrowser b = new NvBrowser(this, ui);
        b.kbHost = s -> { kbd.sink = s; kbd.clear(); if (!wKeys.visible) rend.setVisible(wKeys, true, 0f, -28f); };
        final VRRenderer.Win w = rend.addWindow(b, 1.25f);
        w.showInCinema = true;
        bws[i] = w; winApply(7 + i);
        b.onClose = () -> { b.close(); rend.setVisible(w, false, 0f, 0f); if (kbd != null) kbd.sink = null; };
        w.title = "เบราว์เซอร์"; w.titleFn = () -> b.pageTitle(); w.onClose = b.onClose; w.onBack = () -> b.nav("back");
        b.onNewWindow = () -> openBrowser(-1);
        brs[i] = b; bws[i] = w;
        return b;
    }

    /** show browser window i (or the first free one when i < 0) */
    private void openBrowser(int want) {
        int i = want;
        if (i < 0) { for (int k = 0; k < brs.length; k++) if (bws[k] == null || !bws[k].visible) { i = k; break; } }
        if (i < 0) { menu.toast("เปิดเบราว์เซอร์ได้สูงสุด 3 หน้าต่าง"); return; }
        NvBrowser b = browserSlot(i);
        rend.setVisible(bws[i], true, BYAW[i], 4f);
        if (!b.isOpen()) b.open("https://www.google.com");
        b.setActive(true);
    }

    // Android does not let a normal app put another app's screen into its own window, so for the apps that have a website version
    // the floating browser opens that website instead. (package, address, name, icon)
    private static final String[][] WEB = {
        {"com.google.android.youtube", "https://m.youtube.com", "YouTube", "▶"},
        {"com.facebook.katana", "https://m.facebook.com", "Facebook", "📘"},
        {"com.zhiliaoapp.musically", "https://www.tiktok.com", "TikTok", "🎵"},
        {"com.ss.android.ugc.trill", "https://www.tiktok.com", "TikTok", "🎵"},
        {"com.instagram.android", "https://www.instagram.com", "Instagram", "📷"},
        {"com.twitter.android", "https://x.com", "X (Twitter)", "🐦"},
        {"com.netflix.mediaclient", "https://www.netflix.com", "Netflix", "🎬"},
        {"com.spotify.music", "https://open.spotify.com", "Spotify", "🎧"},
        {"com.google.android.apps.maps", "https://www.google.com/maps", "แผนที่", "🗺"},
        {"com.google.android.gm", "https://mail.google.com", "Gmail", "✉"},
        {"com.discord", "https://discord.com/app", "Discord", "💬"},
        {"com.whatsapp", "https://web.whatsapp.com", "WhatsApp", "🟢"},
        {"org.telegram.messenger", "https://web.telegram.org", "Telegram", "✈"},
        {"com.reddit.frontpage", "https://www.reddit.com", "Reddit", "👽"},
        {"tv.twitch.android.app", "https://www.twitch.tv", "Twitch", "🎮"},
        {"com.shopee.th", "https://shopee.co.th", "Shopee", "🛍"},
        {"com.lazada.android", "https://www.lazada.co.th", "Lazada", "🛒"},
        {"com.google.android.apps.photos", "https://photos.google.com", "Google Photos", "🖼"},
        {"com.android.chrome", "https://www.google.com", "Chrome", "🌐"},
    };
    private static String webFor(String pkg) { for (String[] w : WEB) if (w[0].equals(pkg)) return w[1]; return null; }

    /** open a website in a floating browser window (a free one if there is one) */
    private void openWebFloating(String url) {
        int i = -1;
        for (int k = 0; k < brs.length; k++) if (bws[k] == null || !bws[k].visible) { i = k; break; }
        if (i < 0) i = 0;
        NvBrowser b = browserSlot(i);
        rend.setVisible(bws[i], true, BYAW[i], 4f);
        if (!b.isOpen()) b.open(url); else b.navigate(url);
        b.setActive(true);
        menu.toast("เปิดหน้าต่างลอยแล้ว");
    }

    // ---- real apps in floating windows (through Shizuku) ----
    private final NvAppWin[] aps = new NvAppWin[2];
    private final VRRenderer.Win[] aws = new VRRenderer.Win[2];
    private static final float[] AYAW = {0f, 50f};

    private NvAppWin appSlot(final int i) {
        if (aps[i] != null) return aps[i];
        final NvAppWin a = new NvAppWin(this, ui);
        a.kbHost = s -> { kbd.sink = s; kbd.clear(); if (!wKeys.visible) rend.setVisible(wKeys, true, 0f, -28f); };
        final VRRenderer.Win w = rend.addWindow(a, 1.25f);
        w.showInCinema = true;
        aws[i] = w; winApply(10 + i);
        a.onClose = () -> { a.close(); rend.setVisible(w, false, 0f, 0f); if (kbd != null) kbd.sink = null; };
        w.title = "แอป"; w.onClose = a.onClose;
        aps[i] = a;
        return a;
    }

    /** open an installed app inside a floating window; false = Shizuku is not ready, nothing was done */
    private boolean openRealApp(String pkg, String comp, String name) {
        if (!NvShizuku.ready()) return false;
        int i = -1;
        for (int k = 0; k < aps.length; k++) if (aps[k] != null && aps[k].isOpen() && pkg.equals(aps[k].pkg())) { i = k; break; }      // already open: bring it up again
        if (i < 0) for (int k = 0; k < aps.length; k++) if (aws[k] == null || !aws[k].visible) { i = k; break; }
        if (i < 0) { menu.toast("เปิดแอปลอยได้สูงสุด 2 หน้าต่าง"); return true; }
        NvAppWin a = appSlot(i);
        rend.setVisible(aws[i], true, AYAW[i], 0f);
        a.open(pkg, comp, name);
        a.setActive(true);
        menu.toast("กำลังเปิด " + name);
        return true;
    }

    private int browsersShown() { int n = 0; for (VRRenderer.Win w : bws) if (w != null && w.visible) n++; return n; }

    // ================= settings window =================
    private void refreshGlass() {
        menu.markDirty();
        for (VRRenderer.Win w : rend.wins) w.c.markDirty();
    }

    private void buildSettings() {
        final NvSettings s = settings;
        s.cat("","Hands");
        s.toggle("✋", "คาดการณ์มือ (ตามมือทัน)", () -> rend.predict, () -> rend.predict = !rend.predict);
        s.cycle("🔍", "ความละเอียดตรวจมือ", () -> cam.detW + " px" + (cam.detW <= 320 ? " (เร็ว)" : cam.detW >= 640 ? " (แม่นสุด)" : ""), () -> cam.detW = cam.detW <= 320 ? 480 : (cam.detW <= 480 ? 640 : 320));
        s.cycle("🙌", "จำนวนมือที่ตรวจ", () -> cam.handsN == 2 ? "2 ข้าง (เปลี่ยนเป็น 1 เองถ้าไม่ใช้)" : "ข้างเดียว (เร็ว)", () -> cam.handsN = cam.handsN == 2 ? 1 : 2);
        s.toggle("🎞", "ตามมือต่อระหว่างโมเดล (ลดหน่วง)", () -> cam.flowOn, () -> cam.flowOn = !cam.flowOn);
        s.toggle("🤏", "ตามปลายนิ้วต่อระหว่างโมเดล (ท่าจีบไวขึ้น)", () -> cam.tipsOn, () -> cam.tipsOn = !cam.tipsOn);
        s.toggle("🙋", "ตรวจเฉพาะมือผู้ใช้ (ตัดมือคนอื่นที่อยู่ไกลออกไป)", () -> hands.onlyMine, () -> hands.onlyMine = !hands.onlyMine);
        s.cycle("", "ระยะมือที่รับ", () -> String.format("%.1f ม.", hands.maxDistM), () -> hands.maxDistM = hands.maxDistM < 0.7f ? 0.85f : hands.maxDistM < 0.9f ? 1.1f : hands.maxDistM < 1.2f ? 1.5f : 0.6f);
        s.toggle("", "ล็อกมือผู้ใช้ (ตามมือที่อยู่ในภาพต่อเนื่อง 1 วินาที มือใหม่จากที่อื่นไม่รับ)", () -> hands.lockMine, () -> hands.lockMine = !hands.lockMine);
        s.toggle("🖐", "เมนูบนฝ่ามือซ้าย (แบมือซ้าย จิ้มไอคอนด้วยนิ้วมือขวา)", () -> rend.palmMenu, () -> rend.palmMenu = !rend.palmMenu);
        s.cycle("🫱", "มือหลัก (ตอนยกสองมือ)", () -> rend.mainHand == 0 ? "มือซ้าย" : "มือขวา", () -> rend.mainHand = rend.mainHand == 0 ? 1 : 0);

        s.cat("","Performance");
        s.cycle("🌡", "ความร้อน / ประหยัดพลังงาน", () -> (eco == 0 ? "ปิด" : eco == 1 ? "อัตโนมัติ" : eco == 2 ? "ประหยัด 45 fps" : "ประหยัดมาก 30 fps") + " • " + Math.round(tempC) + "°C" + (ecoLevel > 0 ? " (กำลังลดความเร็ว)" : ""), () -> eco = (eco + 1) % 4);
        s.toggle("🖼", "ปรับความละเอียดภาพอัตโนมัติ (ให้ลื่น)", () -> rend.autoRes, () -> rend.autoRes = !rend.autoRes);
        s.cycle("🧠", "ตัวประมวลผลตรวจมือ", () -> cam.handsGpu ? "การ์ดจอ GPU (ค่าเดิม)" : "CPU (ลองถ้าภาพแลค)", () -> cam.handsGpu = !cam.handsGpu);

        s.cat("","Display");
        s.cycle("", "มองในที่มืด", () -> cam.night == 0 ? "ปิด" : cam.night == 1 ? "กลาง" : "สูง", () -> { cam.setNight((cam.night + 1) % 3); rend.lift = cam.night == 0 ? 0f : cam.night == 1 ? 0.6f : 1.0f; });
        s.step("🪟", "ความทึบของแผง", () -> Math.round(NvUi.glass * 100f / 255f) + " %",
                () -> { NvUi.glass = Math.max(0x40, NvUi.glass - 0x10); refreshGlass(); },
                () -> { NvUi.glass = Math.min(0xFF, NvUi.glass + 0x10); refreshGlass(); });
        s.cycle("🖱", "เคอร์เซอร์", () -> rend.cursorSz == 0 ? "เล็กจาง" : rend.cursorSz == 1 ? "กลาง" : "ใหญ่", () -> rend.cursorSz = (rend.cursorSz + 1) % 3);
        s.cycle("🦴", "โครงมือ", () -> rend.skel == 0 ? "ปิด" : rend.skel == 1 ? "เส้นโครง" : rend.skel == 2 ? "มือกระจกใส (เส้นขอบ + ฟิล์ม)" : rend.skel == 3 ? "เส้นขอบเรืองแสง" : "มือจริงทะลุจอ", () -> rend.skel = (rend.skel + 1) % 5);
        s.toggle("📊", "ตัวเลขบนจอ", () -> hudOn, () -> hudOn = !hudOn);
        s.step("✨", "ความคมภาพกล้อง", () -> rend.sharpen <= 0.01f ? "ปิด (เร็วสุด)" : Math.round(rend.sharpen * 100f) + " %",
                () -> rend.sharpen = Math.max(0f, Math.round((rend.sharpen - 0.1f) * 10f) / 10f),
                () -> rend.sharpen = Math.min(1f, Math.round((rend.sharpen + 0.1f) * 10f) / 10f));
        s.toggle("🎈", "หน้าต่างลอยเบาๆ", () -> rend.floaty, () -> rend.floaty = !rend.floaty);

        s.cat("","VR");
        s.toggle("🥽", "แยกจอซ้าย-ขวา", () -> rend.stereo, () -> rend.stereo = !rend.stereo);
        s.cycle("👓", "เลนส์ (ชดเชยความโค้ง)", () -> rend.lens == 0 ? "ปิด" : rend.lens == 1 ? "เบา" : rend.lens == 2 ? "กลาง" : "แรง", () -> rend.lens = (rend.lens + 1) % 4);
        s.step("🔭", "มุมมองต่อตา (ให้ตรงกับเลนส์แว่น)", () -> Math.round(rend.vrFov) + " °",
                () -> rend.vrFov = Math.max(50f, rend.vrFov - 5f),
                () -> rend.vrFov = Math.min(110f, rend.vrFov + 5f));
        s.step("🌈", "แก้ขอบสีแดง/ฟ้า (สีเหลื่อมจากเลนส์)", () -> String.format("%+.3f", rend.chroma),
                () -> rend.chroma = Math.max(-0.06f, Math.round((rend.chroma - 0.005f) * 1000f) / 1000f),
                () -> rend.chroma = Math.min(0.06f, Math.round((rend.chroma + 0.005f) * 1000f) / 1000f));
        s.toggle("🦴", "จำลองการหมุนรอบคอ (ให้หน้าต่างมีมิติลึกตอนหันหัว)", () -> rend.neck, () -> rend.neck = !rend.neck);
        s.toggle("🎯", "ชดเชยภาพกล้องตามการหันหัว (ลดหน่วง)", () -> rend.reproj, () -> rend.reproj = !rend.reproj);
        s.step("🕐", "ความหน่วงของภาพกล้อง (ปรับให้ภาพนิ่งตอนหันหัว)", () -> rend.camLagMeasured > 0f ? "อัตโนมัติ " + Math.round(rend.camLagMeasured) + " ms" : rend.camLagMs + " ms",
                () -> rend.camLagMs = Math.max(0, rend.camLagMs - 10),
                () -> rend.camLagMs = Math.min(250, rend.camLagMs + 10));
        s.step("👁", "แก้ภาพซ้อน (ขยับเข้าหาจมูก)", () -> String.format("%.2f", rend.eyeShift),
                () -> rend.eyeShift = Math.max(-0.20f, Math.round((rend.eyeShift - 0.03f) * 100f) / 100f),
                () -> rend.eyeShift = Math.min(0.40f, Math.round((rend.eyeShift + 0.03f) * 100f) / 100f));

        s.cat("","Position");
        s.toggle("🧭", "เข็มทิศช่วยแก้ทิศ (กันจอไหล)", () -> compass, () -> compass = !compass);
        s.action("📍", "จัดหน้าต่างมาตรงหน้า", "จัดกลางใหม่", () -> rend.recenter = true);
        s.cycle("🚶", "เดินรอบได้", () -> motion.enabled ? (motion.err.isEmpty() ? String.format("เปิด %.1f,%.1f ม.", motion.px, motion.py) : "ผิดพลาด") : "ปิดอยู่", () -> {
            motion.enabled = !motion.enabled;
            motion.reset();
            for (VRRenderer.Win w : rend.wins) w.placeReq = true;
        });
        s.step("📏", "ขนาดห้อง (ความไวเดิน)", () -> String.format("ประมาณ %.1f ม.", motion.depthM),
                () -> motion.depthM = Math.max(1.5f, motion.depthM - 0.5f),
                () -> motion.depthM = Math.min(8f, motion.depthM + 0.5f));
        s.cycle("📐", "ปรับเทียบการเดิน", () -> motion.calibrating ? String.format("วัดได้ %.2f ม.", motion.calWalked()) : "เดิน 1 ม. แล้วกด", () -> {
            if (!motion.enabled) return;
            if (!motion.calibrating) motion.calStart(); else motion.calEnd(1.0f);
        });
        s.step("⏱", "หน่วงภาพกล้องตอนเดิน", () -> motion.tsReal ? "ใช้เวลาจากภาพ" : motion.lagMs + " ms",
                () -> motion.lagMs = Math.max(20, motion.lagMs - 20),
                () -> motion.lagMs = Math.min(200, motion.lagMs + 20));

        s.cat("","System");
        s.toggle("🎙", "สั่งด้วยเสียง (เฮ้ พ็อกเก็ต)", () -> voice != null && voice.isOn(), () -> toggleVoice());
        s.cycle("🌐", "ภาษาที่ฟัง", () -> voiceLangMode == 0 ? "ตามภาษาแอป" : voiceLangMode == 1 ? "ไทย" : voiceLangMode == 2 ? "อังกฤษ (English)" : "ไทย+อังกฤษ (สลับกัน)", () -> { voiceLangMode = (voiceLangMode + 1) % 4; if (voice != null && voice.isOn()) { voice.stop(); voice.start(); } });
        s.cycle("", "ภาษา", () -> Tr.thai ? "ไทย" : "English", () -> { Tr.setThai(!Tr.thai); refreshGlass(); settings.markDirty(); });
        s.cycle("🔌", "Shizuku (เปิดแอปจริงแบบลอย)", () -> NvShizuku.statusText() + (NvShizuku.ready() ? " | " + NvShizuku.inputInfo() : ""), () -> {
            NvShizuku.request();
            if (NvShizuku.ready()) { NvShizuku.restartInput(getPackageCodePath()); menu.toast("ตัวช่วยสัมผัสเร็ว: เริ่มใหม่"); }
            else menu.toast("Shizuku: " + NvShizuku.statusText());
        });
        s.action("♻", "คืนค่าตั้งต้นทั้งหมด", "รีเซ็ต", () -> { applyPrefs(defaults); resetWins(); refreshGlass(); savePrefsNow(); menu.toast("คืนค่าตั้งต้นแล้ว"); });
        s.action("🕸", "เวอร์ชันเว็บ (สำรอง)", "เปิด", () -> runOnUiThread(() -> {
            try { startActivity(new android.content.Intent(this, MainActivity.class)); } catch (Throwable t) { err = "web: " + t; }
            finish();
        }));
        s.action("⏻", "ออกจากแอป", "ออก", () -> runOnUiThread(() -> finishAffinity()));

        s.build();
    }

    // ================= saved settings (kept when the app is closed) =================
    private static final String[] PK = {"sel", "pokeS", "predict", "detW", "floaty", "glass", "cursorSz", "skel", "hud", "sharpen", "stereo", "lens", "eyeShift", "compass", "depthM", "lagMs", "handsN", "vrFov", "reproj", "camLagMs", "mainHand", "chroma", "handsGpu", "neck4", "flow", "eco", "autoRes", "tips", "pmenu", "lang", "lock", "range", "night", "scene"};
    private double[] defaults;
    private double[] lastSaved;

    private double[] snapPrefs() {
        return new double[]{rend.sel, rend.pokeS, rend.predict ? 1 : 0, cam.detW, rend.floaty ? 1 : 0, NvUi.glass, rend.cursorSz, rend.skel, hudOn ? 1 : 0,
                rend.sharpen, rend.stereo ? 1 : 0, rend.lens, rend.eyeShift, compass ? 1 : 0, motion.depthM, motion.lagMs, cam.handsN, rend.vrFov, rend.reproj ? 1 : 0, rend.camLagMs, rend.mainHand, rend.chroma, cam.handsGpu ? 1 : 0, rend.neck ? 1 : 0, cam.flowOn ? 1 : 0, eco, rend.autoRes ? 1 : 0, cam.tipsOn ? 1 : 0, rend.palmMenu ? 1 : 0, Tr.thai ? 1 : 0, hands.lockMine ? 1 : 0, hands.maxDistM, cam.night, rend.envScene};
    }

    private void applyPrefs(double[] v) {
        rend.sel = (int) v[0]; rend.pokeS = (int) v[1]; rend.predict = v[2] > 0.5; cam.detW = (int) v[3]; rend.floaty = v[4] > 0.5;
        NvUi.glass = (int) v[5]; rend.cursorSz = (int) v[6]; rend.skel = (int) v[7]; hudOn = v[8] > 0.5;
        rend.sharpen = (float) v[9]; rend.stereo = v[10] > 0.5; rend.lens = (int) v[11]; rend.eyeShift = (float) v[12]; compass = v[13] > 0.5;
        motion.depthM = (float) v[14]; motion.lagMs = (int) v[15]; cam.handsN = Math.max(1, Math.min(2, (int) v[16])); rend.vrFov = (float) Math.max(50, Math.min(110, v[17]));
        rend.reproj = v[18] > 0.5; rend.camLagMs = Math.max(0, Math.min(250, (int) v[19])); rend.mainHand = v[20] > 0.5 ? 1 : 0; rend.chroma = (float) Math.max(-0.06, Math.min(0.06, v[21])); cam.handsGpu = v[22] > 0.5; rend.neck = v[23] > 0.5; cam.flowOn = v[24] > 0.5; eco = Math.max(0, Math.min(3, (int) v[25])); rend.autoRes = v[26] > 0.5; cam.tipsOn = v[27] > 0.5; rend.palmMenu = v[28] > 0.5; Tr.setThai(v.length > 29 && v[29] > 0.5); if (v.length > 31) { hands.lockMine = v[30] > 0.5; hands.maxDistM = (float) Math.max(0.4, Math.min(2.0, v[31])); } if (v.length > 32) { cam.night = Math.max(0, Math.min(2, (int) v[32])); rend.lift = cam.night == 0 ? 0f : cam.night == 1 ? 0.6f : 1.0f; } if (v.length > 33) rend.envScene = Math.max(0, Math.min(1, (int) v[33]));
    }

    // ----- where the windows were left (turn, height, size, distance) -----
    private final float[][] winSv = new float[12][5];
    private double[] lastWin;
    private VRRenderer.Win winAt(int i) {
        switch (i) { case 0: return wMenu; case 1: return wClock; case 2: return wStats; case 3: return wKeys; case 4: return wSet; case 5: return wVideo; case 6: return wMedia; case 10: return aws[0]; case 11: return aws[1]; default: return i - 7 < bws.length ? bws[i - 7] : null; }
    }
    private void winApply(int i) {
        // window places are not kept any more: every window that opens slides in next to the others (settings are still kept)
    }
    private double[] winSnap() {
        return new double[60];                                                        // nothing about window places is kept
    }
    private void resetWins() {
        for (int i = 0; i < 12; i++) {
            java.util.Arrays.fill(winSv[i], 0f);
            VRRenderer.Win w = winAt(i);
            if (w != null) { w.hasSaved = false; w.placeDist = 0f; w.wantScale = 0f; }
        }
    }

    private void loadPrefs() {
        defaults = snapPrefs();
        try {
            android.content.SharedPreferences.Editor ed0 = getSharedPreferences("pocketvr_native", MODE_PRIVATE).edit();      // forget the places saved by older versions
            for (int i = 0; i < 12; i++) for (int k = 0; k < 5; k++) ed0.remove("w" + i + "_" + k);
            ed0.apply();
        } catch (Throwable ignored) { }
        lastWin = winSnap();
        try {
            android.content.SharedPreferences sp = getSharedPreferences("pocketvr_native", MODE_PRIVATE);
            double[] v = defaults.clone();
            for (int i = 0; i < PK.length; i++) if (sp.contains(PK[i])) v[i] = sp.getFloat(PK[i], (float) v[i]);
            applyPrefs(v);
        } catch (Throwable ignored) { }
        lastSaved = snapPrefs();
    }

    private void savePrefsNow() {
        try {
            double[] v = snapPrefs();
            android.content.SharedPreferences.Editor ed = getSharedPreferences("pocketvr_native", MODE_PRIVATE).edit();
            for (int i = 0; i < PK.length; i++) ed.putFloat(PK[i], (float) v[i]);
            ed.apply();
            lastSaved = v; lastWin = winSnap();
        } catch (Throwable ignored) { }
    }

    private void savePrefsIfChanged() {
        if (lastSaved == null || !java.util.Arrays.equals(lastSaved, snapPrefs()) || lastWin == null || !java.util.Arrays.equals(lastWin, winSnap())) savePrefsNow();
    }


    // ---- voice commands ("เฮ้ พ็อกเก็ต ...") ----
    private NvVoice voice;
    private int voiceLangMode = 0;                                   // 0 follows the app language, 1 Thai, 2 English, 3 both (switches each time)
    private boolean voiceResume = false, voiceAfterPerm = false;
    private int lastTyped = 0;

    private boolean micOk() { return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED; }

    private void initVoice() {
        voice = new NvVoice(this, new NvVoice.Out() {
            @Override public void onCommand(String t) { try { runVoice(t); } catch (Throwable x) { say("คำสั่งผิดพลาด: " + x.getClass().getSimpleName()); } }
            @Override public void onDictate(String t) { try { dictate(t); } catch (Throwable x) { say("พิมพ์ไม่ได้: " + x.getClass().getSimpleName()); } }
            @Override public void onState(String s) { voiceMsg = s; if (!s.isEmpty()) menu.toast(s); }
            @Override public void onHeard(String s) { heard = s; }
        });
        voice.langSrc = c -> voiceLangMode == 1 ? "th-TH" : voiceLangMode == 2 ? "en-US" : voiceLangMode == 3 ? (c % 2 == 0 ? "th-TH" : "en-US") : (Tr.thai ? "th-TH" : "en-US");
    }

    private volatile String voiceMsg = "", heard = "";
    private void say(String s) { voiceMsg = s; menu.toast(s); try { settings.markDirty(); menu.markDirty(); } catch (Throwable ignored) { } }

    private void voiceAsk(final boolean once) {
        if (!micOk()) { voiceAfterPerm = true; voiceOnce = once; menu.toast("ถอดแว่นแล้วกดอนุญาตไมโครโฟนบนหน้าจอ"); runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 78)); return; }
        if (once) voice.listenOnce(); else voice.start();
        menu.markDirty(); settings.markDirty();
    }
    private boolean voiceOnce = false;

    private void toggleVoice() {
        if (voice.isOn()) { voice.stop(); say("ปิดสั่งเสียงแล้ว"); } else voiceAsk(false);
        menu.markDirty(); settings.markDirty();
    }

    private static boolean has(String n, String... ks) { for (String k : ks) if (n.contains(NvVoice.norm(k))) return true; return false; }
    private static boolean startsWith(String n, String... ks) { for (String k : ks) if (n.startsWith(NvVoice.norm(k))) return true; return false; }
    private static String after(String raw, String... ks) {
        String low = raw.toLowerCase(java.util.Locale.ROOT).trim();
        for (String k : ks) { int i = low.indexOf(k); if (i >= 0) return raw.trim().substring(Math.min(raw.trim().length(), i + k.length())).trim(); }
        return "";
    }

    private int firstBrowser() { for (int k = 0; k < brs.length; k++) if (brs[k] != null && bws[k] != null && bws[k].visible) return k; return -1; }

    private void hideWin(VRRenderer.Win w) { if (w != null && w.visible) rend.setVisible(w, false, 0f, 0f); }
    private void showWin(VRRenderer.Win w, float yaw, float pitch) { if (w != null && !w.visible) rend.setVisible(w, true, yaw, pitch); }

    /** web app names the way people say them (Thai and English), matched to the WEB list by name */
    private static final String[][] WEBSAY = {
        {"YouTube", "ยูทูป", "ยูทูบ", "youtube", "you tube"}, {"Facebook", "เฟซบุ๊ก", "เฟซบุค", "เฟส", "facebook"}, {"TikTok", "ติ๊กต๊อก", "ติ๊กต็อก", "ติกตอก", "tiktok", "tik tok"},
        {"Instagram", "อินสตาแกรม", "ไอจี", "instagram"}, {"X (Twitter)", "ทวิตเตอร์", "twitter"}, {"Netflix", "เน็ตฟลิกซ์", "เน็ตฟลิก", "netflix"}, {"Spotify", "สปอติฟาย", "สปอตติฟาย", "spotify"},
        {"แผนที่", "แผนที่", "แมพ", "maps", "google map"}, {"Gmail", "จีเมล", "อีเมล", "gmail", "email"}, {"Discord", "ดิสคอร์ด", "discord"}, {"WhatsApp", "วอทส์แอป", "วอตส์แอป", "whatsapp"},
        {"Telegram", "เทเลแกรม", "telegram"}, {"Reddit", "เรดดิต", "เรดดิท", "reddit"}, {"Twitch", "ทวิตช์", "twitch"}, {"Shopee", "ช็อปปี้", "ช้อปปี้", "ช็อปปี", "shopee"},
        {"Lazada", "ลาซาด้า", "lazada"}, {"Google Photos", "กูเกิลโฟโต้", "กูเกิลโฟโตส์", "google photos"},
    };

    private String webUrlFor(String n) {
        for (String[] a : WEBSAY) {
            boolean hit = false;
            for (int i = 1; i < a.length; i++) if (n.contains(NvVoice.norm(a[i]))) { hit = true; break; }
            if (!hit) continue;
            for (String[] w : WEB) if (w[2].equals(a[0])) return w[1];
        }
        return null;
    }

    private void closeAllWindows() {
        for (int k = 0; k < brs.length; k++) if (brs[k] != null && bws[k] != null && bws[k].visible) { brs[k].setActive(false); rend.setVisible(bws[k], false, 0f, 0f); }
        hideWin(wKeys); hideWin(wSet); hideWin(wFruit); hideWin(wSword3); hideWin(wClock); hideWin(wStats); hideWin(wMedia);
        if (wVideo.visible) video.onClose.run();
    }

    private void dictate(String t) {
        String n = NvVoice.norm(t);
        if (startsWith(n, "เสร็จ", "จบ", "หยุดพิมพ์", "พอ", "ยกเลิก", "stop", "done")) { voice.dictating = false; say("เลิกพิมพ์ด้วยเสียงแล้ว"); return; }
        NvKeyboard.Sink sk = kbd == null ? null : kbd.sink;
        if (sk == null) { voice.dictating = false; say("ไม่มีช่องพิมพ์ (แตะช่องพิมพ์ก่อน)"); return; }
        if (startsWith(n, "ลบ", "delete", "undo")) { for (int i = 0; i < Math.max(1, lastTyped); i++) sk.onBackspace(); lastTyped = 0; say("ลบแล้ว"); return; }
        if (n.equals(NvVoice.norm("ส่ง")) || n.equals(NvVoice.norm("ค้นหา")) || n.equals(NvVoice.norm("เอนเตอร์")) || n.equals(NvVoice.norm("ตกลง")) || n.equals("enter") || n.equals("search") || n.equals("send")) {
            sk.onEnter(kbd.text()); lastTyped = 0; say("กด Enter แล้ว"); return;
        }
        String s = t.trim();
        sk.onText(s); lastTyped = s.length();
        say("พิมพ์: " + s);
    }


    private static final String[] FILLER = {"เปิดให้หน่อย", "ให้หน่อย", "หน่อย", "ให้ที", "เปิดแอป", "เปิดหน้าต่าง", "เปิด", "ปิด", "แอปพลิเคชัน", "แอป", "หน้าต่าง", "ขอ", "ช่วย", "ไปที่", "ไปยัง", "ไป", "open", "launch", "start", "run", "show", "close", "the", "app", "please", "window", "go to"};
    /** last resort: the spoken words are compared by sound with the name of EVERY button / app / tool in the menu; the best (clear) match is pressed */
    private boolean fuzzyOpen(String raw, boolean close) {
        java.util.List<String> heard = new java.util.ArrayList<>();
        heard.add(raw);
        for (String s : voice.alts) if (s != null && !s.trim().isEmpty() && !heard.contains(s.trim())) heard.add(s.trim());
        NvUi.Btn best = null; float bs = 0f, second = 0f;
        for (NvUi.Btn b : menu.allButtons()) {
            if (b.act == null) continue;
            String nm; try { nm = b.label.get(); } catch (Throwable t) { continue; }
            if (nm == null || nm.isEmpty()) continue;
            String nm2 = Tr.t(nm);
            float sc = 0f;
            for (String h : heard) {
                String hh = h.toLowerCase(java.util.Locale.ROOT);
                for (String f : FILLER) hh = hh.replace(f, " ");
                sc = Math.max(sc, Math.max(NvVoice.similar(hh, nm), nm2.equals(nm) ? 0f : NvVoice.similar(hh, nm2)));
                sc = Math.max(sc, NvVoice.similar(h, nm) * 0.96f);
            }
            if (sc > bs) { second = bs; bs = sc; best = b; } else if (sc > second) second = sc;
        }
        if (best == null || bs < 0.72f || (second > bs - 0.04f && bs < 1f)) return false;
        final NvUi.Btn hit = best;
        boolean isOn = hit.on != null && hit.on.get();
        if (close && hit.on != null && !isOn) { say("ปิดอยู่แล้ว: " + hit.label.get()); return true; }
        if (!close && hit.on != null && isOn) { say("เปิดอยู่แล้ว: " + hit.label.get()); return true; }
        if (close && hit.on == null) { say("ปิดอันนี้ด้วยเสียงไม่ได้: " + hit.label.get()); return true; }
        say((close ? "ปิด " : "เปิด ") + hit.label.get());
        runOnUiThread(() -> { try { hit.act.run(); } catch (Throwable t) { say("ทำไม่ได้: " + t.getClass().getSimpleName()); } });
        return true;
    }

    private void runVoice(String raw) {
        final String n = NvVoice.norm(raw);
        if (n.isEmpty()) return;
        // stop listening
        if (has(n, "หยุดฟัง", "ปิดสั่งเสียง", "เลิกฟัง", "stop listening")) { voice.stop(); say("ปิดสั่งเสียงแล้ว"); return; }
        // dictation
        if (startsWith(n, "พิมพ์", "type ", "dictate")) {
            String rest = after(raw, "พิมพ์", "type", "dictate");
            if (kbd == null || kbd.sink == null) { say("แตะช่องพิมพ์ก่อน แล้วค่อยพูดว่า \"พิมพ์\""); return; }
            if (!rest.isEmpty()) { dictate(rest); return; }
            voice.dictating = true; lastTyped = 0; say("พูดข้อความได้เลย — จบด้วย \"เสร็จ\" / \"ส่ง\"");
            return;
        }
        // search
        if (startsWith(n, "ค้นหา", "search", "เสิร์ช", "หาใน", "google") || (startsWith(n, "หา") && n.length() > 3)) {
            String q = after(raw, "ค้นหา", "search", "เสิร์ช", "google");
            if (q.isEmpty() && raw.trim().startsWith("หา")) q = raw.trim().substring(2).trim();
            if (q.toLowerCase(java.util.Locale.ROOT).startsWith("for ")) q = q.substring(4).trim();
            if (q.isEmpty()) { say("จะค้นหาอะไร?"); return; }
            int b = firstBrowser();
            if (b >= 0) { brs[b].navigate(q); say("ค้นหา: " + q); }
            else { try { openWebFloating("https://www.google.com/search?q=" + java.net.URLEncoder.encode(q, "UTF-8")); } catch (Throwable t) { openWebFloating("https://www.google.com"); } say("ค้นหา: " + q); }
            return;
        }
        // browser navigation
        if (has(n, "ย้อนกลับ", "ถอยหลัง", "goback", "pageback") || n.equals("กลับ") || n.equals("back")) { int b = firstBrowser(); if (b >= 0) { brs[b].nav("back"); say("ย้อนกลับ"); } else say("ไม่มีเบราว์เซอร์ที่เปิดอยู่"); return; }
        if (has(n, "ไปข้างหน้า", "ถัดไป", "forward", "goforward")) { int b = firstBrowser(); if (b >= 0) { brs[b].nav("forward"); say("ไปข้างหน้า"); } return; }
        if (has(n, "รีเฟรช", "โหลดใหม่", "refresh", "reload")) { int b = firstBrowser(); if (b >= 0) { brs[b].nav("reload"); say("โหลดใหม่"); } return; }
        // video
        if (wVideo.visible && has(n, "หยุดวิดีโอ", "พักวิดีโอ", "เล่นต่อ", "เล่นวิดีโอ", "หยุดชั่วคราว", "pause", "play", "resume")) { video.playPause(); say(video.isPlaying() ? "เล่นต่อ" : "หยุดชั่วคราว"); return; }
        if (wVideo.visible && (n.equals("หยุด") || n.equals("เล่น") || n.equals("พัก"))) { video.playPause(); say(video.isPlaying() ? "เล่นต่อ" : "หยุดชั่วคราว"); return; }
        // controls
        if (has(n, "จัดกลาง", "รีเซ็ตกลาง", "รีเซนเตอร์", "recenter", "recentre", "re-center", "centerthe", "กลางจอ", "จัดใหม่", "resetview")) { rend.recenter = true; say("จัดกลางใหม่แล้ว"); return; }
        if (has(n, "ภาษาอังกฤษ", "english")) { Tr.setThai(false); refreshGlass(); settings.markDirty(); say("English"); return; }
        if (has(n, "ภาษาไทย", "thai")) { Tr.setThai(true); refreshGlass(); settings.markDirty(); say("ภาษาไทย"); return; }
        if (has(n, "ช่วยด้วย", "ทำอะไรได้", "คำสั่ง", "help", "whatcanyou", "commands")) {
            say("ลอง: เปิดเบราว์เซอร์ · เปิดยูทูป · ค้นหา … · ปิดทั้งหมด · พิมพ์ · จัดกลาง · โหมดโรงหนัง · เปิดเกมผลไม้");
            return;
        }
        boolean close = startsWith(n, "ปิด", "close", "เก็บ", "ซ่อน", "hide", "turnoff", "shutdown", "exit", "dismiss") || has(n, "ปิด");
        boolean open = !close;
        if (close && (has(n, "ทั้งหมด", "ทุกอัน", "ทุกหน้าต่าง", "closeall", "allwindows", "everything", "allapps"))) { closeAllWindows(); say("ปิดหน้าต่างทั้งหมดแล้ว"); return; }
        // web apps
        String url = webUrlFor(n);
        if (url != null) {
            if (close) { int b = firstBrowser(); if (b >= 0) { brs[b].setActive(false); rend.setVisible(bws[b], false, 0f, 0f); say("ปิดแล้ว"); } return; }
            openWebFloating(url); return;
        }
        if (has(n, "เบราว์เซอร์", "เบราเซอร์", "เว็บ", "browser", "chrome", "โครม", "internet", "webpage")) {
            if (close) { for (int k = 0; k < brs.length; k++) if (brs[k] != null && bws[k] != null && bws[k].visible) { brs[k].setActive(false); rend.setVisible(bws[k], false, 0f, 0f); } say("ปิดเบราว์เซอร์แล้ว"); }
            else { openBrowser(-1); say("เปิดเบราว์เซอร์"); }
            return;
        }
        if (has(n, "แป้นพิมพ์", "คีย์บอร์ด", "keyboard")) { if (close) hideWin(wKeys); else showWin(wKeys, 0f, -24f); say(close ? "ปิดคีย์บอร์ด" : "เปิดคีย์บอร์ด"); return; }
        if (has(n, "ตั้งค่า", "settings")) { if (close) hideWin(wSet); else showWin(wSet, 0f, 5f); say(close ? "ปิดตั้งค่า" : "เปิดตั้งค่า"); return; }
        if (has(n, "ฟันผลไม้", "เกมผลไม้", "ผลไม้", "fruit", "slicing")) { if (close) hideWin(wFruit); else showWin(wFruit, 0f, 2f); say(close ? "ปิดเกมผลไม้" : "เปิดเกมผลไม้"); return; }
        if (has(n, "ฟันดาบ", "เกมดาบ", "ดาบ", "มอนสเตอร์", "sword", "monster")) { if (close) hideWin(wSword3); else showWin(wSword3, 0f, 30f); say(close ? "ปิดเกมดาบ" : "เปิดเกมดาบ"); return; }
        if (has(n, "นาฬิกา", "จับเวลา", "clock", "timer")) { if (close) hideWin(wClock); else showWin(wClock, 18f, 0f); say(close ? "ปิดนาฬิกา" : "เปิดนาฬิกา"); return; }
        if (has(n, "สถานะ", "status")) { if (close) hideWin(wStats); else showWin(wStats, -18f, 0f); say(close ? "ปิดสถานะ" : "เปิดสถานะ"); return; }
        if (has(n, "ฉากเมือง", "ฉากเสมือน", "โลกเสมือน", "virtualscene", "virtualworld", "citysc", "virtualroom")) { rend.envScene = close ? 0 : 1; say(close ? "กลับเป็นภาพกล้อง" : "เปิดฉากเมืองเสมือน"); return; }
        if (has(n, "โรงหนัง", "cinema", "theater")) { rend.cinema = !close; rend.recenter = true; say(close ? "ปิดโหมดโรงหนัง" : "เปิดโหมดโรงหนัง"); return; }
        if (has(n, "แกลเลอรี", "แกลลอรี่", "รูป", "ภาพ", "วิดีโอ", "gallery", "photos", "pictures", "videos")) {
            if (close) { if (wMedia.visible) media.onClose.run(); if (wVideo.visible) video.onClose.run(); say("ปิดแล้ว"); }
            else { openGallery(); say("เปิดแกลเลอรี"); }
            return;
        }
        if (has(n, "กอริลลา", "กอริลล่า", "gorilla")) {
            if (close) return;
            try {
                Intent gi = getPackageManager().getLaunchIntentForPackage("com.htmltoapk.gorillaworld");
                if (gi == null) say("ยังไม่ได้ติดตั้ง Gorilla World"); else { gi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(gi); }
            } catch (Throwable t) { say("เปิด Gorilla World ไม่ได้"); }
            return;
        }
        if (has(n, "เมนู", "menu")) { menu.setOpen(open); say(open ? "เปิดเมนู" : "ปิดเมนู"); return; }
        if (fuzzyOpen(raw, close)) return;
        say("ไม่เข้าใจ: " + raw.trim() + "  (พูดว่า \"ช่วยด้วย\")");
    }


    // ---- status shown on the menu dock (clock is drawn by the menu itself) ----
    private long statT = 0;
    private void updateStatus() {
        long now = System.currentTimeMillis();
        if (now - statT < 4000 || menu == null) return;
        statT = now;
        boolean ch = false;
        try {
            android.content.Intent bi = registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            if (bi != null) {
                int lv = bi.getIntExtra("level", -1), sc = bi.getIntExtra("scale", 100), st = bi.getIntExtra("status", -1);
                int pct = sc > 0 && lv >= 0 ? lv * 100 / sc : -1;
                boolean cg = st == 2 || st == 5;
                if (pct != menu.battery || cg != menu.charging) { menu.battery = pct; menu.charging = cg; ch = true; }
            }
        } catch (Throwable ignored) { }
        try {
            int bars = -1; String kind = "";
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            android.net.NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            if (ni != null && ni.isConnected()) {
                if (ni.getType() == android.net.ConnectivityManager.TYPE_WIFI) {
                    kind = "Wi-Fi"; bars = 3;
                    try {
                        android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                        bars = Math.max(1, Math.min(4, android.net.wifi.WifiManager.calculateSignalLevel(wm.getConnectionInfo().getRssi(), 5)));
                    } catch (Throwable ignored) { }
                } else { kind = "มือถือ"; bars = 3; }
            }
            if (bars != menu.net || !kind.equals(menu.netKind)) { menu.net = bars; menu.netKind = kind; ch = true; }
        } catch (Throwable ignored) { }
        boolean mo = voice != null && voice.isOn();
        if (mo != menu.micOn) { menu.micOn = mo; ch = true; }
        if (ch) menu.markDirty();
    }

    /** the search field of the menu: the on-screen keyboard types into the filter */
    private void startMenuSearch() {
        runOnUiThread(() -> {
            if (kbd == null) return;
            kbd.sink = new NvKeyboard.Sink() {
                @Override public void onText(String t) { menu.filter = menu.filter + t; menu.relayout(); }
                @Override public void onBackspace() { String f = menu.filter; if (!f.isEmpty()) { menu.filter = f.substring(0, f.length() - 1); menu.relayout(); } }
                @Override public void onEnter(String all) { hideWin(wKeys); kbd.sink = null; }
            };
            showWin(wKeys, 0f, -24f);
        });
    }

    private void buildMenu() {
        buildSettings();
        final int GA = 0xFF5A5A63, GB = 0xFF38383E;
        final java.util.List<NvUi.Btn> webs = menu.addTab("", "เว็บแอป", "เว็บแอปยอดนิยม (เปิดเป็นหน้าต่างลอย)");
        final java.util.List<NvUi.Btn> tools = menu.addTab("", "เครื่องมือ", "เครื่องมือและระบบ");
        java.util.Set<String> shownW = new java.util.HashSet<>();
        for (String[] w : WEB) {
            if (!shownW.add(w[2])) continue;
            final String url = w[1];
            NvUi.Btn wb = new NvUi.Btn(w[3], () -> w[2], () -> "เปิดลอย", null, 0xFF3A6FD8, 0xFF2547A0, () -> openWebFloating(url));
            webs.add(wb);
            loadLogo(wb, url);
        }
        menu.home.add(new NvUi.Btn("🌐", () -> "เบราว์เซอร์", () -> browsersShown() > 0 ? browsersShown() + " หน้าต่าง" : "ปิดอยู่", () -> browsersShown() > 0, GA, GB, () -> {
            if (browsersShown() > 0) {                                     // hide them all (they keep their pages and rest quietly)
                for (int k = 0; k < brs.length; k++) if (brs[k] != null && bws[k].visible) { brs[k].setActive(false); rend.setVisible(bws[k], false, 0f, 0f); }
            } else {
                boolean any = false;                                         // bring back the ones that were open
                for (int k = 0; k < brs.length; k++) if (brs[k] != null && brs[k].isOpen()) { rend.setVisible(bws[k], true, BYAW[k], 4f); brs[k].setActive(true); any = true; }
                if (!any) openBrowser(0);
            }
        }));
        menu.home.add(new NvUi.Btn("🖼", () -> "แกลเลอรี/โรงหนัง", () -> rend.panoActive() ? "กำลังดูภาพ 360°" : (wVideo.visible ? "กำลังเล่นวิดีโอ" : (wMedia.visible ? "เปิดอยู่" : "รูป วิดีโอ 360°")), () -> wMedia.visible || wVideo.visible || rend.panoActive(), GA, GB, () -> {
            if (wMedia.visible) { media.onClose.run(); }
            else if (wVideo.visible) { video.onClose.run(); }
            else openGallery();
        }));
        menu.home.add(new NvUi.Btn("🎬", () -> "โหมดโรงหนัง", () -> rend.cinema ? "ห้องมืด เห็นแค่จอ" : "ปิดอยู่ (เห็นห้องจริง)", () -> rend.cinema, GA, GB, () -> { rend.cinema = !rend.cinema; rend.recenter = true; }));
        menu.home.add(new NvUi.Btn("⚙", () -> "ตั้งค่า", () -> wSet.visible ? "เปิดอยู่" : "ควบคุม แสดงผล VR ทดลอง", () -> wSet.visible, GA, GB, () -> rend.toggle(wSet, 0f, 5f)));
        tools.add(new NvUi.Btn("🌆", () -> "ฉากเมืองเสมือน", () -> rend.envScene == 1 ? "เปิดอยู่ (แทนภาพกล้อง)" : "ปิดอยู่ (เห็นห้องจริง)", () -> rend.envScene == 1, 0xFF8A5AE0, 0xFF5A32A8, () -> rend.envScene = rend.envScene == 1 ? 0 : 1));
        tools.add(new NvUi.Btn("🦍", () -> "Gorilla World", () -> "เปิดเกมกอริลลา (ต้องติดตั้งแยก)", null, GA, GB, () -> runOnUiThread(() -> {
            try {
                android.content.Intent gi = getPackageManager().getLaunchIntentForPackage("com.htmltoapk.gorillaworld");
                if (gi == null) { err = "ยังไม่ได้ติดตั้ง Gorilla World"; android.widget.Toast.makeText(this, "ยังไม่ได้ติดตั้ง Gorilla World", android.widget.Toast.LENGTH_LONG).show(); }
                else { gi.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(gi); }
            } catch (Throwable t) { err = "gorilla: " + t; }
        })));
        tools.add(new NvUi.Btn("🎙", () -> "สั่งด้วยเสียง", () -> voice.isOn() ? "ฟังอยู่ — \"เฮ้ พ็อกเก็ต\" แล้วสั่ง" : "ปิดอยู่ (กดเพื่อเปิด)", () -> voice.isOn(), 0xFFE0506A, 0xFFA02A42, () -> toggleVoice()));
        tools.add(new NvUi.Btn("🗣", () -> "พูดคำสั่งตอนนี้", () -> "ไม่ต้องมีคำปลุก พูดได้ 8 วินาที", null, GA, GB, () -> voiceAsk(true)));
        tools.add(new NvUi.Btn("⚔", () -> "เกมฟันดาบ 3D", () -> wSword3.visible ? "เปิดอยู่" : "มอนสเตอร์ 3D ในห้อง", () -> wSword3.visible, GA, GB, () -> rend.toggle(wSword3, 0f, 30f)));
        tools.add(new NvUi.Btn("🍉", () -> "เกมฟันผลไม้", () -> wFruit.visible ? "เปิดอยู่" : "ปัดนิ้วฟันผลไม้", () -> wFruit.visible, GA, GB, () -> rend.toggle(wFruit, 0f, 2f)));
        tools.add(new NvUi.Btn("⏱", () -> "นาฬิกา/จับเวลา", () -> wClock.visible ? "เปิดอยู่" : "ปิดอยู่", () -> wClock.visible, GA, GB, () -> rend.toggle(wClock, 18f, 0f)));
        tools.add(new NvUi.Btn("📈", () -> "สถานะระบบ", () -> wStats.visible ? "เปิดอยู่" : "ปิดอยู่", () -> wStats.visible, GA, GB, () -> rend.toggle(wStats, -18f, 0f)));
        menu.home.add(new NvUi.Btn("📍", () -> "จัดกลางใหม่", () -> "วางเมนูตรงหน้า", null, GA, GB, () -> rend.recenter = true));
        tools.add(new NvUi.Btn("🕸", () -> "เวอร์ชันเว็บ (สำรอง)", () -> "เปิดหน้าเว็บเดิม", null, 0xFF8D6BFF, 0xFF4A2FB8, () -> runOnUiThread(() -> {
            try { startActivity(new android.content.Intent(this, MainActivity.class)); } catch (Throwable t) { err = "web: " + t; }
            finish();
        })));
        tools.add(new NvUi.Btn("⏻", () -> "ออกจากแอป", () -> "ปิด PocketVR", null, 0xFF6A3036, 0xFF4A2024, () -> runOnUiThread(() -> finishAffinity())));

        menu.onMic = () -> toggleVoice();
        menu.onSearch = () -> startMenuSearch();
        menu.dock.add(new NvUi.Btn("🏠", () -> "เมนู", null, () -> menu.open, 0, 0, () -> menu.toggleOpen()));
        menu.dock.add(new NvUi.Btn("⌨", () -> "คีย์บอร์ด", null, () -> wKeys.visible, 0, 0, () -> rend.toggle(wKeys, 0f, -24f)));
        menu.dock.add(new NvUi.Btn("🥽", () -> "แยกจอ", null, () -> rend.stereo, 0, 0, () -> rend.stereo = !rend.stereo));
        menu.dock.add(new NvUi.Btn("👓", () -> "เลนส์", null, () -> rend.lens > 0, 0, 0, () -> rend.lens = (rend.lens + 1) % 4));
        menu.dock.add(new NvUi.Btn("✋", () -> "คาดการณ์", null, () -> rend.predict, 0, 0, () -> rend.predict = !rend.predict));
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.Intent q = new android.content.Intent(android.content.Intent.ACTION_MAIN);
            q.addCategory(android.content.Intent.CATEGORY_LAUNCHER);
            java.util.List<android.content.pm.ResolveInfo> infos = pm.queryIntentActivities(q, 0);
            java.util.List<android.content.pm.ResolveInfo> keep = new java.util.ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (android.content.pm.ResolveInfo ri : infos) {
                String p = ri.activityInfo.packageName;
                if (p.equals(getPackageName()) || !seen.add(p)) continue;
                keep.add(ri);
            }
            java.util.Collections.sort(keep, (a, b) -> String.valueOf(a.loadLabel(pm)).compareToIgnoreCase(String.valueOf(b.loadLabel(pm))));
            for (int i = 0; i < keep.size() && i < 60; i++) {
                final android.content.pm.ResolveInfo ri = keep.get(i);
                final String label = String.valueOf(ri.loadLabel(pm));
                final String pkg = ri.activityInfo.packageName;
                final String webUrl = webFor(pkg);
                final String comp = ri.activityInfo.packageName + "/" + ri.activityInfo.name;
                final Runnable outside = () -> runOnUiThread(() -> {
                    try {
                        android.content.Intent li = getPackageManager().getLaunchIntentForPackage(pkg);
                        if (li != null) startActivity(li);
                    } catch (Throwable ignored) { }
                });
                NvUi.Btn b = new NvUi.Btn("", () -> label,
                        () -> NvShizuku.ready() ? "📱 เปิดลอย" : (webUrl != null ? "🌐 เปิดลอย" : "เปิดนอก VR"),
                        null, 0xFF3A3F4B, 0xFF262932, () -> {
                    if (openRealApp(pkg, comp, label)) return;                          // the real app, in a floating window
                    if (NvShizuku.alive()) { NvShizuku.request(); menu.toast("กดอนุญาต PocketVR ในหน้าต่าง Shizuku แล้วลองใหม่"); return; }
                    if (webUrl != null) openWebFloating(webUrl); else outside.run();
                });
                b.icon = iconOf(ri.loadIcon(pm));
                menu.apps.add(b);
            }
        } catch (Throwable t) { err = "apps: " + t; }
        menu.relayout();
    }

    /** the real logo of a website for its card: kept on the phone after the first time (so it also shows offline), fetched in the background */
    private void loadLogo(final NvUi.Btn b, final String url) {
        new Thread(() -> {
            try {
                String host = android.net.Uri.parse(url).getHost();
                if (host == null) return;
                java.io.File f = new java.io.File(getCacheDir(), "logo_" + host.replaceAll("[^a-z0-9.]", "_") + ".png");
                android.graphics.Bitmap src = f.exists() ? android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
                if (src == null) {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("https://www.google.com/s2/favicons?domain=" + host + "&sz=128").openConnection();
                    c.setConnectTimeout(6000); c.setReadTimeout(6000);
                    try (java.io.InputStream in = c.getInputStream()) { src = android.graphics.BitmapFactory.decodeStream(in); }
                    if (src == null) return;
                    try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { src.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o); } catch (Throwable ignored) { }
                }
                // on a light rounded plate, so a dark or white logo is seen on the dark card
                android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(160, 160, android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas cv = new android.graphics.Canvas(bm);
                android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
                p.setColor(0xFFF4F6FA);
                cv.drawRoundRect(new android.graphics.RectF(0, 0, 160, 160), 36, 36, p);
                cv.drawBitmap(src, null, new android.graphics.RectF(30, 30, 130, 130), p);
                b.icon = bm;
                runOnUiThread(() -> menu.relayout());
            } catch (Throwable ignored) { }
        }, "logo").start();
    }

    private static android.graphics.Bitmap iconOf(android.graphics.drawable.Drawable d) {
        android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bm);
        d.setBounds(0, 0, 96, 96);
        d.draw(c);
        return bm;
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        if (rc == 78) {
            if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) { if (voiceAfterPerm) { if (voiceOnce) voice.listenOnce(); else voice.start(); } }
            else menu.toast("ยังไม่ได้รับอนุญาตไมโครโฟน");
            voiceAfterPerm = false;
            return;
        }
        if (rc == 77) {                                                  // pictures / videos permission (the app keeps running if it is refused)
            if (hasMediaPerm()) { media.reload(); rend.setVisible(wMedia, true, 0f, 0f); }
            else menu.toast("ยังไม่ได้รับอนุญาตให้อ่านรูป");
            return;
        }
        if (r.length == 0 || r[0] != PackageManager.PERMISSION_GRANTED) finish();
        else { gl.onPause(); gl.onResume(); }
    }

    private void immersive() {
        try {                                                  // the picture also under the camera hole / notch (on the side of the front camera), so the app is really full screen
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.layoutInDisplayCutoutMode = android.os.Build.VERSION.SDK_INT >= 30 ? 3 : 1;    // ALWAYS (30+) / SHORT_EDGES
                getWindow().setAttributes(lp);
            }
        } catch (Throwable ignored) { }
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    /** is the VR screen in front? (a floating app that jumps onto the phone's own screen makes this false) */
    public static volatile boolean resumed = false;

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        immersive();
        if (voiceResume && voice != null && micOk()) { voiceResume = false; voice.start(); }
        gl.onResume();
        try {
            sm = (android.hardware.SensorManager) getSystemService(SENSOR_SERVICE);
            rotSensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_GAME_ROTATION_VECTOR);
            if (rotSensor == null) rotSensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR);
            magSensor = rotSensor != null && rotSensor.getType() == android.hardware.Sensor.TYPE_GAME_ROTATION_VECTOR ? sm.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR) : null;
            if (rotSensor != null) {                                 // fast readings (200 per second) so turning the head looks smooth
                boolean okFast = false;
                try { okFast = sm.registerListener(sl, rotSensor, 5000); } catch (Throwable ignored) { }
                if (!okFast) sm.registerListener(sl, rotSensor, android.hardware.SensorManager.SENSOR_DELAY_GAME);
            }
            if (magSensor != null) sm.registerListener(sl, magSensor, android.hardware.SensorManager.SENSOR_DELAY_UI);
            else err = "no turn sensor";
        } catch (Throwable t) { err = "sensor: " + t; }
        rend.recenter = true;
        ui.post(tick);
    }

    @Override protected void onPause() {
        resumed = false;
        savePrefsNow();
        ui.removeCallbacks(tick);
        try { if (sm != null) sm.unregisterListener(sl); } catch (Throwable ignored) { }
        voiceResume = voice != null && voice.isOn() && !voice.once();
        if (voice != null) voice.stop();
        cam.stop();
        gl.onPause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        try { if (video != null) video.release(); } catch (Throwable ignored) { }
        bgIo.shutdown();
        for (NvBrowser b : brs) { try { if (b != null) b.close(); } catch (Throwable ignored) { } }
        super.onDestroy();
    }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) immersive(); }

    // called on the drawing thread when a new camera texture exists
    @Override public void onCamSurface(SurfaceTexture st) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) cam.start(st);
    }

    @Override public void onImageSize(int w, int h, boolean rot180) { rend.setImage(w, h, rot180); }

    @Override public void onError(String msg) { err = msg; }
}
