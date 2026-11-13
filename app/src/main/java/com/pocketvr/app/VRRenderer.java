package com.pocketvr.app;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Draws camera -> small scene picture (+ hand skeleton) -> screen, mono or split (VR box) with optional lens warp. */
public class VRRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
    public interface Host { void onCamSurface(SurfaceTexture st); }

    private static final float[] LENS_K = {0f, 0.12f, 0.22f, 0.35f};
    private static final int[] BONES = {0,1,1,2,2,3,3,4, 0,5,5,6,6,7,7,8, 5,9,9,10,10,11,11,12, 9,13,13,14,14,15,15,16, 13,17,17,18,18,19,19,20, 0,17};

    private final Host host;
    private final NvCam cam;
    public volatile boolean stereo = true;
    public volatile NvSword3D world3 = null;             // a 3D game in the room (monsters), drawn while its score window is shown
    public volatile Win world3Win = null;
    private final int[] depthRb = new int[1];
    public volatile int lens = 1;
    public volatile float chroma = 0f;                    // lens colour fringes (red / blue edges): the three colours are drawn a hair apart at the edges, the other way round
    public volatile float fps = 0;
    public volatile int fpsCap = 0;                        // 0 = as fast as the screen allows; 30 / 45 = slow down to save heat and battery
    public volatile boolean autoRes = true;                // lower the size of the picture when the frame rate cannot be kept, and raise it again when there is room
    public volatile float sceneScale = 1f;                 // 0.6 .. 1
    private long lastScaleT = 0, lastFrameNs = 0;
    public volatile boolean predict = true;
    public volatile int envScene = 0;       // 0 = the camera picture, 1 = a made-up dusk city instead (a virtual room around you)
    public volatile int skel = 1;           // 0 off, 1 skeleton, 2 glowing outline (like the web version)
    public volatile float predMs = 0;
    public NvUi ui;
    private int progUi, uiTex = 0;
    private volatile float[] rotNow = new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    public volatile boolean recenter = false;
    public volatile boolean cinema = false;                // dark room: no camera picture, only the windows marked showInCinema
    public volatile float eyeShift = 0.12f;                // VR split view: how far each eye's picture is moved towards the nose (fixes a doubled picture)
    public volatile float lift = 0f;                      // brightens a dark camera picture (0 = off)
    public volatile float sharpen = 0.5f;                  // camera picture sharpening 0..1
    private boolean shown(Win w) { return w.visible && (!cinema || w.showInCinema); }
    private final float[] mP = new float[16], mM = new float[16];
    private float tanH = 0.65f, tanV = 0.37f;           // what the windows are drawn with (the field of view you see)
    private float tanHc = 0.65f, tanVc = 0.37f;         // what the camera picture covers (hands, pointer)
    private float winZoom = 1f;                          // windows are drawn this much bigger in the VR split view
    private float camScale = 1f;                        // camera picture size relative to the scene (below 1 = smaller, with a dark border)
    public volatile float vrFov = 80f;                  // VR split view: how wide each eye's view is in degrees; should match the headset lenses
    private static final float PANEL_D = 1.5f;
    private int progPanel, progVid, progPano, progEnv;
    // ---- 360 picture around the user (replaces the camera picture while it is on) ----
    private volatile android.graphics.Bitmap panoBmp = null;
    private volatile int panoCmd = 0;                       // 1 = show panoBmp, 2 = switch off
    private volatile boolean panoOn = false;
    private int panoTex = 0;
    private float panoLon0 = 0f;
    private final float[] rm3 = new float[9];
    public boolean panoActive() { return panoOn || panoCmd == 1; }
    /** a whole-sky (equirectangular) picture to look around in; null = back to the camera */
    public void setPano(android.graphics.Bitmap b) { panoBmp = b; panoCmd = b == null ? 2 : 1; }
    private float cnx = 0, cny = 0;
    private volatile float[] rotPrev = null;
    private volatile long tPrev = 0, tLast = 0;
    private volatile float[] rotView = new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};   // what the picture is drawn with (the turn, carried on to this very moment)
    public void setRotation(float[] m) {
        float[] c = m.clone();
        rotPrev = rotNow; tPrev = tLast; tLast = System.nanoTime();
        rotNow = c;
        synchronized (rhLock) { rhT[rhI] = System.nanoTime(); rhM[rhI] = c; rhI = (rhI + 1) % rhT.length; }
    }
    // camera picture timewarp: the picture is ~100 ms old when it is shown, so it is turned by how far the head has turned since it was taken
    public volatile boolean reproj = true;
    public volatile int camLagMs = 80;                    // used when the picture's own time cannot be trusted
    public volatile float camLagMeasured = 0f;            // how old the picture really is when shown (ms); 0 = not known
    private final Object rhLock = new Object();
    private final long[] rhT = new long[160];
    private final float[][] rhM = new float[160][];
    private int rhI = 0;
    private float[] frameRot = null;
    private final float[] dm3 = new float[9];
    /** keep the turn a clean rotation (columns of length 1, at right angles) */
    private static void ortho(float[] m) {
        float a0 = m[0], a1 = m[1], a2 = m[2];
        float l = (float) Math.sqrt(a0 * a0 + a1 * a1 + a2 * a2); if (l < 1e-6f) return;
        a0 /= l; a1 /= l; a2 /= l;
        float b0 = m[4], b1 = m[5], b2 = m[6];
        float d = a0 * b0 + a1 * b1 + a2 * b2;
        b0 -= d * a0; b1 -= d * a1; b2 -= d * a2;
        l = (float) Math.sqrt(b0 * b0 + b1 * b1 + b2 * b2); if (l < 1e-6f) return;
        b0 /= l; b1 /= l; b2 /= l;
        m[0] = a0; m[1] = a1; m[2] = a2; m[4] = b0; m[5] = b1; m[6] = b2;
        m[8] = a1 * b2 - a2 * b1; m[9] = a2 * b0 - a0 * b2; m[10] = a0 * b1 - a1 * b0;
        m[3] = m[7] = m[11] = m[12] = m[13] = m[14] = 0f; m[15] = 1f;
    }

    /** the head's turn at a moment in the past, in between the two readings around it (smooth even when the sensor is slow) */
    private float[] rotBefore(long t) {
        synchronized (rhLock) {
            float[] a = null, b = null; long ta = Long.MIN_VALUE, tb = Long.MAX_VALUE;
            for (int i = 0; i < rhT.length; i++) {
                if (rhM[i] == null) continue;
                if (rhT[i] <= t && rhT[i] > ta) { ta = rhT[i]; a = rhM[i]; }
                if (rhT[i] > t && rhT[i] < tb) { tb = rhT[i]; b = rhM[i]; }
            }
            if (a == null || b == null || tb - ta > 100_000_000L) return a;
            float f = (float) (t - ta) / (float) (tb - ta);
            float[] o = new float[16];
            for (int i = 0; i < 16; i++) o[i] = a[i] + (b[i] - a[i]) * f;
            ortho(o);
            return o;
        }
    }

    /** once per picture: carry the last two readings on to this moment, so the view turns smoothly even if the sensor is slower than the screen */
    private float[] rvSm = null;
    private void updateView() {
        float[] r1 = rotNow; long t1 = tLast;
        long now = System.nanoTime();
        if (r1 == null) return;
        // the turning speed is measured over about 40 ms, not between the last two readings: the readings reach the app in bunches, so two neighbours can be
        // 0.3 ms or 9 ms apart in arrival time, and a speed taken from them jumps about (the windows then sway while you turn)
        float[] r0 = null; long t0 = 0;
        synchronized (rhLock) {
            int n = rhT.length;
            for (int k = 1; k < n; k++) {
                int idx = ((rhI - 1 - k) % n + n) % n;
                if (rhM[idx] == null || rhT[idx] == 0 || rhT[idx] > t1) break;
                r0 = rhM[idx]; t0 = rhT[idx];
                if (t1 - rhT[idx] >= 40_000_000L) break;
            }
        }
        float span = (t1 - t0) / 1e9f, dt = (now - t1) / 1e9f;
        if (r0 == null || span < 0.012f || span > 0.25f || dt <= 0f) { rotView = r1; rvSm = r1.clone(); return; }
        // how fast the head is turning (rad/s): the angle of R1 * R0^T over the time between the two readings
        float tr = 0f;
        for (int r = 0; r < 3; r++) for (int j = 0; j < 3; j++) tr += r1[j * 4 + r] * r0[j * 4 + r];
        float ang = (float) Math.acos(Math.max(-1f, Math.min(1f, (tr - 1f) * 0.5f)));
        float speed = ang / span;
        // sticky when still: the sensor's tiny noise must not make the windows tremble, so below a slow turn the view is not carried forward and is eased heavily;
        // from a calm turn upwards it follows at once (no lag while you really move your head)
        float f = Math.max(0f, Math.min(1f, (speed - 0.02f) / 0.05f));        // 1 deg/s and slower: held still; 4.6 deg/s and faster: follows at once      // (it was 0.04 .. 0.34 rad/s: a slow turn was eased more than a quicker one, and the windows swayed while turning)
        float k = Math.min(dt, 0.004f) / span * f;             // never look further ahead than 12 ms (25 ms made the windows swing past when the head stopped)
        float[] e = new float[9];
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) {
            float dl = 0f;
            for (int j = 0; j < 3; j++) dl += r1[j * 4 + r] * r0[j * 4 + c];          // R1 * R0^T
            e[r * 3 + c] = (r == c ? 1f : 0f) + k * (dl - (r == c ? 1f : 0f));
        }
        float[] o = new float[16];
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) {
            float sm = 0f;
            for (int j = 0; j < 3; j++) sm += e[r * 3 + j] * r1[c * 4 + j];
            o[c * 4 + r] = sm;
        }
        ortho(o);
        float[] pv = rvSm;
        if (pv != null) {
            float tq = 0f;
            for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) tq += o[c * 4 + r] * pv[c * 4 + r];
            float dev = (float) Math.acos(Math.max(-1f, Math.min(1f, (tq - 1f) * 0.5f)));     // how far the new turn is from what is shown now (rad)
            float al = 0.15f + 0.85f * f;                                // turning slowly must not lag behind the room (the windows then seem to swim and tilt against the camera picture)
            if (f < 0.3f && dev < 0.0012f) al = 0f;                    // still and less than ~0.07 deg off: it does not move at all (no trembling)
                                  // held still: only 8 % of the way each picture, so hand tremor and sensor noise do not show                      // still: 10 % of the way each picture; turning: all the way
            for (int i = 0; i < 16; i++) o[i] = pv[i] + (o[i] - pv[i]) * al;
            ortho(o);
        }
        rvSm = o.clone();
        rotView = o;
    }
    public float[] rotCopy() { return rotNow; }
    public volatile NvMotion motion;
    private final float[] tE = new float[16], tF = new float[16];
    private void shiftBy(Win w, float[] m, float ux, float uy, float uz) {   // the user is at (ux,uy,uz); the window was set up at its base
        android.opengl.Matrix.setIdentityM(tE, 0);
        android.opengl.Matrix.translateM(tE, 0, w.wx - ux, w.wy - uy, w.wz - uz);
        android.opengl.Matrix.multiplyMM(tF, 0, tE, 0, m, 0);
        System.arraycopy(tF, 0, m, 0, 16);
    }
    public volatile boolean floaty = false;               // windows stay perfectly still unless this is switched on
    public volatile int cursorSz = 0;                     // 0 small and faint, 1 medium, 2 large
    public volatile int sel = 2;              // how things are pressed: 0 pinch, 1 air touch (push the index finger forward), 2 both
    public volatile int pokeS = 1;            // air touch sensitivity: 0 light, 1 medium, 2 firm
    private static final float[] POKE_TH = {0.07f, 0.10f, 0.14f};
    private float pkBase = 0, pkPeak = 0, pkProg = 0;
    private long pkT = 0;
    private boolean pkOn = false, pkInit = false;
    private long pkT2 = 0, pkAbove = 0, idxSince = 0, pkRelT = 0, lastFreshT = 0;
    private float pkDepthF = 0, handSpd = 0, lastTx = 0, lastTy = 0;
    private float[] lastRef = null;
    private final float[] tA = new float[16], tB = new float[16], tC = new float[16], tD = new float[16];

    /** A floating window in the room: it keeps its place when the phone turns, can float a little, be grabbed by its bar, and be pulled bigger or smaller. */
    public static class Win {
        public boolean lowDock = false;                    // the keyboard: rises up from below at the bottom, and no other window has to slide away for it
        public boolean anchor = false;                      // the main menu: new windows prefer to sit right next to it
        public final NvContent c;
        public final float widthM;
        public volatile boolean visible = false, placeReq = false, showInCinema = false;
        public volatile boolean hasSaved = false;            // the user moved / resized it once: remember where, and put it back there next time
        public volatile float defYaw = 0, defPitch = 0;      // where it appears by default (before the user moved it)
        public volatile float svYaw = 0, svPitch = 0, svScale = 1f, svDist = 1.5f, placeDist = 0f;
        public volatile float wantScale = 0;                 // size to take when (re)placed in front of the user; 0 = normal
        public volatile boolean newOpen = false;             // just opened (not just re-centred): it comes in with a slide
        float slideDegP = 0f;                                // the same, upwards
        float slideDeg = 0f;                                 // at the start of the slide the window is this many degrees to the right of where it ends up (it starts behind its neighbour)
        float animA = 1f, animS = 1f;                        // fade and size while it comes in
        boolean animOn = false;
        long animT0 = 0;
        public volatile boolean ghost = false;             // just chosen from the menu: shown see-through in front of you, one more click opens it for real
        public boolean game = false;                       // a game window: while one is up the thumb + middle finger menu gesture and its double tap are off (a fast swipe looks like them)
        public boolean noGhost = false;                    // opens at once (the menu itself, keyboard, cinema screen ...)
        public String title = "";                          // shown on the little bar under the window
        public NvUi.Txt titleFn = null;                    // (or worked out each time)
        public Runnable onClose = null, onBack = null;     // the bar's close and back buttons
        public boolean noBar = false;                      // no title bar under this window
        int btex = 0, barHov = -1, barDrawnHov = -2; String barDrawnTitle = "";
        android.graphics.Bitmap bbmp = null;
        long confT0 = 0;                                   // when the ghost was confirmed (it fades up from see-through)
        float pendYaw = 0f;                                // degrees it still has to slide sideways (+ = to the right) to make room for a new window
        int texW = 0, texH = 0;
        public volatile float offYaw = 0, offPitch = 0;      // where it appears, relative to the view centre (degrees)
        float[] rot = null;
        float scale = 1f;
        int tex = 0;
        final float[] mv = new float[16], mvp = new float[16], inv = new float[16];
        float[] aimV = null;                               // where (a direction in the phone's view) it is to be put when it is placed next to others
        boolean shrunk = false;                            // it was made smaller to fit beside the others (back to the usual size next time it opens)
        float rollKeep = 0f;                               // degrees of the phone's own tilt it keeps (so it looks straight to the eyes when the head is a little tilted)
        float wx, wy, wz;                                  // where the user stood when this window was put up (walk-around)
        float yawA, pitchA, dragYawOff, dragPitchOff, dragS0 = 1f, dragSize0 = 1f, dist = 1.5f, dragD0 = 1.5f;
        final float phase = (float) (Math.random() * 6.28);
        Win(NvContent c, float w) { this.c = c; this.widthM = w; }
    }
    public final java.util.concurrent.CopyOnWriteArrayList<Win> wins = new java.util.concurrent.CopyOnWriteArrayList<>();
    private Win menuWin = null, dragWin = null;
    private float rayYaw = 0, rayPitch = 0;
    private boolean prevPinch = false;

    public Win addWindow(NvContent c, float widthM) {
        Win w = new Win(c, widthM);
        wins.add(w);
        if (c instanceof NvUi) { menuWin = w; w.showInCinema = true; }
        return w;
    }

    /** show / hide a window (it appears in front of you, shifted by the given angles) */
    public void setVisible(Win w, boolean v, float offYaw, float offPitch) {
        w.offYaw = offYaw; w.offPitch = offPitch; w.defYaw = offYaw; w.defPitch = offPitch;
        if (v && !w.visible && w.hasSaved) { w.offYaw = w.svYaw; w.offPitch = w.svPitch; w.wantScale = w.svScale; w.placeDist = w.svDist; }
        if (v && !w.visible) {
            w.placeReq = true; w.newOpen = true; w.pendYaw = 0f; w.confT0 = 0;
            NvUi u = ui;
            if (u != null && u.open && w != menuWin && !w.noGhost) { w.ghost = true; u.setOpen(false); }       // picked from the menu: the menu folds away, the new window waits for one more click
            else w.ghost = false;
        }
        if (!v) { w.ghost = false; w.pendYaw = 0f; }
        w.visible = v;
    }
    public void toggle(Win w, float offYaw, float offPitch) { setVisible(w, !w.visible, offYaw, offPitch); }
    public void hideMenu() { if (menuWin != null) menuWin.visible = false; }

    private void toggleMenuGL() {
        if (menuWin == null) return;
        if (menuWin.visible) { menuWin.visible = false; if (dragWin == menuWin) endDrag(); }
        else {
            menuWin.offYaw = menuWin.hasSaved ? menuWin.svYaw : 0; menuWin.offPitch = menuWin.hasSaved ? menuWin.svPitch : 0;
            if (menuWin.hasSaved) { menuWin.wantScale = menuWin.svScale; menuWin.placeDist = menuWin.svDist; }
            menuWin.placeReq = true; menuWin.newOpen = true; menuWin.visible = true; if (ui != null) ui.setOpen(true); }
    }

    private boolean anyVisible() { for (Win w : wins) if (shown(w)) return true; return false; }
    public void setWinScale(Win w, float s) { w.scale = Math.max(0.5f, Math.min(2.5f, s)); }

    private void computeProj() {
        float aspect = (float) sceneW / Math.max(1, sceneH);
        float hf = Math.max(0.8f, Math.min(1.9f, cam.hFov));
        tanH = (float) Math.tan(hf / 2f);
        tanV = tanH / aspect;
        tanHc = tanH; tanVc = tanV;
        float tanBase = tanH;
        winZoom = 1f;
        if (stereo && vw > 1 && vh > 1) {
            // in the VR box every eye looks through a lens with its own field of view. If the picture is drawn with a different one,
            // the world turns faster or slower than your head (that is what makes you dizzy). So the scene is drawn with the lens' field of view,
            // and the camera picture is shown at its true angular size inside it.
            float ea = (float) (vw / 2) / vh;
            float scx = ea < aspect ? ea / aspect : 1f;
            float fv = Math.max(40f, Math.min(120f, vrFov));
            tanH = (float) Math.tan(Math.toRadians(fv) / 2.0) / scx;
            tanV = tanH / aspect;
            winZoom = Math.max(1f, Math.min(2.2f, tanH / tanBase)) * 1.2f;   // a wider view makes windows look smaller: make them bigger by the same amount
        }
        // the camera picture always fills the whole eye (a small picture with black borders is worse than a slight zoom),
        // so hands, cursor and pointer use the same mapping as the picture
        tanHc = tanH; tanVc = tanV;
        camScale = 1f;
        float fovy = (float) Math.toDegrees(2.0 * Math.atan(tanV));
        android.opengl.Matrix.perspectiveM(mP, 0, fovy, aspect, 0.05f, 20f);
    }

    // turn a window about the phone's own view axes (yaw about the vertical view axis, pitch about the horizontal one)
    private void turnWin(Win w, float[] rn, float th, float ph) {
        // the sideways turn is about the world's UP (not the phone's own vertical axis): when the head is tilted down or up while a window is put up or moved,
        // a turn about the phone's axis would put the window on a slanted circle, and it would slide up or down on screen as you turn your head to it
        float gx = rn[8], gy = rn[9], gz = rn[10], gl = (float) Math.sqrt(gx * gx + gy * gy + gz * gz);
        if (gl < 0.5f) { gx = 0f; gy = 1f; gz = 0f; } else { gx /= gl; gy /= gl; gz /= gl; }
        android.opengl.Matrix.setRotateM(tA, 0, th, gx, gy, gz);
        android.opengl.Matrix.setRotateM(tB, 0, ph, 1f, 0f, 0f);
        android.opengl.Matrix.multiplyMM(tC, 0, tA, 0, tB, 0);
        android.opengl.Matrix.multiplyMM(tD, 0, tC, 0, rn, 0);
        android.opengl.Matrix.transposeM(tA, 0, rn, 0);                    // rn is a pure turn: its transpose is its inverse
        android.opengl.Matrix.multiplyMM(tB, 0, tA, 0, tD, 0);
        android.opengl.Matrix.multiplyMM(tC, 0, tB, 0, w.rot, 0);
        w.rot = tC.clone();
    }

    // put a window at the exact place yaw/pitch degrees (right / up) from where the phone looks, facing the viewer.
    // The neighbours' places (yawA / pitchA) are measured this way, so the new window lands right beside them even when the head is tilted up or down
    // (a turn about the world's vertical would put it on a slanted circle and it would not line up with its neighbour)
    private void aimWin(Win w, float yawDeg, float pitchDeg) { aimDir(w, viewDir(yawDeg, pitchDeg)); }

    // turn the window (which faces straight ahead) by the smallest turn so that its middle is in the direction d (unit vector in the phone's view)
    private void aimDir(Win w, float[] d) {
        float ang = (float) Math.toDegrees(Math.acos(Math.max(-1f, Math.min(1f, -d[2]))));
        if (ang < 0.01f || Float.isNaN(ang)) return;
        float ax = d[1], ay = -d[0], al = (float) Math.sqrt(ax * ax + ay * ay);
        if (al < 1e-4f) { ax = 0f; ay = 1f; } else { ax /= al; ay /= al; }
        android.opengl.Matrix.setRotateM(tE, 0, ang, ax, ay, 0f);
        android.opengl.Matrix.multiplyMM(tF, 0, w.rot, 0, tE, 0);
        w.rot = tF.clone();
    }

    // The window stands like a wall (top edge up, no sideways tilt) and leans a little towards the eyes: below eye level it tilts its face up, above eye level it tilts down,
    // by half the angle it sits above / below (at most TILT_MAX), so it is easy to read without ever lying flat. It turns about its own centre, so it does not move.
    private static final float TILT_K = 0.75f, TILT_MAX = 40f;   // below eye level the face tilts up, above it tilts down (towards the eyes)
    private void uprightAboutCentre(Win w) {
        float[] r = w.rot;
        if (r == null) return;
        float bx = r[8], by = r[9], bz = r[10];
        float hl = (float) Math.sqrt(bx * bx + by * by);
        if (hl < 0.2f) { levelRoll(r); return; }                       // facing almost straight up or down: only the sideways tilt is taken out
        float nx = bx / hl, ny = by / hl;
        float cx = w.wx - bx * w.dist - sux, cy = w.wy - by * w.dist - suy, cz = w.wz - bz * w.dist - suz;     // where its middle is, seen from the eyes
        float el = (float) Math.toDegrees(Math.atan2(cz, Math.sqrt(cx * cx + cy * cy)));                      // + above eye level, - below
        float t = Math.max(-TILT_MAX, Math.min(TILT_MAX, -TILT_K * el));                                       // + = face tilted up
        float ct = (float) Math.cos(Math.toRadians(t)), st = (float) Math.sin(Math.toRadians(t));
        float mx = nx * ct, my = ny * ct, mz = st;                                                             // the new back axis (points to the eyes)
        w.wx += w.dist * (mx - bx); w.wy += w.dist * (my - by); w.wz += w.dist * (mz - bz);                    // the middle stays where it is: the origin moves with the turn
        float rx = -ny, ry = nx;                                                                              // right (level)
        float ux = -st * nx, uy = -st * ny, uz = ct;                                                          // up (level)
        float ca = (float) Math.cos(Math.toRadians(w.rollKeep)), sa = (float) Math.sin(Math.toRadians(w.rollKeep));
        r[0] = rx * ca + ux * sa; r[1] = ry * ca + uy * sa; r[2] = uz * sa;                                  // turned about its back axis by the kept tilt
        r[4] = -rx * sa + ux * ca; r[5] = -ry * sa + uy * ca; r[6] = uz * ca;
        r[8] = mx; r[9] = my; r[10] = mz;
    }

    // how much of the phone's own sideways tilt a new window keeps: a small head tilt is kept (the window looks straight in the view),
    // a big one (phone held upright, 90 degrees) is not: the window then stands level in the room
    private static float keepOf(float rollDeg) {
        float a = Math.abs(rollDeg);
        float k = a <= 25f ? 1f : (a >= 50f ? 0f : (50f - a) / 25f);
        return rollDeg * k;
    }
    private static float headRoll(float[] r) {                           // r: the window turn right after it was made from the phone's turn
        float bx = r[8], by = r[9], bz = r[10];
        if (Math.abs(bz) > 0.97f) return 0f;
        float rl = (float) Math.sqrt(bx * bx + by * by);
        float rx = -by / rl, ry = bx / rl;                              // level right
        float ux = -bz * ry, uy = bz * rx, uz = bx * ry - by * rx;       // level up = back x right
        float dr = r[0] * rx + r[1] * ry, du = r[0] * ux + r[1] * uy + r[2] * uz;
        return (float) Math.toDegrees(Math.atan2(du, dr));
    }

    // remove the sideways tilt (roll) from a window turn: keep where it faces, make its top edge point up (world up = +Z of the rotation sensor)
    private static void levelRoll(float[] r) {
        float bx = r[8], by = r[9], bz = r[10];                       // the window's back axis in the room
        if (Math.abs(bz) > 0.97f) return;                              // facing straight up or down: no sideways direction to level against
        float rx = -by, ry = bx;                                       // right = up x back, with up = (0,0,1)
        float rl = (float) Math.sqrt(rx * rx + ry * ry);
        if (rl < 1e-4f) return;
        rx /= rl; ry /= rl;
        float ux = by * 0f - bz * ry, uy = bz * rx - bx * 0f, uz = bx * ry - by * rx;   // up = back x right
        r[0] = rx; r[1] = ry; r[2] = 0f;
        r[4] = ux; r[5] = uy; r[6] = uz;
    }

    private final float[] lvl = new float[16];
    private float sux = 0, suy = 0, suz = 0;          // smoothed user position, so the estimate arriving in small steps looks smooth
    // When you have walked away from the spot where a window was put up, turning/pushing it "about that old spot" goes the wrong way.
    // So at the moment of grabbing, the window is re-based on where you stand now: same place in the room, now measured from you,
    // and turned to face you (the smallest turn that does it).
    private void rebase(Win w, float ux, float uy, float uz) {
        if (w.rot == null) return;
        float[] r = w.rot;
        float ax = -r[8], ay = -r[9], az = -r[10];
        float cx = w.wx + ax * w.dist, cy = w.wy + ay * w.dist, cz = w.wz + az * w.dist;
        float vx = cx - ux, vy = cy - uy, vz = cz - uz;
        float L = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (!(L > 0.2f) || Float.isNaN(L)) return;
        float nx = vx / L, ny = vy / L, nz = vz / L;
        float kx = ay * nz - az * ny, ky = az * nx - ax * nz, kz = ax * ny - ay * nx;
        float s = (float) Math.sqrt(kx * kx + ky * ky + kz * kz), c = ax * nx + ay * ny + az * nz;
        if (c > -0.99f && s > 1e-4f) {
            android.opengl.Matrix.setRotateM(tE, 0, (float) Math.toDegrees(Math.atan2(s, c)), kx / s, ky / s, kz / s);
            android.opengl.Matrix.multiplyMM(tF, 0, tE, 0, w.rot, 0);
            w.rot = tF.clone();
        }
        w.wx = ux; w.wy = uy; w.wz = uz;
        w.dist = L; w.dragD0 = L;
    }

    public volatile boolean neck = false;                   // the eyes sit in front of the neck: turning the head also moves the eyes a little (gives depth to the windows)
    private float mux = 0, muy = 0, muz = 0, hpx = 0, hpy = 0, hpz = 0;
    private void smoothUser() {
        NvMotion mo = motion;
        if (mo != null && mo.enabled) {
            // dead zone: the held position only follows when the tracked position is more than 2.5 cm away from it, so small jitter in the tracking never moves the windows
            float dx = mo.px - hpx, dy = mo.py - hpy, dz = mo.pz - hpz, dl = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dl > 0.025f) { float g = (1f - 0.025f / dl) * 0.2f; hpx += dx * g; hpy += dy * g; hpz += dz * g; }
            mux += (hpx - mux) * 0.12f; muy += (hpy - muy) * 0.12f; muz += (hpz - muz) * 0.12f;
        }
        else { mux = muy = muz = hpx = hpy = hpz = 0f; }
        float nx = 0f, ny = 0f, nz = 0f;
        float[] rn = rotView;
        if (neck && rn != null) {
            // the eyes are 9 cm above and 10 cm in front of the point the head turns around (the neck): where they are in the room = that offset turned by the head's turn
            final float ey = 0.09f, ez = -0.10f;
            nx = rn[1] * ey + rn[2] * ez; ny = rn[5] * ey + rn[6] * ez; nz = rn[9] * ey + rn[10] * ez;
        }
        sux = mux + nx; suy = muy + ny; suz = muz + nz;
    }

    /**
     * A window that comes up on its standard place (the user has not moved it yet) sits right next to the windows that are already up:
     * flush against the side of one of them (nearest to the standard place, the right side first), so the windows form a row and never lie on top of each other.
     * It then slides out from behind that neighbour into its place (see updateWin). Angles: positive yaw = to the right, positive pitch = up.
     */
    public volatile String placeDbg = "";                          // what the last placement did (shown in the HUD, to find out why a window did not land beside another)
    private float rowUx = 0f, rowUy = 1f, rowUz = 0f;                // the 'up' of the row of windows being built (the main menu's own up, in the phone's view): beside = along its own horizontal
    private float hitPw = 0.5f, hitPh = 0.4f, hitD = 1.5f;
    // a window tilted towards the eyes has its near edge closer than its middle, so it looks wider there: the distance (horizontal, m) of that near edge
    private static float nearD(float dh, float halfH, float sinTilt) { return Math.max(0.3f * dh, dh - halfH * Math.abs(sinTilt)); }
    private static float tiltSinOfEl(float elDeg) { return (float) Math.sin(Math.toRadians(Math.min(TILT_MAX, Math.abs(TILT_K * elDeg)))); }                      // the window being placed: its drawn width (m) and distance (m)
    // does a window put in the direction n (unit vector in the phone's view) land on another window? Measured along the row's horizontal (the same as where it is put),
    // so a window right beside its neighbour is not counted as overlapping it when the head is tilted up or down
    private boolean hitsAt(Win w, float[] nc, float hw, float hh) {
        float ux = rowUx, uy = rowUy, uz = rowUz;
        float fx = 0f, fy = 0f, fz = -1f, dp = fz * uz;
        fx -= dp * ux; fy -= dp * uy; fz -= dp * uz;                // straight ahead, flattened onto the row's horizontal
        float fl = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        boolean flat = fl > 0.2f;
        float hx = 0f, hy = 0f, hz = 0f;
        if (flat) { fx /= fl; fy /= fl; fz /= fl; hx = uy * fz - uz * fy; hy = uz * fx - ux * fz; hz = ux * fy - uy * fx; }
        float elC = (float) Math.asin(Math.max(-1f, Math.min(1f, nc[0] * ux + nc[1] * uy + nc[2] * uz)));
        float azC = flat ? (float) Math.atan2(nc[0] * hx + nc[1] * hy + nc[2] * hz, nc[0] * fx + nc[1] * fy + nc[2] * fz) : 0f;
        float hwC = flat ? (float) Math.toDegrees(Math.atan(hitPw / 2f / nearD(hitD * Math.max(0.3f, (float) Math.cos(elC)), hitPh / 2f, tiltSinOfEl((float) Math.toDegrees(elC))))) : hw;
        float cyaw = (float) Math.toDegrees(Math.atan2(nc[0], -nc[2])), cpit = (float) Math.toDegrees(Math.asin(Math.max(-1f, Math.min(1f, nc[1]))));
        for (Win o : wins) {
            if (o == w || !shown(o) || o.rot == null || o.placeReq || o.showInCinema && cinema) continue;
            android.graphics.Bitmap ob = o.c.bitmap();
            if (ob == null || ob.getWidth() <= 0) continue;
            float opw0 = o.widthM * o.scale * winZoom, oph = opw0 * ob.getHeight() / ob.getWidth(), opw = opw0 * o.c.visFrac();
            float od = Math.max(0.3f, o.dist);
            float ohh = (float) Math.toDegrees(Math.atan(oph / 2f / od));
            float ohwView = (float) Math.toDegrees(Math.atan(opw / 2f / od));
            if (ohwView > 45f) continue;                       // something huge (the cinema screen): do not dodge it
            if (!flat) {
                float dy = Math.abs(cyaw - o.yawA); if (dy > 180f) dy = 360f - dy;
                if (dy < hw + ohwView + 0.3f && Math.abs(cpit - o.pitchA) < hh + ohh + 0.3f) return true;
                continue;
            }
            float[] no = viewDir(o.yawA, o.pitchA);
            if (Math.abs(o.yawA) > 90f) { no[0] = (float) (Math.sin(Math.toRadians(o.yawA)) * Math.cos(Math.toRadians(o.pitchA))); no[1] = (float) Math.sin(Math.toRadians(o.pitchA)); no[2] = (float) (-Math.cos(Math.toRadians(o.yawA)) * Math.cos(Math.toRadians(o.pitchA))); }
            float elO = (float) Math.asin(Math.max(-1f, Math.min(1f, no[0] * ux + no[1] * uy + no[2] * uz)));
            float azO = (float) Math.atan2(no[0] * hx + no[1] * hy + no[2] * hz, no[0] * fx + no[1] * fy + no[2] * fz);
            float dAz = Math.abs(azC - azO); if (dAz > Math.PI) dAz = (float) (2 * Math.PI) - dAz;
            float ohwAz = (float) Math.toDegrees(Math.atan(opw / 2f / nearD(od * Math.max(0.3f, (float) Math.cos(elO)), oph / 2f, o.rot[10])));
            float dEl = (float) Math.toDegrees(Math.abs(elC - elO));
            if (Math.toDegrees(dAz) < hwC + ohwAz + 0.3f && dEl < hh + ohh + 0.3f) return true;
        }
        return false;
    }

    private static final float OPEN_MAXW = 0.85f;               // a window opens at most this wide (metres at the standard distance): not too big for the view
    private static final float MAX_FRONT_YAW = 65f;              // a new window never goes further round than this: never to the side of you or behind you
    private float openScale(Win w) { return Math.min(1f, OPEN_MAXW / Math.max(0.1f, w.widthM)); }

    /**
     * A new window sits right next to the windows that are already up (not next to your body): flush against the right or left side of one of them,
     * or just above / below it, never on top of another one. Of all the free places beside the open windows it takes the one nearest to where you look,
     * so the group grows outwards from the middle. It never goes round to the side or behind you, and it slides out from behind its neighbour (see updateWin).
     * Angles: positive yaw = to the right, positive pitch = up.
     */
    // direction (unit vector in the phone's view) of a spot yaw / pitch degrees to the right / up of straight ahead
    private static float[] viewDir(float yawDeg, float pitchDeg) {
        float x = (float) Math.tan(Math.toRadians(yawDeg)), y = (float) Math.tan(Math.toRadians(pitchDeg)), z = -1f;
        float l = (float) Math.sqrt(x * x + y * y + 1f);
        return new float[]{x / l, y / l, z / l};
    }
    // turn a direction about the room's up axis (k = up, in view coords) by deg degrees (positive = to the left seen from above)
    private static float[] turnAboutUp(float[] n, float deg, float kx, float ky, float kz) {
        float c = (float) Math.cos(Math.toRadians(deg)), s = (float) Math.sin(Math.toRadians(deg));
        float dot = kx * n[0] + ky * n[1] + kz * n[2];
        float cx = ky * n[2] - kz * n[1], cy = kz * n[0] - kx * n[2], cz = kx * n[1] - ky * n[0];
        return new float[]{n[0] * c + cx * s + kx * dot * (1 - c), n[1] * c + cy * s + ky * dot * (1 - c), n[2] * c + cz * s + kz * dot * (1 - c)};
    }

    // a window's own 'up' axis, seen from the phone (the main menu may be a little tilted, and windows beside it are lined up along its own horizontal)
    private static float[] upInView(Win o, float[] rn) {
        float x = o.rot[4], y = o.rot[5], z = o.rot[6];
        float vx = rn[0] * x + rn[4] * y + rn[8] * z, vy = rn[1] * x + rn[5] * y + rn[9] * z, vz = rn[2] * x + rn[6] * y + rn[10] * z;
        float l = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
        return l > 1e-4f ? new float[]{vx / l, vy / l, vz / l} : new float[]{rn[8], rn[9], rn[10]};
    }
    private void avoidOverlap(Win w) {
        w.slideDeg = 0f; w.slideDegP = 0f; w.aimV = null;
        if (w.hasSaved) return;
        if (w.anchor || w.lowDock) return;                        // the main menu always opens straight in front of you, other windows make room by sitting beside it
        if (w.shrunk) { w.wantScale = 0f; w.shrunk = false; }
        android.graphics.Bitmap bm = w.c.bitmap();
        if (bm == null || bm.getWidth() <= 0) { placeDbg = "place: no picture yet"; return; }
        // it must join the others: first beside the main menu in front of you, then smaller, then further and further round the side (a row around you: turn your head to it)
        if (tryPlace(w, bm, 1f, MAX_FRONT_YAW, false)) return;
        if (tryPlace(w, bm, 0.8f, MAX_FRONT_YAW, false)) return;
        if (tryPlace(w, bm, 0.65f, MAX_FRONT_YAW, false)) return;
        if (tryPlace(w, bm, 1f, 120f, false)) return;
        if (tryPlace(w, bm, 1f, 175f, false)) return;
        tryPlace(w, bm, 0.65f, 175f, true);                       // the last resort (not joined to the menu): stacked above / below the standard place
    }

    private static float asinDeg(float x) { return (float) Math.toDegrees(Math.asin(Math.max(-1f, Math.min(1f, x)))); }

    private boolean tryPlace(Win w, android.graphics.Bitmap bm, float f, float maxYaw, boolean stack) {
        float sc = (w.wantScale > 0 ? w.wantScale : openScale(w)) * f, d = w.placeDist > 0f ? w.placeDist : PANEL_D;
        float pw = w.widthM * sc * winZoom * w.c.visFrac(), ph = w.widthM * sc * winZoom * bm.getHeight() / bm.getWidth();
        float hw = (float) Math.toDegrees(Math.atan(pw / 2f / d)), hh = (float) Math.toDegrees(Math.atan(ph / 2f / d));
        float baseY = w.offYaw, baseP = w.offPitch;
        hitPw = pw; hitPh = ph; hitD = d;
        float[] rv = rotView; float wux = rv[8], wuy = rv[9], wuz = rv[10];            // the room's up, seen from the phone
        rowUx = wux; rowUy = wuy; rowUz = wuz;
        for (Win an : wins) if (an != w && an.anchor && shown(an) && an.rot != null && !an.placeReq) { float[] ua = upInView(an, rv); rowUx = ua[0]; rowUy = ua[1]; rowUz = ua[2]; break; }
        final float gap = 0.8f;                                   // degrees between two windows
        int cap = 4 * wins.size() + 4;
        float[][] cv = new float[cap][];
        float[] cy = new float[cap], cp = new float[cap], sY = new float[cap], sP = new float[cap];
        boolean[] isAnchor = new boolean[cap], isVert = new boolean[cap];
        int nc = 0;
        for (Win o : wins) {
            if (o == w || !shown(o) || o.rot == null || o.placeReq || o.showInCinema && cinema) continue;
            android.graphics.Bitmap ob = o.c.bitmap();
            if (ob == null || ob.getWidth() <= 0) continue;
            float opw0 = o.widthM * o.scale * winZoom, oph = opw0 * ob.getHeight() / ob.getWidth(), opw = opw0 * o.c.visFrac();
            float od = Math.max(0.3f, o.dist);
            float ohw = (float) Math.toDegrees(Math.atan(opw / 2f / od)), ohh = (float) Math.toDegrees(Math.atan(oph / 2f / od));
            if (ohw > 50f) continue;                                  // the cinema screen: not one to sit next to
            float[] nv = viewDir(o.yawA, o.pitchA);
            if (Math.abs(o.yawA) > 90f) { double ya = Math.toRadians(o.yawA), pa = Math.toRadians(o.pitchA); nv = new float[]{(float) (Math.sin(ya) * Math.cos(pa)), (float) Math.sin(pa), (float) (-Math.cos(ya) * Math.cos(pa))}; }
            float[] ko = upInView(o, rv);                                                                   // beside = along that window's own horizontal
            float kx = o.rot[0], ky = o.rot[1], kz = o.rot[2];                                              // its own right axis (room) -> view
            float[] kr = {rv[0] * kx + rv[4] * ky + rv[8] * kz, rv[1] * kx + rv[5] * ky + rv[9] * kz, rv[2] * kx + rv[6] * ky + rv[10] * kz};
            float sinEl = nv[0] * ko[0] + nv[1] * ko[1] + nv[2] * ko[2];
            float cosEl = Math.max(0.3f, (float) Math.sqrt(Math.max(0f, 1f - sinEl * sinEl)));
            float elNb = asinDeg(nv[0] * wux + nv[1] * wuy + nv[2] * wuz);                                  // the neighbour's height above eye level: both windows lean by about this much
            float wo = (float) Math.toDegrees(Math.atan(opw / 2f / nearD(od * cosEl, oph / 2f, o.rot[10])));
            float wn = (float) Math.toDegrees(Math.atan(pw / 2f / nearD(d * cosEl, ph / 2f, tiltSinOfEl(elNb))));
            float dA = wo + wn + gap, ev = ohh + hh + gap;
            for (int v4 = 0; v4 < 4; v4++) {
                float[] q; float slY = 0f, slP = 0f;
                if (v4 < 2) { int sd = v4 == 0 ? 1 : -1; q = turnAboutUp(nv, -sd * dA, ko[0], ko[1], ko[2]); slY = -sd * dA; }       // right of it / left of it
                else { int sg = v4 == 2 ? 1 : -1; q = turnAboutUp(nv, sg * ev, kr[0], kr[1], kr[2]); slP = -sg * ev; }               // above it / below it
                cv[nc] = q; cy[nc] = (float) Math.toDegrees(Math.atan2(q[0], -q[2])); cp[nc] = asinDeg(q[1]);
                sY[nc] = slY; sP[nc] = slP; isAnchor[nc] = o.anchor; isVert[nc] = v4 >= 2; nc++;
            }
        }
        if (nc == 0) { placeDbg = "place: nothing to sit next to (menu shown=" + (menuWin != null && shown(menuWin)) + ")"; return true; }      // nothing to sit next to: the standard place
        int best = -1; float bestC = 1e9f;
        boolean anchorOnly = false;                                // a free place next to the main menu beats every other place
        for (int i = 0; i < nc; i++) if (isAnchor[i] && (stack || !isVert[i]) && Math.abs(cy[i]) <= maxYaw && Math.abs(cp[i]) <= 50f && !hitsAt(w, cv[i], hw, hh)) { anchorOnly = true; break; }
        for (int i = 0; i < nc; i++) {
            if (anchorOnly && !isAnchor[i]) continue;
            if (Math.abs(cy[i]) > maxYaw || Math.abs(cp[i]) > 50f) continue;
            if (isVert[i] && !stack) continue;                        // above / below only as the very last resort: windows must line up beside each other
            if (hitsAt(w, cv[i], hw, hh)) continue;
            float c = 0.75f * Math.abs(cy[i]) + 0.25f * Math.abs(cy[i] - baseY) + Math.abs(cp[i] - baseP) + (isVert[i] ? 30f : 0f) + (isAnchor[i] ? -25f : 0f) + (sY[i] > 0f ? 0.01f : 0f);     // the right side wins a tie
            if (c < bestC) { bestC = c; best = i; }
        }
        if (best < 0) {
            placeDbg = "place: no free spot beside anything (" + nc + " tried, size " + Math.round(f * 100) + "%, to " + Math.round(maxYaw) + " deg)";
            if (stack) for (int k = 1; k <= 4; k++) for (int sg = -1; sg <= 1; sg += 2) {
                float p = baseP + sg * k * (hh * 2f + gap);
                if (Math.abs(p) <= 50f && !hitsAt(w, viewDir(baseY, p), hw, hh)) { w.offPitch = p; if (f < 1f) { w.wantScale = sc; w.shrunk = true; } return true; }
            }
            return false;
        }
        placeDbg = String.format(java.util.Locale.US, "place: %d spots, menu-first=%b, pick %d at yaw %.0f pitch %.0f (half-width %.0f deg, size %d%%)", nc, anchorOnly, best, cy[best], cp[best], hw, Math.round(f * 100));
        w.aimV = cv[best]; w.offYaw = cy[best]; w.offPitch = cp[best];
        w.slideDeg = sY[best];                                    // it starts at the middle of its neighbour and slides out to its place
        w.slideDegP = sP[best];
        if (f < 1f) { w.wantScale = sc; w.shrunk = true; }          // it came in smaller to fit
        return true;
    }

    private boolean pushRightNext = true;                           // a window exactly in the middle gives way to the right first, then to the left, and so on
    private boolean prevUiOpen = false;

    /**
     * A window that is opened comes up straight in front of you. The windows already there that would lie under it slide sideways (to the right or the left,
     * taking turns) and stay in a row beside it: each one only moves as far as it needs to, and the ones next to it move on in turn.
     */
    private void placeCentre(Win w) {
        w.slideDeg = 0f; w.slideDegP = 0f; w.aimV = null;
        if (w.shrunk) { w.wantScale = 0f; w.shrunk = false; }
        if (w == menuWin && w.newOpen) w.slideDegP = -16f;           // the main menu rises up out of the navigation bar
        if (w.lowDock) w.slideDegP = -14f;                           // the keyboard rises up from the bottom
        float yc = 0f;                                              // the yaw the new window comes up at: straight ahead, or where the user left it
        if (w.hasSaved) yc = w.offYaw; else w.offYaw = 0f;
        android.graphics.Bitmap bm = w.c.bitmap();
        float aspect = (bm == null || bm.getWidth() <= 0) ? 0.62f : (float) bm.getHeight() / bm.getWidth();      // no picture yet (a browser still loading): a usual window shape
        float sc = w.wantScale > 0 ? w.wantScale : openScale(w), d = w.placeDist > 0f ? w.placeDist : PANEL_D;
        float pw = w.widthM * sc * winZoom * w.c.visFrac(), ph = pw * aspect / Math.max(0.2f, w.c.visFrac());
        float elN = w.offPitch;
        float hwN = (float) Math.toDegrees(Math.atan(pw / 2f / nearD(d, ph / 4f, tiltSinOfEl(elN))));      // half the lean allowance: windows touch in the middle, hardly any gap
        float hhN = (float) Math.toDegrees(Math.atan(ph / 2f / d));
        if (w.lowDock) { placeDbg = "place: keyboard at the bottom (nothing slides away)"; return; }
        final float gap = 0.0f;                                   // flush: the edges touch
        java.util.ArrayList<Win> right = new java.util.ArrayList<>(), left = new java.util.ArrayList<>();
        float[] hwO = new float[wins.size()], yf = new float[wins.size()];
        java.util.HashMap<Win, Integer> idx = new java.util.HashMap<>();
        int n = 0;
        for (Win o : wins) {
            if (o == w || !shown(o) || o.rot == null || o.placeReq || o.ghost || !o.c.occupies() || o.showInCinema && cinema) continue;
            android.graphics.Bitmap ob = o.c.bitmap();
            float oasp = (ob == null || ob.getWidth() <= 0) ? 0.62f : (float) ob.getHeight() / ob.getWidth();
            float opw = o.widthM * o.scale * winZoom * o.c.visFrac(), oph = opw * oasp / Math.max(0.2f, o.c.visFrac());
            float od = Math.max(0.3f, o.dist);
            float ohh = (float) Math.toDegrees(Math.atan(oph / 2f / od));
            if (Math.abs(o.pitchA - elN) >= ohh + hhN) continue;                      // above or below: no need to give way
            float cosE = Math.max(0.3f, (float) Math.cos(Math.toRadians(o.pitchA)));
            float ohw = (float) Math.toDegrees(Math.atan(opw / 2f / nearD(od * cosE, oph / 4f, o.rot[10])));
            if (ohw > 50f) continue;                                                   // the cinema screen
            float y = o.yawA + o.pendYaw - yc;                                         // seen from the new window's own middle
            while (y > 180f) y -= 360f; while (y < -180f) y += 360f;
            idx.put(o, n); hwO[n] = ohw; yf[n] = y; n++;
            if (Math.abs(y) < 1.5f) { if (pushRightNext) right.add(o); else left.add(o); pushRightNext = !pushRightNext; }
            else if (y > 0f) right.add(o); else left.add(o);
        }
        if (n == 0) { placeDbg = "place: in the middle (nothing to move)"; return; }
        final float[] yfF = yf;
        right.sort((a, b) -> Float.compare(yfF[idx.get(a)], yfF[idx.get(b)]));
        left.sort((a, b) -> Float.compare(yfF[idx.get(b)], yfF[idx.get(a)]));
        float cur = hwN + gap; int moved = 0;
        for (Win o : right) {
            int i = idx.get(o); float pos = yf[i];
            if (pos - hwO[i] < cur) { pos = cur + hwO[i]; o.pendYaw = pos - (yf[i] - o.pendYaw); moved++; }
            cur = pos + hwO[i] + gap;
        }
        cur = -hwN - gap;
        for (Win o : left) {
            int i = idx.get(o); float pos = yf[i];
            if (pos + hwO[i] > cur) { pos = cur - hwO[i]; o.pendYaw = pos - (yf[i] - o.pendYaw); moved++; }
            cur = pos - hwO[i] - gap;
        }
        placeDbg = "place: in the middle, " + moved + " window(s) slide aside (" + right.size() + " right, " + left.size() + " left)";
    }

    private static final float ANIM_MS = 520f;                     // how long a window takes to slide in

    private void updateWin(Win w, float[] rn, float t) {
        float ux = sux, uy = suy, uz = suz;
        if (w.placeReq || w.rot == null) {
            placeCentre(w);
            if (w.newOpen) { w.newOpen = false; w.animOn = true; w.animT0 = System.nanoTime(); }     // a window that is opened (not just re-centred) slides in
            else w.animOn = false;
            android.opengl.Matrix.transposeM(tA, 0, rn, 0);
            w.rot = tA.clone();
            w.rollKeep = 0f;                                           // like a real thing in the room: level with the floor, whatever the head's tilt was when it came up (it used to keep up to 25 deg of it, which left windows rolled once the head was straight again)
            if (w.aimV != null) aimDir(w, w.aimV);
            else if (w.offYaw != 0f || w.offPitch != 0f) aimWin(w, w.offYaw, w.offPitch);
            w.scale = w.wantScale > 0 ? w.wantScale : openScale(w);
            w.dist = w.placeDist > 0f ? w.placeDist : PANEL_D;
            w.wx = ux; w.wy = uy; w.wz = uz;
            uprightAboutCentre(w);                                     // a wall standing straight up in the room, wherever the head was pitched when it was put up
            w.placeReq = false;
        }
        if (w.pendYaw != 0f && dragWin != w) {                        // sliding sideways to make room for a window that was just opened
            float st = Math.abs(w.pendYaw) < 0.1f ? w.pendYaw : w.pendYaw * 0.12f;
            turnWin(w, rn, -st, 0f);
            uprightAboutCentre(w);
            w.pendYaw -= st;
            if (Math.abs(w.pendYaw) < 1e-3f) w.pendYaw = 0f;
        } else if (dragWin == w) w.pendYaw = 0f;
        // where the window centre is now, seen from the phone: angle to the right and angle upwards
        android.opengl.Matrix.translateM(mM, 0, w.rot, 0, 0f, 0f, -w.dist);
        shiftBy(w, mM, ux, uy, uz);
        android.opengl.Matrix.multiplyMM(w.mv, 0, rn, 0, mM, 0);
        w.yawA = (float) Math.toDegrees(Math.atan2(w.mv[12], -w.mv[14]));
        w.pitchA = (float) Math.toDegrees(Math.atan2(w.mv[13], -w.mv[14]));
        if (dragWin == w && !resizing) {
            float th = (w.yawA - (rayYaw + w.dragYawOff)) * 0.65f;
            float ph = ((rayPitch + w.dragPitchOff) - w.pitchA) * 0.65f;
            th = Math.max(-7f, Math.min(7f, th));              // at most a few degrees per frame, so nothing can snap away
            ph = Math.max(-7f, Math.min(7f, ph));
            if (th != 0f || ph != 0f) turnWin(w, rn, th, ph);
            uprightAboutCentre(w);                             // and it stays standing straight while it is carried up or down
        }
        // (a window that is carried is kept standing straight in the room by uprightAboutCentre above: no turn to the phone's own horizontal, which would roll it when the head is tilted)
        float bx = 0f, by = 0f, bz = 0f;
        if (floaty && dragWin != w) {
            bx = 0.006f * (float) Math.sin(t * 0.6f + 1f + w.phase);
            by = 0.008f * (float) Math.sin(t * 0.9f + w.phase);
            bz = 0.010f * (float) Math.sin(t * 0.4f + 2f + w.phase);
        }
        // coming in: it starts behind its neighbour (a bit further away, a bit small, see-through, turned towards the middle) and slides out to its place
        float sx = 0f, sy = 0f, sz = 0f, tilt = 0f;
        w.animA = 1f; w.animS = 1f;
        if (w.animOn) {
            float x = (System.nanoTime() - w.animT0) / 1e6f / ANIM_MS;
            if (x >= 1f) w.animOn = false;
            else {
                float rest = 1f - x, e = 1f - rest * rest * rest, left = 1f - e;                 // ease out: quick at first, settles softly
                sx = w.dist * 2f * (float) Math.sin(Math.toRadians(w.slideDeg) / 2.0) * left;               // sideways, along the window's own right
                sy = w.dist * 2f * (float) Math.sin(Math.toRadians(w.slideDegP) / 2.0) * left;              // and upwards / downwards
                sz = -0.16f * left;                                                              // further away at first, comes forward
                tilt = Math.signum(w.slideDeg) * 14f * left;
                w.animA = Math.min(1f, x * 2.6f);
                w.animS = 0.9f + 0.1f * e;
            }
        }
        if (w.ghost) w.animA *= 0.38f;                                  // waiting for the confirming click: see-through
        else if (w.confT0 != 0) {
            float cx = (System.nanoTime() - w.confT0) / 1e6f / 420f;
            if (cx >= 1f) w.confT0 = 0; else { float cr = 1f - cx; w.animA *= 0.38f + 0.62f * (1f - cr * cr * cr); }
        }
        // no sideways lean in the picture: the window turns about its own centre (about the line to your eyes) until its top edge points up on the screen. This is worked out
        // straight from the geometry of where the window is seen, so a window off to the side or below eye level does not show the slanted top and bottom edges of a wall
        // (and it does not depend on a roll angle that gets noisy when you look up or down). A big difference (the phone held upright) fades out: then the window stays level in the room.
        // a window is a thing in the room: its top edge is made level with the floor here every picture (whatever tilt its stored turn may still carry)
        float[] rl = lvl; System.arraycopy(w.rot, 0, rl, 0, 16); levelRoll(rl);
        // (the extra roll that was tried here to take out the slant of a window seen from the side turned the windows far too much: taken out again)
        android.opengl.Matrix.translateM(mM, 0, rl, 0, bx + sx, by + sy, -w.dist + bz + sz);
        if (tilt != 0f) android.opengl.Matrix.rotateM(mM, 0, tilt, 0f, 1f, 0f);
        shiftBy(w, mM, ux, uy, uz);
        android.opengl.Matrix.multiplyMM(w.mv, 0, rn, 0, mM, 0);
        android.opengl.Matrix.multiplyMM(w.mvp, 0, mP, 0, w.mv, 0);
        android.opengl.Matrix.invertM(w.inv, 0, w.mv, 0);
    }

    private void computePanel() {
        { NvUi u0 = ui; boolean uo = u0 != null && u0.open;
          if (uo && !prevUiOpen && menuWin != null && menuWin.visible && !menuWin.placeReq && !menuWin.hasSaved) { menuWin.placeReq = true; menuWin.newOpen = true; menuWin.offYaw = 0f; }
          prevUiOpen = uo; }
        float[] rn = rotView;                                        // windows are fixed in the room: they do not follow the phone's tilt
        if (recenter) {
            recenter = false;
            if (resetLayoutReq) {                                    // the OK sign: forget where the windows were left, back to the standard places and sizes
                resetLayoutReq = false;
                for (Win w : wins) { w.hasSaved = false; w.wantScale = 0f; w.placeDist = 0f; w.offYaw = w.defYaw; w.offPitch = w.defPitch; }
                if (menuWin != null) { menuWin.offYaw = 0f; menuWin.offPitch = 0f; }
                if (ui != null) ui.toast("จัดหน้าต่างและขนาดใหม่แล้ว");
            }
            // every window that is up goes back in front of you (keeping the place the user gave it, relative to where you look now)
            for (Win w : wins) if (w.visible) {
                w.placeReq = true;
                if (w.hasSaved) { w.offYaw = w.svYaw; w.offPitch = w.svPitch; w.wantScale = w.svScale; w.placeDist = w.svDist; } else w.placeDist = 0f;
            }
        }
        computeProj();
        float t = (System.nanoTime() / 1e9f) % 600f;
        smoothUser();
        for (Win w : wins) if (shown(w)) updateWin(w, rn, t);
    }

    // where the pointing finger meets the window, in that window's picture pixels
    private boolean hitWin(Win w, float nx, float ny, float[] out) {
        android.graphics.Bitmap bm = w.c.bitmap();
        if (bm == null) return false;
        float dx = nx * tanHc, dy = ny * tanVc, dz = -1f;
        float[] mInv = w.inv;
        float ox = mInv[12], oy = mInv[13], oz = mInv[14];
        float Dz = mInv[2] * dx + mInv[6] * dy + mInv[10] * dz;
        float Dx = mInv[0] * dx + mInv[4] * dy + mInv[8] * dz;
        float Dy = mInv[1] * dx + mInv[5] * dy + mInv[9] * dz;
        if (Math.abs(Dz) < 1e-5f) return false;
        float t = -oz / Dz;
        if (t <= 0) return false;
        float lx = ox + t * Dx, ly = oy + t * Dy;
        float pw = w.widthM * w.scale * winZoom;
        float ph = pw * bm.getHeight() / bm.getWidth();
        float u = lx / pw + 0.5f, v = 0.5f - ly / ph;
        if (u < -0.05f || u > 1.05f || v < -0.05f || v > 1.05f) return false;
        out[0] = u * bm.getWidth();
        out[1] = v * bm.getHeight();
        return true;
    }

    private void endDrag() {
        if (dragWin != null) {
            Win dw = dragWin;
            if (dw.rot != null && !Float.isNaN(dw.yawA) && !Float.isNaN(dw.pitchA)) {
                dw.svYaw = dw.yawA; dw.svPitch = dw.pitchA; dw.svScale = dw.scale; dw.svDist = dw.dist;      // (no longer remembered for next time: a window that is opened always slides in next to the others)
            }
            dw.c.setDragging(false);
        }
        dragWin = null;
        resizing = false;
    }

    private float[] lastRes, vel = new float[1];
    private long handT = 0;
    private float[] disp = new float[]{0};
    private float curX = 0, curY = 0;
    private boolean curOk = false;

    private volatile int imgW = 0, imgH = 0;
    private volatile boolean rot180 = false;
    private int sceneW = 0, sceneH = 0;
    private int[] fbo = new int[1], sceneTex = new int[1], camTex = new int[1];
    private SurfaceTexture st;
    private final Object lock = new Object();
    private boolean newFrame = false;
    private final float[] stm = new float[16];
    private int vw = 1, vh = 1;
    private int progCam, progCol, progFin, progRing, progCity;
    private int lutTex = 0; private long lutUp = 0;
    private int segLutTex = 0, segLutUse = 0; private NvCam.SegOut segLutFor = null; private int progSeg = 0; private final int[] segTex = new int[2]; private final NvCam.SegOut[] segUp = new NvCam.SegOut[2]; private boolean segDraw = false;
    private final float[] sgV = new float[16]; private final java.nio.FloatBuffer sgBuf = java.nio.ByteBuffer.allocateDirect(16 * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
    private int[] maskFbo = new int[1], maskTex = new int[1], refFbo = new int[1], refTex = new int[1];   // maskTex: the hand shape from the model; refTex: the same, checked against the skin colour of the camera picture
    private int maskW = 0, maskH = 0;
    private FloatBuffer triBuf;
    private FloatBuffer quad, lineBuf, ptBuf;
    private long fc = 0, ft0 = 0;

    public VRRenderer(Host h, NvCam c) { host = h; cam = c; }

    public void setImage(int w, int h, boolean r180) { rot180 = r180; imgH = h; imgW = w; }

    @Override public void onFrameAvailable(SurfaceTexture s) { synchronized (lock) { newFrame = true; } }

    private static int sh(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        return s;
    }
    private static int prog(String vs, String fs) {
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, sh(GLES20.GL_VERTEX_SHADER, vs));
        GLES20.glAttachShader(p, sh(GLES20.GL_FRAGMENT_SHADER, fs));
        GLES20.glLinkProgram(p);
        return p;
    }
    private static FloatBuffer fb(int n) {
        return ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    // uniform places are looked up once per program (asking the driver by name every frame is wasted work)
    private final java.util.HashMap<Integer, java.util.HashMap<String, Integer>> ulc = new java.util.HashMap<>();
    private int ul(int prog, String n) {
        java.util.HashMap<String, Integer> m = ulc.get(prog);
        if (m == null) { m = new java.util.HashMap<>(); ulc.put(prog, m); }
        Integer v = m.get(n);
        if (v == null) { v = GLES20.glGetUniformLocation(prog, n); m.put(n, v); }
        return v;
    }

    @Override public void onSurfaceCreated(GL10 gl, EGLConfig cfg) {
        ulc.clear();
        java.util.Arrays.fill(alc, 0);
        depthRb[0] = 0;
        { NvSword3D w3 = world3; if (w3 != null) w3.glLost(); }
        GLES20.glDisable(GLES20.GL_DITHER);                // no dithering: a little less work for every pixel
        panoTex = 0; panoOn = false;
        for (Win w : wins) if (w.c instanceof NvVideo) ((NvVideo) w.c).glLost();
        progCam = prog(
            "attribute vec2 p; uniform float zs; varying vec2 pp; void main(){ gl_Position=vec4(p*zs,0.,1.); pp=p; }",
            "#extension GL_OES_EGL_image_external : require\n#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n" +
            "uniform samplerExternalOES tex; uniform vec2 px; uniform float amt; uniform float lift; uniform float rf; uniform float wp; uniform mat3 dm; uniform vec2 tn; uniform sampler2D mk; uniform vec2 mpx; uniform float mz; uniform float mkOn; uniform vec4 sm; uniform float smOn; uniform float sy; uniform vec4 sb; uniform float sbOn; uniform sampler2D lut; uniform float lutOn; uniform float geo; uniform float so; uniform sampler2D cl; varying vec2 pp;" +
            "float clog(vec3 c){ vec3 u=clamp(c*16.-0.5,0.,15.); float b0=floor(u.b), fb=u.b-b0, b1=min(b0+1.,15.);" +
            " vec2 x0=vec2(mod(b0,4.)*16.+u.r+0.5, floor(b0/4.)*16.+u.g+0.5)*(1./64.), x1=vec2(mod(b1,4.)*16.+u.r+0.5, floor(b1/4.)*16.+u.g+0.5)*(1./64.);" +
            " return (mix(texture2D(cl,x0).r,texture2D(cl,x1).r,fb)-0.5)*10.; }" +
            "float skinAt(vec3 c){ float cb=-0.169*c.r-0.331*c.g+0.5*c.b+0.5, cr=0.5*c.r-0.419*c.g-0.081*c.b+0.5, yy=0.299*c.r+0.587*c.g+0.114*c.b;" +
            " vec2 d=smOn>0.5 ? vec2(cb-sm.x,cr-sm.y)/(sm.zw*2.4) : vec2(cb-0.41,cr-0.60)/vec2(0.11,0.09);" +        // learnt skin colour of this person (Gaussian in Cb/Cr, brightness left out so shade does not matter), or a general one until it is learnt
            " float lum=smOn>0.5 ? 1.-smoothstep(0.30,0.48,abs(yy-sy)) : 1.;" +                                      // wood / furniture of a similar colour is usually darker or lighter than the palm
            " float bgk=1.;" +
            " if(smOn>0.5 && sbOn>0.5){ vec2 ds=vec2(cb-sm.x,cr-sm.y)/sm.zw, db=vec2(cb-sb.x,cr-sb.y)/sb.zw; float gs=exp(-0.5*dot(ds,ds)), gb=exp(-0.5*dot(db,db)); bgk=smoothstep(0.30,0.70,gs/(gs+gb+0.001)); }" +   // closer to the hand's colour than to the background's colour (floor, table)
            " if(lutOn>0.5){ float pr=texture2D(lut,clamp(vec2(cb,cr),0.,1.)).r; return smoothstep(0.35,0.70,pr)*smoothstep(0.02,0.10,yy); }" +                    // the colour table learnt from the real pixels of this hand and of the room round it
            " return (1.-smoothstep(0.7,1.15,length(d)))*lum*bgk*smoothstep(0.02,0.10,yy); }" +
            "void main(){ vec2 q=pp; if(wp>0.5){ vec3 d=dm*vec3(pp.x*tn.x,pp.y*tn.y,-1.); if(d.z>-0.02){ gl_FragColor=vec4(0.,0.,0.,1.-mkOn); return; } q=vec2(d.x/(-d.z)/tn.x, d.y/(-d.z)/tn.y); }" +
            " if(abs(q.x)>1.||abs(q.y)>1.){ gl_FragColor=vec4(0.,0.,0.,1.-mkOn); return; }" +
            " vec2 t=q*0.5+0.5; t=mix(t,1.-t,rf); vec2 uv=vec2(t.x,1.-t.y); vec4 c=texture2D(tex,uv); vec4 c0=c;" +
            " if(amt>0.){ vec4 b=(texture2D(tex,uv+vec2(px.x,0.))+texture2D(tex,uv-vec2(px.x,0.))+texture2D(tex,uv+vec2(0.,px.y))+texture2D(tex,uv-vec2(0.,px.y)))*0.25; c=clamp(c+(c-b)*amt,0.,1.); }" +
            " if(lift>0.){ c.rgb=min(pow(c.rgb,vec3(1./(1.+lift*0.8)))*(1.+lift*0.5),vec3(1.)); }" +
            // masked pass ("see the real hand through the windows"): the camera picture is drawn again, only where the hand is
            " if(mkOn>0.5){ vec2 mu=pp*mz*0.5+0.5;" +
            "  if(mkOn>1.5){" +                                               // refine: the model's hand shape (R = band, G = sure core) checked against the skin colour of THIS camera picture
            "   float m=texture2D(mk,mu).r*0.25+(texture2D(mk,mu+vec2(mpx.x,0.)).r+texture2D(mk,mu-vec2(mpx.x,0.)).r+texture2D(mk,mu+vec2(0.,mpx.y)).r+texture2D(mk,mu-vec2(0.,mpx.y)).r)*0.125" +
            "    +(texture2D(mk,mu+mpx).r+texture2D(mk,mu-mpx).r+texture2D(mk,mu+vec2(mpx.x,-mpx.y)).r+texture2D(mk,mu+vec2(-mpx.x,mpx.y)).r)*0.0625;" +
            "   float gc=texture2D(mk,mu).g*0.4+(texture2D(mk,mu+vec2(mpx.x,0.)).g+texture2D(mk,mu-vec2(mpx.x,0.)).g+texture2D(mk,mu+vec2(0.,mpx.y)).g+texture2D(mk,mu-vec2(0.,mpx.y)).g)*0.15;" +
            "   float core=smoothstep(0.35,0.65,gc);" +
            "   if(so>0.5){ float g=texture2D(mk,mu).b*0.4+(texture2D(mk,mu+vec2(mpx.x,0.)).b+texture2D(mk,mu-vec2(mpx.x,0.)).b+texture2D(mk,mu+vec2(0.,mpx.y)).b+texture2D(mk,mu-vec2(0.,mpx.y)).b)*0.15; float segA=smoothstep(0.40,0.60,g); float a2=max(segA,core);" +
            "    if(so>1.5){ if(m<0.03&&g<0.05&&core<0.05){ gl_FragColor=vec4(0.); return; } vec2 o1=vec2(px.x*3.,0.), o2=vec2(0.,px.y*3.);" +                                        // the colours of THIS camera picture (not of the picture the cut was made from) decide inside the widened cut
            "     float lg=(clog(c0.rgb)*2.+clog(texture2D(tex,uv+o1).rgb)+clog(texture2D(tex,uv-o1).rgb)+clog(texture2D(tex,uv+o2).rgb)+clog(texture2D(tex,uv-o2).rgb))/6.;" +
            "     float cp=1./(1.+exp(-lg)); float dilA=smoothstep(0.30,0.55,m);" +                                  // m = the widened cut (R)
            "     a2=max(max(smoothstep(0.35,0.65,cp)*dilA, segA*smoothstep(0.10,0.35,cp)), core*smoothstep(0.05,0.20,cp)); }" +
            "    gl_FragColor=vec4(a2,a2,a2,a2); return; }" +   // the hand cut out of the real pixels (NvSeg), carried to where the hand is now
            "   float a=smoothstep(0.30,0.60,m); if(a<0.01&&core<0.01){ gl_FragColor=vec4(0.); return; }" +
            "   if(geo>0.5){ a=max(a,core); gl_FragColor=vec4(a,a,a,a); return; }" +                            // outline modes: the shape alone (steady, no flowing from the colour test)
            "   float sk=skinAt(c0.rgb);" +
            "   vec2 o1=vec2(px.x*2.,0.), o2=vec2(0.,px.y*2.), o3=vec2(px.x*2.,px.y*2.), o4=vec2(px.x*2.,-px.y*2.);" +
            "   float sa=(sk*2.+skinAt(texture2D(tex,uv+o1).rgb)+skinAt(texture2D(tex,uv-o1).rgb)+skinAt(texture2D(tex,uv+o2).rgb)+skinAt(texture2D(tex,uv-o2).rgb)" +
            "    +skinAt(texture2D(tex,uv+o3).rgb)+skinAt(texture2D(tex,uv-o3).rgb)+skinAt(texture2D(tex,uv+o4).rgb)+skinAt(texture2D(tex,uv-o4).rgb))*0.1;" +        // averaged over 9 taps: no speckles, smooth edge
            "   sk=sa;" +
            "   a=max(a*smoothstep(0.30,0.62,sk),core); gl_FragColor=vec4(a,a,a,a); return; }" +
            "  float m=texture2D(mk,mu).r*0.25+(texture2D(mk,mu+vec2(mpx.x,0.)).r+texture2D(mk,mu-vec2(mpx.x,0.)).r+texture2D(mk,mu+vec2(0.,mpx.y)).r+texture2D(mk,mu-vec2(0.,mpx.y)).r)*0.125" +
            "   +(texture2D(mk,mu+mpx).r+texture2D(mk,mu-mpx).r+texture2D(mk,mu+vec2(mpx.x,-mpx.y)).r+texture2D(mk,mu+vec2(-mpx.x,mpx.y)).r)*0.0625;" +
            "  float a=smoothstep(0.35,0.65,m); gl_FragColor=vec4(c.rgb*a,a); } else gl_FragColor=c; }");
        progCol = prog(
            "attribute vec2 p; uniform float ps; void main(){ gl_Position=vec4(p,0.,1.); gl_PointSize=ps; }",
            "precision mediump float; uniform vec4 col; uniform float rnd; void main(){ if (rnd>0.5 && length(gl_PointCoord-0.5)>0.5) discard; gl_FragColor=col; }");
        progSeg = prog(
            "attribute vec2 p; attribute vec2 t; varying vec2 uv; void main(){ gl_Position=vec4(p,0.,1.); uv=t; }",
            "precision mediump float; uniform sampler2D tex; uniform float w; varying vec2 uv; void main(){ vec4 t=texture2D(tex,uv); gl_FragColor=vec4(t.a,0.,t.r,w); }");
        segTex[0] = 0; segTex[1] = 0; segUp[0] = null; segUp[1] = null; segLutTex = 0; segLutFor = null;
        progFin = prog(
            "attribute vec2 p; varying vec2 c; void main(){ gl_Position=vec4(p,0.,1.); c=p; }",
            "precision mediump float; uniform sampler2D tex; uniform vec2 sc; uniform float k; uniform float sh; uniform float ca; varying vec2 c;" +
            "void main(){ vec2 q=c; q.x+=sh; float r2=dot(q,q); q*=1.+k*r2; vec2 uv=0.5+0.5*q*sc;" +
            " if(uv.x<0.||uv.x>1.||uv.y<0.||uv.y>1.){ gl_FragColor=vec4(0.,0.,0.,1.); return; }" +
            " if(abs(ca)<0.0005){ gl_FragColor=vec4(texture2D(tex,uv).rgb,1.); return; }" +
            " vec2 ur=0.5+0.5*q*(1.+ca*r2)*sc, ub=0.5+0.5*q*(1.-ca*r2)*sc;" +
            " gl_FragColor=vec4(texture2D(tex,clamp(ur,0.,1.)).r, texture2D(tex,uv).g, texture2D(tex,clamp(ub,0.,1.)).b, 1.); }");
        progUi = prog(
            "attribute vec2 p; varying vec2 uv; void main(){ gl_Position=vec4(p,0.,1.); uv=vec2(p.x*0.5+0.5, 0.5-p.y*0.5); }",
            "precision mediump float; uniform sampler2D tex; varying vec2 uv; void main(){ gl_FragColor=texture2D(tex,uv); }");
        uiTex = 0;
        for (Win w : wins) { w.tex = 0; w.texW = 0; w.texH = 0; }      // a new graphics context: every window picture must be uploaded again in full
        progRing = prog(
            "attribute vec2 p; varying vec2 uv; void main(){ gl_Position=vec4(p,0.,1.); uv=p*0.5+0.5; }",
            "precision mediump float; uniform sampler2D tex; uniform vec2 px; uniform float rad; uniform vec4 h0; uniform vec4 h1; uniform vec3 hs; uniform float gl; varying vec2 uv;" +
            "float s(float x, float y){ return texture2D(tex,uv+vec2(x,y)*px*rad).r; }" +
            // how much of this spot belongs to the hand and not to the forearm: full over the palm and fingers, melting away from the wrist down the arm
            "float hf(vec4 h, vec2 q){ vec2 d=q-h.xy; float t=dot(d,h.zw); float L=hs.x; return smoothstep(-0.30*L,0.40*L,t)*(1.0-smoothstep(2.2*L,3.4*L,length(d))); }" +
            "void main(){" +
            " float m = s(0.,0.)*0.25 + (s(1.,0.)+s(-1.,0.)+s(0.,1.)+s(0.,-1.))*0.125 + (s(1.,1.)+s(-1.,1.)+s(1.,-1.)+s(-1.,-1.))*0.0625;" +
            " float w = (s(3.,0.)+s(-3.,0.)+s(0.,3.)+s(0.,-3.))*0.25;" +
            " float line = 1.0 - smoothstep(0.10, 0.30, abs(m-0.5));" +
            " float glow = 0.45*smoothstep(0.0,0.6,w)*(1.0-smoothstep(0.30,0.50,m));" +
            " float e = max(line, glow);" +
            " float dk = 0.40*(1.0 - smoothstep(0.0, 0.16, abs(m-0.28)));" +
            " float fl = 0.0;" +
            " if (gl > 0.5) {" +
            "  vec2 q = vec2(uv.x*hs.y, uv.y);" +
            "  float f = hf(h0, q); if (hs.z > 1.5) f = max(f, hf(h1, q));" +
            "  float ins = smoothstep(0.40, 0.62, m);" +
            "  fl = 0.24*ins*f;" +                                           // a thin clear white film over the hand, like frosted glass
            "  e *= 0.35 + 0.65*f; dk *= f;" +
            " }" +
            " float a = max(max(e, dk), fl);" +
            " gl_FragColor = vec4(vec3(0.92,0.97,1.0)*max(e, fl), a); }");
        progAA = prog(
            "attribute vec2 p; attribute vec4 q; varying vec4 vq; void main(){ gl_Position=vec4(p,0.,1.); vq=q; }",
            "precision mediump float; uniform vec3 col; uniform float sf; uniform float fade; varying vec4 vq;" +
            "void main(){ float t=clamp(vq.x,0.,vq.z); float d=length(vec2(vq.x-t,vq.y)); float al=clamp((1.-d)*vq.w/sf,0.,1.); gl_FragColor=vec4(col*al,al)*fade; }");
        aaBuf = fb(6 * 6 * 60);
        triBuf = fb(4096);
        lutTex = 0; maskW = 0; maskFbo[0] = 0; maskTex[0] = 0; refFbo[0] = 0; refTex[0] = 0;
        progPanel = prog(
            "attribute vec2 p; uniform mat4 mvp; uniform vec2 hs; varying vec2 uv; void main(){ gl_Position=mvp*vec4(p*hs,0.,1.); uv=vec2(p.x*0.5+0.5, 0.5-p.y*0.5); }",
            "precision mediump float; uniform sampler2D tex; uniform float al; varying vec2 uv; void main(){ gl_FragColor=texture2D(tex,uv)*al; }");
        progVid = prog(
            "attribute vec2 p; uniform mat4 mvp; uniform vec2 hs; uniform vec2 off; uniform vec2 fit; uniform mat4 stm; varying vec2 uv; varying vec2 c;" +
            "void main(){ gl_Position=mvp*vec4(p*hs+off,0.,1.); c=p*0.5*fit+0.5; uv=(stm*vec4(c,0.,1.)).xy; }",
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES tex; varying vec2 uv; varying vec2 c;" +
            "void main(){ if(c.x<0.||c.x>1.||c.y<0.||c.y>1.) gl_FragColor=vec4(0.,0.,0.,1.); else gl_FragColor=texture2D(tex,uv); }");
        progPano = prog(
            "attribute vec2 p; uniform vec2 tn; varying vec2 ray; void main(){ gl_Position=vec4(p,0.,1.); ray=p*tn; }",
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n" +
            "uniform sampler2D tex; uniform mat3 rm; uniform float lon0; varying vec2 ray;" +
            "void main(){ vec3 d=normalize(vec3(ray,-1.)); vec3 w=d*rm; float lon=atan(w.x,w.y)+lon0; float lat=asin(clamp(w.z,-1.,1.));" +
            " gl_FragColor=texture2D(tex,vec2(fract(lon/6.2831853+0.5),0.5-lat/3.1415927)); }");
        progEnv = prog(                                  // the night scene of the 3D game: sky, moon, stars, far hills and a stone floor, all worked out per pixel from the way you look
            "attribute vec2 p; uniform vec2 tn; varying vec2 ray; void main(){ gl_Position=vec4(p,0.,1.); ray=p*tn; }",
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n" +
            "uniform mat3 rm; uniform vec3 eo; varying vec2 ray;" +
            "float h21(vec2 p){ return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453); }" +
            "void main(){ vec3 d=normalize(vec3(ray,-1.)); vec3 w=d*rm; vec3 hor=vec3(0.40,0.15,0.20); vec3 col;" +
            " if(w.z<-0.004){ float t=eo.z/(-w.z); vec2 g=eo.xy+w.xy*t; vec2 c=g*0.8; vec2 f=fract(c); vec2 id=floor(c);" +
            "  float ln=step(0.05,f.x)*step(0.05,f.y); float sh=0.10+0.10*h21(id); vec3 gc=mix(vec3(0.02,0.02,0.03),vec3(sh*0.95,sh*0.9,sh*1.05),ln);" +
            "  float ring=smoothstep(0.0,1.0,1.0-clamp(t/9.,0.,1.)); gc+=vec3(0.10,0.05,0.02)*ring*0.5;" +
            "  float fog=clamp(t/26.,0.,1.); fog=fog*fog*(3.-2.*fog); col=mix(gc,hor,fog); }" +
            " else { float e=max(w.z,0.); col=mix(hor,vec3(0.02,0.02,0.10),pow(min(e*1.3,1.),0.55));" +
            "  vec3 md=normalize(vec3(0.5,0.8,0.42)); float m=dot(w,md); col+=vec3(1.,0.95,0.78)*smoothstep(0.9965,0.9985,m)+vec3(0.35,0.30,0.25)*pow(max(m,0.),70.);" +
            "  vec3 q=floor(w*110.); float st=step(0.9935,h21(q.xy+q.z*13.7)); col+=vec3(st*0.85)*smoothstep(0.12,0.5,e);" +
            "  float az=atan(w.y,w.x); float ridge=0.035+0.030*sin(az*4.+1.)+0.018*sin(az*11.+2.)+0.010*sin(az*27.);" +
            "  if(w.z<ridge) col=mix(vec3(0.035,0.025,0.05),hor*0.5,clamp(w.z/ridge,0.,1.)*0.5); }" +
            " gl_FragColor=vec4(col,1.); }");
        progCity = prog(                                 // an invented dusk city: sky, sun, layers of lit towers, a glowing round plaza and a mirror-like water floor, all worked out per pixel from the way you look
            "attribute vec2 p; uniform vec2 tn; varying vec2 ray; void main(){ gl_Position=vec4(p,0.,1.); ray=p*tn; }",
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n" +
            "uniform mat3 rm; uniform vec3 eo; varying vec2 ray;" +
            "float h21(vec2 p){ return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453); }" +
            "vec3 sky(vec3 w){ float e=clamp(w.z,0.,1.); vec3 c=mix(vec3(1.0,0.68,0.74),vec3(0.62,0.38,0.80),smoothstep(0.0,0.32,e)); c=mix(c,vec3(0.09,0.10,0.34),smoothstep(0.28,0.95,e));" +
            " vec3 sd=normalize(vec3(0.62,-0.45,0.20)); float m=max(dot(w,sd),0.); c+=vec3(1.0,0.84,0.68)*(pow(m,36.)*0.55+smoothstep(0.9972,0.9988,m)); return c; }" +
            "vec4 tower(float az,float e,float K,float seed,float hmax,vec3 base){ float x=az*K; float col=floor(x); float fx=fract(x);" +
            " float hh=0.04+hmax*h21(vec2(col,seed)); if(e<0.||e>hh||fx<0.10||fx>0.90) return vec4(0.); float fy=e/hh;" +
            " vec2 wc=vec2(fx*7.,e*70.); vec2 cid=floor(wc)+vec2(col*3.1,seed); vec2 cf=fract(wc);" +
            " float lit=step(0.60,h21(cid))*step(0.2,cf.x)*step(cf.x,0.8)*step(0.2,cf.y)*step(cf.y,0.75);" +
            " vec3 c=base*(0.65+0.55*fy)+lit*vec3(1.0,0.84,0.52)*0.9; c+=vec3(0.35,0.9,1.0)*smoothstep(0.93,1.0,fy)*0.9; return vec4(c,1.); }" +
            "void main(){ vec3 d=normalize(vec3(ray,-1.)); vec3 w=d*rm; vec3 col;" +
            " if(w.z<-0.004){ float t=eo.z/(-w.z); vec2 g=eo.xy+w.xy*t; float r=length(g);" +
            "  vec3 rf=vec3(w.x,w.y,-w.z); vec3 wat=sky(rf)*0.55+vec3(0.02,0.04,0.10);" +
            "  float rip=0.5+0.5*sin(r*3.+h21(floor(g*2.))*6.); wat+=vec3(0.05,0.03,0.08)*rip;" +
            "  float R=7.0; float inside=1.0-smoothstep(R-0.12,R+0.12,r);" +
            "  float ring=smoothstep(0.43,0.5,abs(fract(r*0.9)-0.5)); vec3 pl=mix(vec3(0.07,0.06,0.17),vec3(0.18,0.11,0.36),0.5+0.5*sin(r*1.7));" +
            "  pl+=vec3(0.30,0.85,1.0)*ring*0.75*(1.0-smoothstep(0.,R,r)*0.45); pl+=sky(rf)*0.18;" +
            "  float edge=1.0-smoothstep(0.0,0.35,abs(r-R)); pl+=vec3(1.0,0.45,0.85)*edge*0.9;" +
            "  col=mix(wat,pl,inside); col+=vec3(1.0,0.45,0.85)*edge*(1.0-inside)*0.5;" +
            "  float fog=clamp(t/60.,0.,1.); col=mix(col,vec3(1.0,0.68,0.74)*0.9,fog*fog); }" +
            " else { col=sky(w); float az=atan(w.y,w.x); float e=asin(clamp(w.z,-1.,1.));" +
            "  vec4 a=tower(az,e,9.0,1.0,0.20,vec3(0.34,0.26,0.52)); col=mix(col,mix(a.rgb,vec3(0.95,0.62,0.72),0.45),a.a);" +
            "  vec4 b=tower(az,e,6.0,7.0,0.28,vec3(0.22,0.18,0.42)); col=mix(col,mix(b.rgb,vec3(0.95,0.62,0.72),0.22),b.a);" +
            "  vec4 c=tower(az,e,3.7,13.0,0.36,vec3(0.12,0.10,0.28)); col=mix(col,c.rgb,c.a); }" +
            " gl_FragColor=vec4(col,1.); }");
        quad = fb(8);
        quad.put(new float[]{-1,-1, 1,-1, -1,1, 1,1}).position(0);
        lineBuf = fb(BONES.length * 2 * 2 * 2);
        ptBuf = fb(21 * 2 * 2);

        GLES20.glGenTextures(1, camTex, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        sceneW = 0; sceneH = 0; fbo[0] = 0; sceneTex[0] = 0;
        android.opengl.Matrix.setIdentityM(stm, 0);
        st = new SurfaceTexture(camTex[0]);
        st.setOnFrameAvailableListener(this);
        host.onCamSurface(st);
    }

    @Override public void onSurfaceChanged(GL10 gl, int w, int h) { vw = w; vh = h; }

    private void makeScene(int iw, int ih) {
        int w = Math.max(320, Math.round(Math.min(iw, 1280) * Math.max(0.6f, Math.min(1f, sceneScale))) & ~1);
        int h = Math.max(2, Math.round(w * (float) ih / iw));
        if (sceneTex[0] != 0) { GLES20.glDeleteTextures(1, sceneTex, 0); GLES20.glDeleteFramebuffers(1, fbo, 0); }
        GLES20.glGenTextures(1, sceneTex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sceneTex[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glGenFramebuffers(1, fbo, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, sceneTex[0], 0);
        if (depthRb[0] != 0) GLES20.glDeleteRenderbuffers(1, depthRb, 0);                    // depth, so 3D things in the room hide each other properly
        GLES20.glGenRenderbuffers(1, depthRb, 0);
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, depthRb[0]);
        GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16, w, h);
        GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_RENDERBUFFER, depthRb[0]);
        sceneW = w; sceneH = h;
        maskW = 0;
        if (ui != null) { ui.resize(1024, 576); ui.markDirty(); }
        if (uiTex != 0) { GLES20.glDeleteTextures(1, new int[]{uiTex}, 0); uiTex = 0; }
    }

    // the hand model answers 20-30 times a second but the screen is drawn 60 times a second: between answers the points are moved on
    // by their measured speed. Times are the moments the pictures were TAKEN (from the camera thread), not the moments this loop noticed
    // them, so the speed is not shaken by the 16 ms steps of the draw loop and the lead covers the real delay of the picture.
    private float[] rfl = new float[NvFlow.NTRK * 2];          // the followers' moves at the time of the model's picture
    private final float[] hsx = new float[42], hsy = new float[42], kfR = new float[21], shO = new float[2];     // per hand, per point: how far it has moved since that picture
    private final boolean[] foh = new boolean[2];              // per hand: is it followed at the camera rate right now
    private float[] rTip = null;
    private final float[] tsx = new float[6], tsy = new float[6];
    private final boolean[] tuse = new boolean[6];
    private float[] dispDraw = new float[]{0}, corr = new float[1];
    private float[] drawRes = null;
    private long lastPredT = 0;
    /** speed with a dead zone: noise of the model while the hand rests must not become a guessed movement */
    private static float dz(float v) { float a = Math.abs(v) - 0.08f; return a <= 0f ? 0f : (v < 0f ? -a : a); }
    private void predictNow(long now) {
        float[] r = cam.result;
        if (r != lastRes) {
            float[] prev = lastRes;
            long tPrev = handT;
            long tc = cam.resultCapT;
            if (tc <= 0 || tc > now) tc = now;
            lastRes = r; handT = tc;
            rfl = cam.resFl; rTip = cam.resTip;
            if (vel.length != r.length) vel = new float[r.length];
            if (prev != null && prev.length == r.length && r[0] >= 1 && tc - tPrev >= 8 && tc - tPrev < 350) {
                float dt = (tc - tPrev) / 1000f;
                for (int i = 1; i < r.length; i++) {
                    if ((i - 1) % 3 == 2) continue;
                    float v = (r[i] - prev[i]) / dt;
                    v = Math.max(-3f, Math.min(3f, v));
                    vel[i] = vel[i] * 0.4f + v * 0.6f;
                }
            } else if (prev == null || prev.length != r.length || r[0] < 1 || tc - tPrev >= 350) {
                java.util.Arrays.fill(vel, 0f);
            }
        }
        if (disp.length != r.length) disp = new float[r.length];
        // how far the whole hand has moved since the picture the model answered about (seen by the camera-rate follower): the points are moved on by that,
        // so the hand is drawn where it is now, not where it was ~100 ms ago
        // every hand has its own followers (palm and fingers): two hands moving in different ways do not pull on each other, and a hand that
        // turns or rolls has its fingers moved more than its palm (NvFlow.kFactors / shiftOf give the move of every single point)
        int nh = r[0] >= 1 ? Math.min(2, Math.min((int) r[0], (r.length - 1) / 63)) : 0;
        foh[0] = foh[1] = false;
        boolean fo = false;
        float[] fnow = cam.flowNow; int fmask = cam.flowMask;
        if (cam.flowOn && nh > 0 && now - cam.flowT < 150 && fnow != null && rfl != null && fnow.length >= 8 && rfl.length >= 8) {
            for (int hnd = 0; hnd < nh; hnd++) {
                int t0 = hnd * 2;
                boolean pok = (fmask & (1 << t0)) != 0, fok = (fmask & (2 << t0)) != 0;
                if (!pok && !fok) continue;
                float pdx = 0f, pdy = 0f, fdx = 0f, fdy = 0f;
                if (pok) { pdx = fnow[t0 * 2] - rfl[t0 * 2]; pdy = fnow[t0 * 2 + 1] - rfl[t0 * 2 + 1]; }
                if (fok) { fdx = fnow[(t0 + 1) * 2] - rfl[(t0 + 1) * 2]; fdy = fnow[(t0 + 1) * 2 + 1] - rfl[(t0 + 1) * 2 + 1]; }
                NvFlow.kFactors(r, 1 + hnd * 63, kfR);
                for (int lm = 0; lm < 21; lm++) {
                    boolean has = NvFlow.shiftOf(kfR[lm], pok, pdx, pdy, fok, fdx, fdy, shO);
                    hsx[hnd * 21 + lm] = has ? shO[0] : 0f; hsy[hnd * 21 + lm] = has ? shO[1] : 0f;
                }
                foh[hnd] = true; fo = true;
            }
        }
        boolean newAns = r != drawRes;                       // a new answer of the model arrived since the last frame
        float[] prevDraw = null;
        // each thumb / index / middle fingertip that is followed on its own is moved by its own amount (so a closing pinch shows up at the camera rate)
        java.util.Arrays.fill(tuse, false);
        if (fo && cam.tipsOn) {
            float[] tn = cam.tipNow; float[] rt = rTip;
            if (tn != null && rt != null && tn.length >= 18 && rt.length >= 18) {
                for (int hnd = 0; hnd < nh; hnd++) {
                    if (!foh[hnd]) continue;
                    for (int j = 0; j < 3; j++) {
                        int sl = hnd * 3 + j, lm = NvCam.TIPS[j];
                        if (tn[sl * 3 + 2] > 0.5f && rt[sl * 3 + 2] > 0.5f) {
                            float ex = tn[sl * 3] - rt[sl * 3], ey = tn[sl * 3 + 1] - rt[sl * 3 + 1];
                            if (Math.abs(ex - hsx[hnd * 21 + lm]) < 0.08f && Math.abs(ey - hsy[hnd * 21 + lm]) < 0.08f) { tsx[sl] = ex; tsy[sl] = ey; tuse[sl] = true; }
                        }
                    }
                }
            }
        }
        if (dispDraw.length != r.length) { dispDraw = new float[r.length]; corr = new float[r.length]; newAns = false; }
        else if (newAns && dispDraw.length > 0 && dispDraw[0] >= 1 && r[0] >= 1) prevDraw = dispDraw.clone();
        drawRes = r;
        long fdt = lastPredT == 0 ? 16 : Math.max(1, Math.min(60, now - lastPredT));
        lastPredT = now;
        float decay = (float) Math.exp(-fdt / 60f);
        for (int i = 0; i < r.length; i++) {
            float v = r[i], vt = r[i];
            if (i > 0) {
                int m = (i - 1) % 3;
                int hnd = (i - 1) / 63, lm = ((i - 1) % 63) / 3;
                float ax = 0f, ay = 0f;                         // the outline / skeleton moves with the palm + fingers followers only: the single fingertips jitter a little and made it bounce
                if (m != 2 && hnd < 2 && foh[hnd]) { ax = hsx[hnd * 21 + lm]; ay = hsy[hnd * 21 + lm]; }
                float bx = ax, by = ay;                         // the pointer and the pinch look at the fingertips' own movement
                if (m != 2 && hnd < 2) {
                    int j = lm == 4 ? 0 : (lm == 8 ? 1 : (lm == 12 ? 2 : -1));
                    if (j >= 0 && tuse[hnd * 3 + j]) { bx = tsx[hnd * 3 + j]; by = tsy[hnd * 3 + j]; }
                }
                if (m == 0) { v += ax; vt += bx; } else if (m == 1) { v += ay; vt += by; }
            }
            disp[i] = vt;
            if (i == 0 || (i - 1) % 3 == 2) { dispDraw[i] = v; continue; }
            // a new model answer corrects the points a little (the follower drifts a bit): instead of a jump, the difference melts away over ~90 ms
            if (prevDraw != null && prevDraw.length == r.length) {
                float c = prevDraw[i] - v;
                corr[i] = Math.abs(c) < 0.06f ? c : 0f;
            }
            corr[i] *= decay;
            dispDraw[i] = v + corr[i];
        }
        // the hand outline / skeleton is drawn where the hand is in the camera picture that is on screen right now (that picture is already a bit old),
        // so it sticks to the real hand; the pointer below looks ahead instead, to feel quick
        if (r[0] >= 1) {                                    // a hand that is not followed by the camera is drawn from its speed instead
            float dl = Math.max(0, Math.min(now - handT - 16, skel >= 2 ? 140 : 90)) / 1000f;       // drawn where the hand really is in the picture on screen: only a short, careful step on from the model's answer (a long guess made the outline float away from the hand)
            for (int i = 1; i < r.length; i++) {
                if ((i - 1) % 3 == 2) continue;
                int hnd = (i - 1) / 63;
                if (hnd >= 2 || !foh[hnd]) dispDraw[i] += dz(vel[i]) * dl * (skel >= 2 ? 0.95f : 0.7f);
            }
        }
        if (predict && r[0] >= 1) {
            // from the moment of the newest picture that was followed (or of the model's picture) to the moment this frame is shown
            float leadF = Math.max(0, Math.min(now - cam.flowT + 18, 90)) / 1000f, leadM = Math.max(0, Math.min(now - handT + 18, 140)) / 1000f;
            predMs = (fo ? leadF : leadM) * 1000f;
            for (int i = 1; i < r.length; i++) {
                if ((i - 1) % 3 == 2) continue;
                int hnd = (i - 1) / 63;
                disp[i] += dz(vel[i]) * ((hnd < 2 && foh[hnd]) ? leadF : leadM) * 0.9f;
            }
        } else predMs = 0;
    }

    /** the video picture, straight from the player's texture, in the top part of the window (the control bar is drawn over the rest) */
    private void drawVideo(Win w, NvVideo v, android.graphics.Bitmap bm) {
        if (v.st == null) {
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0]);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            v.glReady(new SurfaceTexture(t[0]), t[0]);
        }
        if (v.st == null) return;
        if (v.frameAvail) {
            v.frameAvail = false;
            try { v.st.updateTexImage(); v.st.getTransformMatrix(v.stm); } catch (Throwable ignored) { }
        }
        if (v.vw <= 0 || v.vh <= 0) return;
        float wa = (float) NvVideo.W / NvVideo.VH, va = (float) v.vw / v.vh;
        float fx = va > wa ? 1f : wa / va, fy = va > wa ? va / wa : 1f;
        float pw = w.widthM * w.scale * winZoom * w.animS;
        float hh = pw * bm.getHeight() / bm.getWidth() / 2f;
        float fr = (float) NvVideo.VH / bm.getHeight();
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glUseProgram(progVid);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, v.oes);
        GLES20.glUniform1i(ul(progVid, "tex"), 0);
        GLES20.glUniformMatrix4fv(ul(progVid, "mvp"), 1, false, w.mvp, 0);
        GLES20.glUniformMatrix4fv(ul(progVid, "stm"), 1, false, v.stm, 0);
        GLES20.glUniform2f(ul(progVid, "hs"), pw / 2f, hh * fr);
        GLES20.glUniform2f(ul(progVid, "off"), 0f, hh - hh * fr);
        GLES20.glUniform2f(ul(progVid, "fit"), fx, fy);
        drawQuad(progVid);
    }

    /** the camera picture, full screen; or (thru) only where the hand mask is, on top of what is already drawn (the real hand seen through the windows) */
    private void drawCamera(boolean thru) { drawCamera(thru ? 1 : 0); }
    private void drawCamera(int mode) {
        boolean thru = mode > 0;
        GLES20.glUseProgram(progCam);
        GLES20.glUniform1f(ul(progCam, "zs"), camScale);
        GLES20.glUniform1f(ul(progCam, "mkOn"), mode == 2 ? 2f : (mode == 1 ? 1f : 0f));
        GLES20.glUniform1i(ul(progCam, "mk"), 1);                // the mask sampler never shares unit 0 with the camera (two sampler types on one unit is an error)
        if (thru) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mode == 2 ? maskTex[0] : refTex[0]);
            GLES20.glUniform1i(ul(progCam, "mk"), 1);
            GLES20.glUniform2f(ul(progCam, "mpx"), 1f / maskW, 1f / maskH);
            GLES20.glUniform1f(ul(progCam, "mz"), camScale);
            if (mode == 1) { GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA); } else GLES20.glDisable(GLES20.GL_BLEND);
        }
        float[] fr = frameRot, rn0 = rotView;
        boolean wp = reproj && fr != null && rn0 != null;
        if (wp) {
            for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
                float sum = 0f;
                for (int k = 0; k < 3; k++) sum += fr[k * 4 + i] * rn0[k * 4 + j];
                dm3[j * 3 + i] = sum;
            }
            GLES20.glUniformMatrix3fv(ul(progCam, "dm"), 1, false, dm3, 0);
            GLES20.glUniform2f(ul(progCam, "tn"), tanH, tanV);
        }
        GLES20.glUniform1f(ul(progCam, "wp"), wp ? 1f : 0f);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex[0]);
        GLES20.glUniform1i(ul(progCam, "tex"), 0);
        GLES20.glUniformMatrix4fv(ul(progCam, "stm"), 1, false, stm, 0);
        GLES20.glUniform1f(ul(progCam, "rf"), rot180 ? 1f : 0f);
        GLES20.glUniform2f(ul(progCam, "px"), 1f / Math.max(1, imgW), 1f / Math.max(1, imgH));
        {   // the learnt colour table (32 x 32 over Cb / Cr): a probability of "hand" for every colour
            byte[] lt = cam.lut; long ltT = cam.lutT;
            boolean lo = mode == 2 && lt != null && lt.length == 1024 && System.currentTimeMillis() - ltT < 3000;
            GLES20.glUniform1i(ul(progCam, "lut"), 2);
            GLES20.glUniform1f(ul(progCam, "geo"), (mode == 2 && skel != 4) ? 1f : 0f);
            GLES20.glUniform1f(ul(progCam, "so"), (mode == 2 && segDraw) ? (segLutUse != 0 ? 2f : 1f) : 0f);
            GLES20.glUniform1i(ul(progCam, "cl"), 3);
            if (mode == 2 && segDraw && segLutUse != 0) { GLES20.glActiveTexture(GLES20.GL_TEXTURE3); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, segLutUse); GLES20.glActiveTexture(GLES20.GL_TEXTURE0); }
            GLES20.glUniform1f(ul(progCam, "lutOn"), lo ? 1f : 0f);
            if (lo) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
                if (lutTex == 0) {
                    int[] t = new int[1]; GLES20.glGenTextures(1, t, 0); lutTex = t[0]; lutUp = 0;
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTex);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                } else GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTex);
                if (lutUp != ltT) {
                    GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, 32, 32, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, java.nio.ByteBuffer.wrap(lt));
                    lutUp = ltT;
                }
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            }
        }
        { float[] sk = cam.skin; boolean ok = mode == 2 && sk != null && sk[2] > 0f && System.currentTimeMillis() - cam.skinT < 3000;
          GLES20.glUniform1f(ul(progCam, "smOn"), ok ? 1f : 0f);
          GLES20.glUniform1f(ul(progCam, "sy"), ok && sk.length > 5 ? sk[5] : 0.5f);
          boolean bo = ok && sk.length > 10 && sk[6] > 0.5f;
          GLES20.glUniform1f(ul(progCam, "sbOn"), bo ? 1f : 0f);
          GLES20.glUniform4f(ul(progCam, "sb"), bo ? sk[7] : 0f, bo ? sk[8] : 0f, bo ? sk[9] : 1f, bo ? sk[10] : 1f);
          GLES20.glUniform4f(ul(progCam, "sm"), ok ? sk[0] : 0f, ok ? sk[1] : 0f, ok ? sk[2] : 1f, ok ? sk[3] : 1f); }
        GLES20.glUniform1f(ul(progCam, "amt"), sharpen);
        GLES20.glUniform1f(ul(progCam, "lift"), lift);
        drawQuad(progCam);
        if (thru) { GLES20.glDisable(GLES20.GL_BLEND); GLES20.glActiveTexture(GLES20.GL_TEXTURE0); }
    }

    private void draw3D() {
        NvSword3D wd = world3; Win ww = world3Win;
        if (wd == null || ww == null || !shown(ww) || cinema) return;
        try {
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT);
            if (wd.envActive()) {                                          // the room is swapped for the night scene
                float[] m = rotView;
                rm3[0] = m[0]; rm3[1] = m[1]; rm3[2] = m[2]; rm3[3] = m[4]; rm3[4] = m[5]; rm3[5] = m[6]; rm3[6] = m[8]; rm3[7] = m[9]; rm3[8] = m[10];
                GLES20.glUseProgram(progEnv);
                GLES20.glUniform2f(ul(progEnv, "tn"), tanH, tanV);
                GLES20.glUniformMatrix3fv(ul(progEnv, "rm"), 1, false, rm3, 0);
                GLES20.glUniform3f(ul(progEnv, "eo"), sux, suy, 1.15f);
                drawQuad(progEnv);
            }
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            GLES20.glDepthFunc(GLES20.GL_LEQUAL);
            wd.drawWorld(mP, rotView, sux, suy, suz);
        } catch (Throwable ignored) { }
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
    }


    // ---- the title bar that floats under every window: back, name, more, bigger, smaller, close ----
    private static final int BAR_W = 900, BAR_H = 100, NBTN = 6;
    private static final float[] BAR_X = {56f, 770f, 640f, 570f, 842f, 500f};     // centres of the round buttons: 0 back, 1 more, 2 bigger (+), 3 smaller (-), 4 close, 5 keyboard
    public volatile java.util.function.Consumer<Win> onKeysBtn;                                            // the keyboard button of the bar (set by the activity)
    private Win barHovWin = null; private int barHovIdx = -1;
    private final float[] bg = new float[3], barMv = new float[16], barMvp = new float[16];

    private boolean barOn(Win w) { return shown(w) && w != menuWin && !w.noBar && !w.ghost && w.animA > 0.9f && !cinemaHidesBar(w); }
    private boolean cinemaHidesBar(Win w) { return w.c instanceof NvVideo; }

    /** width, height and how far down (metres, in the window's own plane) the bar is */
    private void barGeom(Win w, android.graphics.Bitmap bm) {
        float pw = w.widthM * w.scale * winZoom, ph = pw * bm.getHeight() / bm.getWidth();
        float dk = Math.max(1f, Math.min(5f, Math.max(0.3f, w.dist) / PANEL_D));                       // a window far away (or very big) gets a bar of the same size to the eye
        float bw = Math.max(0.62f * dk, Math.min(pw * 0.75f, 3.2f * dk)), bh = bw * BAR_H / BAR_W;     // grows with the window when it is made bigger           // always a big, easy bar, whatever the window's size
        bg[0] = bw; bg[1] = bh; bg[2] = -(ph / 2f + bh / 2f + 0.012f);                                  // stuck right under the window: one piece with it
    }

    private String barTitle(Win w) { try { return w.titleFn != null ? w.titleFn.get() : w.title; } catch (Throwable t) { return w.title; } }

    private void paintBar(Win w) {
        if (w.bbmp == null) w.bbmp = android.graphics.Bitmap.createBitmap(BAR_W, BAR_H, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(w.bbmp);
        cv.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        android.graphics.RectF pill = new android.graphics.RectF(2, 2, BAR_W - 2, BAR_H - 2);
        int ga = Math.min(255, NvUi.glass + 6);
        NvGlass.fill(cv, p, pill, (ga << 24) | 0x0F1628, pill.height() / 2f);
        NvGlass.ring(cv, p, pill, 0x33FFFFFF, 1.8f, pill.height() / 2f);
        float cy = BAR_H / 2f;
        for (int i = 0; i < NBTN; i++) {
            float cx = BAR_X[i], rr = 34f;
            boolean hot = w.barHov == i, dim = i == 0 && w.onBack == null;
            if (hot) NvGlass.fill(cv, p, new android.graphics.RectF(cx - rr, cy - rr, cx + rr, cy + rr), i == 4 ? 0xFFE5484D : 0x55FFFFFF, rr);
            cv.save(); cv.translate(cx, cy); cv.scale(1.35f, 1.35f); cv.translate(-cx, -cy);
            p.setShader(null); p.setStyle(android.graphics.Paint.Style.STROKE); p.setStrokeWidth(3.2f); p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            p.setColor(dim ? 0x55FFFFFF : 0xFFFFFFFF);
            switch (i) {
                case 0: cv.drawLine(cx + 4, cy - 10, cx - 6, cy, p); cv.drawLine(cx - 6, cy, cx + 4, cy + 10, p); break;
                case 1: p.setStyle(android.graphics.Paint.Style.FILL); for (int k = -1; k <= 1; k++) cv.drawCircle(cx + k * 9, cy, 2.8f, p); break;
                case 2: cv.drawLine(cx - 9, cy, cx + 9, cy, p); cv.drawLine(cx, cy - 9, cx, cy + 9, p); break;
                case 20: cv.drawLine(cx - 9, cy - 3, cx - 9, cy - 9, p); cv.drawLine(cx - 9, cy - 9, cx - 3, cy - 9, p); cv.drawLine(cx + 9, cy + 3, cx + 9, cy + 9, p); cv.drawLine(cx + 9, cy + 9, cx + 3, cy + 9, p); cv.drawLine(cx + 9, cy - 3, cx + 9, cy - 9, p); cv.drawLine(cx + 9, cy - 9, cx + 3, cy - 9, p); cv.drawLine(cx - 9, cy + 3, cx - 9, cy + 9, p); cv.drawLine(cx - 9, cy + 9, cx - 3, cy + 9, p); break;
                case 3: cv.drawLine(cx - 9, cy, cx + 9, cy, p); break;
                case 5: { android.graphics.RectF kr = new android.graphics.RectF(cx - 11, cy - 7, cx + 11, cy + 7); cv.drawRoundRect(kr, 3, 3, p); p.setStyle(android.graphics.Paint.Style.FILL); for (int a = -1; a <= 1; a++) { cv.drawCircle(cx + a * 6, cy - 2.5f, 1.3f, p); cv.drawCircle(cx + a * 6, cy + 2f, 1.3f, p); } break; }
                default: cv.drawLine(cx - 8, cy - 8, cx + 8, cy + 8, p); cv.drawLine(cx + 8, cy - 8, cx - 8, cy + 8, p); break;
            }
            cv.restore();
            p.setStyle(android.graphics.Paint.Style.FILL);
        }
        String t = Tr.t(barTitle(w));
        NvGlass.font(p, true);
        p.setColor(0xFFFFFFFF); p.setTextAlign(android.graphics.Paint.Align.LEFT);
        float size = 36f, maxW = 340f;
        p.setTextSize(size);
        while (p.measureText(t) > maxW && size > 12) { size -= 1; p.setTextSize(size); }
        cv.drawText(t, 112f, cy + size * 0.35f, p);
        w.barDrawnHov = w.barHov; w.barDrawnTitle = barTitle(w);
    }

    private void drawBars() {
        for (Win w : wins) {
            if (!barOn(w)) continue;
            android.graphics.Bitmap bm = w.c.bitmap();
            if (bm == null) continue;
            w.barHov = (barHovWin == w) ? barHovIdx : -1;
            boolean fresh = w.btex == 0;
            if (fresh) {
                int[] t = new int[1];
                GLES20.glGenTextures(1, t, 0);
                w.btex = t[0];
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, w.btex);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            }
            String tt = barTitle(w);
            if (fresh || w.barHov != w.barDrawnHov || !tt.equals(w.barDrawnTitle)) {
                paintBar(w);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, w.btex);
                if (fresh) android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, w.bbmp, 0); else android.opengl.GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, w.bbmp);
            }
            barGeom(w, bm);
            android.opengl.Matrix.translateM(barMv, 0, w.mv, 0, 0f, bg[2], 0f);
            android.opengl.Matrix.multiplyMM(barMvp, 0, mP, 0, barMv, 0);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glUseProgram(progPanel);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, w.btex);
            GLES20.glUniform1i(ul(progPanel, "tex"), 0);
            GLES20.glUniform1f(ul(progPanel, "al"), w.animA);
            GLES20.glUniformMatrix4fv(ul(progPanel, "mvp"), 1, false, barMvp, 0);
            GLES20.glUniform2f(ul(progPanel, "hs"), bg[0] / 2f, bg[1] / 2f);
            drawQuad(progPanel);
            GLES20.glDisable(GLES20.GL_BLEND);
        }
    }

    /** where the pointer is on the bar of this window: out = picture pixels of the bar; false = not on the bar */
    private boolean hitBar(Win w, float nx, float ny, float[] out) {
        if (!barOn(w)) return false;
        android.graphics.Bitmap bm = w.c.bitmap();
        if (bm == null) return false;
        float dx = nx * tanHc, dy = ny * tanVc, dz = -1f;
        float[] mInv = w.inv;
        float ox = mInv[12], oy = mInv[13], oz = mInv[14];
        float Dz = mInv[2] * dx + mInv[6] * dy + mInv[10] * dz;
        float Dx = mInv[0] * dx + mInv[4] * dy + mInv[8] * dz;
        float Dy = mInv[1] * dx + mInv[5] * dy + mInv[9] * dz;
        if (Math.abs(Dz) < 1e-5f) return false;
        float t = -oz / Dz;
        if (t <= 0) return false;
        float lx = ox + t * Dx, ly = oy + t * Dy;
        barGeom(w, bm);
        float u = lx / bg[0] + 0.5f, v = 0.5f - (ly - bg[2]) / bg[1];
        if (u < -0.03f || u > 1.03f || v < -0.35f || v > 1.35f) return false;
        out[0] = u * BAR_W; out[1] = v * BAR_H;
        return true;
    }

    /** 0..5 = a round button, 6 = the empty part (grab to move the window), -1 = outside the pill */
    private int barRegion(float x, float y) {
        float cy = BAR_H / 2f;
        for (int i = 0; i < NBTN; i++) if (Math.hypot(x - BAR_X[i], y - cy) < 40f) return i;
        return (x >= 0 && x <= BAR_W && y >= -6 && y <= BAR_H + 6) ? 6 : -1;
    }

    private void barAct(Win w, int idx) {
        switch (idx) {
            case 0: if (w.onBack != null) try { w.onBack.run(); } catch (Throwable ignored) { } break;
            case 1: w.placeReq = true; w.newOpen = false; break;                                           // "more": bring it back in front of you
            case 2: w.scale = Math.min(3.0f, w.scale * 1.18f); w.wantScale = w.scale; break;               // bigger (+)
            case 3: w.scale = Math.max(0.35f, w.scale / 1.18f); w.wantScale = w.scale; break;              // smaller (-)
            case 5: { java.util.function.Consumer<Win> k = onKeysBtn; if (k != null) try { k.accept(w); } catch (Throwable ignored) { } break; }
            case 4: if (w.onClose != null) try { w.onClose.run(); } catch (Throwable ignored) { } else setVisible(w, false, 0f, 0f); break;
            default: break;
        }
    }

    private void drawWins() {
        for (Win w : wins) {
            if (!shown(w)) continue;
            android.graphics.Bitmap bm = w.c.bitmap();
            if (bm == null) continue;
            boolean d = w.c.takeDirty();
            if (w.tex == 0) {
                int[] t = new int[1];
                GLES20.glGenTextures(1, t, 0);
                w.tex = t[0];
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, w.tex);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                w.c.markDirty();
                w.c.takeDirty();
                d = true;
            }
            if (d) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, w.tex);
                if (w.texW == bm.getWidth() && w.texH == bm.getHeight())
                    android.opengl.GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bm);   // same size: no new memory each time (smoother)
                else {
                    android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bm, 0);
                    w.texW = bm.getWidth(); w.texH = bm.getHeight();
                }
            }
            if (w.c instanceof NvVideo) drawVideo(w, (NvVideo) w.c, bm);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glUseProgram(progPanel);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, w.tex);
            GLES20.glUniform1i(ul(progPanel, "tex"), 0);
            GLES20.glUniform1f(ul(progPanel, "al"), w.c instanceof NvVideo ? 1f : w.animA);
            GLES20.glUniformMatrix4fv(ul(progPanel, "mvp"), 1, false, w.mvp, 0);
            float pw = w.widthM * w.scale * winZoom * w.animS;
            GLES20.glUniform2f(ul(progPanel, "hs"), pw / 2f, pw * bm.getHeight() / bm.getWidth() / 2f);
            drawQuad(progPanel);
            GLES20.glDisable(GLES20.GL_BLEND);
        }
    }

    // finger pointer: index fingertip; pinch = thumb tip close to index tip (relative to hand size)
    private boolean pinching = false;
    private float pinchAmt = 0f, rippleX = 0f, rippleY = 0f;
    private float ratioF = 0f; private long ratioT = 0, onSince = 0, offSince = 0;     // the finger gap, eased; when a change of the pinch state started
    private long popT = 0; private float popX = 0f, popY = 0f; private boolean popOk = false;
    private void holdPop(float nx, float ny, boolean ok) { popT = System.currentTimeMillis(); popX = nx; popY = ny; popOk = ok; }
    private long rippleT = 0;
    private long lastPinchT = 0, lostT = 0, mpSince = 0;
    private float sizeEma = 0, dnx = 0, dny = 0;
    private boolean resizing = false, gripHot = false;
    private float rsD0 = 1f;
    private long dragT0 = 0;
    private boolean mpOn = false, mpLatched = false;
    private long mpT0 = 0, mpTapT = 0;
    private float mpProg = 0, mpNx = 0, mpNy = 0;

    // with two hands in view, one of them drives the pointer: the one that is pinching / poking, otherwise the one nearest to where the pointer was
    public volatile int mainHand = 0;                     // with two hands in view: 0 = the left hand drives the pointer, 1 = the right hand
    private volatile boolean resetLayoutReq = false;
    private long okT0 = 0; private float okProg = 0; private boolean okDone = false;
    private int selHand = 0, handBase = 0;
    private float selTx = 0, selTy = 0;
    private final float[] oneHand = new float[64];
    private float[] pickHand(float[] d) {
        int n = d.length > 0 ? (int) d[0] : 0;
        handBase = 0;
        if (n <= 1 || d.length < 1 + n * 63) { selHand = 0; return d; }
        float ar = (float) Math.max(1, imgW) / Math.max(1, imgH);
        int best = 0; float bestScore = 1e9f;
        if (palmMenu && n == 2) {                                      // the left hand carries the palm menu, so with two hands the right hand is always the pointer
            best = rot180 ? 0 : 1;                                    // (otherwise an open left palm would also be read as pinches / pokes of the pointer)
            selHand = best; handBase = best * 63;
            oneHand[0] = 1f;
            System.arraycopy(d, 1 + best * 63, oneHand, 1, 63);
            selTx = oneHand[25]; selTy = oneHand[26];
            return oneHand;
        }
        for (int i = 0; i < n; i++) {
            int b = 1 + i * 63;
            float tx = d[b + 24], ty = d[b + 25], ax = d[b + 12], ay = d[b + 13], kx = d[b + 36], ky = d[b + 37];
            float size = Math.max(1e-3f, (float) Math.hypot((d[b + 27] - d[b]) * ar, d[b + 28] - d[b + 1]));
            float rp = (float) Math.hypot((tx - ax) * ar, ty - ay) / size, rm = (float) Math.hypot((kx - ax) * ar, ky - ay) / size;
            boolean active = rp < 0.25f || rm < 0.25f;
            float score = (float) Math.hypot((tx - selTx) * ar, ty - selTy);
            if (active) score -= 10f;
            if (i == (n == 2 ? ((mainHand == 0) != rot180 ? 0 : 1) : i)) score -= 0.5f;     // the chosen main hand (hands are sorted left to right in the picture)
            if (i == selHand) score -= 0.08f;                          // a little loyalty to the hand already in use
            if (i == selHand && active) score -= 10f;                  // keep the hand that is in the middle of a gesture
            if (score < bestScore) { bestScore = score; best = i; }
        }
        selHand = best;
        handBase = best * 63;
        oneHand[0] = 1f;
        System.arraycopy(d, 1 + best * 63, oneHand, 1, 63);
        selTx = oneHand[25]; selTy = oneHand[26];
        return oneHand;
    }

    // ----- menu on the left palm (two hands): open the left hand with the palm to the camera and two round icons appear on it.
    // Poke one with the index finger of the other hand and hold it there for a moment: left icon = open / close the menu, right icon = put the screens back in front of you.
    public volatile boolean palmMenu = true;
    public volatile Runnable onPalmSettings;                  // the right icon: open / close the settings window (set by the activity)
    private long pmOpenT = 0, pmLostT = 0, pmHotT = 0;
    private float pmFade = 0f, pmDwell = 0f, pmAx = 0f, pmAy = 0f, pmBx = 0f, pmBy = 0f, pmR = 0f;
    private int pmHot = -1;
    private boolean pmLatch = false;

    private float spx(float[] d, int base, int k) { float hx = d[base + k * 3]; if (rot180) hx = 1f - hx; return (hx * 2f - 1f) * sceneW / 2f * camScale; }
    private float spy(float[] d, int base, int k) { float hy = d[base + k * 3 + 1]; if (rot180) hy = 1f - hy; return (1f - hy * 2f) * sceneH / 2f * camScale; }

    private float pmCal = 0f;                                    // >0: the model's "left" label = the user's left hand (as assumed), <0: the other way round (learned whenever two hands are in view)
    private void palmMenu(long nowMs) {
        float[] d = dispDraw;                                    // what is drawn on the picture: the icon and the fingertip are lined up with what you see
        boolean two = palmMenu && d != null && d.length >= 64 && d[0] >= 1f && sceneW > 0;     // one hand is enough to SHOW the icons (the left palm); the other hand is only needed to poke
        boolean both = two && d.length >= 127 && d[0] >= 2f;
        boolean open = false, fin = false;
        float tx = 0f, ty = 0f;
        if (two) {
            int lh = both ? (rot180 ? 1 : 0) : 0, rh = 1 - lh;     // hands are ordered left to right in the picture (the other way round when the picture is turned); a lone hand is read as the left one
            int bL = 1 + lh * 63, bR = 1 + rh * 63;
            float ar = (float) Math.max(1, imgW) / Math.max(1, imgH);
            float wx = d[bL], wy = d[bL + 1];
            float size = (float) Math.hypot((d[bL + 27] - wx) * ar, d[bL + 28] - wy);
            boolean ext = size > 1e-3f;
            int[] tp = {8, 12, 16, 20}, pp = {6, 10, 14, 18};
            for (int q = 0; q < 4 && ext; q++) {
                float dt = (float) Math.hypot((d[bL + tp[q] * 3] - wx) * ar, d[bL + tp[q] * 3 + 1] - wy);
                float dp = (float) Math.hypot((d[bL + pp[q] * 3] - wx) * ar, d[bL + pp[q] * 3 + 1] - wy);
                if (!(dt > dp * 1.12f)) ext = false;              // all four fingers stretched out
            }
            // palm to the camera: for a left hand that shows the palm, the index knuckle -> pinky knuckle turn is one way round (the back of the hand: the other way)
            float v1x = (d[bL + 15] - wx) * ar, v1y = d[bL + 16] - wy, v2x = (d[bL + 51] - wx) * ar, v2y = d[bL + 52] - wy;
            boolean facing = (v1x * v2y - v1y * v2x) > 0.10f * size * size;      // (on the phone the palm side gave the opposite sign to the first guess)
            if (both) {                                                  // two hands in view: the left one in the picture IS the left hand, so this shows which way the model's left/right labels run on this phone
                float l0 = cam.leftness(0), l1 = cam.leftness(1);
                if (l0 >= 0f && l1 >= 0f && Math.abs(l0 - l1) > 0.3f) pmCal += ((l0 > l1 ? 1f : -1f) - pmCal) * 0.25f;
            }
            if (!both) { float lf = cam.leftness(0); if (lf >= 0f && pmCal < 0f) lf = 1f - lf; if (lf >= 0f && lf < 0.5f) facing = false; }      // a lone hand has to be the user's LEFT one (the model's own guess), so a right hand coming in with its back to the camera shows nothing
            open = ext && facing;
            // where the icons go: on the palm, next to each other across it (index side / little finger side), so they stay on the right sides when the hand turns
            float x0 = spx(d, bL, 0), y0 = spy(d, bL, 0), x9 = spx(d, bL, 9), y9 = spy(d, bL, 9);
            float L = (float) Math.hypot(x9 - x0, y9 - y0);
            float sxv = spx(d, bL, 5) - spx(d, bL, 17), syv = spy(d, bL, 5) - spy(d, bL, 17);
            float sl = (float) Math.hypot(sxv, syv);
            if (L > 1f && sl > 1f) {
                sxv /= sl; syv /= sl;
                float cx = x0 + (x9 - x0) * 0.52f, cy = y0 + (y9 - y0) * 0.52f, off = 0.25f * L;
                float u = sceneH / 540f;
                pmAx = cx + sxv * off; pmAy = cy + syv * off;
                pmBx = cx - sxv * off; pmBy = cy - syv * off;
                pmR = Math.max(11f * u, Math.min(42f * u, 0.18f * L));
            } else open = false;
            // the finger of the other hand: its index tip, with the finger stretched
            if (both) {
            float rwx = d[bR], rwy = d[bR + 1];
            float d8 = (float) Math.hypot((d[bR + 24] - rwx) * ar, d[bR + 25] - rwy), d6 = (float) Math.hypot((d[bR + 18] - rwx) * ar, d[bR + 19] - rwy);
            fin = d8 > d6 * 1.1f;
            tx = spx(d, bR, 8); ty = spy(d, bR, 8);
            }
        }
        if (open) { pmLostT = 0; if (pmOpenT == 0) pmOpenT = nowMs; }
        else { if (pmLostT == 0) pmLostT = nowMs; if (nowMs - pmLostT > 150) pmOpenT = 0; }
        boolean show = open && pmOpenT != 0 && nowMs - pmOpenT > 300;       // the palm has to stay open for a moment
        pmFade += ((show ? 1f : 0f) - pmFade) * (show ? 0.25f : 0.35f);
        if (pmFade < 0.02f && !show) pmFade = 0f;
        int hot = -1;
        if (show && fin) {
            float dA = (float) Math.hypot(tx - pmAx, ty - pmAy), dB = (float) Math.hypot(tx - pmBx, ty - pmBy);
            if (dA < pmR * 1.25f && dA <= dB) hot = 0; else if (dB < pmR * 1.25f) hot = 1;
        }
        if (hot != pmHot) { pmHot = hot; pmHotT = nowMs; if (hot < 0) pmLatch = false; }
        pmDwell = (hot >= 0 && !pmLatch) ? Math.min(1f, (nowMs - pmHotT) / 380f) : 0f;
        if (hot >= 0 && !pmLatch && pmDwell >= 1f) {
            pmLatch = true;                                                // once per visit: take the finger away to use it again
            float kx = sceneW / 2f * camScale, ky = sceneH / 2f * camScale;
            if (hot == 0) { holdPop(pmAx / kx, pmAy / ky, false); toggleMenuGL(); }
            else { holdPop(pmBx / kx, pmBy / ky, false); Runnable rs = onPalmSettings; if (rs != null) { try { rs.run(); } catch (Throwable ignored) { } } }
        }
    }

    private void drawPalmMenu() {
        if (pmFade < 0.02f) return;
        float u = sceneH / 540f, a = pmFade;
        for (int i = 0; i < 2; i++) {
            float x = i == 0 ? pmAx : pmBx, y = i == 0 ? pmAy : pmBy;
            boolean hot = pmHot == i;
            float rr = pmR * (hot ? 1.12f : 1f);
            float cr = i == 0 ? 0.62f : 1f, cg = i == 0 ? 0.82f : 0.78f, cb = i == 0 ? 1f : 0.35f;
            aaFade = 0.50f * a; aaBegin(); aaSeg(x, y, x, y, rr + 1.5f * u); aaFlush(1f, 1f, 1f);          // rim
            aaFade = 0.88f * a; aaBegin(); aaSeg(x, y, x, y, rr); aaFlush(0.09f, 0.10f, 0.14f);            // dark glass
            if (pmDwell > 0f && hot) { aaFade = a; aaBegin(); aaArc(x, y, rr * 0.80f, 0f, pmDwell, 2.6f * u); aaFlush(cr, cg, cb); }
            else { aaFade = 0.22f * a; aaBegin(); aaArc(x, y, rr * 0.80f, 0f, 1f, 1.8f * u); aaFlush(1f, 1f, 1f); }
            aaFade = a;
            aaBegin();
            if (i == 0) {                                                    // menu: four dots (a little grid of apps)
                float g = rr * 0.26f;
                for (int sx = -1; sx <= 1; sx += 2) for (int sy = -1; sy <= 1; sy += 2) aaSeg(x + sx * g, y + sy * g, x + sx * g, y + sy * g, rr * 0.12f);
            } else {                                                         // settings: a gear (a ring with eight teeth)
                aaArc(x, y, rr * 0.30f, 0f, 1f, 2.2f * u);
                for (int q = 0; q < 8; q++) {
                    float an = q * 0.7853982f, c = (float) Math.cos(an), sn = (float) Math.sin(an);
                    aaSeg(x + c * rr * 0.42f, y + sn * rr * 0.42f, x + c * rr * 0.56f, y + sn * rr * 0.56f, 2.6f * u);
                }
            }
            aaFlush(cr, cg, cb);
        }
        aaFade = 1f;
    }

    private boolean gameUp() {
        for (Win w : wins) if (w.game && shown(w)) return true;
        return false;
    }

    private void cursor() {
        palmMenu(System.currentTimeMillis());
        float[] r = pickHand(disp);
        long nowMs = System.currentTimeMillis();
        boolean hand = r.length >= 64 && r[0] >= 1;
        if (!hand) {
            pinchAmt *= 0.6f;
            curOk = false; pkOn = false; pkProg = 0; pkInit = false;
            mpOn = false; mpSince = 0; mpProg = 0; mpLatched = false;
            if (lostT == 0) lostT = nowMs;
            // a short loss of the hand (detection blink) must not drop a grabbed window
            if (!(dragWin != null && nowMs - lostT < 400)) { pinching = false; prevPinch = false; endDrag(); }
            for (Win w : wins) if (shown(w)) w.c.update(false, 0, 0, false);
            { NvSword3D wd = world3; if (wd != null) wd.pointer(false, 0f, 0f, 0f, nowMs); }
            return;
        }
        lostT = 0;
        float ar = (float) Math.max(1, imgW) / Math.max(1, imgH);
        float tx = r[1 + 8 * 3], ty = r[1 + 8 * 3 + 1];
        float ax = r[1 + 4 * 3], ay = r[1 + 4 * 3 + 1];
        float kx = r[1 + 12 * 3], ky = r[1 + 12 * 3 + 1];
        float wx = r[1], wy = r[2], mx = r[1 + 9 * 3], my = r[1 + 9 * 3 + 1];
        float size = (float) Math.hypot((mx - wx) * ar, my - wy);
        sizeEma = sizeEma <= 0f ? size : sizeEma + (size - sizeEma) * (dragWin != null ? 0.35f : 0.15f);
        // while moving a window the pointer is the middle of the knuckles: it stays steady when the fingers pinch
        float kxn = (r[1 + 5 * 3] + r[1 + 9 * 3] + r[1 + 13 * 3]) / 3f, kyn = (r[1 + 5 * 3 + 1] + r[1 + 9 * 3 + 1] + r[1 + 13 * 3 + 1]) / 3f;
        if (rot180) { kxn = 1f - kxn; kyn = 1f - kyn; }
        float tnx = kxn * 2f - 1f, tny = 1f - kyn * 2f;
        float d = (float) Math.hypot((tx - ax) * ar, ty - ay);
        // the hand size the finger gap is compared with: the length of the palm, but when the hand points at the camera that gets short, so the palm width counts too
        float palmW = (float) Math.hypot((r[1 + 5 * 3] - r[1 + 17 * 3]) * ar, r[1 + 5 * 3 + 1] - r[1 + 17 * 3 + 1]);
        float psize = Math.max(size, 1.15f * palmW);
        float ratio = psize > 1e-4f ? d / psize : 9f;
        float dm = (float) Math.hypot((kx - ax) * ar, ky - ay);
        float ratioM = psize > 1e-4f ? dm / psize : 9f;
        // the gap is eased a little (one noisy look must not flip the pinch), and a change of state has to hold for a moment:
        // closing: below 0.12 at once, below 0.18 for 30 ms; opening: above 0.45 at once, above 0.31 for 50 ms
        ratioF = ratioF <= 0f || nowMs - ratioT > 300 ? ratio : ratioF + (ratio - ratioF) * 0.6f;
        ratioT = nowMs;
        if (!pinching) {
            offSince = 0;
            if (ratioF < 0.15f) { pinching = true; onSince = 0; }
            else if (ratioF < 0.23f) { if (onSince == 0) onSince = nowMs; else if (nowMs - onSince >= 30) { pinching = true; onSince = 0; } }
            else onSince = 0;
        } else {
            onSince = 0;
            if (ratioF > 0.45f) { pinching = false; offSince = 0; }
            else if (ratioF > 0.34f) { if (offSince == 0) offSince = nowMs; else if (nowMs - offSince >= 50) { pinching = false; offSince = 0; } }
            else offSince = 0;
        }
        if (pinching) lastPinchT = nowMs;
        // how closed the fingers are (0..1), eased: the pointer ring tightens as the fingers close; a ripple goes out when the pinch lands
        float pinchTarget = pinching ? 1f : Math.max(0f, Math.min(0.85f, (0.36f - ratioF) / (0.36f - 0.17f)));
        pinchAmt += (pinchTarget - pinchAmt) * 0.35f;
        if (pinching && !prevPinch) { rippleT = nowMs; rippleX = cnx; rippleY = cny; }

        // air touch (as in the web version): the index finger is out and is pushed forward = the hand gets smaller in the picture.
        // Made calmer: the depth is smoothed, the pose has to be steady for a moment, a fast-moving hand does not count as a push,
        // the push must last a moment, and there is a short pause after a press.
        int selM = 0;                                   // air touch was taken out (too hard to use): pinching is the only way to press
        {
            float z8 = r[1 + 8 * 3 + 2], z0 = r[1 + 2];
            float sz = Math.max(1e-3f, size);
            float depthRaw = -((float) Math.log(sz) - 0.6f * (z8 - z0) / sz);
            float dtF = pkT2 == 0 ? 0.02f : Math.min(0.1f, (nowMs - pkT2) / 1000f);
            pkT2 = nowMs;
            pkDepthF = !pkInit ? depthRaw : pkDepthF + (depthRaw - pkDepthF) * (1f - (float) Math.exp(-dtF / 0.10f));
            float depth = pkDepthF;
            if (vel.length > handBase + 27) handSpd = (float) Math.hypot(vel[1 + handBase + 8 * 3], vel[1 + handBase + 8 * 3 + 1]);    // how fast the fingertip crosses the picture (picture widths per second)
            float d8 = (float) Math.hypot((tx - wx) * ar, ty - wy);
            float d6 = (float) Math.hypot((r[1 + 6 * 3] - wx) * ar, r[1 + 6 * 3 + 1] - wy);
            boolean idxOut = !pinching && ratio > 0.30f && d8 > d6 * 1.1f;
            if (!idxOut) idxSince = 0; else if (idxSince == 0) idxSince = nowMs;
            boolean ready = idxOut && nowMs - idxSince > 120 && nowMs - pkRelT > 300 && handSpd < 0.6f;
            if (selM == 0 || !idxOut) { pkOn = false; pkBase = depth; pkT = nowMs; pkProg = 0; pkInit = true; pkAbove = 0; }
            else if (!ready && !pkOn) { pkBase = depth; pkT = nowMs; pkProg = 0; pkAbove = 0; pkInit = true; }
            else {
                if (!pkInit) { pkBase = depth; pkT = nowMs; pkInit = true; }
                float th = POKE_TH[Math.max(0, Math.min(2, pokeS))] * 1.6f;
                pkProg = pkOn ? 1f : Math.max(0f, Math.min(1f, (depth - pkBase) / th));
                if (!pkOn) {
                    float dt = Math.min(0.5f, (nowMs - pkT) / 1000f); pkT = nowMs;
                    if (depth - pkBase > th) {
                        if (pkAbove == 0) pkAbove = nowMs;
                        if (nowMs - pkAbove > 70) { pkOn = true; pkPeak = depth; }      // must stay pushed for a moment
                    } else {
                        pkAbove = 0;
                        pkBase += (depth - pkBase) * Math.min(1f, dt / 0.6f);              // slow changes (the whole arm moving) are not a poke
                    }
                } else {
                    if (depth > pkPeak) pkPeak = depth;
                    if (depth - pkBase < th * 0.45f) { pkOn = false; pkRelT = nowMs; pkAbove = 0; }   // finger pulled back
                }
            }
        }
        final boolean press = selM == 0 ? pinching : (selM == 1 ? pkOn : (pinching || pkOn));

        // thumb + middle finger held together for a moment: show / hide the menu
        boolean mpPrev = mpOn;
        // with two hands the pointing pose (index out, thumb on the middle finger) IS this gesture, so poking a palm icon opened the menu first: it is off while the palm menu is in use
        boolean mpBlock = (palmMenu && disp != null && disp.length > 0 && disp[0] >= 2f) || gameUp();
        mpOn = !mpBlock && (mpOn ? (ratioM < 0.32f) : (ratioM < 0.20f && ratio > 0.35f));
        if (mpOn && !mpPrev) mpT0 = nowMs;
        if (!mpOn && mpPrev && !mpLatched && nowMs - mpT0 < 500) {        // a quick tap; two quick taps = put the screens back in front of you
            if (nowMs - mpTapT < 1000) { recenter = true; mpTapT = 0; } else mpTapT = nowMs;
        }
        if (mpOn && !mpLatched) {
            if (mpSince == 0) mpSince = nowMs;
            mpProg = Math.min(1f, (nowMs - mpSince) / 700f);
            if (mpProg >= 1f) { holdPop(mpNx, mpNy, false); toggleMenuGL(); mpLatched = true; mpProg = 0; }
        } else if (!mpOn) { mpSince = 0; mpProg = 0; mpLatched = false; }
        float mhx = (kx + ax) * 0.5f, mhy = (ky + ay) * 0.5f;
        if (rot180) { mhx = 1f - mhx; mhy = 1f - mhy; }
        mpNx = mhx * 2f - 1f; mpNy = 1f - mhy * 2f;

        // the pointer sits between thumb tip and index tip, so closing the fingers does not push it off the target
        float hx = selM == 0 ? (tx + ax) * 0.5f : tx, hy = selM == 0 ? (ty + ay) * 0.5f : ty;     // air touch: the fingertip itself
        if (rot180) { hx = 1f - hx; hy = 1f - hy; }
        float nx = hx * 2f - 1f, ny = 1f - hy * 2f;
        boolean hold = selM != 0 && (ratio < 0.45f || pkProg > 0.5f);          // fingers closing / finger being pushed: the pointer stays where it was
        if (!curOk) { cnx = nx; cny = ny; } else if (!hold) {
            // adaptive follow: a slow, shaky fingertip is calmed down, a quick one is followed almost exactly (sticks to the hand)
            float sp = (float) Math.hypot(nx - cnx, ny - cny);
            float al = Math.max(0.50f, Math.min(0.95f, 0.50f + sp * 9f));      // the points are already steadied at the source, so this only needs to calm the last bit
            cnx += (nx - cnx) * al; cny += (ny - cny) * al;
        }
        curOk = true;
        if (dragWin != null) {
            float ddx = tnx - dnx, ddy = tny - dny;
            float dl = (float) Math.hypot(ddx, ddy);
            float stp = Math.min(dl * 0.55f, 0.10f);                     // smooth, and never a sudden big jump
            if (dl > 1e-5f) { dnx += ddx / dl * stp; dny += ddy / dl * stp; }
            rayYaw = (float) Math.toDegrees(Math.atan(dnx * tanHc));
            rayPitch = (float) Math.toDegrees(Math.atan(dny * tanVc));
        } else {
            rayYaw = (float) Math.toDegrees(Math.atan(cnx * tanHc));
            rayPitch = (float) Math.toDegrees(Math.atan(cny * tanVc));
        }
        {                                                                // the fingertip's direction in the room, for the 3D game
            NvSword3D wd = world3;
            if (wd != null) {
                float vx = cnx * tanHc, vy = cny * tanVc, vz = -1f;
                float[] rv3 = rotView;
                wd.pointer(true, rv3[0] * vx + rv3[1] * vy + rv3[2] * vz, rv3[4] * vx + rv3[5] * vy + rv3[6] * vz, rv3[8] * vx + rv3[9] * vy + rv3[10] * vz, nowMs);
            }
        }

        float[] o = new float[2];
        Win target = null, gripWin = null, barWin = null;
        int barReg = -1;
        if (dragWin == null && !mpOn) {
            for (int i = wins.size() - 1; i >= 0; i--) {                 // the one drawn last is in front
                Win w = wins.get(i);
                if (barOn(w) && hitBar(w, cnx, cny, o)) { int rg = barRegion(o[0], o[1]); if (rg >= 0) { barWin = w; barReg = rg; break; } }
                if (!shown(w) || !hitWin(w, cnx, cny, o)) continue;
                if (w.ghost) { target = w; break; }                       // a see-through window waiting for a click: it is hit anywhere
                if (w.c.inGrip(o[0], o[1])) { gripWin = w; break; }      // a bottom corner: pull it to resize
                if (w.c.covers(o[0], o[1])) { target = w; break; }
            }
        }
        barHovWin = barWin; barHovIdx = barWin != null ? barReg : -1;
        if (barWin != null && pinching && !prevPinch && barReg >= 0 && barReg < 6) { holdPop(cnx, cny, false); barAct(barWin, barReg); }
        gripHot = gripWin != null || (dragWin != null && resizing);
        // OK sign (thumb and index in a ring, the other three fingers stretched out) held for a moment over empty space:
        // all windows go back to the standard places and sizes
        {
            boolean ext = true;
            int[] tp = {12, 16, 20}, pp = {10, 14, 18};
            for (int q = 0; q < 3; q++) {
                float dt = (float) Math.hypot((r[1 + tp[q] * 3] - wx) * ar, r[1 + tp[q] * 3 + 1] - wy);
                float dp = (float) Math.hypot((r[1 + pp[q] * 3] - wx) * ar, r[1 + pp[q] * 3 + 1] - wy);
                if (!(dt > dp * 1.12f)) ext = false;
            }
            if (pinching && ext && target == null && gripWin == null && dragWin == null && !mpOn) {
                if (okT0 == 0) okT0 = nowMs;
                okProg = Math.min(1f, (nowMs - okT0) / 1500f);
                if (okProg >= 1f && !okDone) { okDone = true; resetLayoutReq = true; recenter = true; holdPop(cnx, cny, true); }
            } else { okT0 = 0; okProg = 0; if (!pinching) okDone = false; }
        }
        if (target != null && target.ghost && pinching && !prevPinch) {      // the confirming click: it opens for real
            target.ghost = false; target.confT0 = System.nanoTime(); holdPop(cnx, cny, false);
        }
        if (gripWin != null && pinching && !prevPinch) {
            if (motion != null && motion.enabled) rebase(gripWin, sux, suy, suz);
            dragWin = gripWin; resizing = true;
            dnx = tnx; dny = tny;
            rayYaw = (float) Math.toDegrees(Math.atan(dnx * tanHc)); rayPitch = (float) Math.toDegrees(Math.atan(dny * tanVc));
            rsD0 = Math.max(3f, (float) Math.hypot(rayYaw - gripWin.yawA, rayPitch - gripWin.pitchA));
            gripWin.dragS0 = gripWin.scale;
            gripWin.c.setDragging(true);
            wins.remove(gripWin); wins.add(gripWin);
        }
        Win gwin = (target != null && target.c.handleRecent()) ? target : ((barWin != null && barReg == 6) ? barWin : null);
        if (gwin != null && pinching && !prevPinch) {      // grabbed the bar under a window
            if (motion != null && motion.enabled) rebase(gwin, sux, suy, suz);
            dragWin = gwin;
            dnx = tnx; dny = tny;
            float gy = (float) Math.toDegrees(Math.atan(dnx * tanHc)), gp = (float) Math.toDegrees(Math.atan(dny * tanVc));
            rayYaw = gy; rayPitch = gp;
            gwin.dragYawOff = gwin.yawA - gy;
            gwin.dragPitchOff = gwin.pitchA - gp;
            gwin.dragS0 = gwin.scale;
            gwin.dragSize0 = Math.max(1e-3f, sizeEma);
            gwin.dragD0 = gwin.dist;
            dragT0 = nowMs;
            gwin.c.setDragging(true);
            wins.remove(gwin);                                          // bring to the front
            wins.add(gwin);
        }
        if (dragWin != null && resizing && pinching) {                 // the corner follows the hand: size = distance from the window centre
            float rd = (float) Math.hypot(rayYaw - dragWin.yawA, rayPitch - dragWin.pitchA);
            float tg = Math.max(0.5f, Math.min(2.5f, dragWin.dragS0 * rd / rsD0));
            dragWin.scale += (tg - dragWin.scale) * 0.3f;
        }
        if (dragWin != null && !resizing && pinching && nowMs - dragT0 > 180) {
            // push / pull: the hand coming closer to the camera brings the window closer, the hand going away pushes it away.
            // Small changes are ignored (soft dead zone) and it moves gently, so it does not stutter or run away.
            float lr = (float) Math.log(Math.max(1e-3f, sizeEma) / dragWin.dragSize0);
            float f = lr > 0.04f ? lr - 0.04f : (lr < -0.04f ? lr + 0.04f : 0f);
            float tgD = Math.max(0.5f, Math.min(Math.max(6f, dragWin.dragD0), dragWin.dragD0 * (float) Math.exp(-2.8f * f)));
            dragWin.dist += (tgD - dragWin.dist) * 0.25f;
        }
        // let go only when the pinch has been open for a moment (one bad detection must not drop the window)
        if (dragWin != null && !pinching && nowMs - lastPinchT > 250) endDrag();
        prevPinch = pinching;
        for (Win w : wins) {
            if (!shown(w)) continue;
            if (w == target && dragWin == null && !w.ghost) w.c.update(true, o[0], o[1], press);
            else w.c.update(false, 0, 0, false);
        }
    }

    private void drawCursor() {
        float u = sceneH / 540f;
        // a small glass badge with three bars, one above the other, that light up from the top down while a gesture is held
        // (menu: thumb + middle finger; layout reset: OK sign). When all three are lit the badge pops and fades.
        drawPalmMenu();
        if (mpProg > 0f) drawHold(mpNx, mpNy, mpProg, false, 1f, 1f);
        if (okProg > 0f && curOk) drawHold(cnx, cny, okProg, true, 1f, 1f);
        long pa0 = System.currentTimeMillis() - popT;
        if (popT != 0 && pa0 >= 0 && pa0 < 380) {
            float k = pa0 / 380f;
            drawHold(popX, popY, 1f, popOk, 1f + 0.55f * (1f - (1f - k) * (1f - k)), 1f - k);
        }
        if (!curOk || !anyVisible()) return;
        float x = cnx * sceneW / 2f * camScale, y = cny * sceneH / 2f * camScale;
        // small and faint so it does not cover what you are pointing at. Like on Quest: the ring tightens as the fingers close,
        // the dot grows, and a soft ripple spreads from the point where the pinch landed.
        float[] ro = {8f, 12f, 18f}, ri = {2.6f, 4f, 6f}, fo = {0.55f, 0.7f, 1f};
        int cs = Math.max(0, Math.min(2, cursorSz));
        boolean pv = pinching || pkOn;
        float pa = Math.max(pinchAmt, pv ? 1f : 0f);
        long age = System.currentTimeMillis() - rippleT;
        if (rippleT != 0 && age >= 0 && age < 340) {
            float k = age / 340f, e = 1f - (1f - k) * (1f - k);
            float rx = rippleX * sceneW / 2f * camScale, ry = rippleY * sceneH / 2f * camScale;
            aaFade = 0.45f * (1f - k);
            aaBegin();
            aaSeg(rx, ry, rx, ry, ro[cs] * (0.9f + 2.6f * e) * u);
            aaFlush(1f, 1f, 1f);
        }
        aaFade = fo[cs];
        aaBegin();
        aaSeg(x, y, x, y, ro[cs] * (1f - 0.42f * pa) * (1f - 0.2f * pkProg) * u);
        if (gripHot) aaFlush(0.13f, 0.72f, 0.81f); else aaFlush(0.55f + 0.45f * pa, 0.9f - 0.35f * pa, 1f - pa);
        aaFade = Math.min(1f, fo[cs] + 0.2f);
        aaBegin();
        aaSeg(x, y, x, y, ri[cs] * (1f + 0.6f * pa) * u);
        aaFlush(1f, 1f, 1f);
        aaFade = 1f;
    }

    /** part of a circle drawn as short capsules. Angles in turns (0 = top, clockwise); scene y points up. */
    private void aaArc(float cx, float cy, float r, float t0, float t1, float hw) {
        if (t1 - t0 < 0.002f) return;
        int n = Math.max(1, (int) Math.ceil((t1 - t0) * 48f));
        float px = cx + r * (float) Math.sin(t0 * 6.2831853f), py = cy + r * (float) Math.cos(t0 * 6.2831853f);
        for (int i = 1; i <= n; i++) {
            float t = t0 + (t1 - t0) * i / n;
            float qx = cx + r * (float) Math.sin(t * 6.2831853f), qy = cy + r * (float) Math.cos(t * 6.2831853f);
            aaSeg(px, py, qx, qy, hw);
            px = qx; py = qy;
        }
    }

    /**
     * The badge shown above the hand while a gesture is held: a round glass disc with a ring and a small picture, two different looks:
     *  - menu (thumb + middle finger): the ring FILLS clockwise and a grid of 3 x 3 dots lights up row by row, top to bottom;
     *  - layout reset (OK sign): like a countdown, the ring EMPTIES, and four corner brackets (a screen frame) close in towards the middle. In green.
     * When the hold is complete the badge swells and fades.
     */
    private void drawHold(float nx, float ny, float prog, boolean ok, float scale, float alpha) {
        float u = sceneH / 540f * scale;
        float x = nx * sceneW / 2f * camScale, y = ny * sceneH / 2f * camScale + 58f * sceneH / 540f;      // a little above the hand
        float[] c = ok ? new float[]{0.25f, 0.88f, 0.45f} : new float[]{0.62f, 0.82f, 1f};
        // rim, then the dark glass disc
        aaFade = 0.50f * alpha;
        aaBegin(); aaSeg(x, y, x, y, 31.5f * u); aaFlush(1f, 1f, 1f);
        aaFade = 0.88f * alpha;
        aaBegin(); aaSeg(x, y, x, y, 30f * u); aaFlush(0.09f, 0.10f, 0.14f);
        // the ring: a faint track, and the bright part
        aaFade = 0.20f * alpha;
        aaBegin(); aaArc(x, y, 23.5f * u, 0f, 1f, 2.1f * u); aaFlush(1f, 1f, 1f);
        aaFade = alpha;
        aaBegin();
        if (ok) aaArc(x, y, 23.5f * u, prog, 1f, 2.6f * u);       // countdown: what is left
        else aaArc(x, y, 23.5f * u, 0f, prog, 2.6f * u);          // filling up
        aaFlush(c[0], c[1], c[2]);
        if (!ok) {
            // 3 x 3 dots; a row lights up when the hold has reached it (top row first)
            for (int r = 0; r < 3; r++) {
                float f = Math.max(0f, Math.min(1f, prog * 3f - r));
                for (int q = 0; q < 3; q++) {
                    float dx = x + (q - 1) * 8.5f * u, dy = y + (1 - r) * 8.5f * u;
                    aaFade = 0.28f * alpha;
                    aaBegin(); aaSeg(dx, dy, dx, dy, 2.2f * u); aaFlush(1f, 1f, 1f);
                    if (f > 0f) { aaFade = alpha * f; aaBegin(); aaSeg(dx, dy, dx, dy, 2.5f * u); aaFlush(1f, 1f, 1f); }
                }
            }
        } else {
            // four corner brackets that close in as the time runs
            float sq = (15f - 7f * prog) * u, arm = 6f * u;
            aaFade = alpha;
            aaBegin();
            for (int sx = -1; sx <= 1; sx += 2) for (int sy = -1; sy <= 1; sy += 2) {
                aaSeg(x + sx * sq, y + sy * sq, x + sx * (sq - arm), y + sy * sq, 1.9f * u);
                aaSeg(x + sx * sq, y + sy * sq, x + sx * sq, y + sy * (sq - arm), 1.9f * u);
            }
            aaFlush(c[0], c[1], c[2]);
            if (prog > 0.75f) { aaFade = alpha * Math.min(1f, (prog - 0.75f) * 4f); aaBegin(); aaSeg(x, y, x, y, 2.4f * u); aaFlush(c[0], c[1], c[2]); }
        }
        aaFade = 1f;
    }

    private final int[] alc = new int[256];                    // attribute places already asked for (location + 1; 0 = not asked yet)
    private int attr(int prog, String n, int slot) {
        int k = prog * 2 + slot;
        if (k >= 0 && k < alc.length) {
            if (alc[k] == 0) alc[k] = GLES20.glGetAttribLocation(prog, n) + 1;
            return alc[k] - 1;
        }
        return GLES20.glGetAttribLocation(prog, n);
    }

    private void drawQuad(int prog) {
        int a = attr(prog, "p", 0);
        GLES20.glEnableVertexAttribArray(a);
        GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(a);
    }

    private void ensureMask() {
        if (maskW != 0 && maskW == sceneW * 3 / 4) return;
        if (maskTex[0] != 0) { GLES20.glDeleteTextures(1, maskTex, 0); GLES20.glDeleteFramebuffers(1, maskFbo, 0); }
        if (refTex[0] != 0) { GLES20.glDeleteTextures(1, refTex, 0); GLES20.glDeleteFramebuffers(1, refFbo, 0); }
        maskW = Math.max(2, sceneW * 3 / 4); maskH = Math.max(2, sceneH * 3 / 4);
        GLES20.glGenTextures(1, maskTex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, maskW, maskH, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glGenFramebuffers(1, maskFbo, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, maskFbo[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, maskTex[0], 0);
        GLES20.glGenTextures(1, refTex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, refTex[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, maskW, maskH, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glGenFramebuffers(1, refFbo, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, refFbo[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, refTex[0], 0);
    }

    // hand shape (fingers as fat soft bars, palm filled) drawn to a small soft mask; the mask is blurred a little and a thin smooth line
    // is drawn along its middle value, with a faint glow outside: a clean single contour like the web version
    // step 1 of the outline: the soft hand shape into the small mask. This is done BEFORE the camera picture is started, so the big scene
    // picture is drawn in one go (switching away from it half way makes a phone GPU save and reload the whole picture: a lot of memory traffic)
    private boolean maskReady = false;
    private final float[] ringH = new float[8];
    /** half width of the bone ending at each landmark, in hand-size units (size = wrist to the root of the middle finger): the fingers get thinner towards the tip, the palm lines are wide */
    private static final float[] BONE_W = {0f, 0.10f, 0.11f, 0.105f, 0.09f, 0.10f, 0.088f, 0.078f, 0.068f, 0.10f, 0.092f, 0.082f, 0.07f, 0.10f, 0.088f, 0.078f, 0.066f, 0.10f, 0.072f, 0.064f, 0.056f};

    private void renderMask(float[] r) {
        maskReady = false;
        int n = (int) r[0];
        if (n <= 0 || sceneW == 0) return;
        ensureMask();
        maskReady = true;
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, maskFbo[0]);
        GLES20.glViewport(0, 0, maskW, maskH);
        GLES20.glColorMask(true, true, true, true);
        GLES20.glClearColor(0, 0, 0, 0);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        float hwS = sceneW / 2f, hhS = sceneH / 2f;
        final boolean thr = true, arm = skel == 4;
        for (int pass = 0; pass < 2; pass++) {              // pass 0 (thr only): R = the skin-checked band around the hand; pass 1: G (thr) or the whole thing (other modes) = the sure core
            if (thr) GLES20.glColorMask(pass == 0, pass == 1, pass == 0, true);
            for (int i = 0; i < n; i++) {
                float[] X = ohX, Y = ohY;
                for (int k = 0; k < 21; k++) {
                    float hx = r[1 + i * 63 + k * 3], hy = r[1 + i * 63 + k * 3 + 1];
                    if (rot180) { hx = 1f - hx; hy = 1f - hy; }
                    X[k] = (hx * 2f - 1f) * hwS * camScale; Y[k] = (1f - hy * 2f) * hhS * camScale;
                }
                float size = (float) Math.hypot(X[9] - X[0], Y[9] - Y[0]);
                boolean core = thr && pass == 1;
                float hw = Math.max(3f, size * (thr ? (core ? 0.034f : (arm ? 0.15f : 0.125f) + Math.min(0.10f, handSpd * 0.10f)) : 0.115f));
                aaBegin();
                for (int b2 = 0; b2 < BONES.length; b2 += 2) {
                    int p0 = BONES[b2], p1 = BONES[b2 + 1];
                    float x1 = X[p1], y1 = Y[p1];
                    if (p1 == 4 || p1 == 8 || p1 == 12 || p1 == 16 || p1 == 20) {
                        float dx = x1 - X[p0], dy = y1 - Y[p0], l = (float) Math.hypot(dx, dy);
                        if (l > 1e-3f) {
                            float ext = thr ? (core ? -0.0f : (arm ? 0.05f : 0.015f) * size) : -hw * 0.8f;   // see-through: the stroke reaches to the nail (the end cap adds the rest); the other modes keep the cap inside the fingertip
                            x1 += dx / l * ext; y1 += dy / l * ext;
                        }
                    }
                    // the width follows the real finger (so the gaps between the fingers stay open): the sure core a little inside of it, the colour-checked band a little outside of it
                    boolean palmLine = p1 == 1 || p1 == 5 || p1 == 9 || p1 == 13 || p1 == 17;
                    float wf = BONE_W[p1 < 21 ? p1 : 0];
                    float bwid = core ? (palmLine ? 0.10f : Math.max(0.028f, wf - 0.045f)) : (arm ? wf + 0.06f + Math.min(0.06f, handSpd * 0.06f) : wf + 0.012f + Math.min(0.02f, handSpd * 0.02f));
                    aaSeg(X[p0], Y[p0], x1, y1, Math.max(3f, size * bwid));
                }
                if (thr) {
                    if (core) {                                                     // the root of the thumb joins the palm: sure hand (close to the palm only)
                        float cw = size * 0.06f; aaSeg(X[0], Y[0], X[2], Y[2], cw); aaSeg(X[1], Y[1], X[5], Y[5], cw); aaSeg(X[2], Y[2], X[5], Y[5], cw);
                    } else {
                        // the thumb root and the web between thumb and index: the triangle (wrist side of the thumb, thumb joint, root of the index) is filled, but nothing reaches out to the index's middle joint
                        float wh = size * (arm ? 0.10f : 0.085f);
                        aaSeg(X[0], Y[0], X[2], Y[2], wh); aaSeg(X[1], Y[1], X[5], Y[5], wh); aaSeg(X[2], Y[2], X[5], Y[5], wh); aaSeg(X[3], Y[3], X[5], Y[5], wh * 0.8f);
                        aaSeg((X[2] + X[3]) / 2f, (Y[2] + Y[3]) / 2f, X[5], Y[5], wh);
                    }
                }
                aaSeg(X[0] + (X[9] - X[0]) * 0.32f, Y[0] + (Y[9] - Y[0]) * 0.32f, X[0] + (X[9] - X[0]) * 0.78f, Y[0] + (Y[9] - Y[0]) * 0.78f, size * (thr ? (core ? 0.25f : 0.44f) : 0.38f));
                if (arm && size > 1e-3f) {
                    float ax = (X[0] - X[9]) / size, ay = (Y[0] - Y[9]) / size;
                    aaSeg(X[0] + ax * size * 0.10f, Y[0] + ay * size * 0.10f, X[0] + ax * size * 5.0f, Y[0] + ay * size * 5.0f, size * (core ? 0.12f : 0.36f));
                }
                aaFlush(1f, 1f, 1f);
            }
        }
        GLES20.glColorMask(true, true, true, true);
        segDraw = false; segLutUse = 0;
        if (thr) { try { drawSegs(r, n); } catch (Throwable t) { segDraw = false; } }
    }

    /** puts the hand cut out of the real pixels (NvSeg) into the B channel of the mask, moved and turned to where the hand is NOW (the cut was made ~100 ms ago, the 21 points have moved since) */
    public volatile String segDbg = "";
    private final float[] sgP = new float[8];
    private static final int[] SG_PAL = {0, 5, 9, 13, 17};
    private void drawSegs(float[] r, int n) {
        cam.segArm = skel == 4;
        NvCam.SegOut[] ss = cam.segs;
        if (ss == null || progSeg == 0) { segDbg = "seg: ไม่มีข้อมูล (" + (progSeg == 0 ? "shader" : "null") + ") ผิดพลาด=" + cam.segErr; return; }
        String dbg = "seg: รอบ " + cam.segCount + " " + Math.round(cam.segMs) + "ms" + (cam.segErr.isEmpty() ? "" : " ผิดพลาด=" + cam.segErr);
        long now = System.currentTimeMillis();
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        for (int i = 0; i < n && i < 2; i++) {
            NvCam.SegOut s = ss[i];
            if (s == null || s.p == null || s.la == null) { dbg += " | มือ" + i + ": ยังไม่มีผลตัด"; continue; }
            if (now - s.t > 700) { dbg += " | มือ" + i + ": เก่า " + (now - s.t) + "ms"; continue; }
            if (s.arm != (skel == 4) || r.length < 1 + (i + 1) * 63) { dbg += " | มือ" + i + ": โหมดแขนไม่ตรง"; continue; }
            float W = s.W, H = s.H;
            // similarity (turn + scale + move) that carries the palm points of the cut to the palm points now
            float cx = 0, cy = 0, nx = 0, ny = 0;
            for (int k : SG_PAL) { cx += s.lm[k * 2]; cy += s.lm[k * 2 + 1]; nx += r[1 + i * 63 + k * 3] * W; ny += r[1 + i * 63 + k * 3 + 1] * H; }
            cx /= 5f; cy /= 5f; nx /= 5f; ny /= 5f;
            float sr = 0, si = 0, den = 0;
            for (int k : SG_PAL) {
                float a = s.lm[k * 2] - cx, b = s.lm[k * 2 + 1] - cy, a2 = r[1 + i * 63 + k * 3] * W - nx, b2 = r[1 + i * 63 + k * 3 + 1] * H - ny;
                sr += a2 * a + b2 * b; si += b2 * a - a2 * b; den += a * a + b * b;
            }
            if (den < 1e-3f) continue;
            sr /= den; si /= den;
            float sc = (float) Math.hypot(sr, si);
            if (sc < 0.5f || sc > 2f) continue;
            // how well the moved cut still fits the hand: the fingers may have bent since (then the cut is trusted less and the plain shape takes over)
            float dev = 0, szc = (float) Math.hypot((r[1 + i * 63 + 27] - r[1 + i * 63]) * W, (r[1 + i * 63 + 28] - r[1 + i * 63 + 1]) * H);
            for (int k = 0; k < 21; k++) {
                float a = s.lm[k * 2] - cx, b = s.lm[k * 2 + 1] - cy;
                float tx = nx + sr * a - si * b, ty = ny + si * a + sr * b;
                dev += (float) Math.hypot(tx - r[1 + i * 63 + k * 3] * W, ty - r[1 + i * 63 + k * 3 + 1] * H);
            }
            dev = dev / 21f / Math.max(4f, szc);
            float t0 = Math.min(1f, Math.max(0f, (dev - 0.10f) / 0.14f));
            float w = 1f - t0 * t0 * (3f - 2f * t0);
            float ag = (now - s.t - 350f) / 350f; if (ag > 0f) w *= 1f - Math.min(1f, ag);
            dbg += String.format(" | มือ%d: เบี่ยง %.2f น้ำหนัก %.2f อายุ %dms", i, dev, w, now - s.t);
            if (w < 0.02f) continue;
            if (segTex[i] == 0) {
                int[] t = new int[1]; GLES20.glGenTextures(1, t, 0); segTex[i] = t[0]; segUp[i] = null;
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, segTex[i]);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            } else GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, segTex[i]);
            if (segUp[i] != s) {
                GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE_ALPHA, NvSeg.S, NvSeg.S, 0, GLES20.GL_LUMINANCE_ALPHA, GLES20.GL_UNSIGNED_BYTE, java.nio.ByteBuffer.wrap(s.la));
                segUp[i] = s;
            }
            if (segLutUse == 0 && s.cl != null && s.cl.length == 4096) {          // the colour table of the first hand in view (both hands are of the same person)
                if (segLutTex == 0) {
                    int[] t = new int[1]; GLES20.glGenTextures(1, t, 0); segLutTex = t[0]; segLutFor = null;
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, segLutTex);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                } else GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, segLutTex);
                if (segLutFor != s) {
                    GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, 64, 64, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, java.nio.ByteBuffer.wrap(s.cl));
                    segLutFor = s;
                }
                segLutUse = segLutTex;
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, segTex[i]);
            // the four corners of the square area, carried to now, then into the scene (same mapping as the 21 points)
            float[] v4 = sgV;
            for (int c = 0; c < 4; c++) {
                float u = (c & 1), vv = (c >> 1);
                float bx = s.x0 + u * s.side, by = s.y0 + vv * s.side;
                float a = bx - cx, b = by - cy;
                float qx = (nx + sr * a - si * b) / W, qy = (ny + si * a + sr * b) / H;
                if (rot180) { qx = 1f - qx; qy = 1f - qy; }
                v4[c * 4] = (qx * 2f - 1f) * camScale; v4[c * 4 + 1] = (1f - qy * 2f) * camScale; v4[c * 4 + 2] = u; v4[c * 4 + 3] = vv;
            }
            sgBuf.position(0); sgBuf.put(v4, 0, 16); sgBuf.position(0);
            GLES20.glUseProgram(progSeg);
            GLES20.glColorMask(true, false, true, false);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, segTex[i]);
            GLES20.glUniform1i(ul(progSeg, "tex"), 0);
            GLES20.glUniform1f(ul(progSeg, "w"), w);
            int ap = attr(progSeg, "p", 0), at = attr(progSeg, "t", 1);
            GLES20.glEnableVertexAttribArray(ap); GLES20.glEnableVertexAttribArray(at);
            sgBuf.position(0); GLES20.glVertexAttribPointer(ap, 2, GLES20.GL_FLOAT, false, 16, sgBuf);
            sgBuf.position(2); GLES20.glVertexAttribPointer(at, 2, GLES20.GL_FLOAT, false, 16, sgBuf);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(ap); GLES20.glDisableVertexAttribArray(at);
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glColorMask(true, true, true, true);
            segDraw = true;
        }
        segDbg = dbg;
    }

    // step 2 of the outline: a thin line along the middle of the blurred mask, drawn on the scene picture
    private void drawOutline(float[] r) {
        int n = (int) r[0];
        if (n <= 0 || !maskReady) return;
        float hwS = sceneW / 2f, hhS = sceneH / 2f;
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        // the thin outline only needs to be worked out around the hands, not over the whole picture (a big saving on a weak GPU)
        float bx0 = 1e9f, by0 = 1e9f, bx1 = -1e9f, by1 = -1e9f, mg = 0f;
        for (int i = 0; i < n; i++) {
            float sz0 = 0f;
            for (int k = 0; k < 21; k++) {
                float hx = r[1 + i * 63 + k * 3], hy = r[1 + i * 63 + k * 3 + 1];
                if (rot180) { hx = 1f - hx; hy = 1f - hy; }
                float px = (hx * 2f - 1f) * hwS * camScale + hwS, py = (1f - hy * 2f) * hhS * camScale + hhS;
                bx0 = Math.min(bx0, px); bx1 = Math.max(bx1, px); by0 = Math.min(by0, py); by1 = Math.max(by1, py);
            }
            sz0 = Math.max(bx1 - bx0, by1 - by0);
            mg = Math.max(mg, sz0 * 0.35f + 40f);
        }
        int sx0 = Math.max(0, (int) (bx0 - mg)), sy0 = Math.max(0, (int) (by0 - mg));
        int sx1 = Math.min(sceneW, (int) (bx1 + mg)), sy1 = Math.min(sceneH, (int) (by1 + mg));
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
        GLES20.glScissor(sx0, sy0, Math.max(1, sx1 - sx0), Math.max(1, sy1 - sy0));
        GLES20.glUseProgram(progRing);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, refTex[0]);
        GLES20.glUniform1i(ul(progRing, "tex"), 0);
        GLES20.glUniform2f(ul(progRing, "px"), 1f / maskW, 1f / maskH);
        GLES20.glUniform1f(ul(progRing, "rad"), 2.4f);
        // for the glass look: where each wrist is and which way the hand points (in picture-height units), so the film and the line melt away down the forearm
        float asp = (float) sceneW / Math.max(1, sceneH);
        float[] hu = ringH;
        java.util.Arrays.fill(hu, 0f);
        hu[3] = 1f; hu[7] = 1f;
        float palmL = 0.12f;
        for (int i = 0; i < n && i < 2; i++) {
            float x0 = r[1 + i * 63], y0 = r[1 + i * 63 + 1], x9 = r[1 + i * 63 + 27], y9 = r[1 + i * 63 + 28];
            if (rot180) { x0 = 1f - x0; y0 = 1f - y0; x9 = 1f - x9; y9 = 1f - y9; }
            float ux0 = (x0 * 2f - 1f) * camScale * 0.5f + 0.5f, uy0 = 0.5f - (y0 * 2f - 1f) * camScale * 0.5f;
            float ux9 = (x9 * 2f - 1f) * camScale * 0.5f + 0.5f, uy9 = 0.5f - (y9 * 2f - 1f) * camScale * 0.5f;
            float dx = (ux9 - ux0) * asp, dy = uy9 - uy0, dl = (float) Math.hypot(dx, dy);
            if (dl < 1e-4f) { dx = 0f; dy = 1f; dl = 1f; }
            hu[i * 4] = ux0 * asp; hu[i * 4 + 1] = uy0; hu[i * 4 + 2] = dx / dl; hu[i * 4 + 3] = dy / dl;
            if (i == 0) palmL = Math.max(0.03f, dl); else palmL = (palmL + Math.max(0.03f, dl)) * 0.5f;
        }
        GLES20.glUniform4f(ul(progRing, "h0"), hu[0], hu[1], hu[2], hu[3]);
        GLES20.glUniform4f(ul(progRing, "h1"), hu[4], hu[5], hu[6], hu[7]);
        GLES20.glUniform3f(ul(progRing, "hs"), palmL * 1.15f, asp, n);
        GLES20.glUniform1f(ul(progRing, "gl"), skel == 2 ? 1f : 0f);
        drawQuad(progRing);
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    // ---- soft-edged lines and dots: every stroke is a small quad whose edge fades over ~1.5 px, so nothing looks jagged ----
    private int progAA;
    private float aaFade = 1f;
    private FloatBuffer aaBuf;
    private int aaN = 0;
    private float aaSoft = 1.5f;

    private void aaBegin() { aaBuf.position(0); aaN = 0; }

    // one rounded stroke from (x0,y0) to (x1,y1), half width hw, all in scene pixels centred on the picture
    private void aaSeg(float x0, float y0, float x1, float y1, float hw) {
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.hypot(dx, dy);
        if (len < 1e-3f) { dx = 1f; dy = 0f; len = 0f; } else { dx /= len; dy /= len; }
        float nx = -dy, ny = dx;
        float hwS = sceneW / 2f, hhS = sceneH / 2f;
        float lh = len / hw;
        float pad = hw + aaSoft;
        float pa = pad / hw;
        // the four corners go into a fixed array (nothing new is made here: this runs dozens of times every frame)
        float[] c = aaC;
        c[0] = x0 - dx * pad + nx * pad;  c[1] = y0 - dy * pad + ny * pad;  c[2] = -pa;      c[3] = pa;
        c[4] = x0 - dx * pad - nx * pad;  c[5] = y0 - dy * pad - ny * pad;  c[6] = -pa;      c[7] = -pa;
        c[8] = x1 + dx * pad + nx * pad;  c[9] = y1 + dy * pad + ny * pad;  c[10] = lh + pa; c[11] = pa;
        c[12] = x1 + dx * pad - nx * pad; c[13] = y1 + dy * pad - ny * pad; c[14] = lh + pa; c[15] = -pa;
        for (int k : AA_IDX) {
            int o = k * 4;
            aaBuf.put(c[o] / hwS).put(c[o + 1] / hhS).put(c[o + 2]).put(c[o + 3]).put(lh).put(hw);
            aaN++;
        }
    }
    private final float[] aaC = new float[16];
    private static final int[] AA_IDX = {0, 1, 2, 1, 3, 2};
    private final float[] ohX = new float[21], ohY = new float[21];

    private void aaFlush(float cr, float cg, float cb) {
        if (aaN == 0) return;
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUseProgram(progAA);
        int ap = attr(progAA, "p", 0), aq = attr(progAA, "q", 1);
        GLES20.glEnableVertexAttribArray(ap);
        GLES20.glEnableVertexAttribArray(aq);
        aaBuf.position(0);
        GLES20.glVertexAttribPointer(ap, 2, GLES20.GL_FLOAT, false, 24, aaBuf);
        aaBuf.position(2);
        GLES20.glVertexAttribPointer(aq, 4, GLES20.GL_FLOAT, false, 24, aaBuf);
        GLES20.glUniform3f(ul(progAA, "col"), cr, cg, cb);
        GLES20.glUniform1f(ul(progAA, "sf"), aaSoft);
        GLES20.glUniform1f(ul(progAA, "fade"), aaFade);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, aaN);
        GLES20.glDisableVertexAttribArray(ap);
        GLES20.glDisableVertexAttribArray(aq);
        GLES20.glDisable(GLES20.GL_BLEND);
        aaBuf.position(0);
        aaN = 0;
    }

    private void drawSkeleton(float[] r) {
        int n = (int) r[0];
        if (n <= 0) return;
        float u = sceneH / 540f;
        float hwS = sceneW / 2f, hhS = sceneH / 2f;
        for (int i = 0; i < n; i++) {
            float[] X = ohX, Y = ohY;
            for (int k = 0; k < 21; k++) {
                float hx = r[1 + i * 63 + k * 3], hy = r[1 + i * 63 + k * 3 + 1];
                if (rot180) { hx = 1f - hx; hy = 1f - hy; }
                X[k] = (hx * 2f - 1f) * hwS * camScale; Y[k] = (1f - hy * 2f) * hhS * camScale;
            }
            aaBegin();
            for (int b = 0; b < BONES.length; b += 2) aaSeg(X[BONES[b]], Y[BONES[b]], X[BONES[b + 1]], Y[BONES[b + 1]], 5f * u);
            aaFlush(0.0f, 0.45f, 0.62f);
            aaBegin();
            for (int b = 0; b < BONES.length; b += 2) aaSeg(X[BONES[b]], Y[BONES[b]], X[BONES[b + 1]], Y[BONES[b + 1]], 2.4f * u);
            aaFlush(0.45f, 1f, 1f);
            aaBegin();
            for (int k = 0; k < 21; k++) aaSeg(X[k], Y[k], X[k], Y[k], 5.5f * u);
            aaFlush(1f, 1f, 1f);
        }
    }

    @Override public void onDrawFrame(GL10 gl) {
        int cap = fpsCap;
        if (cap > 0) {                                     // slow down: wait until it is time for the next picture
            long target = 1_000_000_000L / cap;
            long el = System.nanoTime() - lastFrameNs;
            if (el < target) { try { Thread.sleep((target - el) / 1_000_000L, (int) ((target - el) % 1_000_000L)); } catch (Throwable ignored) { } }
        }
        lastFrameNs = System.nanoTime();
        long now = System.nanoTime() / 1000000L;
        fc++;
        if (ft0 == 0) ft0 = now;
        if (now - ft0 >= 1000) {
            fps = fc * 1000f / (now - ft0); fc = 0; ft0 = now;
            // automatic picture size: step down 10 % when the frame rate is clearly under what we aim at, step up when it is at the aim for a while
            float aim = cap > 0 ? cap : 60f;
            if (autoRes) {
                if (fps < aim * 0.9f && now - lastScaleT > 2500 && sceneScale > 0.61f) { sceneScale = Math.max(0.6f, sceneScale - 0.1f); sceneW = 0; lastScaleT = now; }
                else if (fps >= aim - 1.5f && sceneScale < 0.99f && now - lastScaleT > 9000) { sceneScale = Math.min(1f, sceneScale + 0.1f); sceneW = 0; lastScaleT = now; }
            } else if (sceneScale < 1f) { sceneScale = 1f; sceneW = 0; }
        }

        updateView();
        int iw = imgW, ih = imgH;
        if (iw <= 0 || ih <= 0) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, vw, vh);
            GLES20.glClearColor(0, 0, 0, 1);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            return;
        }
        if (sceneW == 0) makeScene(iw, ih);
        boolean upd;
        synchronized (lock) { upd = newFrame; newFrame = false; }
        if (upd && st != null) {
            try { st.updateTexImage(); st.getTransformMatrix(stm); } catch (Throwable ignored) { }
            // how old is this picture? the camera stamps it with the time it was taken; if that stamp makes sense, use it instead of a guess
            long lagNs = (long) camLagMs * 1_000_000L;
            try {
                long age = android.os.SystemClock.elapsedRealtimeNanos() - st.getTimestamp();
                if (age > 8_000_000L && age < 350_000_000L) {
                    camLagMeasured = camLagMeasured <= 0f ? age / 1e6f : camLagMeasured + (age / 1e6f - camLagMeasured) * 0.15f;
                    lagNs = (long) (camLagMeasured * 1e6f);
                } else camLagMeasured = 0f;
            } catch (Throwable ignored) { camLagMeasured = 0f; }
            frameRot = reproj ? rotBefore(System.nanoTime() - lagNs) : null;
        }
        int pc = panoCmd;
        if (pc != 0) {
            panoCmd = 0;
            if (pc == 2) { panoOn = false; panoBmp = null; }
            else {
                android.graphics.Bitmap pb = panoBmp; panoBmp = null;
                if (pb != null) {
                    try {
                        int[] mx = new int[1];
                        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, mx, 0);
                        int lim = Math.max(1024, mx[0]);
                        if (pb.getWidth() > lim) {
                            android.graphics.Bitmap b2 = android.graphics.Bitmap.createScaledBitmap(pb, lim, Math.max(1, (int) ((long) pb.getHeight() * lim / pb.getWidth())), true);
                            pb.recycle(); pb = b2;
                        }
                        if (panoTex == 0) { int[] t = new int[1]; GLES20.glGenTextures(1, t, 0); panoTex = t[0]; }
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, panoTex);
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, pb, 0);
                        pb.recycle();
                        float[] m = rotView;                              // the picture's middle is where you are looking now
                        panoLon0 = -(float) Math.atan2(-m[2], -m[6]);
                        panoOn = true;
                    } catch (Throwable t) { panoOn = false; }
                }
            }
        }
        predictNow(now);                                    // where the hand is on screen right now (needed first: the outline mask is made before the scene picture)
        if (skel >= 2) {
            renderMask(dispDraw);
            if (maskReady) {                                          // the same for every hand mode: the shape is checked against the skin colour of the camera picture, so it sticks to the real hand
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, refFbo[0]);
                GLES20.glViewport(0, 0, maskW, maskH);
                GLES20.glClearColor(0, 0, 0, 0);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                drawCamera(2);
            }
        } else maskReady = false;
        // 1) scene picture: camera (or the 360 picture) + hand skeleton
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
        GLES20.glViewport(0, 0, sceneW, sceneH);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);         // always start clean: the GPU then does not have to load last frame's picture back in
        if (panoOn && panoTex != 0) {
            float[] m = rotView;
            rm3[0] = m[0]; rm3[1] = m[1]; rm3[2] = m[2]; rm3[3] = m[4]; rm3[4] = m[5]; rm3[5] = m[6]; rm3[6] = m[8]; rm3[7] = m[9]; rm3[8] = m[10];
            GLES20.glUseProgram(progPano);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, panoTex);
            GLES20.glUniform1i(ul(progPano, "tex"), 0);
            GLES20.glUniform2f(ul(progPano, "tn"), tanH, tanV);
            GLES20.glUniformMatrix3fv(ul(progPano, "rm"), 1, false, rm3, 0);
            GLES20.glUniform1f(ul(progPano, "lon0"), panoLon0);
            drawQuad(progPano);
        } else if (envScene == 1 && !cinema) {
            float[] m = rotView;
            rm3[0] = m[0]; rm3[1] = m[1]; rm3[2] = m[2]; rm3[3] = m[4]; rm3[4] = m[5]; rm3[5] = m[6]; rm3[6] = m[8]; rm3[7] = m[9]; rm3[8] = m[10];
            GLES20.glUseProgram(progCity);
            GLES20.glUniform2f(ul(progCity, "tn"), tanH, tanV);
            GLES20.glUniformMatrix3fv(ul(progCity, "rm"), 1, false, rm3, 0);
            GLES20.glUniform3f(ul(progCity, "eo"), sux, suy, 1.15f);
            drawQuad(progCity);
        } else if (cinema) {
            GLES20.glClearColor(0.01f, 0.01f, 0.015f, 1f);          // the room goes dark: only the screen is left
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        } else {
        drawCamera(false);
        }
        draw3D();
        computePanel();
        cursor();
        drawWins();
        drawBars();
        if (skel == 4 && maskReady) drawCamera(true);
        if (skel == 1) drawSkeleton(dispDraw); else if (skel == 2 || skel == 3) drawOutline(dispDraw);
        drawCursor();

        // 2) screen: mono or split, with lens warp
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glClearColor(0, 0, 0, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(progFin);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sceneTex[0]);
        GLES20.glUniform1i(ul(progFin, "tex"), 0);
        boolean s = stereo;
        int eyes = s ? 2 : 1;
        int ew = vw / eyes;
        float ea = (float) ew / vh, sa = (float) sceneW / sceneH;
        float scx = ea < sa ? ea / sa : 1f, scy = ea < sa ? 1f : sa / ea;
        GLES20.glUniform2f(ul(progFin, "sc"), scx, scy);
        GLES20.glUniform1f(ul(progFin, "k"), LENS_K[Math.max(0, Math.min(3, lens))]);
        GLES20.glUniform1f(ul(progFin, "ca"), stereo ? chroma : 0f);
        int ush = ul(progFin, "sh");
        for (int e = 0; e < eyes; e++) {
            GLES20.glViewport(e * ew, 0, ew, vh);
            GLES20.glUniform1f(ush, eyes == 2 ? (e == 0 ? -eyeShift : eyeShift) : 0f);   // each eye's picture is moved towards the nose so its middle sits in front of that lens
            drawQuad(progFin);
        }
    }
}
