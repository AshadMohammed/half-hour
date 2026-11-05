package com.ash.halfhour;

import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

/** The whole JS surface: all strings, in and out. */
final class Bridge {
    private final MainActivity app;

    Bridge(MainActivity app) {
        this.app = app;
    }

    @JavascriptInterface
    public String entries(String days) {
        int n = 8;
        try {
            n = Integer.parseInt(days);
        } catch (Exception ignored) {
        }
        JSONArray a = Data.entries(app, n);
        return a.toString();
    }

    @JavascriptInterface
    public String save(String json) {
        try {
            JSONObject in = new JSONObject(json);
            Dbg.log("save block " + in.optString("block") + ", score " + in.optInt("score", -1)
                    + ", text len " + in.optString("text").length() + ", plan len " + in.optString("plan").length()
                    + ", via " + in.optString("via"));
            String out = Data.save(app, in).toString();
            // the open ping is about the half hour that just ended; once that one is logged, clear it
            if (in.optString("block").equals(Data.endedBlock(System.currentTimeMillis()))) Notify.cancel(app);
            return out;
        } catch (Exception e) {
            Dbg.log("save failed: " + e);
            return "{}";
        }
    }

    @JavascriptInterface
    public String last() {
        JSONObject o = Data.last(app);
        return o == null ? "" : o.toString();
    }

    /** "top,right,bottom,left" in CSS px, live from the window insets. */
    @JavascriptInterface
    public String insets() {
        return app.insets();
    }

    @JavascriptInterface
    public String plan(String block) {
        return Data.plan(app, block);
    }

    @JavascriptInterface
    public String setPlan(String block, String text) {
        Data.setPlan(app, block, text);
        return "ok";
    }

    @JavascriptInterface
    public void startSpeech() {
        app.startSpeech();
    }

    @JavascriptInterface
    public void stopSpeech() {
        app.stopSpeech();
    }

    @JavascriptInterface
    public void haptic(String kind) {
        app.haptic(kind);
    }

    @JavascriptInterface
    public String currentBlock() {
        return Data.nowBlock();
    }

    /** Quiet hours as "2-10", for the today dial's quiet band. */
    @JavascriptInterface
    public String quiet() {
        return Data.quietStart(app) + "-" + Data.quietEnd(app);
    }

    /** The half hour the notification was about, or "" when the app was opened normally. */
    @JavascriptInterface
    public String openBlock() {
        return app.openBlock();
    }

    @JavascriptInterface
    public void screen(String name) {
        app.setScreen(name);
    }
}
