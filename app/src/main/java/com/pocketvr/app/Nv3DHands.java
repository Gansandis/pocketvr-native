package com.pocketvr.app;

import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;

/**
 * 3D mode: the hands drawn as 3D models (glass / solid / bones), a few 3D things in the room that can be pushed and picked up with the fingers,
 * and the left-hand "I love you" sign (thumb, index and little finger out, middle and ring finger folded) that switches the mode on and off.
 *
 * Everything is built on the CPU into small triangle lists already in VIEW space (x right, y up, the camera looks down -z), so the shader only needs the
 * projection and there are only a handful of draw calls a frame. The hands are put where the camera picture shows them: every joint is a ray through its
 * place in the picture, at a distance worked out from the size of the palm (a palm is ~9.5 cm long), with the depth the hand model reports for each joint.
 * The only Android class used is GLES20, so the geometry and the gesture can be tested on any computer.
 */
final class Nv3DHands {
    // ---- settings (the settings screen changes them, the render thread reads them) ----
    volatile int mode = 0;               // 0 off, 1 hands only, 2 the room + 3D things, 3 the whole scene (camera picture swapped for the virtual city)
    volatile int lastMode = 2;           // what the gesture switches on
    volatile int style = 0;              // 0 glass, 1 solid, 2 bones
    volatile int color = 0;              // index into COLORS
    volatile float opacity = 0.9f;       // 0.3 .. 1
    volatile float thick = 1f;           // 0.7 .. 1.4 : finger thickness
    volatile boolean gesture = true;     // the sign switches the mode
    volatile boolean gestureBoth = false;// the sign counts with either hand (otherwise: the left hand only)
    volatile boolean hideCam = true;     // while on: no outline / skeleton of the hand from the camera picture
    volatile int nObj = 7;               // how many 3D things in the room (modes 2 and 3)
    volatile boolean respawn = false;    // put the things in front of you again
    volatile String dbg = "";
    volatile int ilyHand = -1;           // which hand makes the sign right now

    static final int[] COLORS = {0x58D6FF, 0xE9B692, 0xEFEFF5, 0xFF6BB0, 0x62FF9F, 0xF2C14E};
    static final int[] OBJ_COL = {0xFF5C5C, 0xFFB347, 0xFFE45C, 0x6BE675, 0x4FC3FF, 0x8F7CFF, 0xFF7CD6, 0x5CE1E6};

    // ---- gl ----
    private int prog = 0, aP = -1, aN = -1, aC = -1, uProj = -1, uAl = -1, uGls = -1, uRim = -1;
    private boolean failed = false;
    private FloatBuffer[] hb = new FloatBuffer[2];
    private FloatBuffer ob;
    private final float[][] hv = new float[2][];
    private float[] ov;
    private final int[] hn = new int[2];
    private int on = 0;
    private int cur = 0;                  // write position while a list is being filled
    private float[] tgt;
    private static final int HAND_VERTS = 8000, OBJ_VERTS = 4600;

