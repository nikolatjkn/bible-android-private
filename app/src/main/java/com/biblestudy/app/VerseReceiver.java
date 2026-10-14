package com.biblestudy.app;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fires at the reminder times, and re-arms everything after reboot / app update. */
public class VerseReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) return;
        try {
            if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                    || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
                StreakReminders.scheduleAll(context);
            } else if (StreakReminders.ACTION_MORNING.equals(action)) {
                StreakReminders.scheduleOne(context, action);
                if (!StreakStore.readToday(context)) {
                    StreakReminders.showMorning(context, StreakReminders.ID_MORNING);
                }
            } else if (StreakReminders.ACTION_EVENING.equals(action)) {
                StreakReminders.scheduleOne(context, action);
                if (!StreakStore.readToday(context)) {
                    StreakReminders.showEvening(context, StreakReminders.ID_EVENING);
                } else {
                    NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) nm.cancel(StreakReminders.ID_EVENING);
                }
            }
        } catch (Exception ignored) { }
        StreakWidgetProvider.refreshAll(context);
    }
}
