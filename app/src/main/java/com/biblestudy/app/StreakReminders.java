package com.biblestudy.app;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.util.Calendar;

/** Schedules the daily verse / evening nudge and builds their notifications. */
final class StreakReminders {

    static final String ACTION_MORNING = "com.biblestudy.app.VERSE_MORNING";
    static final String ACTION_EVENING = "com.biblestudy.app.VERSE_EVENING";
    private static final String CHANNEL = "daily_verse";
    static final int ID_MORNING = 7001, ID_EVENING = 7002, ID_TEST = 7003;

    private StreakReminders() { }

    static void scheduleAll(Context c) {
        schedule(c, ACTION_MORNING, 101, StreakStore.morningOn(c), StreakStore.morningH(c), StreakStore.morningM(c));
        schedule(c, ACTION_EVENING, 102, StreakStore.eveningOn(c), StreakStore.eveningH(c), StreakStore.eveningM(c));
    }

    static void scheduleOne(Context c, String action) {
        if (ACTION_MORNING.equals(action)) {
            schedule(c, action, 101, StreakStore.morningOn(c), StreakStore.morningH(c), StreakStore.morningM(c));
        } else if (ACTION_EVENING.equals(action)) {
            schedule(c, action, 102, StreakStore.eveningOn(c), StreakStore.eveningH(c), StreakStore.eveningM(c));
        }
    }

    private static void schedule(Context c, String action, int req, boolean on, int h, int m) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(c, VerseReceiver.class).setAction(action);
        PendingIntent pi = PendingIntent.getBroadcast(c, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (!on) { am.cancel(pi); return; }
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, h);
        cal.set(Calendar.MINUTE, m);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis() + 1000) cal.add(Calendar.DAY_OF_MONTH, 1);
        // Inexact on purpose: no special "exact alarm" permission needed.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
    }

    static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Daily verse", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Verse of the day and reading reminders");
            ch.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        }
    }

    static void show(Context c, int id, String title, String text, int mood) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ensureChannel(c);
        Intent open = new Intent(c, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 300 + id, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setColor(0xFF2FBF71)
                .setContentIntent(pi);
        Bitmap big = JesusPhotos.load(c, mood, 256);
        if (big != null) b.setLargeIcon(big);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(id, b.build());
    }

    static void showMorning(Context c, int id) {
        StreakStore.Verse v = StreakStore.todaysVerse(c);
        String title = "\uD83D\uDCD6 Verse of the day" + (v.ref.isEmpty() ? "" : " \u00B7 " + v.ref);
        String text = v.text.isEmpty() ? "Open the app and read today's verse." : v.text;
        show(c, id, title, text, StreakStore.MOOD_HAPPY);
    }

    static void showEvening(Context c, int id) {
        StreakStore.State st = StreakStore.state(c);
        StreakStore.Verse v = StreakStore.todaysVerse(c);
        String face = st.mood == StreakStore.MOOD_WORRIED ? "\uD83D\uDE1F " :
                st.mood == StreakStore.MOOD_SAD ? "\uD83D\uDE41 " :
                st.mood == StreakStore.MOOD_CRYING ? "\uD83D\uDE22 " : "\uD83D\uDE42 ";
        String text = st.sub + (v.text.isEmpty() ? "" : "\n\n\u201C" + v.text + "\u201D " + v.ref);
        show(c, id, face + st.title, text, st.mood);
    }
}
