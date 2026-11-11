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
            if (l != null && !l.isEmpty() && (dictating || System.currentTimeMillis() < armUntil || hasWake(l.get(0)))) out.onState("ได้ยิน: " + l.get(0));
        }
        @Override public void onError(int e) {
            listening = false;
            if (!on) return;
            if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { out.onState("ยังไม่ได้อนุญาตไมโครโฟน"); stop(); return; }
            if (once && System.currentTimeMillis() > armUntil) { stop(); return; }
            if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) { restart(150); return; }
            if (e == SpeechRecognizer.ERROR_NETWORK || e == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) out.onState("ไม่มีอินเทอร์เน็ต ฟังเสียงไม่ได้");
            restart(Math.min(5000, 600L * (1 + fails++)));
        }
        @Override public void onResults(Bundle b) {
            listening = false;
            ArrayList<String> l = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
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

    /** index in the original text just after the wake word, or -1 */
    static int wakeEnd(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        String[] w = {"พ็อกเก็ต", "พ๊อกเก็ต", "พ๊อคเก็ต", "พ็อคเก็ต", "ป๊อกเก็ต", "ปอกเก็ต", "พอกเก็ต", "พอคเก็ต", "pocket", "poket", "pockets", "พ็อกเก็ท", "พ็อกเกต", "พอกเกต", "ปอกเกต", "pockit", "pocked", "heypacket", "hey packet", "hey pockit", "hey locket", "hey pocked", "a pocket", "hey, pocket"};
        int best = -1;
        for (String k : w) { int i = t.indexOf(k); if (i >= 0) best = Math.max(best, i + k.length()); }
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
            armUntil = 0; out.onCommand(rest); return;
        }
        if (now < armUntil) { armUntil = 0; out.onCommand(l.get(0).trim()); return; }
        // not for us: say nothing
    }
}
