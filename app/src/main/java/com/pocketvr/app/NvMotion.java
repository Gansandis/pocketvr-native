package com.pocketvr.app;

import android.media.Image;

import java.nio.ByteBuffer;

import android.os.SystemClock;

/**
 * EXPERIMENTAL walk-around estimate without ARCore.
 * The turn of the head is known from the gyro, so every tracked spot in the camera picture is first "un-turned"; what is left over is
 * caused by the body moving: sideways/up shift of the whole picture = moved sideways/up, picture growing from its centre = walked forward.
 * The real depth of the room is not known, so a fixed distance is assumed (depthM). It drifts, which is why it is a separate switch.
 */
public class NvMotion {
    public interface RotSource { float[] get(); }       // 4x4 world->view turn, as the renderer uses it

    public volatile boolean enabled = false;
    public volatile float depthM = 3.0f;                // assumed distance of what the camera sees
    public volatile float px = 0, py = 0, pz = 0;       // where the user is, metres, world axes
    public volatile float quality = 0;                  // share of spots that agree, 0..1
    public volatile RotSource rot;
    public volatile boolean rot180 = false;
    public volatile float[] hands;                      // latest hand result [n, 21*(x,y,z) per hand], in the camera picture: spots on a hand are not the room

    private float[] hb = new float[16];                 // up to 2 hand boxes: x0,y0,x1,y1 (0..1 of the picture), already grown
    private int nhb = 0;
    private float lastA = 0, lastB = 0, lastC = 0;      // movement of the picture last time: where to look first when walking fast
    private void makeBoxes() {
        nhb = 0;
        float[] r = hands;
        if (r == null || r.length < 64 || r[0] < 1) return;
        int n = Math.min(2, (int) r[0]);
        for (int h = 0; h < n; h++) {
            float x0 = 9, y0 = 9, x1 = -9, y1 = -9;
            for (int i = 0; i < 21; i++) {
                int o = 1 + h * 63 + i * 3;
                if (o + 1 >= r.length) break;
                x0 = Math.min(x0, r[o]); x1 = Math.max(x1, r[o]); y0 = Math.min(y0, r[o + 1]); y1 = Math.max(y1, r[o + 1]);
            }
            if (x1 < x0) continue;
            float mx = 0.10f + 0.25f * (x1 - x0), my = 0.10f + 0.25f * (y1 - y0);      // hand moves between looks, and the result is a little old
            hb[nhb * 4] = x0 - mx; hb[nhb * 4 + 1] = y0 - my; hb[nhb * 4 + 2] = x1 + mx; hb[nhb * 4 + 3] = y1 + my;
            nhb++;
        }
    }
    private boolean inHand(float px, float py) {
        float u = (px + 0.5f) / GW, v = (py + 0.5f) / GH;
        for (int i = 0; i < nhb; i++) if (u >= hb[i * 4] && u <= hb[i * 4 + 2] && v >= hb[i * 4 + 1] && v <= hb[i * 4 + 3]) return true;
        return false;
    }

    private static final int GW = 128, GH = 96, NX = 8, NY = 6, PR = 3, SR = 8, K = 3;      // K: compare with the picture K looks ago (3x the movement, same noise)
    private final byte[][] frames = new byte[K + 1][GW * GH];
    private final float[][] rots = new float[K + 1][];
    private byte[] prev, cur;
    private int wr = 0, count = 0;
    private float[] rotPrev = null;
    public volatile int tracked = 0, usedN = 0, looks = 0;     // frames = how many pictures it has looked at
    public volatile String err = "";
    private long lastMs = 0;

    // calibration: walk a known 1 m sideways between calStart() and calEnd(); the assumed depth is then corrected to match
    // ---- turn history: the camera picture is a little old, so the turn must be the one from the moment it was taken ----
    private static final int RN = 64;
    private final long[] rts = new long[RN];
    private final float[][] rms = new float[RN][];
    private int rhead = 0;
    public volatile boolean tsReal = false;             // camera times are on the same clock as the sensors
    public volatile int lagMs = 70;                     // otherwise: assume the picture is this old when it arrives
    public volatile String how = "", size = "";

    public synchronized void pushRot(long tsNs, float[] m) {
        rhead = (rhead + 1) % RN;
        rts[rhead] = tsNs; rms[rhead] = m.clone();
    }
    private synchronized float[] rotAt(long tsNs) {
        float[] best = null; long bd = Long.MAX_VALUE;
        for (int i = 0; i < RN; i++) {
            if (rms[i] == null) continue;
            long d = Math.abs(rts[i] - tsNs);
            if (d < bd) { bd = d; best = rms[i]; }
        }
        return (best != null && bd < 150_000_000L) ? best : null;     // nothing close enough: do not guess
    }

