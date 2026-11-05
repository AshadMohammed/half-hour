package com.ash.halfhour;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

/** The notification's same again and inline spoken reply. Both log silently and dismiss the ping. */
public final class ActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        String block = intent.getStringExtra(Notify.BLOCK);
        if (block == null || block.isEmpty()) block = Data.endedBlock(System.currentTimeMillis());
        Dbg.log(ctx, "notification action " + action + ", block " + block);

        if (Notify.SAME.equals(action)) {
            JSONObject last = Data.lastBefore(ctx, block);
            if (last != null) {
                save(ctx, block, last.optString("text"), last.optInt("score"), "", "notif-same");
            }
            Notify.cancel(ctx);
        } else if (Notify.REPLY.equals(action)) {
            CharSequence said = null;
            android.os.Bundle results = android.app.RemoteInput.getResultsFromIntent(intent);
            if (results != null) said = results.getCharSequence(Notify.KEY_REPLY);
            String text = said == null ? "" : said.toString().trim();
            if (!text.isEmpty()) {
                String[] parts = Data.splitPlan(text);
                JSONObject last = Data.lastBefore(ctx, block);
                int score = last == null ? 3 : last.optInt("score", 3);
                save(ctx, block, parts[0].isEmpty() ? text : parts[0], score, parts[1], "notif-reply");
            }
            Notify.cancel(ctx);
        }
    }

    private static void save(Context ctx, String block, String text, int score, String plan, String via) {
        try {
            JSONObject o = new JSONObject();
            o.put("block", block);
            o.put("text", text);
            o.put("score", score);
            o.put("plan", plan);
            o.put("via", via);
            Data.save(ctx, o);
        } catch (Exception ignored) {
        }
    }
}
