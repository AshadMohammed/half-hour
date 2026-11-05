package com.ash.halfhour;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;

/** One exact alarm on every :00 and :30, re-armed after each fire, at boot and whenever the app opens. */
final class Alarms {
    static final String ACTION = "com.ash.halfhour.PING";
    private static final int REQ = 1;

    private Alarms() {}

    static void schedule(Context ctx) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;
        long at = next(System.currentTimeMillis());
        PendingIntent pi = pi(ctx);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                // Permission refused (sideloaded apps can be denied): a normal alarm still pings, just less sharply.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    static long next(long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.get(Calendar.MINUTE) < 30) {
            c.set(Calendar.MINUTE, 30);
        } else {
            c.set(Calendar.MINUTE, 0);
            c.add(Calendar.HOUR_OF_DAY, 1);
        }
        long t = c.getTimeInMillis();
        if (t <= now + 1000) t += 30 * 60000L;
        return t;
    }

    private static PendingIntent pi(Context ctx) {
        Intent i = new Intent(ACTION, null, ctx, PingReceiver.class);
        return PendingIntent.getBroadcast(ctx, REQ, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
