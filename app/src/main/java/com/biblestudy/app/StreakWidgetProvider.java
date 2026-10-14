package com.biblestudy.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import java.util.Calendar;
import java.util.Set;

/** Home-screen widget: streak number, week row and a Jesus photo whose mood follows your reading. */
public class StreakWidgetProvider extends AppWidgetProvider {

    private static final int[] BGS = {
            R.drawable.streak_bg_happy, R.drawable.streak_bg_worried,
            R.drawable.streak_bg_sad, R.drawable.streak_bg_crying};
    private static final int[] GLOWS = {
            R.drawable.streak_glow_happy, R.drawable.streak_glow_worried,
            R.drawable.streak_glow_sad, R.drawable.streak_glow_crying};
    private static final int[] WK = {R.id.wk0, R.id.wk1, R.id.wk2, R.id.wk3, R.id.wk4, R.id.wk5, R.id.wk6};
    private static final int[] WL = {R.id.wl0, R.id.wl1, R.id.wl2, R.id.wl3, R.id.wl4, R.id.wl5, R.id.wl6};

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        RemoteViews rv = build(context);
        for (int id : ids) mgr.updateAppWidget(id, rv);
    }

    static RemoteViews build(Context ctx) {
        StreakStore.State st = StreakStore.state(ctx);
        StreakStore.Verse v = StreakStore.todaysVerse(ctx);
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_streak);
        rv.setInt(R.id.widget_root, "setBackgroundResource", BGS[st.mood]);
        rv.setInt(R.id.widget_char_wrap, "setBackgroundResource", GLOWS[st.mood]);
        android.graphics.Bitmap face = JesusPhotos.load(ctx, st.mood, 300);
        if (face != null) rv.setImageViewBitmap(R.id.widget_char, face);
        rv.setImageViewResource(R.id.widget_flame, st.flameOn ? R.drawable.flame_on : R.drawable.flame_off);
        rv.setTextViewText(R.id.widget_num, String.valueOf(st.num));
        rv.setTextViewText(R.id.widget_label, st.label);
        rv.setTextViewText(R.id.widget_sub, st.sub);

        // Week row (Monday to Sunday of the current week)
        Set<Long> days = StreakStore.recentDays(ctx);
        long today = StreakStore.today();
        int dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK); // Sunday = 1
        int idx = (dow + 5) % 7;                                    // Monday = 0
        for (int i = 0; i < 7; i++) {
            long d = today - idx + i;
            int res;
            if (days.contains(d)) res = R.drawable.dot_done;
            else if (d == today) res = R.drawable.dot_today;
            else if (d < today) res = R.drawable.dot_miss;
            else res = R.drawable.dot_future;
            rv.setImageViewResource(WK[i], res);
            rv.setTextColor(WL[i], d == today ? 0xFFFFFFFF : 0x80FFFFFF);
        }

        String verse = v.text.isEmpty() ? "" : "\u201C" + v.text + "\u201D " + v.ref;
        rv.setTextViewText(R.id.widget_verse, verse);
        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 200, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.widget_root, pi);
        return rv;
    }

    /** Redraw every placed widget (after reading, at reminder times, on boot). */
    static void refreshAll(Context ctx) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, StreakWidgetProvider.class));
            if (ids != null && ids.length > 0) mgr.updateAppWidget(ids, build(ctx));
        } catch (Exception ignored) { }
    }
}
