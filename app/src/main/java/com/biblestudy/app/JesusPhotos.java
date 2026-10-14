package com.biblestudy.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;

import java.io.InputStream;

/**
 * Jesus photos shown instead of the old owl mascot (widget + notifications).
 * Same photo table as assets/www/streak.js: index = mood (0 happy, 1 worried, 2 sad, 3 crying),
 * numbers are the file numbers in assets/www/jesus/jNN.jpg.
 */
final class JesusPhotos {

    private static final int[][] BOY = {{1, 2, 4}, {3, 13}, {12, 9, 10}, {16, 11, 18}};
    private static final int[][] GIRL = {{7}, {5}, {17, 15, 10}, {19, 8, 6}};

    private JesusPhotos() { }

    static String gender(Context c) {
        return "girl".equals(StreakStore.sp(c).getString("gender", "boy")) ? "girl" : "boy";
    }

    static void setGender(Context c, String g) {
        StreakStore.sp(c).edit().putString("gender", "girl".equals(g) ? "girl" : "boy").apply();
    }

    static String assetPath(Context c, int mood) {
        int[][] t = "girl".equals(gender(c)) ? GIRL : BOY;
        int[] list = t[Math.max(0, Math.min(3, mood))];
        long day = StreakStore.today();
        int n = list[(int) (Math.abs(day) % list.length)];
        return String.format("www/jesus/j%02d.jpg", n);
    }

    /** Rounded-corner square crop of today's photo for this mood, or null if it cannot be read. */
    static Bitmap load(Context c, int mood, int sizePx) {
        try {
            Bitmap src;
            try (InputStream in = c.getAssets().open(assetPath(c, mood))) {
                src = BitmapFactory.decodeStream(in);
            }
            if (src == null) return null;
            int side = Math.min(src.getWidth(), src.getHeight());
            int x = (src.getWidth() - side) / 2;
            int y = Math.max(0, Math.min(src.getHeight() - side, (int) ((src.getHeight() - side) * 0.4f)));
            Bitmap sq = Bitmap.createBitmap(src, x, y, side, side);
            Bitmap scaled = Bitmap.createScaledBitmap(sq, sizePx, sizePx, true);
            Bitmap out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(out);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            float r = sizePx * 0.18f;
            cv.drawRoundRect(new RectF(0, 0, sizePx, sizePx), r, r, p);
            p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
            cv.drawBitmap(scaled, 0, 0, p);
            return out;
        } catch (Exception e) {
            return null;
        }
    }
}