    private float cx0, cy0;
    public volatile boolean calibrating = false;
    public void calStart() { cx0 = px; cy0 = py; calibrating = true; }
    public float calWalked() { return (float) Math.hypot(px - cx0, py - cy0); }
    public boolean calEnd(float knownM) {
        calibrating = false;
        float d = calWalked();
        if (d < 0.08f) return false;
        depthM = Math.max(1f, Math.min(25f, depthM * knownM / d));
        return true;
    }

    public void reset() { px = py = pz = 0; count = 0; quality = 0; tracked = 0; usedN = 0; }

    public void feed(Image img, float hFov) {
        long now = System.nanoTime() / 1000000L;
        if (now - lastMs < 55) return;                  // ~18 looks a second is enough, and cheap
        RotSource rs = rot;
        if (rs == null) return;
        float[] rn = null;
        long nowEl = SystemClock.elapsedRealtimeNanos();
        long imgTs = img.getTimestamp();
        if (tsReal && Math.abs(nowEl - imgTs) < 1_000_000_000L) { rn = rotAt(imgTs); how = "เวลาภาพ"; }
        if (rn == null) { rn = rotAt(nowEl - lagMs * 1_000_000L); how = "หน่วง" + lagMs; }
        if (rn == null) { rn = rs.get(); how = "ล่าสุด"; }
        if (rn == null) return;
        lastMs = now;
        looks++;
        Image.Plane pl = img.getPlanes()[0];
        ByteBuffer yb = pl.getBuffer();
        int rowS = pl.getRowStride(), w = img.getWidth(), h = img.getHeight();
        size = w + "x" + h;
        byte[] dst = frames[wr];
        int lim = yb.limit();
        for (int y = 0; y < GH; y++) {
            int iy = Math.min(h - 2, y * h / GH);                 // works for any picture size, also small ones
            int r0 = iy * rowS, r1 = r0 + rowS;
            for (int x = 0; x < GW; x++) {
                int o = Math.min(w - 2, x * w / GW);
                if (r1 + o + 1 >= lim) { dst[y * GW + x] = dst[Math.max(0, y * GW + x - 1)]; continue; }
                int v = (yb.get(r0 + o) & 0xFF) + (yb.get(r0 + o + 1) & 0xFF) + (yb.get(r1 + o) & 0xFF) + (yb.get(r1 + o + 1) & 0xFF);
                dst[y * GW + x] = (byte) (v >> 2);
            }
        }
        rots[wr] = rn;
        float tanH = (float) Math.tan(hFov / 2.0), tanV = tanH * h / w;
        if (count >= K) {
            int old = (wr + 1) % (K + 1);                // the picture K looks ago
            cur = dst; prev = frames[old]; rotPrev = rots[old];
            if (rotPrev != null) {
                try { track(rn, tanH, tanV); } catch (Throwable t) { quality = 0; err = String.valueOf(t); }
            }
        }
        wr = (wr + 1) % (K + 1);
        count++;
    }

    private int g(byte[] im, int x, int y) { return im[y * GW + x] & 0xFF; }

