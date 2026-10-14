package com.biblestudy.app;

import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

/**
 * Exposed to the web page as window.AndroidBackup.
 *
 * Why this exists: the web app's "Export" button builds a Blob and clicks
 * a hidden &lt;a download&gt; link — the normal way to trigger a download in
 * a real browser. Inside this app's WebView there's no DownloadListener
 * wired up for blob: URLs, and even where there is one, WebView's handling
 * of &lt;a download&gt; on blob: URLs is unreliable — so that click just
 * silently does nothing. The user sees no file, no error, nothing.
 *
 * This bridge instead hands the backup JSON straight to Android's own
 * "Save As" (Storage Access Framework) dialog via MainActivity.saveTextFile,
 * so the user picks exactly where it goes and gets a real save
 * confirmation, the same way the app already handles audio file/folder
 * picking (see AudioImportBridge).
 */
public class BackupBridge {

    private final MainActivity activity;
    private final WebView webView;

    BackupBridge(MainActivity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    @JavascriptInterface
    public void exportBackup(String jsonText, String suggestedName) {
        activity.runOnUiThread(() ->
                activity.saveTextFile(suggestedName, jsonText, this::notifyResult));
    }

    private void notifyResult(Uri savedUri) {
        boolean success = savedUri != null;
        String js = "window.dispatchEvent(new CustomEvent('android-export-result', { detail: { success: " + success + " } }));";
        webView.post(() -> webView.evaluateJavascript(js, null));
    }
}
