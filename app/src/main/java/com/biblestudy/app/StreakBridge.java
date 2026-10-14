package com.biblestudy.app;

import android.appwidget.AppWidgetManager;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.webkit.JavascriptInterface;

/** Exposed to the web page as window.AndroidStreak. */
public class StreakBridge {

    private final Context ctx;

    StreakBridge(MainActivity activity) {
        this.ctx = activity.getApplicationContext();
    }

    /** The page calls this once the user has actually read something today. */
    @JavascriptInterface
    public void markRead() {
        if (StreakStore.markRead(ctx)) {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(StreakReminders.ID_EVENING);
        }
        StreakWidgetProvider.refreshAll(ctx);
    }

    /** JSON array: [{"d":epochDay,"ref":"John 3:16","text":"..."}, ...] for the next ~30 days. */
    @JavascriptInterface
    public void setVerses(String json) {
        StreakStore.saveVerses(ctx, json);
        StreakWidgetProvider.refreshAll(ctx);
    }

    @JavascriptInterface
    public void configure(String json) {
        try {
            StreakStore.saveConfig(ctx, json);
        } catch (Exception ignored) { }
        StreakReminders.scheduleAll(ctx);
    }

    @JavascriptInterface
    public String getConfig() {
        return StreakStore.configJson(ctx);
    }

    @JavascriptInterface
    public void testNotification() {
        StreakReminders.showMorning(ctx, StreakReminders.ID_TEST);
    }

    /** Asks the launcher to pin the widget. Returns false if the launcher can't do that. */
    @JavascriptInterface
    public boolean requestPinWidget() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            if (!mgr.isRequestPinAppWidgetSupported()) return false;
            return mgr.requestPinAppWidget(new ComponentName(ctx, StreakWidgetProvider.class), null, null);
        } catch (Exception e) {
            return false;
        }
    }
}
