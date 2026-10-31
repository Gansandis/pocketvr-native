package com.pocketvr.app;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Size;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Gallery window: a grid of the phone's pictures and videos; tap a picture to view it (and turn it into a 360 view), tap a video to play it. */
public class NvMedia implements NvContent {
    public interface Host {
        void onPano(Uri uri);              // show / hide this picture as a 360 view around you
        void onVideo(Uri uri);             // play this video
    }

    public Host host;
    public Runnable onClose;
    public volatile boolean panoOn = false;

    private static final int W = 1000, H = 600;
    private static final int IMGS = 0, VIDS = 1, PREV = 2, NEXT = 3, CLOSE = 4, BACK = 5, PANO = 6, CELL = 7;
    private static final int COLS = 4, ROWS = 3, PER = COLS * ROWS;

    private static class Item { Uri uri; boolean video; long dur; Bitmap thumb; boolean asked; }
    private static class B { int type; int idx; String label; final RectF r = new RectF(); }

    private final Context ctx;
    private final Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    private final Canvas cv = new Canvas(bmp);
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF handle = new RectF(W / 2f - 110, H - 34, W / 2f + 110, H - 4);
    private final List<B> btns = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private volatile List<Item> imgs = new ArrayList<>(), vids = new ArrayList<>();
    private volatile boolean loading = false, loaded = false, relayoutReq = false;
    private int tab = 0, page = 0;
    private int viewIdx = -1;                       // >= 0: looking at one picture
    private volatile Bitmap photo = null;
    private volatile boolean photoLoading = false;
    private int photoReq = 0;

    private boolean dirty = true, dragging = false, wasPinch = false;
    private B hover = null, lastH = null, pressed = null;
    private boolean hoverHandle = false, lastHandle = false;
    private long lastHT = 0, pressT = 0, lastAct = 0;

    public NvMedia(Context c) { ctx = c.getApplicationContext(); layout(); }

    private List<Item> cur() { return tab == 0 ? imgs : vids; }

