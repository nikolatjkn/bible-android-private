package com.biblestudy.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;

/**
 * Reading-streak state shared by the web page (via StreakBridge), the
 * home-screen widget and the notifications. Everything lives in
 * SharedPreferences so the widget/alarms work even when the app is closed.
 */
final class StreakStore {

    private static final String PREF = "streak_prefs";

    static final int MOOD_HAPPY = 0, MOOD_WORRIED = 1, MOOD_SAD = 2, MOOD_CRYING = 3;

    static final class State {
        int mood;
        String title;   // used by notifications
        String sub;
        int num;        // big number on the widget
        String label;   // text next to the number
        boolean flameOn;
    }

    static final class Verse {
        String ref = "";
        String text = "";
    }

    private StreakStore() { }

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** Days since epoch in the phone's local time zone. */
    static long today() {
        long now = System.currentTimeMillis();
        return (now + TimeZone.getDefault().getOffset(now)) / 86400000L;
    }

    static boolean readToday(Context c) {
        return sp(c).getLong("last", -1) == today();
    }

    /** Returns true if this call was the first read of the day. */
    static boolean markRead(Context c) {
        SharedPreferences p = sp(c);
        long t = today();
        long last = p.getLong("last", -1);
        if (last == t) return false;
        int streak = p.getInt("streak", 0);
        streak = (last == t - 1) ? streak + 1 : 1;
        int best = Math.max(p.getInt("best", 0), streak);
        int total = p.getInt("total", 0) + 1;
        StringBuilder sb = new StringBuilder();
        String[] old = p.getString("days", "").split(",");
        for (int i = Math.max(0, old.length - 59); i < old.length; i++) {
            if (old[i].isEmpty()) continue;
            sb.append(old[i]).append(',');
        }
        sb.append(t);
        p.edit().putLong("last", t).putInt("streak", streak).putInt("best", best)
                .putInt("total", total).putString("days", sb.toString()).apply();
        return true;
    }

    /** Recent days (epoch day numbers) on which something was read. */
    static Set<Long> recentDays(Context c) {
        Set<Long> out = new HashSet<>();
        for (String d : sp(c).getString("days", "").split(",")) {
            if (d.isEmpty()) continue;
            try { out.add(Long.parseLong(d)); } catch (Exception ignored) { }
        }
        return out;
    }

    static State state(Context c) {
        SharedPreferences p = sp(c);
        long t = today();
        long last = p.getLong("last", -1);
        int streak = p.getInt("streak", 0);
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        State s = new State();
        s.mood = MOOD_HAPPY;
        if (last < 0) {
            s.num = 0; s.label = "day streak"; s.flameOn = false;
            s.title = "Start your streak";
            s.sub = "Read today's verse to begin";
            return s;
        }
        long missed = t - last;
        if (missed <= 0) {
            s.num = streak; s.label = "day streak"; s.flameOn = true;
            s.title = "\uD83D\uDD25 " + streak + " day streak";
            s.sub = "Done for today. Well done!";
        } else if (missed == 1) {
            s.num = streak; s.label = "day streak"; s.flameOn = true;
            if (hour >= 18) {
                s.mood = MOOD_WORRIED;
                s.title = "Streak in danger!";
                s.sub = "Read now to save your streak";
            } else {
                s.title = "\uD83D\uDD25 " + streak + " day streak";
                s.sub = "Read today to keep it going";
            }
        } else if (missed <= 3) {
            s.mood = MOOD_SAD; s.num = (int) missed; s.label = "days missed"; s.flameOn = false;
            s.title = missed + " days missed";
            s.sub = "Come back and read a verse";
        } else {
            s.mood = MOOD_CRYING; s.num = (int) missed; s.label = "days abandoned"; s.flameOn = false;
            s.title = missed + " days abandoned";
            s.sub = "Your Bible misses you";
        }
        return s;
    }

    // ---- verses (pushed in by the web page, refreshed each app launch) ----

    static void saveVerses(Context c, String json) {
        sp(c).edit().putString("verses", json).apply();
    }

    static Verse todaysVerse(Context c) {
        Verse v = new Verse();
        try {
            JSONArray arr = new JSONArray(sp(c).getString("verses", "[]"));
            if (arr.length() == 0) return v;
            long t = today();
            JSONObject pick = null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (o.optLong("d", -1) == t) { pick = o; break; }
            }
            // Not refreshed for a while (app not opened): keep cycling the saved ones.
            if (pick == null) pick = arr.getJSONObject((int) (t % arr.length()));
            v.ref = pick.optString("ref", "");
            v.text = pick.optString("text", "");
        } catch (Exception ignored) { }
        return v;
    }

    // ---- reminder settings ----

    static boolean morningOn(Context c) { return sp(c).getBoolean("morning_on", true); }
    static int morningH(Context c) { return sp(c).getInt("morning_h", 7); }
    static int morningM(Context c) { return sp(c).getInt("morning_m", 0); }
    static boolean eveningOn(Context c) { return sp(c).getBoolean("evening_on", true); }
    static int eveningH(Context c) { return sp(c).getInt("evening_h", 20); }
    static int eveningM(Context c) { return sp(c).getInt("evening_m", 0); }

    static void saveConfig(Context c, String json) throws Exception {
        JSONObject o = new JSONObject(json);
        SharedPreferences.Editor e = sp(c).edit();
        if (o.has("morning")) e.putBoolean("morning_on", o.getBoolean("morning"));
        if (o.has("mh")) e.putInt("morning_h", o.getInt("mh"));
        if (o.has("mm")) e.putInt("morning_m", o.getInt("mm"));
        if (o.has("evening")) e.putBoolean("evening_on", o.getBoolean("evening"));
        if (o.has("eh")) e.putInt("evening_h", o.getInt("eh"));
        if (o.has("em")) e.putInt("evening_m", o.getInt("em"));
        e.apply();
    }

    static String configJson(Context c) {
        try {
            SharedPreferences p = sp(c);
            State st = state(c);
            JSONObject o = new JSONObject();
            o.put("morning", morningOn(c)); o.put("mh", morningH(c)); o.put("mm", morningM(c));
            o.put("evening", eveningOn(c)); o.put("eh", eveningH(c)); o.put("em", eveningM(c));
            boolean alive = p.getLong("last", -1) >= today() - 1;
            o.put("streak", alive ? p.getInt("streak", 0) : 0);
            o.put("best", p.getInt("best", 0));
            o.put("total", p.getInt("total", 0));
            o.put("mood", st.mood);
            o.put("label", st.label);
            o.put("num", st.num);
            o.put("sub", st.sub);
            o.put("today", today());
            o.put("readToday", readToday(c));
            JSONArray days = new JSONArray();
            for (Long d : recentDays(c)) days.put(d.longValue());
            o.put("days", days);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
