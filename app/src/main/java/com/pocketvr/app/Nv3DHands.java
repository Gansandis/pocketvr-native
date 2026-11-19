package com.pocketvr.app;

import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;

/**
 * 3D mode: the hands drawn as 3D models (glass / solid / bones), a real 3D scene (sky, floor, towers) instead of a flat picture, a few 3D things in the room
 * that can be pushed and picked up with the fingers, and the left-hand "I love you" sign (thumb, index and little finger out, middle and ring finger folded)
 * that switches the mode on and off.
 *
 * Hands and things are built on the CPU into small triangle lists already in VIEW space (x right, y up, the camera looks down -z); the scene is built once
 * into a GPU buffer and moved with the view matrix every frame. The hands are put where the camera picture shows them: every joint is a ray through its place
 * in the picture, at a distance worked out from the size of the palm (a palm is ~9.5 cm long), with the depth the hand model reports for each joint.
 * The only Android class used is GLES20, so the geometry and the gesture can be tested on any computer.
 */
final class Nv3DHands {
    // ---- settings (the settings screen changes them, the render thread reads them) ----
    volatile int mode = 0;               // 0 off, 1 hands only, 2 the room + 3D things, 3 the whole scene (sky, floor and towers instead of the camera picture)
    volatile int lastMode = 1;           // what the gesture switches on
    volatile int style = 0;              // hand look: 0 glass, 1 solid, 2 bones
    volatile int color = 0;              // index into COLORS
    volatile float opacity = 0.9f;       // 0.3 .. 1
    volatile float thick = 1f;           // 0.7 .. 1.4 : finger thickness
    volatile boolean gesture = true;     // the sign switches the mode
    volatile boolean gestureBoth = false;// the sign counts with either hand (otherwise: the left hand only)
    volatile boolean hideCam = true;     // while on: no outline / skeleton of the hand from the camera picture
    static final boolean OBJECTS = false;   // the floating 3D things are switched off for good (they were in the way); the code stays for the game that comes later
    volatile int nObj = 0;               // how many 3D things in the room (only when OBJECTS is on)
    private int objN() { return OBJECTS ? Math.max(0, Math.min(12, nObj)) : 0; }
    volatile boolean respawn = false;    // put the things in front of you again
    volatile String dbg = "";
    volatile int ilyHand = -1;           // which hand makes the sign right now

    static final String[] MODE_N = {"ปิด", "มือ 3D อย่างเดียว", "(ไม่ใช้แล้ว)", "ฉาก 3D เต็มจอ (ไม่เห็นกล้อง)"};
    static final String[] STYLE_N = {"แก้วเรืองแสง", "ทึบ (สวยสุด)", "โครงกระดูก"};
    static final String[] COLOR_N = {"ฟ้าเรือง", "สีผิว", "ขาวมุก", "ชมพูนีออน", "เขียวนีออน", "ทอง"};
    static final int[] COLORS = {0x58D6FF, 0xE9B692, 0xEFEFF5, 0xFF6BB0, 0x62FF9F, 0xF2C14E};
    static final int[] OBJ_COL = {0xFF5C5C, 0xFFB347, 0xFFE45C, 0x6BE675, 0x4FC3FF, 0x8F7CFF, 0xFF7CD6, 0x5CE1E6};

    // ---- gl ----
    private int prog = 0, aP = -1, aN = -1, aC = -1;
    private int uProj = -1, uMv = -1, uAl = -1, uGls = -1, uRim = -1, uSpc = -1, uUnl = -1, uFogD = -1, uFogC = -1, uLd = -1;
    private boolean failed = false;
    private FloatBuffer[] hb = new FloatBuffer[2];
    private FloatBuffer ob;
    private final float[][] hv = new float[2][];
    private float[] ov;
    private final int[] hn = new int[2];
    private int on = 0;
    private int cur = 0;                  // write position while a list is being filled
    private float[] tgt;
    private static final int HAND_VERTS = 9000, OBJ_VERTS = 4600, SCENE_VERTS = 16000;
    private static final float[] ID = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    private int sceneVbo = 0, sceneN = 0, sDome = 0, sTowers = 0, sFloor = 0, sDecal = 0;     // the scene lives in a GPU buffer: [dome][towers][floor][glowing bits]