    boolean ready() { return prog != 0; }
    void lost() { prog = 0; hb[0] = hb[1] = null; ob = null; failed = false; }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        return ok[0] == 0 ? 0 : s;
    }

    static final String VS = "attribute vec3 p; attribute vec3 n; attribute vec3 c; uniform mat4 proj; varying vec3 vn; varying vec3 vp; varying vec3 vc;" +
            "void main(){ gl_Position=proj*vec4(p,1.0); vn=n; vp=p; vc=c; }";
    static final String FS = "precision mediump float; uniform float al; uniform float gls; uniform float rim; varying vec3 vn; varying vec3 vp; varying vec3 vc;" +
            "void main(){ vec3 N=normalize(vn); vec3 V=normalize(-vp); float nd=abs(dot(N,V)); float fr=pow(1.0-nd,2.2);" +
            " vec3 L=normalize(vec3(0.3,0.55,0.8)); float d=max(dot(N,L),0.0);" +
            " vec3 col=vc*(0.36+0.74*d)+vec3(fr*rim);" +
            " float a=al*mix(1.0,0.20+0.80*fr,gls);" +
            " gl_FragColor=vec4(col*a,a); }";

    void init() {
        if (failed) return;
        try {
            int v = shader(GLES20.GL_VERTEX_SHADER, VS), f = shader(GLES20.GL_FRAGMENT_SHADER, FS);
            if (v == 0 || f == 0) { failed = true; dbg = "3D: shader คอมไพล์ไม่ผ่าน"; return; }
            int pr = GLES20.glCreateProgram();
            GLES20.glAttachShader(pr, v); GLES20.glAttachShader(pr, f);
            GLES20.glLinkProgram(pr);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(pr, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) { failed = true; dbg = "3D: shader ลิงก์ไม่ผ่าน"; return; }
            aP = GLES20.glGetAttribLocation(pr, "p"); aN = GLES20.glGetAttribLocation(pr, "n"); aC = GLES20.glGetAttribLocation(pr, "c");
            uProj = GLES20.glGetUniformLocation(pr, "proj"); uAl = GLES20.glGetUniformLocation(pr, "al");
            uGls = GLES20.glGetUniformLocation(pr, "gls"); uRim = GLES20.glGetUniformLocation(pr, "rim");
            for (int i = 0; i < 2; i++) {
                hv[i] = new float[HAND_VERTS * 9];
                hb[i] = ByteBuffer.allocateDirect(HAND_VERTS * 36).order(ByteOrder.nativeOrder()).asFloatBuffer();
            }
            ov = new float[OBJ_VERTS * 9];
            ob = ByteBuffer.allocateDirect(OBJ_VERTS * 36).order(ByteOrder.nativeOrder()).asFloatBuffer();
            prog = pr;
        } catch (Throwable t) { failed = true; dbg = "3D: " + t; }
    }

    // ======================================================================== templates
    private static final int SEG = 8, RING = 5;
    private static final float[] SPH = new float[SEG * RING * 6 * 3];     // unit sphere triangles (positions = normals)
    private static final float[] CYL = new float[SEG * 6 * 4];            // unit cylinder: ux uy uz (z 0..1) and nothing else; the normal is (ux, uy)
    private static final float[] BOX = new float[36 * 6];                 // unit cube: position, normal
    static {
        int o = 0;
        for (int i = 0; i < RING; i++) for (int j = 0; j < SEG; j++) {
            double t0 = Math.PI * i / RING, t1 = Math.PI * (i + 1) / RING, p0 = 2 * Math.PI * j / SEG, p1 = 2 * Math.PI * (j + 1) / SEG;
            float[][] q = {sp(t0, p0), sp(t1, p0), sp(t1, p1), sp(t0, p0), sp(t1, p1), sp(t0, p1)};
            for (float[] a : q) { SPH[o++] = a[0]; SPH[o++] = a[1]; SPH[o++] = a[2]; }
        }
        o = 0;
        for (int j = 0; j < SEG; j++) {
            double p0 = 2 * Math.PI * j / SEG, p1 = 2 * Math.PI * (j + 1) / SEG;
            float c0 = (float) Math.cos(p0), s0 = (float) Math.sin(p0), c1 = (float) Math.cos(p1), s1 = (float) Math.sin(p1);
            float[][] q = {{c0, s0, 0}, {c1, s1, 0}, {c1, s1, 1}, {c0, s0, 0}, {c1, s1, 1}, {c0, s0, 1}};
            for (float[] a : q) { CYL[o++] = a[0]; CYL[o++] = a[1]; CYL[o++] = a[2]; o++; }
        }
        o = 0;
        float[][] nrm = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        int[][] sg = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (float[] n : nrm) {
            float[] u, w;
            if (n[0] != 0) { u = new float[]{0, 1, 0}; w = new float[]{0, 0, 1}; }
            else if (n[1] != 0) { u = new float[]{1, 0, 0}; w = new float[]{0, 0, 1}; }
            else { u = new float[]{1, 0, 0}; w = new float[]{0, 1, 0}; }
            float[][] c = new float[4][3];
            for (int k = 0; k < 4; k++) for (int i = 0; i < 3; i++) c[k][i] = n[i] + sg[k][0] * u[i] + sg[k][1] * w[i];
            int[] idx = {0, 1, 2, 0, 2, 3};
            for (int k : idx) { BOX[o++] = c[k][0]; BOX[o++] = c[k][1]; BOX[o++] = c[k][2]; BOX[o++] = n[0]; BOX[o++] = n[1]; BOX[o++] = n[2]; }
        }
    }
    private static float[] sp(double th, double ph) { return new float[]{(float) (Math.sin(th) * Math.cos(ph)), (float) (Math.sin(th) * Math.sin(ph)), (float) Math.cos(th)}; }

    // ======================================================================== building the lists
    private float cr, cg, cb;
    private void colour(int rgb) { cr = ((rgb >> 16) & 0xFF) / 255f; cg = ((rgb >> 8) & 0xFF) / 255f; cb = (rgb & 0xFF) / 255f; }
    private void vtx(float px, float py, float pz, float nx, float ny, float nz) {
        int o = cur * 9;
        if (o + 9 > tgt.length) return;
        float[] a = tgt;
        a[o] = px; a[o + 1] = py; a[o + 2] = pz; a[o + 3] = nx; a[o + 4] = ny; a[o + 5] = nz; a[o + 6] = cr; a[o + 7] = cg; a[o + 8] = cb;
        cur++;
    }
    private void sphere(float x, float y, float z, float r) {
        for (int i = 0; i < SPH.length; i += 3) vtx(x + SPH[i] * r, y + SPH[i + 1] * r, z + SPH[i + 2] * r, SPH[i], SPH[i + 1], SPH[i + 2]);
    }
    /** a tube from a to b with radius r */
    private void tube(float ax, float ay, float az, float bx, float by, float bz, float r) {
        float dx = bx - ax, dy = by - ay, dz = bz - az;
        float L = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(L > 1e-5f)) return;
        float zx = dx / L, zy = dy / L, zz = dz / L;
        float hx = Math.abs(zy) < 0.9f ? 0f : 1f, hy = Math.abs(zy) < 0.9f ? 1f : 0f;
        float xx = hy * zz, xy = -hx * zz, xz = hx * zy - hy * zx;          // h x z
        float xl = (float) Math.sqrt(xx * xx + xy * xy + xz * xz);
        if (!(xl > 1e-6f)) return;
        xx /= xl; xy /= xl; xz /= xl;
        float yx = zy * xz - zz * xy, yy = zz * xx - zx * xz, yz = zx * xy - zy * xx;   // z x x
        for (int i = 0; i < CYL.length; i += 4) {
            float ux = CYL[i], uy = CYL[i + 1], uz = CYL[i + 2];
            float nx = xx * ux + yx * uy, ny = xy * ux + yy * uy, nz = xz * ux + yz * uy;
            vtx(ax + nx * r + dx * uz, ay + ny * r + dy * uz, az + nz * r + dz * uz, nx, ny, nz);
        }
    }
    /** a cube with half-size h, turned by the 3x3 matrix R (row-major) */
    private void box(float x, float y, float z, float h, float[] R) {
        for (int i = 0; i < BOX.length; i += 6) {
            float px = BOX[i] * h, py = BOX[i + 1] * h, pz = BOX[i + 2] * h, nx = BOX[i + 3], ny = BOX[i + 4], nz = BOX[i + 5];
            vtx(x + R[0] * px + R[1] * py + R[2] * pz, y + R[3] * px + R[4] * py + R[5] * pz, z + R[6] * px + R[7] * py + R[8] * pz,
                    R[0] * nx + R[1] * ny + R[2] * nz, R[3] * nx + R[4] * ny + R[5] * nz, R[6] * nx + R[7] * ny + R[8] * nz);
        }
    }

    // ======================================================================== hand geometry
    private static final int[] BONES = {0, 1, 1, 2, 2, 3, 3, 4, 0, 5, 5, 6, 6, 7, 7, 8, 5, 9, 9, 10, 10, 11, 11, 12, 9, 13, 13, 14, 14, 15, 15, 16, 13, 17, 17, 18, 18, 19, 19, 20, 0, 17, 0, 9, 0, 13};
    // joint radii in metres (a hand of normal size)
    private static final float[] JR = {0.0125f,
            0.0115f, 0.0105f, 0.0092f, 0.0078f,
            0.0105f, 0.0092f, 0.0082f, 0.0070f,
            0.0108f, 0.0095f, 0.0084f, 0.0072f,
            0.0100f, 0.0088f, 0.0078f, 0.0066f,
            0.0092f, 0.0080f, 0.0070f, 0.0060f};
    private static boolean palmJ(int k) { return k == 0 || k == 5 || k == 9 || k == 13 || k == 17; }
    private static final float PALM_L = 0.095f, PALM_W = 0.085f;      // wrist -> middle knuckle, index knuckle -> little finger knuckle (metres)

    private final float[][] J = new float[2][63];       // the joints in view space (metres)
    private final float[] dS = {0.5f, 0.5f};            // smoothed distance of each hand
    private final float[] fade = new float[2];
    private final boolean[] live = new boolean[2], wasLive = new boolean[2];
    private final float[] nxT = new float[21], nyT = new float[21], nzT = new float[21];

    /** places the joints of hand i (index into the hand list d, picture coordinates 0..1) in view space; false if it cannot be placed */
    boolean build(int i, float[] d, boolean rot180, float tanH, float tanV) {
        int b = 1 + i * 63;
        if (d == null || d.length < b + 63) return false;
        for (int k = 0; k < 21; k++) {
            float hx = d[b + k * 3], hy = d[b + k * 3 + 1];
            if (rot180) { hx = 1f - hx; hy = 1f - hy; }
            nxT[k] = (hx * 2f - 1f) * tanH; nyT[k] = (1f - hy * 2f) * tanV; nzT[k] = d[b + k * 3 + 2];
            if (Float.isNaN(nxT[k]) || Float.isNaN(nyT[k]) || Float.isNaN(nzT[k])) return false;
        }
        float L = (float) Math.hypot(nxT[9] - nxT[0], nyT[9] - nyT[0]);
        float W = (float) Math.hypot(nxT[5] - nxT[17], nyT[5] - nyT[17]);
        float est = Math.max(L / PALM_L, W / PALM_W);          // how big a metre of hand looks at this distance (1 / distance)
        if (!(est > 1e-3f)) return false;
        float dist = Math.max(0.12f, Math.min(1.4f, 1f / est));
        dS[i] = wasLive[i] ? dS[i] + (dist - dS[i]) * 0.35f : dist;
        float dd = dS[i];
        float zs = 2f * tanH * dd;                             // the model's depth is in units of the picture width
        float[] j = J[i];
        for (int k = 0; k < 21; k++) {
            float dk = dd + Math.max(-0.12f, Math.min(0.12f, nzT[k] * zs));
            j[k * 3] = nxT[k] * dk; j[k * 3 + 1] = nyT[k] * dk; j[k * 3 + 2] = -dk;
        }
        return true;
    }

    private void buildHand(int i) {
        float[] j = J[i];
        tgt = hv[i]; cur = 0;
        colour(COLORS[Math.max(0, Math.min(COLORS.length - 1, color))]);
        int st = style;
        float th = thick;
        float jf = st == 2 ? 0.62f : 1f, bf = st == 2 ? 0.40f : 1f;
        for (int k = 0; k < 21; k++) sphere(j[k * 3], j[k * 3 + 1], j[k * 3 + 2], JR[k] * th * jf);
        for (int q = 0; q < BONES.length; q += 2) {
            int a = BONES[q], b = BONES[q + 1];
            if (st == 2 && q >= 42) continue;                  // no extra palm bones on the bone model
            float r = 0.5f * (JR[a] + JR[b]) * th * bf * (st != 2 && palmJ(a) && palmJ(b) ? 1.18f : 1f);
            tube(j[a * 3], j[a * 3 + 1], j[a * 3 + 2], j[b * 3], j[b * 3 + 1], j[b * 3 + 2], r);
        }
        if (st != 2) {
            // fill the palm: the model has one point for the wrist but a real wrist is ~5 cm wide, so the palm gets a wide base (across = from the little finger side to the index side)
            float ax = j[15] - j[51], ay = j[16] - j[52], az = j[17] - j[53];
            float al = (float) Math.sqrt(ax * ax + ay * ay + az * az);
            if (al > 1e-5f) {
                ax /= al; ay /= al; az /= al;
                int[] kn = {5, 9, 13, 17};
                float[] off = {0.025f, 0.0085f, -0.0085f, -0.025f};
                float[] bx = new float[4], by = new float[4], bz = new float[4];
                float sc = th * Math.max(0.6f, Math.min(1.6f, al / 0.078f));          // a bigger hand (or one closer than guessed) gets a wider base too
                for (int q = 0; q < 4; q++) {
                    float o = off[q] * (al / 0.078f > 0.6f ? Math.min(1.6f, al / 0.078f) : 0.6f);
                    bx[q] = j[0] + ax * o; by[q] = j[1] + ay * o; bz[q] = j[2] + az * o;
                    tube(bx[q], by[q], bz[q], j[kn[q] * 3], j[kn[q] * 3 + 1], j[kn[q] * 3 + 2], 0.0115f * th);
                }
                float[] tt = {0f, 0.5f};
                for (float t : tt) for (int q = 0; q < 3; q++) {
                    float x0 = bx[q] + t * (j[kn[q] * 3] - bx[q]), y0 = by[q] + t * (j[kn[q] * 3 + 1] - by[q]), z0 = bz[q] + t * (j[kn[q] * 3 + 2] - bz[q]);
                    float x1 = bx[q + 1] + t * (j[kn[q + 1] * 3] - bx[q + 1]), y1 = by[q + 1] + t * (j[kn[q + 1] * 3 + 1] - by[q + 1]), z1 = bz[q + 1] + t * (j[kn[q + 1] * 3 + 2] - bz[q + 1]);
                    tube(x0, y0, z0, x1, y1, z1, 0.0125f * th);
                }
                sphere(bx[0], by[0], bz[0], 0.0125f * th); sphere(bx[3], by[3], bz[3], 0.0125f * th);
            }
            tube(j[3], j[4], j[5], j[15], j[16], j[17], 0.0100f * th);                // the web of the thumb
        }
        hn[i] = cur;
    }

    // ======================================================================== 3D things in the room
    private static final class Ob {
        float[] home = new float[3], p = new float[3], v = new float[3];
        float r, flash, ang, spinV, tilt, phase, ghost;
        int type, col, held = -1;
    }
    private final ArrayList<Ob> objs = new ArrayList<>();
    private int prevMode = 0;
    private long lastT = 0;
    private final float[][] jw = new float[2][63];           // joints, in world axes (relative to the eye)
    private final float[][] cPrev = new float[2][18], cVel = new float[2][18];
    private final boolean[] hasPrev = new boolean[2], pinchOn = new boolean[2];
    private final Ob[] held = new Ob[2];
    private final float[][] pinchPrev = new float[2][3], pinchVel = new float[2][3];
    private long seed = 12345;
    private float rnd() { seed = seed * 6364136223846793005L + 1442695040888963407L; return ((seed >>> 33) & 0xFFFFFF) / (float) 0x1000000; }

    private void spawn(float[] rn, float ex, float ey, float ez) {
        objs.clear(); held[0] = held[1] = null;
        float fx = -rn[2], fy = -rn[6];
        if (Math.hypot(fx, fy) < 0.3) { fx = -rn[1]; fy = -rn[5]; }
        float yaw = (float) Math.atan2(fy, fx);
        int n = Math.max(0, Math.min(12, nObj));
        for (int i = 0; i < n; i++) {
            Ob o = new Ob();
            float a = yaw + (i - (n - 1) * 0.5f) * 0.30f;
            float dist = 0.55f + 0.25f * ((i * 7) % 3) / 2f;
            o.home[0] = ex + (float) Math.cos(a) * dist; o.home[1] = ey + (float) Math.sin(a) * dist;
            o.home[2] = ez - 0.12f + 0.20f * ((i * 5) % 4) / 3f;
            System.arraycopy(o.home, 0, o.p, 0, 3);
            o.r = 0.040f + 0.030f * ((i * 3) % 4) / 3f;
            o.type = i % 3; o.col = OBJ_COL[i % OBJ_COL.length];
            o.phase = rnd() * 6.28f; o.ang = rnd() * 6.28f; o.tilt = rnd() * 6.28f;
            objs.add(o);
        }
    }

    private void toWorld(int i, float[] rn, float ex, float ey, float ez) {
        float[] j = J[i], w = jw[i];
        for (int k = 0; k < 21; k++) {
            float vx = j[k * 3], vy = j[k * 3 + 1], vz = j[k * 3 + 2];
            w[k * 3] = ex + rn[0] * vx + rn[1] * vy + rn[2] * vz;
            w[k * 3 + 1] = ey + rn[4] * vx + rn[5] * vy + rn[6] * vz;
            w[k * 3 + 2] = ez + rn[8] * vx + rn[9] * vy + rn[10] * vz;
        }
    }
    private static final int[] TIPS = {4, 8, 12, 16, 20};

    private void stepObjects(float dt, float[] rn, float ex, float ey, float ez, float tsec) {
        for (int i = 0; i < 2; i++) {
            boolean act = live[i] && fade[i] > 0.4f;
            if (!act) {
                hasPrev[i] = false;
                if (held[i] != null) { held[i].held = -1; held[i] = null; }
                pinchOn[i] = false;
                continue;
            }
            toWorld(i, rn, ex, ey, ez);
            float[] w = jw[i];
            float[] c = new float[18];
            for (int q = 0; q < 5; q++) { c[q * 3] = w[TIPS[q] * 3]; c[q * 3 + 1] = w[TIPS[q] * 3 + 1]; c[q * 3 + 2] = w[TIPS[q] * 3 + 2]; }
            int[] pal = {0, 5, 9, 13, 17};
            for (int q = 0; q < 5; q++) { c[15] += w[pal[q] * 3] * 0.2f; c[16] += w[pal[q] * 3 + 1] * 0.2f; c[17] += w[pal[q] * 3 + 2] * 0.2f; }
            if (hasPrev[i]) {
                for (int q = 0; q < 18; q++) {
                    float vel = (c[q] - cPrev[i][q]) / dt;
                    cVel[i][q] += (Math.max(-4f, Math.min(4f, vel)) - cVel[i][q]) * 0.5f;
                }
            } else java.util.Arrays.fill(cVel[i], 0f);
            System.arraycopy(c, 0, cPrev[i], 0, 18);
            hasPrev[i] = true;
            // pinch: thumb tip and index tip together picks a thing up, opening the fingers lets it go (thrown with the speed of the hand)
            float[] j = J[i];
            float pd = (float) Math.sqrt(sq(j[12] - j[24]) + sq(j[13] - j[25]) + sq(j[14] - j[26]));
            boolean pin = held[i] != null ? pd < 0.05f : pd < 0.028f;
            float px = (w[12] + w[24]) * 0.5f, py = (w[13] + w[25]) * 0.5f, pz = (w[14] + w[26]) * 0.5f;
            if (pinchOn[i] && pin) {
                pinchVel[i][0] += ((px - pinchPrev[i][0]) / dt - pinchVel[i][0]) * 0.35f;
                pinchVel[i][1] += ((py - pinchPrev[i][1]) / dt - pinchVel[i][1]) * 0.35f;
                pinchVel[i][2] += ((pz - pinchPrev[i][2]) / dt - pinchVel[i][2]) * 0.35f;
            } else if (!pinchOn[i]) java.util.Arrays.fill(pinchVel[i], 0f);          // (on the frame the fingers open the speed of the last pinching frame is kept: the opening itself is not a throw)
            pinchPrev[i][0] = px; pinchPrev[i][1] = py; pinchPrev[i][2] = pz;
            pinchOn[i] = pin;
            if (pin && held[i] == null) {
                Ob best = null; float bd = 1e9f;
                for (Ob o : objs) {
                    if (o.held >= 0) continue;
                    float dd = (float) Math.sqrt(sq(o.p[0] - px) + sq(o.p[1] - py) + sq(o.p[2] - pz));
                    if (dd < o.r + 0.04f && dd < bd) { bd = dd; best = o; }
                }
                if (best != null) { held[i] = best; best.held = i; best.flash = 1f; }
            } else if (!pin && held[i] != null) {
                Ob o = held[i];
                float sp = (float) Math.sqrt(sq(pinchVel[i][0]) + sq(pinchVel[i][1]) + sq(pinchVel[i][2]));
                float k = sp > 3.5f ? 3.5f / sp : 1f;
                o.v[0] = pinchVel[i][0] * k; o.v[1] = pinchVel[i][1] * k; o.v[2] = pinchVel[i][2] * k;
                o.held = -1; held[i] = null; o.ghost = 0.35f;        // it leaves the fingers without bumping into them
            }
            if (held[i] != null) {
                Ob o = held[i];
                float k = Math.min(1f, dt * 18f);
                o.p[0] += (px - o.p[0]) * k; o.p[1] += (py - o.p[1]) * k; o.p[2] += (pz - o.p[2]) * k;
                o.v[0] = o.v[1] = o.v[2] = 0f;
                continue;
            }
            // touching with the fingertips (small) or the palm (big) pushes things away
            for (Ob o : objs) {
                if (o.held >= 0 || o.ghost > 0f) continue;
                for (int q = 0; q < 6; q++) {
                    float rc = q < 5 ? 0.012f : 0.040f;
                    float dx = o.p[0] - c[q * 3], dy = o.p[1] - c[q * 3 + 1], dz = o.p[2] - c[q * 3 + 2];
                    float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz), md = o.r + rc;
                    if (dist < md && dist > 1e-5f) {
                        float nx = dx / dist, ny = dy / dist, nz = dz / dist;
                        o.p[0] += nx * (md - dist); o.p[1] += ny * (md - dist); o.p[2] += nz * (md - dist);
                        float vn = cVel[i][q * 3] * nx + cVel[i][q * 3 + 1] * ny + cVel[i][q * 3 + 2] * nz;
                        float push = Math.max(0f, vn) * 1.15f + 0.35f;
                        o.v[0] += nx * push; o.v[1] += ny * push; o.v[2] += nz * push;
                        o.flash = 1f; o.spinV += (rnd() - 0.5f) * 6f;
                    }
                }
            }
        }
        float damp = (float) Math.exp(-1.6f * dt), sd = (float) Math.exp(-1.0f * dt);
        for (Ob o : objs) {
            if (o.held >= 0) { o.ang += 2f * dt; continue; }
            float hz = o.home[2] + 0.010f * (float) Math.sin(tsec * 1.2f + o.phase);
            o.v[0] += (o.home[0] - o.p[0]) * 1.1f * dt; o.v[1] += (o.home[1] - o.p[1]) * 1.1f * dt; o.v[2] += (hz - o.p[2]) * 1.1f * dt;
            o.v[0] *= damp; o.v[1] *= damp; o.v[2] *= damp;
            float sp = (float) Math.sqrt(sq(o.v[0]) + sq(o.v[1]) + sq(o.v[2]));
            if (sp > 5f) { float k = 5f / sp; o.v[0] *= k; o.v[1] *= k; o.v[2] *= k; }
            o.p[0] += o.v[0] * dt; o.p[1] += o.v[1] * dt; o.p[2] += o.v[2] * dt;
            o.ang += (o.spinV + 0.45f) * dt; o.spinV *= sd;
            o.flash = Math.max(0f, o.flash - 3f * dt);
            if (o.ghost > 0f) o.ghost -= dt;
        }
    }
    private static float sq(float x) { return x * x; }

    private void buildObjects(float[] rn, float ex, float ey, float ez) {
        tgt = ov; cur = 0;
        float[] R = new float[9];
        for (Ob o : objs) {
            float rx = o.p[0] - ex, ry = o.p[1] - ey, rz = o.p[2] - ez;
            float vx = rn[0] * rx + rn[4] * ry + rn[8] * rz, vy = rn[1] * rx + rn[5] * ry + rn[9] * rz, vz = rn[2] * rx + rn[6] * ry + rn[10] * rz;
            int rgb = o.col;
            float f = o.flash * 0.6f;
            int r = (int) (((rgb >> 16) & 0xFF) * (1 - f) + 255 * f), g = (int) (((rgb >> 8) & 0xFF) * (1 - f) + 255 * f), b = (int) ((rgb & 0xFF) * (1 - f) + 255 * f);
            colour((r << 16) | (g << 8) | b);
            if (o.type == 0) { sphere(vx, vy, vz, o.r); continue; }
            // turn about the world's up, then tip a little, then into the view
            float ca = (float) Math.cos(o.ang), sa = (float) Math.sin(o.ang), ct = (float) Math.cos(o.tilt * 0.3f + o.ang * 0.4f), st2 = (float) Math.sin(o.tilt * 0.3f + o.ang * 0.4f);
            float[] M = {ca, -sa * ct, sa * st2, sa, ca * ct, -ca * st2, 0f, st2, ct};      // Rz * Rx (world)
            for (int a = 0; a < 3; a++) for (int c = 0; c < 3; c++) R[a * 3 + c] = rn[0 + a] * M[c] + rn[4 + a] * M[3 + c] + rn[8 + a] * M[6 + c];   // rn3 * M
            if (o.type == 1) box(vx, vy, vz, o.r * 0.82f, R);
            else {
                float ax = R[2] * o.r, ay = R[5] * o.r, az = R[8] * o.r;                         // the cylinder's axis (world z turned)
                tube(vx - ax, vy - ay, vz - az, vx + ax, vy + ay, vz + az, o.r * 0.7f);
                sphere(vx - ax, vy - ay, vz - az, o.r * 0.7f); sphere(vx + ax, vy + ay, vz + az, o.r * 0.7f);
            }
        }
        on = cur;
    }

    // ======================================================================== the sign
    private long ilyT0 = 0, ilyLast = 0, coolUntil = 0;
    private boolean fired = false;

    private static float dist2(float[] d, int b, int a, int c, float ar) { return (float) Math.hypot((d[b + a * 3] - d[b + c * 3]) * ar, d[b + a * 3 + 1] - d[b + c * 3 + 1]); }

    /** thumb, index and little finger out, middle and ring finger folded (works whichever way the hand is turned in the picture) */
    static boolean ilyPose(float[] d, int b, float ar) {
        if (d == null || d.length < b + 63) return false;
        float size = dist2(d, b, 0, 9, ar);
        if (!(size > 1e-3f)) return false;
        boolean idx = dist2(d, b, 0, 8, ar) > dist2(d, b, 0, 6, ar) * 1.12f;
        boolean pin = dist2(d, b, 0, 20, ar) > dist2(d, b, 0, 18, ar) * 1.12f;
        boolean mid = dist2(d, b, 0, 12, ar) < dist2(d, b, 0, 10, ar);
        boolean rng = dist2(d, b, 0, 16, ar) < dist2(d, b, 0, 14, ar);
        boolean thumb = dist2(d, b, 4, 5, ar) > 0.62f * size && dist2(d, b, 4, 9, ar) > 0.55f * size;
        return idx && pin && mid && rng && thumb;
    }

    /** hold the sign for a second to switch the mode; returns how far the hold is (0..1, 0 when not holding or after it switched) */
    float gestureStep(float[] d, int mask, float ar, long nowMs) {
        int n = d != null && d.length >= 1 ? Math.min(2, (int) d[0]) : 0;
        int hand = -1;
        for (int i = 0; i < n && gesture; i++) if ((mask & (1 << i)) != 0 && ilyPose(d, 1 + i * 63, ar)) { hand = i; break; }
        ilyHand = hand;
        if (hand >= 0) {
            ilyLast = nowMs;
            if (ilyT0 == 0) ilyT0 = nowMs;
            float prog = (nowMs - ilyT0) / 1000f;
            if (prog >= 1f && !fired && nowMs >= coolUntil) {
                fired = true; coolUntil = nowMs + 1200;
                toggle();
            }
            return fired ? 0f : Math.min(1f, prog);
        }
        if (nowMs - ilyLast > 250) { ilyT0 = 0; fired = false; }      // a look that misses it for a moment does not break the hold
        return 0f;
    }

    void toggle() {
        if (mode == 0) mode = (lastMode >= 1 && lastMode <= 3) ? lastMode : 2;
        else { lastMode = mode; mode = 0; }
    }

    // ======================================================================== drawing
    /** one frame: puts the hands and the things where they are and draws them into the scene picture. proj = the scene's projection, rn = world -> view */
    void frame(float[] proj, float[] rn, float[] d, float handAlpha, boolean rot180, float tanH, float tanV, float ex, float ey, float ez, long nowMs) {
        if (!ready()) { init(); if (!ready()) return; }
        float dt = lastT == 0 ? 0.016f : Math.max(0.001f, Math.min(0.05f, (nowMs - lastT) / 1000f));
        lastT = nowMs;
        int md = mode;
        int n = d != null && d.length >= 1 && d[0] >= 1f ? Math.min(2, (int) d[0]) : 0;
        for (int i = 0; i < 2; i++) {
            boolean ok = i < n && build(i, d, rot180, tanH, tanV);
            live[i] = ok; wasLive[i] = ok;
            float target = ok ? Math.max(0f, Math.min(1f, handAlpha)) : 0f;
            fade[i] = target > fade[i] ? Math.min(target, fade[i] + dt * 10f) : Math.max(target, fade[i] - dt * 6f);
        }
        if (md >= 2) {
            if (prevMode < 2 || respawn || objs.size() != Math.max(0, Math.min(12, nObj))) { respawn = false; spawn(rn, ex, ey, ez); }
            stepObjects(dt, rn, ex, ey, ez, nowMs / 1000f);
            buildObjects(rn, ex, ey, ez);
        } else { if (!objs.isEmpty()) { objs.clear(); held[0] = held[1] = null; } on = 0; }
        prevMode = md;
        for (int i = 0; i < 2; i++) { if (fade[i] > 0.02f && J[i] != null) buildHand(i); else hn[i] = 0; }
        // ---- draw ----
        GLES20.glUseProgram(prog);
        GLES20.glUniformMatrix4fv(uProj, 1, false, proj, 0);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);
        GLES20.glDepthMask(true);
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glUniform1f(uAl, 1f); GLES20.glUniform1f(uGls, 0f); GLES20.glUniform1f(uRim, 0.25f);
        if (on > 0) upload(ob, ov, on);
        if (on > 0) drawList(ob, on);
        int st = style;
        float op = Math.max(0.3f, Math.min(1f, opacity));
        boolean glass = st == 0 || op < 0.995f;
        if (glass) {
            // 1) only the depth of the nearest surface, 2) colour where it is the nearest one: every spot is blended once, however the pieces overlap
            GLES20.glColorMask(false, false, false, false);
            for (int i = 0; i < 2; i++) if (hn[i] > 0) { upload(hb[i], hv[i], hn[i]); drawList(hb[i], hn[i]); }
            GLES20.glColorMask(true, true, true, true);
            GLES20.glDepthMask(false);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glUniform1f(uGls, st == 0 ? 1f : 0f); GLES20.glUniform1f(uRim, st == 0 ? 1.0f : 0.3f);
            for (int i = 0; i < 2; i++) if (hn[i] > 0) { GLES20.glUniform1f(uAl, op * fade[i]); drawList(hb[i], hn[i]); }
            GLES20.glDepthMask(true);
        } else {
            GLES20.glUniform1f(uRim, 0.3f);
            for (int i = 0; i < 2; i++) if (hn[i] > 0) { upload(hb[i], hv[i], hn[i]); drawList(hb[i], hn[i]); }
        }
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        if (nowMs - dbgT > 500) {
            dbgT = nowMs;
            dbg = String.format(java.util.Locale.US, "3D: โหมด %d | มือ %d (ไกล %.2f/%.2f ม.) | วัตถุ %d | จุดยอด %d+%d+%d%s", md, n, dS[0], dS[1], objs.size(), hn[0], hn[1], on, ilyHand >= 0 ? " | ท่า🤟 เห็น" : "");
        }
    }
    private long dbgT = 0;

    private void upload(FloatBuffer fb, float[] a, int verts) {
        fb.position(0);
        fb.put(a, 0, verts * 9);
        fb.position(0);
    }
    private void drawList(FloatBuffer fb, int verts) {
        fb.position(0);
        GLES20.glEnableVertexAttribArray(aP); GLES20.glVertexAttribPointer(aP, 3, GLES20.GL_FLOAT, false, 36, fb);
        fb.position(3);
        GLES20.glEnableVertexAttribArray(aN); GLES20.glVertexAttribPointer(aN, 3, GLES20.GL_FLOAT, false, 36, fb);
        fb.position(6);
        GLES20.glEnableVertexAttribArray(aC); GLES20.glVertexAttribPointer(aC, 3, GLES20.GL_FLOAT, false, 36, fb);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, verts);
        GLES20.glDisableVertexAttribArray(aP); GLES20.glDisableVertexAttribArray(aN); GLES20.glDisableVertexAttribArray(aC);
        fb.position(0);
    }

    // test hooks (no GL needed)
    int handVerts(int i) { return hn[i]; }
    float[] handList(int i) { return hv[i]; }
    int objCount() { return objs.size(); }
    float[] objP(int i) { return objs.get(i).p.clone(); }
    float[] objV(int i) { return objs.get(i).v.clone(); }
    float[] objHome(int i) { return objs.get(i).home.clone(); }
    int objHeld(int i) { return objs.get(i).held; }
    float[] pv(int i) { return pinchVel[i].clone(); }
    boolean pinching(int i) { return pinchOn[i]; }
    void forceInitForTest() { hv[0] = new float[HAND_VERTS * 9]; hv[1] = new float[HAND_VERTS * 9]; ov = new float[OBJ_VERTS * 9]; }
    void testBuildHand(int i) { buildHand(i); }
    float[][] joints() { return J; }
    float[] dists() { return dS; }
}