    // ---------- loading the list ----------
    public void reload() {
        if (loading) return;
        loading = true; dirty = true;
        io.execute(() -> {
            try {
                imgs = query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false);
                vids = query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true);
                loaded = true; relayoutReq = true;                          // the grid cells are made from the list: build them now it has arrived
            } catch (Throwable ignored) { }
            loading = false; dirty = true;
        });
    }

    public boolean isLoaded() { return loaded; }

    private List<Item> query(Uri base, boolean video) {
        List<Item> out = new ArrayList<>();
        ContentResolver cr = ctx.getContentResolver();
        String[] cols = video ? new String[]{MediaStore.MediaColumns._ID, MediaStore.Video.Media.DURATION} : new String[]{MediaStore.MediaColumns._ID};
        try (Cursor c = cr.query(base, cols, null, null, MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            if (c == null) return out;
            while (c.moveToNext() && out.size() < 300) {
                Item it = new Item();
                it.uri = ContentUris.withAppendedId(base, c.getLong(0));
                it.video = video;
                if (video) it.dur = c.getLong(1);
                out.add(it);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private void askThumb(final Item it) {
        if (it.asked) return;
        it.asked = true;
        io.execute(() -> {
            Bitmap b = null;
            try {
                if (android.os.Build.VERSION.SDK_INT >= 29) b = ctx.getContentResolver().loadThumbnail(it.uri, new Size(320, 200), null);
            } catch (Throwable ignored) { }
            if (b == null && !it.video) b = decode(it.uri, 320);
            it.thumb = b; dirty = true;
        });
    }

    /** reads a picture so that its longest side is about maxSide (with the camera's rotation applied) */
    public Bitmap decode(Uri u, int maxSide) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = ctx.getContentResolver().openInputStream(u)) { BitmapFactory.decodeStream(in, null, o); }
            int s = 1, m = Math.max(o.outWidth, o.outHeight);
            while (m / (s * 2) >= maxSide) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            Bitmap b;
            try (InputStream in = ctx.getContentResolver().openInputStream(u)) { b = BitmapFactory.decodeStream(in, null, o); }
            if (b == null) return null;
            int rot = 0;
            try (InputStream in = ctx.getContentResolver().openInputStream(u)) {
                android.media.ExifInterface ex = new android.media.ExifInterface(in);
                int ori = ex.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1);
                rot = ori == 6 ? 90 : ori == 3 ? 180 : ori == 8 ? 270 : 0;
            } catch (Throwable ignored) { }
            if (rot != 0) {
                Matrix mx = new Matrix(); mx.postRotate(rot);
                Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), mx, true);
                if (r != b) b.recycle();
                b = r;
            }
            return b;
        } catch (Throwable t) { return null; }
    }

    private void openPhoto(int idx) {
        List<Item> l = cur();
        if (idx < 0 || idx >= l.size()) return;
        viewIdx = idx; photo = null; photoLoading = true; dirty = true;
        final int req = ++photoReq;
        final Uri u = l.get(idx).uri;
        io.execute(() -> {
            Bitmap b = decode(u, 1600);
            if (req == photoReq) { photo = b; photoLoading = false; dirty = true; }
        });
        layout();
    }

    public Uri currentUri() { List<Item> l = cur(); return viewIdx >= 0 && viewIdx < l.size() ? l.get(viewIdx).uri : null; }

    // ---------- layout ----------
    private B mk(int type, String label, float x, float y, float w, float h) {
        B b = new B(); b.type = type; b.label = label; b.r.set(x, y, x + w, y + h); btns.add(b); return b;
    }

    private void layout() {
        btns.clear();
        for (List<Item> li : new List[]{imgs, vids}) {                  // thumbnails of other pages are let go (memory)
            for (int i = 0; i < li.size(); i++) {
                Item it = li.get(i);
                boolean on = li == cur() && i >= page * PER && i < (page + 1) * PER;
                if (!on && it.thumb != null) { it.thumb = null; it.asked = false; }
            }
        }
        mk(CLOSE, "✕", W - 14 - 70, 8, 70, 56);
        if (viewIdx >= 0) {
            mk(BACK, "▦", 14, 8, 70, 56);
            mk(PREV, "‹", 94, 8, 70, 56);
            mk(NEXT, "›", 174, 8, 70, 56);
            mk(PANO, panoOn ? "ออก 360°" : "🌐 360°", 254, 8, 190, 56);
        } else {
            mk(IMGS, "🖼 รูป", 14, 8, 150, 56);
            mk(VIDS, "🎬 วิดีโอ", 172, 8, 170, 56);
            mk(PREV, "‹", 352, 8, 70, 56);
            mk(NEXT, "›", 430, 8, 70, 56);
            List<Item> l = cur();
            float cw = (W - 28 - (COLS - 1) * 8) / (float) COLS, ch = 146;
            for (int i = 0; i < PER; i++) {
                int idx = page * PER + i;
                if (idx >= l.size()) break;
                int c = i % COLS, r = i / COLS;
                B b = mk(CELL, "", 14 + c * (cw + 8), 76 + r * (ch + 8), cw, ch);
                b.idx = idx;
            }
        }
        dirty = true;
    }

    public void refreshLayout() { layout(); }

    // ---------- actions ----------
    private void act(B b) {
        pressed = b; pressT = System.currentTimeMillis(); dirty = true;
        int pages = Math.max(1, (cur().size() + PER - 1) / PER);
        switch (b.type) {
            case IMGS: tab = 0; page = 0; layout(); break;
            case VIDS: tab = 1; page = 0; layout(); break;
            case PREV:
                if (viewIdx >= 0) { if (viewIdx > 0) openPhoto(viewIdx - 1); }
                else { page = (page + pages - 1) % pages; layout(); }
                break;
            case NEXT:
                if (viewIdx >= 0) { if (viewIdx + 1 < cur().size()) openPhoto(viewIdx + 1); }
                else { page = (page + 1) % pages; layout(); }
                break;
            case BACK: viewIdx = -1; photo = null; layout(); break;
            case CLOSE: if (onClose != null) onClose.run(); break;
            case PANO: { Uri u = currentUri(); if (u != null && host != null) host.onPano(u); break; }
            case CELL: {
                Item it = cur().get(b.idx);
                if (it.video) { if (host != null) host.onVideo(it.uri); }
                else openPhoto(b.idx);
                break;
            }
            default: break;
        }
    }

    // ---------- NvContent ----------
    @Override public Bitmap bitmap() { return bmp; }
    @Override public void markDirty() { dirty = true; }
    @Override public void setDragging(boolean d) { if (d != dragging) { dragging = d; dirty = true; } }
    @Override public boolean covers(float x, float y) { return true; }
    @Override public boolean inGrip(float x, float y) { return y > H * 0.92f && (x < W * 0.12f || x > W * 0.88f); }
    @Override public boolean handleRecent() { return hoverHandle || (lastHandle && System.currentTimeMillis() - lastHT < 300); }

    private boolean lastPano = false;
    @Override public boolean takeDirty() {
        long now = System.currentTimeMillis();
        if (lastPano != panoOn) { lastPano = panoOn; layout(); }
        if (relayoutReq) { relayoutReq = false; layout(); }
        if (pressed != null && now - pressT > 150) { pressed = null; dirty = true; }
        if (!dirty) return false;
        dirty = false;
        draw();
        return true;
    }

    @Override public void update(boolean valid, float x, float y, boolean pinch) {
        B h = null; boolean hh = false;
        if (valid) {
            for (B b : btns) if (b.r.contains(x, y)) { h = b; break; }
            if (h == null && handle.contains(x, y)) hh = true;
        }
        long now = System.currentTimeMillis();
        if (h != null) { lastH = h; lastHT = now; lastHandle = false; }
        else if (hh) { lastHandle = true; lastHT = now; }
        if (h != hover || hh != hoverHandle) { hover = h; hoverHandle = hh; dirty = true; }
        boolean edge = pinch && !wasPinch;
        wasPinch = pinch;
        B tgt = h != null ? h : ((lastH != null && !lastHandle && now - lastHT < 250 && btns.contains(lastH)) ? lastH : null);
        if (edge && tgt != null && now - lastAct > 300) { lastAct = now; act(tgt); }
    }

    // ---------- drawing ----------
    private void round(RectF r, int color, float rad) {
        p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(color);
        cv.drawRoundRect(r, rad, rad, p);
    }

    private void stroke(RectF r, int color, float w, float rad) {
        p.setShader(null); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(w); p.setColor(color);
        cv.drawRoundRect(r, rad, rad, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void text(String s, float cx, float cy, float size, int color, Paint.Align al, float maxW) {
        p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(color); p.setTextSize(size); p.setTextAlign(al);
        while (maxW > 0 && p.measureText(s) > maxW && size > 10) { size -= 1; p.setTextSize(size); }
        cv.drawText(s, cx, cy + size * 0.35f, p);
    }

    private static String dur(long ms) {
        long s = ms / 1000;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    private void fitDraw(Bitmap b, RectF area, boolean crop) {
        if (b == null) return;
        float bw = b.getWidth(), bh = b.getHeight();
        float s = crop ? Math.max(area.width() / bw, area.height() / bh) : Math.min(area.width() / bw, area.height() / bh);
        float dw = bw * s, dh = bh * s;
        RectF dst = new RectF(area.centerX() - dw / 2, area.centerY() - dh / 2, area.centerX() + dw / 2, area.centerY() + dh / 2);
        cv.save();
        cv.clipRect(area);
        cv.drawBitmap(b, null, dst, p);
        cv.restore();
    }

    private void draw() {
        cv.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
        RectF pr = new RectF(2, 2, W - 2, H - 2);
        p.setShader(new LinearGradient(0, 0, 0, H, (NvUi.glass << 24) | 0x2A2C36, (NvUi.glass << 24) | 0x121319, Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.FILL);
        cv.drawRoundRect(pr, 30, 30, p);
        p.setShader(null);
        stroke(pr, 0x40FFFFFF, 2f, 30);
        NvPanel.grips(cv, p, pr, 30, 1.4f);

        List<Item> l = cur();
        int pages = Math.max(1, (l.size() + PER - 1) / PER);
        for (B b : btns) {
            if (b.type == CELL) {
                Item it = l.get(b.idx);
                round(b.r, 0xFF1B1D24, 14);
                if (it.thumb != null) {
                    cv.save();
                    android.graphics.Path clip = new android.graphics.Path();
                    clip.addRoundRect(b.r, 14, 14, android.graphics.Path.Direction.CW);
                    cv.clipPath(clip);
                    fitDraw(it.thumb, b.r, true);
                    cv.restore();
                } else askThumb(it);
                if (it.video) {
                    p.setColor(0x99000000); cv.drawCircle(b.r.centerX(), b.r.centerY(), 30, p);
                    text("▶", b.r.centerX() + 3, b.r.centerY(), 30, 0xFFFFFFFF, Paint.Align.CENTER, 0);
                    text(dur(it.dur), b.r.right - 10, b.r.bottom - 16, 22, 0xFFFFFFFF, Paint.Align.RIGHT, 0);
                }
                if (hover == b) stroke(b.r, 0xFF22B8CF, 4f, 14);
                else if (pressed == b) stroke(b.r, 0xFF6FA8FF, 4f, 14);
                continue;
            }
            int c1 = 0xFF454A58, c2 = 0xFF2B2E38;
            if (b.type == CLOSE) { c1 = 0xFF6A3036; c2 = 0xFF4A2024; }
            if ((b.type == IMGS && tab == 0 && viewIdx < 0) || (b.type == VIDS && tab == 1 && viewIdx < 0) || (b.type == PANO && panoOn)) { c1 = 0xFF4D8DFF; c2 = 0xFF2E6BFF; }
            if (pressed == b) { c1 = 0xFF6FA8FF; c2 = 0xFF4D8DFF; }
            else if (hover == b) { c1 = 0xFF5F6675; c2 = 0xFF454B59; }
            p.setShader(new LinearGradient(0, b.r.top, 0, b.r.bottom, c1, c2, Shader.TileMode.CLAMP));
            p.setStyle(Paint.Style.FILL);
            cv.drawRoundRect(b.r, 16, 16, p);
            p.setShader(null);
            if (hover == b) stroke(b.r, 0xFF22B8CF, 3f, 16);
            text(b.label, b.r.centerX(), b.r.centerY(), 28, 0xFFFFFFFF, Paint.Align.CENTER, b.r.width() - 10);
        }
        if (viewIdx >= 0) {
            RectF area = new RectF(14, 76, W - 14, H - 44);
            round(area, 0xFF0C0D11, 14);
            Bitmap ph = photo;
            if (ph != null) fitDraw(ph, area, false);
            else text(photoLoading ? "กำลังเปิดรูป..." : "เปิดรูปไม่ได้", area.centerX(), area.centerY(), 30, 0x99FFFFFF, Paint.Align.CENTER, 0);
            text((viewIdx + 1) + " / " + l.size(), W - 100, 36, 24, 0xCCFFFFFF, Paint.Align.RIGHT, 0);
        } else {
            text((page + 1) + " / " + pages, 590, 36, 26, 0xCCFFFFFF, Paint.Align.LEFT, 0);
            if (l.isEmpty()) text(loading ? "กำลังโหลด..." : (loaded ? "ไม่พบไฟล์ในเครื่อง" : "ยังไม่ได้โหลด"), W / 2f, H / 2f, 30, 0x99FFFFFF, Paint.Align.CENTER, 0);
        }
        float hp = 6;
        RectF hb = new RectF(handle.left + hp, handle.centerY() - 5, handle.right - hp, handle.centerY() + 5);
        round(hb, dragging ? 0xFFFFA24D : (hoverHandle ? 0xFF22B8CF : 0xE6FFFFFF), 5);
    }
}