    private void track(float[] rn, float tanH, float tanV) {
        // turn from the old view to the new view (3x3), R = rn * rotPrev^T
        float[] a = rotPrev, b = rn;
        float[] R = new float[9];
        // while the head is turning fast the gyro and the picture are never perfectly in step, which would look like walking: skip those moments
        float tr = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[4] * b[4] + a[5] * b[5] + a[6] * b[6] + a[8] * b[8] + a[9] * b[9] + a[10] * b[10];
        float cosT = Math.max(-1f, Math.min(1f, (tr - 1f) * 0.5f));
        if (cosT < 0.985f) { quality = 0; lastA = lastB = lastC = 0; return; }       // more than ~10 degrees in these looks
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                R[i * 3 + j] = b[0 * 4 + i] * a[0 * 4 + j] + b[1 * 4 + i] * a[1 * 4 + j] + b[2 * 4 + i] * a[2 * 4 + j];   // column-major 4x4
        makeBoxes();
        final int M = NX * NY;
        float[] ex = new float[M], ey = new float[M], rx = new float[M], ry = new float[M];
        int n = 0;
        int cw = (GW - 2 * (PR + SR + 2)) / NX, ch = (GH - 2 * (PR + SR + 2)) / NY;
        int m0 = PR + SR + 2;
        for (int cy = 0; cy < NY; cy++) {
            for (int cx = 0; cx < NX; cx++) {
                // the most textured spot in this cell
                int bx = -1, by = -1, bs = 0;
                for (int y = m0 + cy * ch; y < m0 + (cy + 1) * ch; y += 2) {
                    for (int x = m0 + cx * cw; x < m0 + (cx + 1) * cw; x += 2) {
                        if (inHand(x, y)) continue;
                        int s = Math.abs(g(prev, x + 2, y) - g(prev, x - 2, y)) + Math.abs(g(prev, x, y + 2) - g(prev, x, y - 2));
                        if (s > bs) { bs = s; bx = x; by = y; }
                    }
                }
                if (bs < 30) continue;
                // where this spot would be if only the head had turned
                float dx = pixToDx(bx), dy = pixToDy(by);
                float vx = dx * tanH, vy = dy * tanV, vz = -1f;
                float wx = R[0] * vx + R[1] * vy + R[2] * vz, wy = R[3] * vx + R[4] * vy + R[5] * vz, wz = R[6] * vx + R[7] * vy + R[8] * vz;
                if (wz > -0.2f) continue;
                float pdx = wx / -wz / tanH, pdy = wy / -wz / tanV;
                float ppx = dxToPix(pdx + lastA + lastC * dx), ppy = dyToPix(pdy + lastB + lastC * dy);      // start looking where last time's movement suggests
                int ix = Math.round(ppx), iy = Math.round(ppy);
                if (ix < PR + SR || ix >= GW - PR - SR || iy < PR + SR || iy >= GH - PR - SR) continue;
                // block match around the predicted place
                int best = Integer.MAX_VALUE, bmx = 0, bmy = 0;
                int[][] sad = new int[2 * SR + 1][2 * SR + 1];
                for (int oy = -SR; oy <= SR; oy++) {
                    for (int ox = -SR; ox <= SR; ox++) {
                        int s = 0;
                        for (int py2 = -PR; py2 <= PR; py2++)
                            for (int px2 = -PR; px2 <= PR; px2++)
                                s += Math.abs(g(prev, bx + px2, by + py2) - g(cur, ix + ox + px2, iy + oy + py2));
                        sad[oy + SR][ox + SR] = s;
                        if (s < best) { best = s; bmx = ox; bmy = oy; }
                    }
                }
                if (best > 20 * (2 * PR + 1) * (2 * PR + 1)) continue;
                if (inHand(ix + bmx, iy + bmy)) continue;
                if (Math.abs(bmx) == SR || Math.abs(bmy) == SR) continue;      // best at the edge of the search = not trustworthy
                float fxs = bmx, fys = bmy;
                int l = sad[bmy + SR][bmx - 1 + SR], c0 = sad[bmy + SR][bmx + SR], r = sad[bmy + SR][bmx + 1 + SR];
                int u = sad[bmy - 1 + SR][bmx + SR], d = sad[bmy + 1 + SR][bmx + SR];
                if (l - 2 * c0 + r > 0) fxs += 0.5f * (l - r) / (float) (l - 2 * c0 + r);
                if (u - 2 * c0 + d > 0) fys += 0.5f * (u - d) / (float) (u - 2 * c0 + d);
                float mdx = pixToDx(ix + fxs), mdy = pixToDy(iy + fys);
                ex[n] = dx; ey[n] = dy; rx[n] = mdx - pdx; ry[n] = mdy - pdy;
                n++;
            }
        }
        tracked = n;
        if (n < 10) { quality = 0; lastA = lastB = lastC = 0; return; }
        boolean[] use = new boolean[n];
        java.util.Arrays.fill(use, true);
        float[] sol = new float[3];
        int used = n;
        for (int it = 0; it < 3; it++) {
            if (!fit(ex, ey, rx, ry, use, n, sol)) { quality = 0; return; }
            float[] res = new float[n];
            for (int i = 0; i < n; i++) {
                float qx = rx[i] - sol[0] - sol[2] * ex[i], qy = ry[i] - sol[1] - sol[2] * ey[i];
                res[i] = (float) Math.sqrt(qx * qx + qy * qy);
            }
            float[] sorted = res.clone();
            java.util.Arrays.sort(sorted);
            float lim = Math.max(0.01f, 3.5f * sorted[n / 2]);     // near things move more than far things: do not throw them all away
            used = 0;
            for (int i = 0; i < n; i++) { use[i] = res[i] <= lim; if (use[i]) used++; }
            if (used < 8) { quality = 0; return; }
        }
        if (!fit(ex, ey, rx, ry, use, n, sol)) { quality = 0; return; }
        usedN = used;
        lastA = sol[0] * 0.9f; lastB = sol[1] * 0.9f; lastC = sol[2] * 0.9f;
        quality = used / (float) (NX * NY);
        if (used < 10) return;
        float Z = depthM;
        float tx = -sol[0] * Z * tanH, ty = -sol[1] * Z * tanV, tzf = sol[2] * Z;
        float mag = (float) Math.sqrt(tx * tx + ty * ty + tzf * tzf);
        if (!(mag > 1e-6f) || Float.isNaN(mag) || Float.isInfinite(mag)) return;       // never let a 0/0 or a bad number into the position
        float shrink = Math.max(0f, mag - 0.003f) / mag;                   // soft: tiny values fade out instead of switching on and off (no stepping)
        tx *= shrink; ty *= shrink; tzf *= shrink; mag *= shrink;
        if (mag <= 0f) return;
        if (mag > 0.30f) { float k = 0.30f / mag; tx *= k; ty *= k; tzf *= k; }
        tx /= K; ty /= K; tzf /= K;                                        // this was K looks of walking: spread it out
        // view axes: x right, y up, z backwards -> world axes with the transpose of the turn
        float vx = tx, vy = ty, vz = -tzf;
        px += rn[0] * vx + rn[1] * vy + rn[2] * vz;
        py += rn[4] * vx + rn[5] * vy + rn[6] * vz;
        pz += rn[8] * vx + rn[9] * vy + rn[10] * vz;
        // you walk on a floor: sideways/forward may be far, but up/down is limited, and the whole thing stays within a sane size.
        // (a window sent far up or down by small errors would be gone when you turn back)
        // (the sensors' world: x east, y north, z UP: so z is the up/down one)
        pz = Math.max(-0.30f, Math.min(0.30f, pz * 0.99f));
        px = Math.max(-12f, Math.min(12f, px)); py = Math.max(-12f, Math.min(12f, py));
        if (Float.isNaN(px + py + pz)) { px = py = pz = 0; }
    }