    boolean ready() { return prog != 0; }
    void lost() { prog = 0; hb[0] = hb[1] = null; ob = null; failed = false; sceneVbo = 0; }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        return ok[0] == 0 ? 0 : s;
    }

    static final String VS = "attribute vec3 p; attribute vec3 n; attribute vec3 c; uniform mat4 proj; uniform mat4 mv; varying vec3 vn; varying vec3 vp; varying vec3 vc;" +
            "void main(){ vec4 q=mv*vec4(p,1.0); gl_Position=proj*q; vn=(mv*vec4(n,0.0)).xyz; vp=q.xyz; vc=c; }";
    static final String FS = "precision mediump float; uniform float al; uniform float gls; uniform float rim; uniform float spc; uniform float unl; uniform float fogd;" +
            " uniform vec3 fogc; uniform vec3 ld; varying vec3 vn; varying vec3 vp; varying vec3 vc;" +
            "void main(){ vec3 N=normalize(vn); vec3 V=normalize(-vp); float nd=abs(dot(N,V)); float fr=pow(1.0-nd,2.2);" +
            " vec3 L=normalize(ld); float d=max(dot(N,L),0.0);" +
            " vec3 col=vc*(0.34+0.76*d)+vec3(fr*rim);" +
            " vec3 H=normalize(L+V); col+=vec3(pow(max(dot(N,H),0.0),28.0)*spc);" +
            " col=mix(col,vc,unl);" +
            " float f=clamp(length(vp)*fogd,0.0,1.0); col=mix(col,fogc,f*f);" +
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
            uProj = GLES20.glGetUniformLocation(pr, "proj"); uMv = GLES20.glGetUniformLocation(pr, "mv"); uAl = GLES20.glGetUniformLocation(pr, "al");
            uGls = GLES20.glGetUniformLocation(pr, "gls"); uRim = GLES20.glGetUniformLocation(pr, "rim"); uSpc = GLES20.glGetUniformLocation(pr, "spc");
            uUnl = GLES20.glGetUniformLocation(pr, "unl"); uFogD = GLES20.glGetUniformLocation(pr, "fogd"); uFogC = GLES20.glGetUniformLocation(pr, "fogc");
            uLd = GLES20.glGetUniformLocation(pr, "ld");
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
    private static final float[] CYL = new float[SEG * 6 * 4];            // unit cylinder: ux uy uz (z 0..1); the normal is (ux, uy)
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
    private static int mixc(int a, int b, float f) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - f) + ((b >> 16) & 0xFF) * f), g = (int) (((a >> 8) & 0xFF) * (1 - f) + ((b >> 8) & 0xFF) * f), bl = (int) ((a & 0xFF) * (1 - f) + (b & 0xFF) * f);
        return (r << 16) | (g << 8) | bl;
    }
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
    /** a tube from a to b, radius r0 at a and r1 at b */
    private void tube(float ax, float ay, float az, float bx, float by, float bz, float r0, float r1) {
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
            float r = r0 + (r1 - r0) * uz;
            vtx(ax + nx * r + dx * uz, ay + ny * r + dy * uz, az + nz * r + dz * uz, nx, ny, nz);
        }
    }
    private void tube(float ax, float ay, float az, float bx, float by, float bz, float r) { tube(ax, ay, az, bx, by, bz, r, r); }
    /** a box with half-sizes hx, hy, hz turned by the 3x3 matrix R (row-major) */
    private void box(float x, float y, float z, float hx, float hy, float hz, float[] R) {
        for (int i = 0; i < BOX.length; i += 6) {
            float px = BOX[i] * hx, py = BOX[i + 1] * hy, pz = BOX[i + 2] * hz, nx = BOX[i + 3], ny = BOX[i + 4], nz = BOX[i + 5];
            vtx(x + R[0] * px + R[1] * py + R[2] * pz, y + R[3] * px + R[4] * py + R[5] * pz, z + R[6] * px + R[7] * py + R[8] * pz,
                    R[0] * nx + R[1] * ny + R[2] * nz, R[3] * nx + R[4] * ny + R[5] * nz, R[6] * nx + R[7] * ny + R[8] * nz);
        }
    }
    private void box(float x, float y, float z, float h, float[] R) { box(x, y, z, h, h, h, R); }
    /** a flat quad (two triangles) through four corners, one normal */
    private void quad(float[] a, float[] b, float[] c, float[] d, float nx, float ny, float nz) {
        float[][] q = {a, b, c, a, c, d};
        for (float[] p : q) vtx(p[0], p[1], p[2], nx, ny, nz);
    }

    // ======================================================================== hand geometry
    private static final int[] BONES = {0, 1, 1, 2, 2, 3, 3, 4, 0, 5, 5, 6, 6, 7, 7, 8, 5, 9, 9, 10, 10, 11, 11, 12, 9, 13, 13, 14, 14, 15, 15, 16, 13, 17, 17, 18, 18, 19, 19, 20, 0, 17, 0, 9, 0, 13};
    // joint radii in metres (a hand of normal size)
    private static final float[] JR = {0.0125f,
            0.0118f, 0.0108f, 0.0096f, 0.0082f,
            0.0108f, 0.0096f, 0.0086f, 0.0074f,
            0.0111f, 0.0099f, 0.0088f, 0.0076f,
            0.0103f, 0.0091f, 0.0081f, 0.0069f,
            0.0095f, 0.0083f, 0.0073f, 0.0063f};
    private static boolean palmJ(int k) { return k == 0 || k == 5 || k == 9 || k == 13 || k == 17; }
    private static final float PALM_L = 0.095f, PALM_W = 0.085f;      // wrist -> middle knuckle, index knuckle -> little finger knuckle (metres)

    private final float[][] J = new float[2][63];       // the joints in view space (metres)
    private final float[] dS = {0.5f, 0.5f};            // smoothed distance of each hand
    private final float[] fade = new float[2];
    private final float[] pinchR = new float[2];        // how close thumb and index tips are in the PICTURE (1 = a palm length apart): the depth is only a guess, the picture is not
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
        pinchR[i] = (float) Math.hypot(nxT[4] - nxT[8], nyT[4] - nyT[8]) / Math.max(1e-4f, Math.max(L, 0.6f * W));
        return true;
    }

    private void buildHand(int i) {
        float[] j = J[i];
        tgt = hv[i]; cur = 0;
        int base = COLORS[Math.max(0, Math.min(COLORS.length - 1, color))];
        int st = style;
        float th = thick;
        float jf = st == 2 ? 0.62f : 1f, bf = st == 2 ? 0.40f : 1f;
        boolean solid = st == 1;
        for (int k = 0; k < 21; k++) {
            boolean tip = k == 4 || k == 8 || k == 12 || k == 16 || k == 20;
            colour(solid && tip ? mixc(base, 0xE0766E, 0.28f) : base);           // warm fingertips on the solid hand
            sphere(j[k * 3], j[k * 3 + 1], j[k * 3 + 2], JR[k] * th * jf);
        }
        colour(base);
        for (int q = 0; q < BONES.length; q += 2) {
            int a = BONES[q], b = BONES[q + 1];
            if (st == 2 && q >= 42) continue;                  // no extra palm bones on the bone model
            float pm = st != 2 && palmJ(a) && palmJ(b) ? 1.18f : 1f;
            tube(j[a * 3], j[a * 3 + 1], j[a * 3 + 2], j[b * 3], j[b * 3 + 1], j[b * 3 + 2], JR[a] * th * bf * pm, JR[b] * th * bf * pm);
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
                float sk = Math.max(0.6f, Math.min(1.6f, al / 0.078f));          // a bigger hand (or one closer than guessed) gets a wider base too
                for (int q = 0; q < 4; q++) {
                    float o = off[q] * sk;
                    bx[q] = j[0] + ax * o; by[q] = j[1] + ay * o; bz[q] = j[2] + az * o;
                    tube(bx[q], by[q], bz[q], j[kn[q] * 3], j[kn[q] * 3 + 1], j[kn[q] * 3 + 2], 0.0115f * th, 0.0108f * th);
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
            // a short forearm so the hand does not end in the air: from the middle knuckle through the wrist, ~7 cm, a little wider at the far end
            float fx = j[0] - j[27], fy = j[1] - j[28], fz = j[2] - j[29];
            float fl = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
            if (fl > 1e-5f) {
                float k = 0.065f / fl;
                float ex = j[0] + fx * k, ey = j[1] + fy * k, ez = j[2] + fz * k;
                float wr = (0.0300f + 0.0030f * th);
                tube(j[0], j[1], j[2], ex, ey, ez, wr * 0.96f, wr * 1.10f);
                sphere(ex, ey, ez, wr * 1.10f);
            }
        }
        hn[i] = cur;
    }

    // ======================================================================== 3D things in the room
    private static final class Ob {
        float[] home = new float[3], p = new float[3], v = new float[3], vw = new float[3];     // vw: where it is, in view space (this frame)
        float r, flash, ang, spinV, tilt, phase, ghost, hover;
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
    private static final float ZW = 0.20f;                   // who touches what: sideways (on the screen) counts fully, depth only a fifth (the depth of a hand is a guess from its size)
    private static final float REACH = 0.065f;               // a thing is within reach of a pinch when closer than its radius + this
    private final float[] rel = new float[3];
    /** how far a point (view space) is from a thing, the way the eye sees it: sideways = along the picture at the thing's distance, depth counts little; false if too far apart in depth */
    private boolean relTo(float[] ov, float px, float py, float pz, float gate) {
        float dO = -ov[2], dP = -pz;
        if (dO < 0.05f || dP < 0.05f) return false;
        float dd = dO - dP;
        if (Math.abs(dd) > gate) return false;
        rel[0] = ov[0] - px / dP * dO; rel[1] = ov[1] - py / dP * dO; rel[2] = -dd * ZW;
        return true;
    }

    private void spawn(float[] rn, float ex, float ey, float ez) {
        objs.clear(); held[0] = held[1] = null;
        float fx = -rn[2], fy = -rn[6];
        if (Math.hypot(fx, fy) < 0.3) { fx = -rn[1]; fy = -rn[5]; }
        float yaw = (float) Math.atan2(fy, fx);
        int n = objN();
        int cols = Math.max(1, (n + 1) / 2);                              // two rows, side by side inside what the screen shows (about +-35 degrees)
        float step = cols > 1 ? Math.min(0.34f, 1.2f / (cols - 1)) : 0f;
        for (int i = 0; i < n; i++) {
            Ob o = new Ob();
            int row = i % 2, col = i / 2;
            float a = yaw + (col - (cols - 1) * 0.5f) * step + (row == 1 && cols > 1 ? step * 0.5f : 0f) * (col == cols - 1 ? -1f : 1f);
            float dist = row == 0 ? 0.42f : 0.54f;                       // within an arm's reach
            o.home[0] = ex + (float) Math.cos(a) * dist; o.home[1] = ey + (float) Math.sin(a) * dist;
            o.home[2] = ez + (row == 0 ? -0.07f : 0.10f) + 0.03f * ((i * 5) % 3 - 1);
            System.arraycopy(o.home, 0, o.p, 0, 3);
            o.r = 0.034f + 0.022f * ((i * 3) % 4) / 3f;
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
    private static final int[] PAL = {0, 5, 9, 13, 17};

    private void stepObjects(float dt, float[] rn, float ex, float ey, float ez, float tsec) {
        for (Ob o : objs) {                                    // where every thing is, in view space
            float rx = o.p[0] - ex, ry = o.p[1] - ey, rz = o.p[2] - ez;
            o.vw[0] = rn[0] * rx + rn[4] * ry + rn[8] * rz; o.vw[1] = rn[1] * rx + rn[5] * ry + rn[9] * rz; o.vw[2] = rn[2] * rx + rn[6] * ry + rn[10] * rz;
            o.hover = 0f;
        }
        for (int i = 0; i < 2; i++) {
            boolean act = live[i] && fade[i] > 0.4f;
            if (!act) {
                hasPrev[i] = false;
                if (held[i] != null) { held[i].held = -1; held[i] = null; }
                pinchOn[i] = false;
                continue;
            }
            toWorld(i, rn, ex, ey, ez);
            float[] w = jw[i], j = J[i];
            float[] c = new float[18], cv = new float[18];     // fingertips + the middle of the palm, in the world (for the speed) and in view space (for who touches what)
            for (int q = 0; q < 5; q++) {
                for (int a = 0; a < 3; a++) { c[q * 3 + a] = w[TIPS[q] * 3 + a]; cv[q * 3 + a] = j[TIPS[q] * 3 + a]; }
                for (int a = 0; a < 3; a++) { c[15 + a] += w[PAL[q] * 3 + a] * 0.2f; cv[15 + a] += j[PAL[q] * 3 + a] * 0.2f; }
            }
            if (hasPrev[i]) {
                for (int q = 0; q < 18; q++) {
                    float vel = (c[q] - cPrev[i][q]) / dt;
                    cVel[i][q] += (Math.max(-4f, Math.min(4f, vel)) - cVel[i][q]) * 0.5f;
                }
            } else java.util.Arrays.fill(cVel[i], 0f);
            System.arraycopy(c, 0, cPrev[i], 0, 18);
            hasPrev[i] = true;
            // pinch, read from the PICTURE (thumb tip and index tip together): picks a thing up when it is within reach, opening the fingers lets it go (thrown with the speed of the hand)
            boolean pin = held[i] != null ? pinchR[i] < 0.55f : pinchR[i] < 0.30f;
            float pwx = (w[12] + w[24]) * 0.5f, pwy = (w[13] + w[25]) * 0.5f, pwz = (w[14] + w[26]) * 0.5f;          // pinch point, world
            float pvx = (j[12] + j[24]) * 0.5f, pvy = (j[13] + j[25]) * 0.5f, pvz = (j[14] + j[26]) * 0.5f;          // pinch point, view
            if (pinchOn[i] && pin) {
                pinchVel[i][0] += ((pwx - pinchPrev[i][0]) / dt - pinchVel[i][0]) * 0.35f;
                pinchVel[i][1] += ((pwy - pinchPrev[i][1]) / dt - pinchVel[i][1]) * 0.35f;
                pinchVel[i][2] += ((pwz - pinchPrev[i][2]) / dt - pinchVel[i][2]) * 0.35f;
            } else if (!pinchOn[i]) java.util.Arrays.fill(pinchVel[i], 0f);          // (on the frame the fingers open the speed of the last pinching frame is kept: the opening itself is not a throw)
            pinchPrev[i][0] = pwx; pinchPrev[i][1] = pwy; pinchPrev[i][2] = pwz;
            pinchOn[i] = pin;
            // things the fingers could pick up glow a little
            Ob near = null; float nd = 1e9f;
            boolean closing = pinchR[i] < 0.75f || held[i] != null;     // thumb and index are coming together: the hand is going to pick something up, not to poke
            for (Ob o : objs) {
                if (o.held >= 0 || !closing) continue;
                if (!relTo(o.vw, pvx, pvy, pvz, 0.55f)) continue;
                float dd = (float) Math.sqrt(sq(rel[0]) + sq(rel[1]) + sq(rel[2]));
                if (dd < o.r + REACH) { o.hover = 0.35f; if (dd < nd) { nd = dd; near = o; } }
            }
            if (pin && held[i] == null && near != null) { held[i] = near; near.held = i; near.flash = 1f; }
            else if (!pin && held[i] != null) {
                Ob o = held[i];
                float sp = (float) Math.sqrt(sq(pinchVel[i][0]) + sq(pinchVel[i][1]) + sq(pinchVel[i][2]));
                float k = sp > 3.5f ? 3.5f / sp : 1f;
                o.v[0] = pinchVel[i][0] * k; o.v[1] = pinchVel[i][1] * k; o.v[2] = pinchVel[i][2] * k;
                o.held = -1; held[i] = null; o.ghost = 0.35f;        // it leaves the fingers without bumping into them
            }
            if (held[i] != null) {
                Ob o = held[i];
                float k = Math.min(1f, dt * 12f);                      // it flies to the fingers, then follows them
                o.p[0] += (pwx - o.p[0]) * k; o.p[1] += (pwy - o.p[1]) * k; o.p[2] += (pwz - o.p[2]) * k;
                o.v[0] = o.v[1] = o.v[2] = 0f;
                continue;
            }
            // touching with the fingertips (small) or the palm (big) pushes things away
            for (Ob o : objs) {
                if (o.held >= 0 || o.ghost > 0f) continue;
                if (closing && o.hover > 0f) continue;                    // a thing the fingers are closing in on is not knocked away by the palm on the way
                for (int q = 0; q < 6; q++) {
                    float rc = q < 5 ? 0.012f : 0.040f;
                    if (!relTo(o.vw, cv[q * 3], cv[q * 3 + 1], cv[q * 3 + 2], 0.45f)) continue;
                    float dx = rel[0], dy = rel[1], dz = rel[2];
                    float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz), md = o.r + rc;
                    if (dist < md && dist > 1e-5f) {
                        float nvx = dx / dist, nvy = dy / dist, nvz = dz / dist;            // the push, in view space; to the world with the transpose
                        float nx = rn[0] * nvx + rn[1] * nvy + rn[2] * nvz, ny = rn[4] * nvx + rn[5] * nvy + rn[6] * nvz, nz = rn[8] * nvx + rn[9] * nvy + rn[10] * nvz;
                        float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                        if (nl < 1e-5f) continue;
                        nx /= nl; ny /= nl; nz /= nl;
                        float gap = md - dist;
                        o.p[0] += nx * gap; o.p[1] += ny * gap; o.p[2] += nz * gap;
                        float vn = cVel[i][q * 3] * nx + cVel[i][q * 3 + 1] * ny + cVel[i][q * 3 + 2] * nz;
                        float push = Math.max(0f, vn) * 1.15f + 0.10f;
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
            float f = Math.max(o.flash, o.hover) * 0.6f;
            colour(mixc(o.col, 0xFFFFFF, f));
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

    // ======================================================================== the scene: a real 3D place (sky dome with a sun and stars, a grid floor, towers with lit windows)
    static final float FLOOR_Z = -1.5f;                       // the floor is 1.5 m below the eyes' starting height
    private static final int SKY_FOG = 0x7A4A86;               // the haze colour: the far floor, the towers and the horizon all fade into it
    private static final float FAR = 13f;                     // fully hazy here
    private long sceneSeed = 777;
    private float srnd() { sceneSeed = sceneSeed * 6364136223846793005L + 1442695040888963407L; return ((sceneSeed >>> 33) & 0xFFFFFF) / (float) 0x1000000; }

    private int skyCol(float e) {                              // e = how high (-1..1)
        if (e <= 0f) return SKY_FOG;
        if (e < 0.30f) return mixc(SKY_FOG, 0x2B2A6E, smooth(e / 0.30f));
        return mixc(0x2B2A6E, 0x090D2B, smooth(Math.min(1f, (e - 0.30f) / 0.55f)));
    }
    private static float smooth(float t) { return t * t * (3f - 2f * t); }

    /** builds the scene into one list: dome, towers, floor, glowing bits (in this order); returns the vertex array */
    float[] buildScene() {
        float[] sv = new float[SCENE_VERTS * 9];
        tgt = sv; cur = 0;
        sceneSeed = 777;
        // ---- dome (radius 14 around the eyes; it is drawn without depth and without haze) ----
        int rings = 14, segs = 24;
        float R = 14f;
        for (int i = 0; i < rings; i++) for (int j = 0; j < segs; j++) {
            double t0 = Math.PI * i / rings, t1 = Math.PI * (i + 1) / rings, p0 = 2 * Math.PI * j / segs, p1 = 2 * Math.PI * (j + 1) / segs;
            float[][] q = {sp(t0, p0), sp(t1, p0), sp(t1, p1), sp(t0, p0), sp(t1, p1), sp(t0, p1)};
            for (float[] a : q) { colour(skyCol(a[2])); vtx(a[0] * R, a[1] * R, a[2] * R, -a[0], -a[1], -a[2]); }
        }
        // stars
        for (int s = 0; s < 110; s++) {
            double az = srnd() * 6.2832, el = 0.22 + srnd() * 1.2;
            float ce = (float) Math.cos(el), se = (float) Math.sin(el);
            float[] c0 = {(float) Math.cos(az) * ce, (float) Math.sin(az) * ce, se};
            float[] tx = {-(float) Math.sin(az), (float) Math.cos(az), 0f};
            float[] ty = {-(float) Math.cos(az) * se, -(float) Math.sin(az) * se, ce};
            float sz = 0.03f + srnd() * 0.05f, rr = R - 0.3f;
            float br = 0.55f + 0.45f * srnd();
            colour(mixc(0x6A78C8, 0xFFFFFF, br));
            for (int k = 0; k < 3; k++) {
                float ang = k * 2.0944f + 0.5f;
                float ux = c0[0] * rr + (tx[0] * (float) Math.cos(ang) + ty[0] * (float) Math.sin(ang)) * sz, uy = c0[1] * rr + (tx[1] * (float) Math.cos(ang) + ty[1] * (float) Math.sin(ang)) * sz,
                        uz = c0[2] * rr + (tx[2] * (float) Math.cos(ang) + ty[2] * (float) Math.sin(ang)) * sz;
                vtx(ux, uy, uz, -c0[0], -c0[1], -c0[2]);
            }
        }
        // sun: a bright disc low over the horizon (a fan of triangles)
        {
            double az = 0.55, el = 0.13;
            float ce = (float) Math.cos(el), se = (float) Math.sin(el);
            float[] c0 = {(float) Math.cos(az) * ce, (float) Math.sin(az) * ce, se};
            float[] tx = {-(float) Math.sin(az), (float) Math.cos(az), 0f};
            float[] ty = {-(float) Math.cos(az) * se, -(float) Math.sin(az) * se, ce};
            float rr = R - 0.4f, sr = 1.0f;
            for (int k = 0; k < 20; k++) {
                float a0 = (float) (k * 6.2832 / 20), a1 = (float) ((k + 1) * 6.2832 / 20);
                colour(0xFFE2A8);
                vtx(c0[0] * rr, c0[1] * rr, c0[2] * rr, -c0[0], -c0[1], -c0[2]);
                colour(0xFFB67A);
                for (float a : new float[]{a0, a1})
                    vtx(c0[0] * rr + (tx[0] * (float) Math.cos(a) + ty[0] * (float) Math.sin(a)) * sr, c0[1] * rr + (tx[1] * (float) Math.cos(a) + ty[1] * (float) Math.sin(a)) * sr,
                            c0[2] * rr + (tx[2] * (float) Math.cos(a) + ty[2] * (float) Math.sin(a)) * sr, -c0[0], -c0[1], -c0[2]);
            }
        }
        sDome = cur;
        // ---- towers: a ring of boxes turned to face the middle ----
        int nT = 30;
        float[] twX = new float[nT], twY = new float[nT], twH = new float[nT], twW = new float[nT], twD = new float[nT], twYaw = new float[nT];
        for (int t = 0; t < nT; t++) {
            float ang = (float) (t * 6.2832 / nT + (srnd() - 0.5) * 0.14);
            float rad = 7.0f + srnd() * 5.5f;
            float w = 0.7f + srnd() * 1.5f, d = 0.7f + srnd() * 1.3f, h = 1.2f + srnd() * srnd() * 8.5f + (t % 5 == 0 ? 3.5f : 0f);
            twX[t] = (float) Math.cos(ang) * rad; twY[t] = (float) Math.sin(ang) * rad; twW[t] = w; twD[t] = d; twH[t] = h; twYaw[t] = ang;
            float ca = (float) Math.cos(ang), sa = (float) Math.sin(ang);
            // local x = tangent (-sa, ca), local y = radial outwards (ca, sa), local z = up; the box turns by the matrix whose columns are those axes
            float[] Rm = {-sa, ca, 0f, ca, sa, 0f, 0f, 0f, 1f};
            colour(mixc(0x1A1740, 0x3A2A66, srnd()));
            box(twX[t], twY[t], FLOOR_Z + h, w, d, h, Rm);
        }
        sTowers = cur;
        // ---- floor: one big dark quad (no depth is written for it) ----
        {
            float f = 16f;
            colour(0x15122B);
            quad(new float[]{-f, -f, FLOOR_Z}, new float[]{f, -f, FLOOR_Z}, new float[]{f, f, FLOOR_Z}, new float[]{-f, f, FLOOR_Z}, 0, 0, 1);
        }
        sFloor = cur;
        // ---- glowing bits: grid lines on the floor, lit windows and roof lights on the towers ----
        colour(0x2FD8FF);
        for (int k = -16; k <= 16; k++) {
            float w = (k % 4 == 0) ? 0.035f : 0.018f, z = FLOOR_Z + 0.002f;
            quad(new float[]{k - w, -16, z}, new float[]{k + w, -16, z}, new float[]{k + w, 16, z}, new float[]{k - w, 16, z}, 0, 0, 1);
            quad(new float[]{-16, k - w, z}, new float[]{16, k - w, z}, new float[]{16, k + w, z}, new float[]{-16, k + w, z}, 0, 0, 1);
        }
        for (int t = 0; t < nT; t++) {
            float ca = (float) Math.cos(twYaw[t]), sa = (float) Math.sin(twYaw[t]);
            float h = twH[t], w = twW[t], d = twD[t];
            // the face towards the middle is at local y = -d; windows on a grid
            float wx = 0.30f, wz = 0.46f;
            int cols = Math.max(1, (int) ((2 * w - 0.2f) / (wx * 1.6f))), rowsN = Math.max(1, (int) ((2 * h - 0.4f) / (wz * 1.7f)));
            for (int rI = 0; rI < rowsN; rI++) for (int cI = 0; cI < cols; cI++) {
                if (srnd() < 0.5f) continue;
                float lx = (cI + 0.5f) / cols * 2f * w - w, lz = (rI + 0.5f) / rowsN * (2f * h - 0.4f) + 0.3f;
                int wc = srnd() < 0.7f ? 0xFFD98A : (srnd() < 0.5f ? 0x7FE6FF : 0xFF9AD0);
                colour(wc);
                float ly = -d - 0.01f;
                float z0 = FLOOR_Z + lz;
                float[] a = loc(twX[t], twY[t], ca, sa, lx - wx * 0.5f, ly, z0 - wz * 0.5f), b = loc(twX[t], twY[t], ca, sa, lx + wx * 0.5f, ly, z0 - wz * 0.5f),
                        c = loc(twX[t], twY[t], ca, sa, lx + wx * 0.5f, ly, z0 + wz * 0.5f), e = loc(twX[t], twY[t], ca, sa, lx - wx * 0.5f, ly, z0 + wz * 0.5f);
                quad(a, b, c, e, -ca, -sa, 0f);
            }
            if (h > 7f) {                                        // a red light on top of the tall ones
                colour(0xFF4A5A);
                float[] cen = {twX[t], twY[t], FLOOR_Z + 2f * h + 0.12f};
                float[] id = {1, 0, 0, 0, 1, 0, 0, 0, 1};
                box(cen[0], cen[1], cen[2], 0.10f, id);
            }
        }
        sDecal = cur;
        sceneN = cur;
        return sv;
    }
    private static float[] loc(float cx, float cy, float ca, float sa, float lx, float ly, float z) {
        return new float[]{cx + (-sa) * lx + ca * ly, cy + ca * lx + sa * ly, z};
    }

    private boolean sceneReady() {
        if (sceneVbo != 0) return true;
        try {
            float[] sv = buildScene();
            FloatBuffer fb = ByteBuffer.allocateDirect(sceneN * 36).order(ByteOrder.nativeOrder()).asFloatBuffer();
            fb.put(sv, 0, sceneN * 9);
            fb.position(0);
            int[] id = new int[1];
            GLES20.glGenBuffers(1, id, 0);
            if (id[0] == 0) return false;
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, id[0]);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, sceneN * 36, fb, GLES20.GL_STATIC_DRAW);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
            sceneVbo = id[0];
            return true;
        } catch (Throwable t) { failed = true; dbg = "3D scene: " + t; return false; }
    }

    private final float[] mvS = new float[16], ldv = new float[3];
    private static final float[] LIGHT_W = {-0.35f, 0.45f, 0.82f};            // the sun's light, in world axes

    /** the light direction in view space for a light fixed in the world */
    private void worldLight(float[] rn) {
        float l = (float) Math.sqrt(sq(LIGHT_W[0]) + sq(LIGHT_W[1]) + sq(LIGHT_W[2]));
        float x = LIGHT_W[0] / l, y = LIGHT_W[1] / l, z = LIGHT_W[2] / l;
        ldv[0] = rn[0] * x + rn[4] * y + rn[8] * z; ldv[1] = rn[1] * x + rn[5] * y + rn[9] * z; ldv[2] = rn[2] * x + rn[6] * y + rn[10] * z;
    }

    private void drawRange(int first, int count) {
        if (count <= 0) return;
        GLES20.glEnableVertexAttribArray(aP); GLES20.glVertexAttribPointer(aP, 3, GLES20.GL_FLOAT, false, 36, 0);
        GLES20.glEnableVertexAttribArray(aN); GLES20.glVertexAttribPointer(aN, 3, GLES20.GL_FLOAT, false, 36, 12);
        GLES20.glEnableVertexAttribArray(aC); GLES20.glVertexAttribPointer(aC, 3, GLES20.GL_FLOAT, false, 36, 24);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, first, count);
        GLES20.glDisableVertexAttribArray(aP); GLES20.glDisableVertexAttribArray(aN); GLES20.glDisableVertexAttribArray(aC);
    }

    /** draws the whole 3D place into the scene picture (instead of the camera picture); false if it cannot (the caller then uses the old city picture) */
    boolean drawScene(float[] proj, float[] rn, float ex, float ey, float ez) {
        if (!ready()) { init(); if (!ready()) return false; }
        if (!sceneReady()) return false;
        GLES20.glUseProgram(prog);
        GLES20.glUniformMatrix4fv(uProj, 1, false, proj, 0);
        GLES20.glClearColor(((SKY_FOG >> 16) & 0xFF) / 255f, ((SKY_FOG >> 8) & 0xFF) / 255f, (SKY_FOG & 0xFF) / 255f, 1f);
        GLES20.glDepthMask(true);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, sceneVbo);
        // 1) the sky: turns with the head, never moves with the body, no depth, lit by nothing, no haze
        System.arraycopy(rn, 0, mvS, 0, 16); mvS[12] = mvS[13] = mvS[14] = 0f;
        GLES20.glUniformMatrix4fv(uMv, 1, false, mvS, 0);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glUniform1f(uAl, 1f); GLES20.glUniform1f(uGls, 0f); GLES20.glUniform1f(uRim, 0f); GLES20.glUniform1f(uSpc, 0f); GLES20.glUniform1f(uUnl, 1f); GLES20.glUniform1f(uFogD, 0f);
        GLES20.glUniform3f(uFogC, 0f, 0f, 0f);
        worldLight(rn);
        GLES20.glUniform3f(uLd, ldv[0], ldv[1], ldv[2]);
        drawRange(0, sDome);
        // 2) the place: moved by the view matrix and by where the body is
        System.arraycopy(rn, 0, mvS, 0, 16);
        mvS[12] = -(rn[0] * ex + rn[4] * ey + rn[8] * ez); mvS[13] = -(rn[1] * ex + rn[5] * ey + rn[9] * ez); mvS[14] = -(rn[2] * ex + rn[6] * ey + rn[10] * ez);
        GLES20.glUniformMatrix4fv(uMv, 1, false, mvS, 0);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);
        GLES20.glUniform1f(uFogD, 1f / FAR);
        GLES20.glUniform3f(uFogC, ((SKY_FOG >> 16) & 0xFF) / 255f, ((SKY_FOG >> 8) & 0xFF) / 255f, (SKY_FOG & 0xFF) / 255f);
        GLES20.glUniform1f(uUnl, 0f); GLES20.glUniform1f(uSpc, 0.12f);
        drawRange(sDome, sTowers - sDome);                                   // the towers write depth
        GLES20.glDepthMask(false);                                           // the floor and everything stuck on a surface do not (no flicker between them)
        GLES20.glUniform1f(uSpc, 0f);
        drawRange(sTowers, sFloor - sTowers);
        GLES20.glEnable(GLES20.GL_POLYGON_OFFSET_FILL);
        GLES20.glPolygonOffset(-2f, -2f);
        GLES20.glUniform1f(uUnl, 1f);
        drawRange(sFloor, sDecal - sFloor);
        GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL);
        GLES20.glDepthMask(true);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        return true;
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
        if (mode == 0) mode = (lastMode == 1 || lastMode == 3) ? lastMode : 1;
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
            if (prevMode < 2 || respawn || objs.size() != objN()) { respawn = false; spawn(rn, ex, ey, ez); }
            stepObjects(dt, rn, ex, ey, ez, nowMs / 1000f);
            buildObjects(rn, ex, ey, ez);
        } else { if (!objs.isEmpty()) { objs.clear(); held[0] = held[1] = null; } on = 0; }
        prevMode = md;
        for (int i = 0; i < 2; i++) { if (fade[i] > 0.02f && J[i] != null) buildHand(i); else hn[i] = 0; }
        // ---- draw ----
        GLES20.glUseProgram(prog);
        GLES20.glUniformMatrix4fv(uProj, 1, false, proj, 0);
        GLES20.glUniformMatrix4fv(uMv, 1, false, ID, 0);
        GLES20.glUniform1f(uFogD, 0f); GLES20.glUniform3f(uFogC, 0f, 0f, 0f); GLES20.glUniform1f(uUnl, 0f);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);
        GLES20.glDepthMask(true);
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glUniform1f(uAl, 1f); GLES20.glUniform1f(uGls, 0f); GLES20.glUniform1f(uRim, 0.25f); GLES20.glUniform1f(uSpc, 0.45f);
        worldLight(rn);                                                          // the things are lit by the sun of the room, the hands by a light on the head
        if (on > 0) {
            GLES20.glUniform3f(uLd, ldv[0], ldv[1], ldv[2]);
            upload(ob, ov, on);
            drawList(ob, on);
        }
        GLES20.glUniform3f(uLd, 0.3f, 0.55f, 0.8f);
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
            GLES20.glUniform1f(uGls, st == 0 ? 1f : 0f); GLES20.glUniform1f(uRim, st == 0 ? 1.0f : 0.3f); GLES20.glUniform1f(uSpc, st == 0 ? 0.55f : 0.25f);
            for (int i = 0; i < 2; i++) if (hn[i] > 0) { GLES20.glUniform1f(uAl, op * fade[i]); drawList(hb[i], hn[i]); }
            GLES20.glDepthMask(true);
        } else {
            GLES20.glUniform1f(uRim, 0.3f); GLES20.glUniform1f(uSpc, st == 1 ? 0.22f : 0.35f);
            for (int i = 0; i < 2; i++) if (hn[i] > 0) { upload(hb[i], hv[i], hn[i]); drawList(hb[i], hn[i]); }
        }
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        if (nowMs - dbgT > 500) {
            dbgT = nowMs;
            dbg = String.format(java.util.Locale.US, "3D: โหมด %d | มือ %d (ไกล %.2f/%.2f ม.) | วัตถุ %d%s | จุดยอด %d+%d+%d%s", md, n, dS[0], dS[1], objs.size(),
                    held[0] != null || held[1] != null ? " (กำลังหยิบ)" : "", hn[0], hn[1], on, ilyHand >= 0 ? " | ท่า🤟 เห็น" : "");
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
    int[] sceneRanges() { return new int[]{sDome, sTowers, sFloor, sDecal, sceneN}; }
    float[] objList() { return ov; }
    int objVerts() { return on; }
}
