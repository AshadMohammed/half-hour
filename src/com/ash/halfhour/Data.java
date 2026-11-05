package com.ash.halfhour;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything that is stored: one JSON object per line in log.jsonl, plus the small settings
 * (quiet hours, plans) in SharedPreferences. A later line for the same half hour
 * replaces the earlier one, so the file is an append-only history.
 */
final class Data {
    static final String PREF = "halfhour";
    static final String KEY_QUIET_START = "quiet_start";
    static final String KEY_QUIET_END = "quiet_end";
    static final String PLAN = "plan:";

    private static final String BLOCK_FMT = "yyyy-MM-dd'T'HH:mm";
    private static final Pattern PLAN_RE = Pattern.compile(
            "(?:^|\\s)(?:for\\s+)?(?:the\\s+)?next\\s+(?:half\\s*hour|30\\s*min(?:ute)?s?)\\b\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);

    private Data() {}

    // ---- blocks and time ----

    static String block(long ms) {
        return new SimpleDateFormat(BLOCK_FMT, Locale.US).format(new Date(ms));
    }

    static String nowBlock() {
        return block(System.currentTimeMillis());
    }

    static long parse(String block) {
        try {
            return new SimpleDateFormat(BLOCK_FMT, Locale.US).parse(block).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    static String shift(String block, int minutes) {
        return block(parse(block) + minutes * 60000L);
    }

    /** The half hour that has just ended, for a ping that fires on a :00 or :30 boundary. */
    static String endedBlock(long now) {
        long t = now - 30 * 60000L;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.get(Calendar.MINUTE) >= 30) c.set(Calendar.MINUTE, 30); else c.set(Calendar.MINUTE, 0);
        return block(c.getTimeInMillis());
    }

    /** "3:00 – 3:30 pm" */
    static String range(String block) {
        long s = parse(block);
        long e = s + 30 * 60000L;
        return clock(s) + " – " + clock(e) + " " + ampm(e);
    }

    static String clock(long ms) {
        return new SimpleDateFormat("h:mm", Locale.US).format(new Date(ms));
    }

    static String ampm(long ms) {
        return new SimpleDateFormat("a", Locale.US).format(new Date(ms)).toLowerCase(Locale.US);
    }

    static String isoNow() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(new Date());
    }

    // ---- the log file ----

    static File file(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) dir = ctx.getFilesDir();
        return new File(dir, "log.jsonl");
    }

