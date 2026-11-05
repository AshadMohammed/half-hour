package com.ash.halfhour;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Debug log: every line goes to logcat (tag "halfhour") and to files/debug.log, so a problem can be
 * read later with: adb pull /sdcard/Android/data/com.ash.halfhour/files/debug.log
 * The file rolls over to debug.old.log at 512 KB.
 */
final class Dbg {
    private static File file;
    private static final SimpleDateFormat TIME = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private Dbg() {}

    static synchronized void init(Context ctx) {
        if (file != null) return;
        File dir = ctx.getApplicationContext().getExternalFilesDir(null);
        if (dir != null) file = new File(dir, "debug.log");
    }

    static void log(Context ctx, String msg) {
        init(ctx);
        log(msg);
    }

    static synchronized void log(String msg) {
        Log.d("halfhour", msg);
        if (file == null) return;
        try {
            if (file.length() > 512 * 1024) {
                File old = new File(file.getParentFile(), "debug.old.log");
                old.delete();
                file.renameTo(old);
            }
            FileWriter w = new FileWriter(file, true);
            w.write(TIME.format(new Date()) + "  " + msg + "\n");
            w.close();
        } catch (Exception ignored) {
        }
    }
}
