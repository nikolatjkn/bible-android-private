package com.biblestudy.app;

import android.content.Intent;
import android.webkit.JavascriptInterface;

/**
 * Exposed to the web page as window.AndroidPdf. The three Tamil PDF books
 * (book introductions, THNGV Tamil Bible, Torah Tail Bible) are bundled as
 * exact copies in assets/pdfs and opened in PdfViewerActivity, so every page,
 * explanation and verse is shown exactly as in the original files.
 */
public class PdfBridge {
    private final MainActivity activity;

    PdfBridge(MainActivity activity) {
        this.activity = activity;
    }

    /** key: "intro" | "thngv" | "torah" */
    @JavascriptInterface
    public void open(final String key) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Intent i = new Intent(activity, PdfViewerActivity.class);
                i.putExtra(PdfViewerActivity.EXTRA_KEY, key);
                activity.startActivity(i);
            }
        });
    }
}
