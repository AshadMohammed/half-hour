package com.ash.halfhour;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.util.ArrayList;

/**
 * The whole app is one WebView on a local page; this class is the small Java layer under it:
 * alarms, notifications, storage, speech and haptics. Nothing leaves the phone.
 */
public final class MainActivity extends Activity {
    private static final String BLOCK = Notify.BLOCK;
    private static final int ASK_AUDIO = 2;
    private static final int ASK_NOTIFICATIONS = 3;

    private WebView web;
    private boolean ready;
    private String blockExtra;
    private String screen = "log";

    /* system-bar insets in CSS px, as the page lays itself out around them */
    private int insTop, insRight, insBottom, insLeft;

    private SpeechRecognizer recognizer;
    private boolean pendingSpeech;
    private long lastTick, lastScore, lastSlice;

    /* ---- speech: one session per hold ----
       session counts the holds. A listener callback carries the number it was started with, so a
       result that turns up after a newer hold began can be dropped, while a result from a hold that
       has simply ended (finger up, no new hold) still lands on the page. */
    private static final long TAIL_MS = 700L;        // keep the mic open this long after the finger lifts
    private static final long READY_GRACE_MS = 1000L; // a lift before onReadyForSpeech stops at ready + this
    private static final long HARD_STOP_MS = 6000L;   // nothing may run on past this after a release
    private final Handler main = new Handler(Looper.getMainLooper());
    private int session;
    private long readyAt;   // uptime of onReadyForSpeech for the current session, 0 until it comes
    private long releaseAt; // uptime of the finger lifting, 0 while it is still down
    private boolean active;     // startListening has been called and no result or error has come back
    private boolean stopPosted; // a stopListening is already scheduled for this session
    private boolean stopped;    // stopListening has been called for this session
    private String lastPartial = "";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Dbg.init(this);
        Data.healOwner(this);
        Dbg.log("app open, block extra=" + (getIntent() == null ? null : getIntent().getStringExtra(BLOCK)));
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().getDecorView(); // the bar controller needs the window's view to exist first
        getWindow().setStatusBarColor(0x00000000);
        getWindow().setNavigationBarColor(0x00000000);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(light, light);
            }
        }
        getWindow().getDecorView().setBackgroundColor(0xFFC9CACB);

        blockExtra = getIntent() == null ? null : getIntent().getStringExtra(BLOCK);

        web = new WebView(this);
        web.setBackgroundColor(0xFFC9CACB);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHapticFeedbackEnabled(true);
        web.setLongClickable(false);
        web.setOnLongClickListener(v -> true);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= 33) s.setAlgorithmicDarkeningAllowed(false);

        web.addJavascriptInterface(new Bridge(this), "HH");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                ready = true;
                pushInsets();
                if (blockExtra != null && !blockExtra.isEmpty()) {
                    callJs("HHopen(" + JSONObject.quote(blockExtra) + ")");
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Dbg.log(m.message() + " @" + m.lineNumber());
                return true;
            }
        });
        // The WebView runs edge to edge, under the status bar and the swipe bar. The page gets the
        // insets in CSS px and lays its own screens out inside them.
        final android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(0xFFC9CACB);
        root.addView(web, new android.widget.FrameLayout.LayoutParams(-1, -1));
        final float density = getResources().getDisplayMetrics().density;
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            int t = Math.round(bars.top / density);
            int r = Math.round(bars.right / density);
            int b = Math.round(bars.bottom / density) + 15; // 15 px of room above the swipe bar
            int l = Math.round(bars.left / density);
            if (t != insTop || r != insRight || b != insBottom || l != insLeft) {
                insTop = t;
                insRight = r;
                insBottom = b;
                insLeft = l;
                if (ready) pushInsets();
            }
            return insets;
        });

        setContentView(root);
        root.requestApplyInsets();
        web.loadUrl("file:///android_asset/app.html");

        Notify.ensureChannel(this);
        askNotifications();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        blockExtra = intent == null ? null : intent.getStringExtra(BLOCK);
        if (!ready) return;
        if (blockExtra != null && !blockExtra.isEmpty()) {
            callJs("HHopen(" + JSONObject.quote(blockExtra) + ")");
        } else {
            callJs("HHgo('log')");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Alarms.schedule(this); // every time the app opens, and every time it comes back
        if (ready) callJs("HHresume()");
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) {
            try { recognizer.destroy(); } catch (Exception ignored) {}
            recognizer = null;
        }
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (!"log".equals(screen) && web != null) {
            callJs("HHgo('log')");
        } else {
            moveTaskToBack(true);
        }
    }

    // ---- called from the page, through Bridge ----

    /** "top,right,bottom,left" in CSS px, for the page's fit(). */
    String insets() {
        return insTop + "," + insRight + "," + insBottom + "," + insLeft;
    }

    private void pushInsets() {
        callJs("window.HHinsets&&HHinsets(" + insTop + "," + insRight + "," + insBottom + "," + insLeft + ")");
    }

    String openBlock() {
        return blockExtra == null ? "" : blockExtra;
    }

    void setScreen(String name) {
        screen = name == null ? "log" : name;
    }

    void haptic(String kind) {
        if (web == null) return;
        runOnUiThread(() -> {
            long now = SystemClock.uptimeMillis();
            int c = HapticFeedbackConstants.KEYBOARD_TAP;
            if ("tick".equals(kind)) {
                if (now - lastTick < 40) return;
                lastTick = now;
                c = HapticFeedbackConstants.CLOCK_TICK;
            } else if ("score".equals(kind)) {
                if (now - lastScore < 40) return;
                lastScore = now;
                c = Build.VERSION.SDK_INT >= 34
                        ? HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
                        : HapticFeedbackConstants.CLOCK_TICK;
            } else if ("slice".equals(kind)) {
                if (now - lastSlice < 35) return;
                lastSlice = now;
                c = Build.VERSION.SDK_INT >= 34
                        ? HapticFeedbackConstants.SEGMENT_TICK
                        : HapticFeedbackConstants.CLOCK_TICK;
            } else if (Build.VERSION.SDK_INT >= 30) {
                c = HapticFeedbackConstants.CONFIRM;
            }
            web.performHapticFeedback(c);
        });
    }

    void startSpeech() {
        runOnUiThread(() -> {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                pendingSpeech = true;
                requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, ASK_AUDIO);
                return;
            }
            listen();
        });
    }

    void stopSpeech() {
        // The finger has lifted, but the last words are still arriving: keep listening for a moment.
        runOnUiThread(() -> {
            if (session == 0) return;
            final int id = session;
            if (releaseAt == 0L) releaseAt = SystemClock.uptimeMillis();
            Dbg.log("release, session " + id + ", ready=" + (readyAt != 0L));
            postStop(id);
            main.postDelayed(() -> {
                if (id != session || stopped || !active) return;
                stopped = true;
                Dbg.log("hard stop, session " + id);
                try { if (recognizer != null) recognizer.stopListening(); } catch (Exception ignored) {}
            }, HARD_STOP_MS);
        });
    }

    /** Stops 700 ms after release, or 1000 ms after ready when the release came before the mic opened. */
    private void postStop(final int id) {
        if (id != session || stopPosted || stopped) return;
        if (releaseAt == 0L) return;
        if (readyAt == 0L) {
            Dbg.log("release before ready, session " + id + ", waiting");
            return; // onReadyForSpeech posts it
        }
        long at = releaseAt >= readyAt ? releaseAt + TAIL_MS : readyAt + READY_GRACE_MS;
        long delay = Math.max(0L, at - SystemClock.uptimeMillis());
        stopPosted = true;
        Dbg.log("stop scheduled in " + delay + " ms, session " + id);
        main.postDelayed(() -> {
            if (id != session || stopped || !active) return;
            stopped = true;
            Dbg.log("stopListening, session " + id);
            try { if (recognizer != null) recognizer.stopListening(); } catch (Exception ignored) {}
        }, delay);
    }

    private void listen() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Dbg.log("no speech service");
            callJs("HHcb.speechError(" + JSONObject.quote(NO_SERVICE) + ")");
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            if (recognizer == null) {
                Dbg.log("recognizer could not be created");
                callJs("HHcb.speechError(" + JSONObject.quote(NO_SERVICE) + ")");
                return;
            }
        }
        if (active) {
            try { recognizer.cancel(); } catch (Exception ignored) {}
        }
        session++;
        final int id = session;
        readyAt = 0L;
        releaseAt = 0L;
        stopPosted = false;
        stopped = false;
        lastPartial = "";
        recognizer.setRecognitionListener(new Listener(id));
        try {
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200);
            callJs("HHcb.speechState('starting')");
            recognizer.startListening(i);
            active = true;
            Dbg.log("startListening, session " + id);
        } catch (Exception e) {
            Dbg.log("startListening failed, session " + id + ", " + e);
            callJs("HHcb.speechError(" + JSONObject.quote(NO_SERVICE) + ")");
        }
    }

    /** A recognizer that has errored can stay deaf, so the next hold gets a fresh one. */
    private void freshRecognizer() {
        Dbg.log("recreating the recognizer");
        active = false;
        if (recognizer != null) {
            try { recognizer.destroy(); } catch (Exception ignored) {}
            recognizer = null;
        }
    }

    private static String errorText(int code) {
        switch (code) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "didn't catch that, hold and talk";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "mic busy, try again";
            default:
                return "speech error " + code;
        }
    }

    private static final String NO_SERVICE = "no speech service";

    private final class Listener implements RecognitionListener {
        private final int id;
        private boolean rmsLogged;

        Listener(int id) {
            this.id = id;
        }

        /** A newer hold has started, so this one's late callbacks are not wanted. */
        private boolean stale() {
            return id != session;
        }

        @Override public void onReadyForSpeech(Bundle b) {
            Dbg.log("onReadyForSpeech, session " + id);
            if (stale()) return;
            readyAt = SystemClock.uptimeMillis();
            callJs("HHcb.speechState('ready')");
            haptic("confirm"); // you feel the moment to talk
            postStop(id);      // the finger may already be up
        }

        @Override public void onBeginningOfSpeech() {
            Dbg.log("onBeginningOfSpeech, session " + id);
        }

        @Override public void onRmsChanged(float rms) {
            // this one fires many times a second, so only the first of a session goes in the log
            if (rmsLogged) return;
            rmsLogged = true;
            Dbg.log("onRmsChanged first, session " + id);
        }

        @Override public void onBufferReceived(byte[] buffer) {
            Dbg.log("onBufferReceived, session " + id);
        }

        @Override public void onEndOfSpeech() {
            Dbg.log("onEndOfSpeech, session " + id);
        }

        @Override public void onPartialResults(Bundle b) {
            String t = first(b);
            Dbg.log("onPartialResults, session " + id + ", len " + t.length());
            if (stale() || t.isEmpty()) return;
            lastPartial = t;
            callJs("HHcb.partial(" + JSONObject.quote(t) + ")");
        }

        @Override public void onResults(Bundle b) {
            String t = first(b);
            Dbg.log("onResults, session " + id + ", len " + t.length()
                    + ", partial len " + lastPartial.length());
            if (stale()) {
                Dbg.log("onResults dropped, session " + id + " is not " + session);
                return;
            }
            active = false;
            if (t.isEmpty()) t = lastPartial; // a final with no words still carries what was heard
            callJs("HHcb.finalText(" + JSONObject.quote(t) + ")");
            callJs("HHcb.speechState('idle')");
        }

        @Override public void onError(int error) {
            Dbg.log("onError " + error + ", session " + id);
            if (stale()) return;
            active = false;
            String msg = errorText(error);
            callJs("HHcb.speechError(" + JSONObject.quote(msg) + ")");
            if (error == SpeechRecognizer.ERROR_CLIENT
                    || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                    || error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) {
                final int mine = session;
                main.postDelayed(() -> {
                    if (mine == session) freshRecognizer();
                }, 50);
            }
        }

        @Override public void onEvent(int type, Bundle b) {
            Dbg.log("onEvent " + type + ", session " + id);
        }
    }

    private static String first(Bundle b) {
        if (b == null) return "";
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty()) return "";
        String s = list.get(0);
        return s == null ? "" : s.trim();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] granted) {
        super.onRequestPermissionsResult(code, permissions, granted);
        if (code == ASK_AUDIO) {
            boolean ok = granted.length > 0 && granted[0] == PackageManager.PERMISSION_GRANTED;
            if (ok && pendingSpeech) listen();
            else if (!ok) callJs("HHcb.speechError('microphone is off')");
            pendingSpeech = false;
        }
    }

    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, ASK_NOTIFICATIONS);
        }
    }

    private void callJs(String js) {
        if (web == null) return;
        runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript(js, null);
        });
    }
}