    // least squares for  dx = a + c*x,  dy = b + c*y
    private static boolean fit(float[] ex, float[] ey, float[] rx, float[] ry, boolean[] use, int n, float[] out) {
        double N = 0, Sx = 0, Sy = 0, Sxx = 0, Syy = 0, Rx = 0, Ry = 0, Rxx = 0, Ryy = 0;
        for (int i = 0; i < n; i++) {
            if (!use[i]) continue;
            N++; Sx += ex[i]; Sy += ey[i]; Sxx += ex[i] * ex[i]; Syy += ey[i] * ey[i];
            Rx += rx[i]; Ry += ry[i]; Rxx += rx[i] * ex[i]; Ryy += ry[i] * ey[i];
        }
        // unknowns a,b,c:  [N 0 Sx; 0 N Sy; Sx Sy Sxx+Syy] * [a b c] = [Rx Ry Rxx+Ryy]
        double[][] m = {{N, 0, Sx, Rx}, {0, N, Sy, Ry}, {Sx, Sy, Sxx + Syy, Rxx + Ryy}};
        for (int i = 0; i < 3; i++) {
            int p = i;
            for (int r = i + 1; r < 3; r++) if (Math.abs(m[r][i]) > Math.abs(m[p][i])) p = r;
            double[] tmp = m[i]; m[i] = m[p]; m[p] = tmp;
            if (Math.abs(m[i][i]) < 1e-9) return false;
            for (int r = 0; r < 3; r++) {
                if (r == i) continue;
                double f = m[r][i] / m[i][i];
                for (int c = i; c < 4; c++) m[r][c] -= f * m[i][c];
            }
        }
        out[0] = (float) (m[0][3] / m[0][0]);
        out[1] = (float) (m[1][3] / m[1][1]);
        out[2] = (float) (m[2][3] / m[2][2]);
        return true;
    }

    // picture pixel <-> what the user sees (x right, y up, -1..1), the same convention as the pointer
    private float pixToDx(float x) { float d = (x + 0.5f) / GW * 2f - 1f; return rot180 ? -d : d; }
    private float pixToDy(float y) { float d = 1f - (y + 0.5f) / GH * 2f; return rot180 ? -d : d; }
    private float dxToPix(float d) { if (rot180) d = -d; return (d + 1f) * 0.5f * GW - 0.5f; }
    private float dyToPix(float d) { if (rot180) d = -d; return (1f - d) * 0.5f * GH - 0.5f; }
}
