package com.ash.halfhour;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.Calendar;

/** Fires on the half hour, re-arms the next one, then posts the ping unless it's sleep time or already logged. */
public final class PingReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        Dbg.log(ctx, "ping fired");
        Alarms.schedule(ctx); // first, so a failure below never silences the next one

        long now = System.currentTimeMillis();
        String block = Data.endedBlock(now);
        Calendar at = Calendar.getInstance();
        at.setTimeInMillis(Data.parse(block));
        if (Data.quiet(ctx, at)) { Dbg.log("ping skipped: quiet hours, block " + block); return; } // never ask about a half hour spent asleep

        if (Data.get(ctx, block) != null) { Dbg.log("ping skipped: already logged, block " + block); return; }

        Notify.post(ctx, block);
    }
}
