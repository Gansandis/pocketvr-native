package com.pocketvr.app;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Voice listener. It keeps Android's speech recogniser (Google, Thai) running, looks for the wake word ("เฮ้ พ็อกเก็ต" / "hey pocket"),
 * and hands what comes after it to the app as a command. After the wake word alone, the next sentence is the command (for 7 seconds).
 * Dictation mode types every sentence until you say a stop word.
 * All calls must be made on the main thread.
 */
final class NvVoice {
    interface Out {
        /** a command sentence (wake word already cut off, lower case, as the recogniser wrote it) */
        void onCommand(String text);
        /** dictation sentence */
        void onDictate(String text);
        /** what the recogniser heard / which error it gave (for the on-screen line) */
        void onHeard(String s);
        /** short status line for the screen */
        void onState(String s);
    }

    private final Context ctx;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Out out;
    private SpeechRecognizer sr;
    private boolean on = false, listening = false, once = false;
    private long armUntil = 0;
    private int fails = 0;
    boolean dictating = false;
    String lang = "th-TH";
    interface LangSrc { String lang(int cycle); }
    LangSrc langSrc = null;
    private int cycle = 0;

    // ---- silence the recogniser's "ding" sounds (they play on the notification / system / media streams) ----
    private final Handler mh = new Handler(Looper.getMainLooper());
    private boolean mutedSys = false, mutedMusic = false;
    private final Runnable unmuteMusic = () -> setMute(AudioManager.STREAM_MUSIC, false);

