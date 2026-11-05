package com.ash.halfhour;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;

import org.json.JSONObject;

/** The one notification: the question and the last entry. A tap opens log on that half hour. */
final class Notify {
    static final String CHANNEL = "pings";
    static final int ID = 1;
    static final String KEY_REPLY = "reply";
    static final String REPLY = "com.ash.halfhour.REPLY";
    static final String SAME = "com.ash.halfhour.SAME";
    static final String BLOCK = "block";

    private Notify() {}

    static void ensureChannel(Context ctx) {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL, "Pings", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("every half hour, what were you doing?");
        ch.enableVibration(true);
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    static void post(Context ctx, String block) {
        ensureChannel(ctx);
        JSONObject last = Data.lastBefore(ctx, block);
        String lastText = last == null
                ? "last: nothing logged yet"
                : "last: " + last.optString("text") + " · " + last.optInt("score");

        String plan = Data.plan(ctx, block);
        String title = plan.isEmpty()
                ? Data.range(block) + " · what were you doing?"
                : "did you " + Data.verb(plan) + "?";

        Notification.Builder b = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_halfhour)
                .setColor(0xFFFF5A1F)
                .setContentTitle(title)
                .setContentText(lastText)
                .setStyle(new Notification.BigTextStyle().bigText(lastText))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(open(ctx, block)); // no buttons: a tap opens log on this half hour

        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(ID, b.build());
    }

    static void cancel(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(ID);
    }

    static PendingIntent open(Context ctx, String block) {
        Intent i = new Intent(ctx, MainActivity.class)
                .putExtra(BLOCK, block)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(ctx, 12, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static PendingIntent same(Context ctx, String block) {
        return broadcast(ctx, SAME, block, 10, false);
    }

    static PendingIntent reply(Context ctx, String block) {
        // Inline reply hands the spoken text back through the intent, so this one must be mutable.
        return broadcast(ctx, REPLY, block, 11, true);
    }

    private static PendingIntent broadcast(Context ctx, String action, String block, int req, boolean mutable) {
        Intent i = new Intent(action, null, ctx, ActionReceiver.class)
                .putExtra(BLOCK, block);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (mutable ? PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_IMMUTABLE);
        return PendingIntent.getBroadcast(ctx, req, i, flags);
    }
}