    static List<JSONObject> readAll(Context ctx) {
        Map<String, JSONObject> byBlock = new LinkedHashMap<>();
        File f = file(ctx);
        if (f.exists()) {
            BufferedReader r = null;
            try {
                r = new BufferedReader(new FileReader(f));
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    try {
                        JSONObject o = new JSONObject(line);
                        String b = o.optString("block", "");
                        if (!b.isEmpty()) {
                            // Fix a bad plan on read, without rewriting the log file.
                            String p = cleanPlan(o.optString("plan", ""));
                            o.put("plan", p.isEmpty() ? JSONObject.NULL : p);
                            int score = o.optInt("score", 0);
                            if (o.optInt("scale", 0) == 5) {
                                score = Math.max(1, Math.min(5, score));
                            } else {
                                score = Math.max(1, (Math.max(0, Math.min(10, score)) + 1) / 2);
                            }
                            o.put("score", score);
                            o.put("scale", 5);
                            byBlock.put(b, o); // a later line wins
                        }
                    } catch (Exception ignored) {
                        // a half-written line is not worth losing the rest of the day over
                    }
                }
            } catch (Exception ignored) {
            } finally {
                try { if (r != null) r.close(); } catch (Exception ignored) {}
            }
        }
        List<JSONObject> list = new ArrayList<>(byBlock.values());
        list.sort((a, b) -> a.optString("block").compareTo(b.optString("block")));
        return list;
    }

    static JSONArray entries(Context ctx, int days) {
        JSONArray out = new JSONArray();
        if (days < 1) days = 1;
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, -(days - 1));
        String from = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.getTime());
        for (JSONObject o : readAll(ctx)) {
            String b = o.optString("block", "");
            if (b.length() >= 10 && b.substring(0, 10).compareTo(from) >= 0) out.put(o);
        }
        return out;
    }

    static JSONObject get(Context ctx, String block) {
        if (block == null || block.isEmpty()) return null;
        for (JSONObject o : readAll(ctx)) {
            if (block.equals(o.optString("block"))) return o;
        }
        return null;
    }

    static JSONObject last(Context ctx) {
        List<JSONObject> all = readAll(ctx);
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    /** The most recent entry before this half hour, which is what "last" means in a ping. */
    static JSONObject lastBefore(Context ctx, String block) {
        JSONObject best = null;
        for (JSONObject o : readAll(ctx)) {
            String b = o.optString("block", "");
            if (b.compareTo(block) < 0 && (best == null || b.compareTo(best.optString("block")) > 0)) best = o;
        }
        return best;
    }

    /** Writes one line and hands back what was written, so the page can show the activity it decided. */
    static JSONObject save(Context ctx, JSONObject in) {
        String block = in.optString("block", "");
        if (block.isEmpty()) block = nowBlock();
        String text = in.optString("text", "").trim();
        if (text.isEmpty()) text = "logged";
        int score = Math.max(1, Math.min(5, in.optInt("score", 3)));
        String plan = cleanPlan(in.optString("plan", ""));
        String via = in.optString("via", "app");
        if (!"notif-same".equals(via) && !"notif-reply".equals(via)) via = "app";

        JSONObject o = new JSONObject();
        try {
            o.put("block", block);
            o.put("text", text);
            o.put("score", score);
            o.put("scale", 5);
            o.put("activity", activity(text));
            o.put("plan", plan.isEmpty() ? JSONObject.NULL : plan);
            o.put("via", via);
            o.put("at", isoNow());
            append(ctx, o.toString());
            if (!plan.isEmpty()) setPlan(ctx, shift(block, 30), plan);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static void append(Context ctx, String line) {
        File f = file(ctx);
        try {
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            BufferedWriter w = new BufferedWriter(new FileWriter(f, true));
            try {
                w.write(line);
                w.newLine();
            } finally {
                w.close();
            }
        } catch (Exception e) {
            Dbg.log(ctx, "append failed: " + e);
        }
    }

    /** If the log was replaced from outside (adb push), copy it so this app owns it again and can keep appending. */
    static void healOwner(Context ctx) {
        File f = file(ctx);
        try {
            if (!f.exists() || android.system.Os.stat(f.getPath()).st_uid == android.os.Process.myUid()) return;
            File tmp = new File(f.getParentFile(), "log.jsonl.tmp");
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            out.close();
            if (tmp.length() == f.length() && f.delete() && tmp.renameTo(f)) Dbg.log(ctx, "log file owner healed");
            else Dbg.log(ctx, "log file heal skipped, sizes " + tmp.length() + "/" + f.length());
        } catch (Exception e) {
            Dbg.log(ctx, "log file heal failed: " + e);
        }
    }

    // ---- activity ----

    /** The small keyword map from the brief, else the first word. */
    static String activity(String text) {
        String[] words = text.toLowerCase(Locale.US).split("[^a-z0-9']+");
        String first = "";
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (first.isEmpty()) first = w;
            if (starts(w, "sleep")) return "sleep";
            if (starts(w, "grocer") || starts(w, "groceries") || starts(w, "shop")) return "groceries";
            if (starts(w, "clean") || starts(w, "trash") || starts(w, "dish") || starts(w, "laundry")
                    || starts(w, "vacuum") || starts(w, "tidy")) return "chores";
            if (starts(w, "cook") || starts(w, "meal") || starts(w, "breakfast")
                    || starts(w, "lunch") || starts(w, "dinner")) return "cooking";
            if (starts(w, "gym") || starts(w, "walk") || starts(w, "run") || starts(w, "exercise")
                    || starts(w, "yoga") || starts(w, "cycl") || starts(w, "stretch")) return "exercise";
            if (starts(w, "movie") || starts(w, "film")) return "movies";
            if (starts(w, "chess")) return "chess";
            if (starts(w, "build") || starts(w, "code") || starts(w, "coding")
                    || starts(w, "design")) return "building";
        }
        return first;
    }

    private static boolean starts(String word, String prefix) {
        return word.startsWith(prefix);
    }

    // ---- settings ----

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    static int quietStart(Context ctx) {
        return prefs(ctx).getInt(KEY_QUIET_START, 2);
    }

    static int quietEnd(Context ctx) {
        return prefs(ctx).getInt(KEY_QUIET_END, 10);
    }

    static boolean quiet(Context ctx, Calendar at) {
        int a = quietStart(ctx), b = quietEnd(ctx);
        int h = at.get(Calendar.HOUR_OF_DAY);
        if (a == b) return false;
        return a < b ? (h >= a && h < b) : (h >= a || h < b);
    }

    static String plan(Context ctx, String block) {
        String key = PLAN + block;
        String p = cleanPlan(prefs(ctx).getString(key, ""));
        if (p.isEmpty() && prefs(ctx).contains(key)) {
            // an older build stored the word "null" here; drop it as it is read
            prefs(ctx).edit().remove(key).apply();
        }
        if (!p.isEmpty()) return p;
        JSONObject prev = get(ctx, shift(block, -30));
        return prev == null ? "" : cleanPlan(prev.optString("plan", ""));
    }

    static void setPlan(Context ctx, String block, String text) {
        if (block == null || block.isEmpty()) return;
        String clean = cleanPlan(text);
        if (clean.isEmpty()) {
            prefs(ctx).edit().remove(PLAN + block).apply();
        } else {
            prefs(ctx).edit().putString(PLAN + block, clean).apply();
        }
    }

    /**
     * One reader for every plan: JSON null, "", "null" and whitespace all mean "no plan".
     * {@code JSONObject.optString} hands back the four letters "null" for a stored JSON null.
     */
    static String cleanPlan(String plan) {
        if (plan == null) return "";
        String s = plan.trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return "";
        return s;
    }

    /** "cooking" -> "cook", so a ping can ask "did you cook?" */
    static String verb(String plan) {
        String p = plan.trim().toLowerCase(Locale.US);
        if (p.startsWith("go to the ")) p = p.substring(10);
        switch (p) {
            case "movies": case "movie": case "film": case "a film": return "watch a film";
            case "gym": return "go to the gym";
            case "chess": return "play chess";
            case "groceries": return "get groceries";
            case "chores": return "do chores";
            case "exercise": return "exercise";
            case "sleep": return "sleep";
            case "cooking": return "cook";
            case "building": return "build";
            case "coding": return "code";
            case "working": return "work";
            case "cleaning": return "clean";
            case "reading": return "read";
            case "writing": return "write";
            case "watching": return "watch";
            case "eating": return "eat";
            case "walking": return "walk";
            case "running": return "run";
            case "shopping": return "shop";
            default:
                if (p.endsWith("ing") && p.length() > 5) return p.substring(0, p.length() - 3);
                return p;
        }
    }

    // ---- plan in a spoken line ----

    /** Returns {entryText, plan} — plan is "" when the sentence holds no plan. */
    static String[] splitPlan(String said) {
        String s = said.trim();
        Matcher m = PLAN_RE.matcher(s);
        if (!m.find()) return new String[] {s, ""};
        String plan = m.group(1).trim();
        plan = plan.replaceFirst("(?i)^(?:i(?:'m| am)?\\s*(?:going\\s+to|gonna|will|'ll)?\\s*)", "");
        plan = plan.replaceFirst("(?i)^to\\s+", "");
        plan = plan.replaceAll("[.,;!?\\s]+$", "").trim();
        if (plan.isEmpty()) return new String[] {s, ""};
        String before = s.substring(0, m.start()).trim().replaceAll("[.,;\\s]+$", "");
        return new String[] {before, plan};
    }
}