    private void setMute(int stream, boolean m) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            if (stream == AudioManager.STREAM_MUSIC) { if (m == mutedMusic) return; mutedMusic = m; }
            else { if (m == mutedSys) return; mutedSys = m; }
            int d = m ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE;
            if (stream != AudioManager.STREAM_MUSIC) { am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, d, 0); am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, d, 0); }
            else am.adjustStreamVolume(AudioManager.STREAM_MUSIC, d, 0);
        } catch (Throwable ignored) { }
    }

    /** mute the dings for the next second (the media stream only when nothing is playing, so a video or music is never cut) */
    private void beepOff() {
        setMute(AudioManager.STREAM_NOTIFICATION, true);
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null && (mutedMusic || !am.isMusicActive())) { setMute(AudioManager.STREAM_MUSIC, true); mh.removeCallbacks(unmuteMusic); mh.postDelayed(unmuteMusic, 1200); }
        } catch (Throwable ignored) { }
    }
    private void beepOn() { mh.removeCallbacks(unmuteMusic); setMute(AudioManager.STREAM_MUSIC, false); setMute(AudioManager.STREAM_NOTIFICATION, false); }

    NvVoice(Context c, Out o) { ctx = c.getApplicationContext(); out = o; }

    boolean isOn() { return on; }
    boolean once() { return once; }
    static boolean available(Context c) { try { return SpeechRecognizer.isRecognitionAvailable(c); } catch (Throwable t) { return false; } }

    /** wake word mode: always listening until stop() */
    void start() {
        if (on) return;
        if (!available(ctx)) { out.onState("เครื่องนี้ไม่มีตัวรู้จำเสียง (ติดตั้ง Google)"); return; }
        on = true; once = false; fails = 0;
        out.onState("ฟังอยู่ — พูดว่า \"เฮ้ พ็อกเก็ต\"");
        listen();
    }

    /** push to talk: listen for one command now (no wake word needed) */
    void listenOnce() {
        if (!available(ctx)) { out.onState("เครื่องนี้ไม่มีตัวรู้จำเสียง (ติดตั้ง Google)"); return; }
        armUntil = System.currentTimeMillis() + 8000;
        if (on) { restart(0); return; }
        on = true; once = true; fails = 0;
        out.onState("พูดคำสั่งได้เลย…");
        listen();
    }

    void stop() {
        on = false; once = false; dictating = false; listening = false;
        h.removeCallbacksAndMessages(null);
        destroy();
        beepOn();
        out.onState("");
    }

    void arm(long ms) { armUntil = System.currentTimeMillis() + ms; }

    private void destroy() {
        if (sr != null) { try { sr.setRecognitionListener(null); sr.cancel(); sr.destroy(); } catch (Throwable ignored) { } sr = null; }
    }

    private void restart(long delay) {
        h.removeCallbacksAndMessages(null);
        destroy();
        listening = false;
        if (on) h.postDelayed(this::listen, delay);
    }

    private void listen() {
        if (!on || listening) return;
        try {
            if (sr == null) { sr = SpeechRecognizer.createSpeechRecognizer(ctx); sr.setRecognitionListener(lis); }
            Intent in = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            if (langSrc != null) { try { lang = langSrc.lang(cycle++); } catch (Throwable ignored) { } }
            in.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            in.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
            in.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
            in.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            in.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            in.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.getPackageName());
            in.putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 900L);
            in.putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 700L);
            listening = true;
            beepOff();
            sr.startListening(in);
        } catch (Throwable t) {
            listening = false;
            out.onState("เปิดไมค์ไม่ได้: " + t.getClass().getSimpleName());
            if (++fails > 5) stop(); else restart(1000);
        }
    }

    private final RecognitionListener lis = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle p) { fails = 0; }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float v) { }
        @Override public void onBufferReceived(byte[] b) { }
        @Override public void onEndOfSpeech() { beepOff(); }
        @Override public void onEvent(int t, Bundle b) { }
        @Override public void onPartialResults(Bundle b) {
            ArrayList<String> l = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (l != null && !l.isEmpty()) out.onHeard(l.get(0));
        }
        @Override public void onError(int e) {
            listening = false;
            if (!on) return;
            if (e != SpeechRecognizer.ERROR_NO_MATCH && e != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) out.onHeard("ไมค์ error " + e + (e == 12 || e == 13 ? " (ภาษา " + lang + " ใช้ไม่ได้)" : ""));
            if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { out.onState("ยังไม่ได้อนุญาตไมโครโฟน"); stop(); return; }
            if (once && System.currentTimeMillis() > armUntil) { stop(); return; }
            if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) { restart(150); return; }
            if (e == SpeechRecognizer.ERROR_NETWORK || e == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) out.onState("ไม่มีอินเทอร์เน็ต ฟังเสียงไม่ได้");
            restart(Math.min(5000, 600L * (1 + fails++)));
        }
        @Override public void onResults(Bundle b) {
            listening = false;
            ArrayList<String> l = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (l != null && !l.isEmpty()) out.onHeard(l.get(0) + (l.size() > 1 ? "  | " + l.get(1) : ""));
            if (on) process(l);
            if (!on) return;
            if (once && System.currentTimeMillis() > armUntil) { stop(); return; }
            restart(80);
        }
    };

    // ---- wake word ----
    /** drop spaces, tone marks and symbols so "เฮ้ย พ็อกเก็ต" and "เฮพอกเกต" look the same */
    static String norm(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c == ' ' || c == '่' || c == '้' || c == '๊' || c == '๋' || c == '็' || c == '์' || c == '.' || c == ',' || c == '-' || c == '\'' || c == '"') continue;
            sb.append(c);
        }
        return sb.toString();
    }


    /** sound-alike folding, so a word the recogniser spelled a little differently (accent, similar consonant/vowel) still lands near the real one */
    /** the other guesses of the recogniser for the last phrase (used when the first one is not understood) */
    public volatile String[] alts = new String[0];

    static String fold(String s) {
        String n = norm(s);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            switch (c) {
                case 'ก': case 'ข': case 'ค': case 'ฆ': case 'ฅ': case 'ฃ': c = 'k'; break;
                case 'ต': case 'ท': case 'ธ': case 'ด': case 'ฎ': case 'ฏ': case 'ฑ': case 'ฒ': case 'ถ': case 'ฐ': c = 't'; break;
                case 'ป': case 'พ': case 'ผ': case 'บ': case 'ภ': c = 'p'; break;
                case 'ส': case 'ศ': case 'ษ': case 'ซ': case 'ฉ': case 'ช': case 'ฌ': case 'จ': c = 's'; break;
                case 'ร': case 'ล': case 'ฬ': case 'ฤ': c = 'l'; break;
                case 'ง': case 'ญ': case 'ณ': case 'น': case 'ม': c = 'n'; break;
                case 'ฟ': case 'ฝ': case 'ว': c = 'w'; break;
                case 'ห': case 'ฮ': case 'อ': case 'ย': case 'ๆ': case 'ฯ': case 'ั': case 'ะ': case 'า': case 'ำ': case 'ิ': case 'ี': case 'ึ': case 'ื': case 'ุ': case 'ู': case 'เ': case 'แ': case 'โ': case 'ไ': case 'ใ': case 'ๅ': c = 0; break;
                default: break;
            }
            if (c == 0) continue;
            if (c >= 'a' && c <= 'z') {
                if ("aeiouhy".indexOf(c) >= 0) continue;               // latin vowels carry the accent: dropped
                if (c == 'c' || c == 'q') c = 'k'; else if (c == 'd') c = 't'; else if (c == 'b') c = 'p'; else if (c == 'z' || c == 'x') c = 's'; else if (c == 'r') c = 'l'; else if (c == 'v' || c == 'f') c = 'w'; else if (c == 'g') c = 'k'; else if (c == 'j') c = 's'; else if (c == 'm') c = 'n';
            }
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) == c) continue;   // doubled sound
            sb.append(c);
        }
        return sb.toString();
    }

    static int lev(String a, String b) {
        int[] p = new int[b.length() + 1], q = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) p[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            q[0] = i;
            for (int j = 1; j <= b.length(); j++) q[j] = Math.min(Math.min(q[j - 1] + 1, p[j] + 1), p[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = p; p = q; q = t;
        }
        return p[b.length()];
    }

    /** 0..1: how well the heard text contains the name (best window of the heard text against the name, on folded sounds) */
    static float similar(String heard, String name) {
        String h = fold(heard), nm = fold(name);
        if (nm.isEmpty() || h.isEmpty()) return 0f;
        if (h.contains(nm)) return 1f;
        float best = 0f;
        for (int len = Math.max(1, nm.length() - 1); len <= nm.length() + 1 && len <= h.length(); len++)
            for (int i = 0; i + len <= h.length(); i++) {
                float sc = 1f - (float) lev(h.substring(i, i + len), nm) / Math.max(len, nm.length());
                if (sc > best) best = sc;
            }
        if (nm.length() <= 2) best *= 0.7f;                         // very short names match too easily
        return best;
    }

    /** index in the original text just after the wake word, or -1. Looks at the text without spaces and tone marks, so every spelling the recogniser may pick matches. */
    static int wakeEnd(String text) {
        String low = text.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        ArrayList<Integer> endAt = new ArrayList<>();
        for (int i = 0; i < low.length(); i++) {
            char c = low.charAt(i);
            if (c == ' ' || c == '\u0e48' || c == '\u0e49' || c == '\u0e4a' || c == '\u0e4b' || c == '\u0e47' || c == '\u0e4c' || c == '.' || c == ',' || c == '-' || c == '\'' || c == '"') continue;
            sb.append(c); endAt.add(i + 1);
        }
        String n = sb.toString();
        String[] w = {"พอกเกต", "ปอกเกต", "พอคเกต", "ปอคเกต", "พอกเกท", "ปอกเกท", "พอคเกท", "ปอคเกท", "พอกเก", "ปอกเก", "พอคเก", "ปอคเก", "pocket", "poket", "pockit", "pocked", "heypacket", "heylocket", "hepocket", "apocket", "heypoker", "hey pocket"};
        int best = -1;
        for (String k : w) { int i = n.indexOf(k); if (i >= 0) best = Math.max(best, endAt.get(i + k.length() - 1)); }
        return best;
    }

    static boolean hasWake(String s) { return wakeEnd(s) >= 0; }

    private void process(ArrayList<String> l) {
        if (l == null || l.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (dictating) { out.onDictate(l.get(0).trim()); return; }
        String wakeText = null; int end = -1;
        for (String s : l) { int e = wakeEnd(s); if (e >= 0) { wakeText = s; end = e; break; } }
        if (wakeText != null) {
            String rest = wakeText.substring(end).trim();
            if (rest.isEmpty()) { armUntil = now + 7000; out.onState("ว่าไง? พูดคำสั่งได้เลย…"); return; }
            armUntil = 0; alts = l.toArray(new String[0]); out.onCommand(rest); return;
        }
        if (now < armUntil) { armUntil = 0; alts = l.toArray(new String[0]); out.onCommand(l.get(0).trim()); return; }
        // not for us: say nothing
    }
}
