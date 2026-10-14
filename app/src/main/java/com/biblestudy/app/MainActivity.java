package com.biblestudy.app;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebViewAssetLoader;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private View loadingOverlay;
    private AudioBridge audioBridge;
    private boolean serviceBound = false;

    private ActivityResultLauncher<Intent> filePickerLauncher;
    private Consumer<List<Uri>> pendingPickerCallback;

    private ActivityResultLauncher<Intent> folderPickerLauncher;
    private Consumer<Uri> pendingFolderCallback;

    // Lets <input type="file"> in the web page (e.g. the "Import" backup
    // button) actually open Android's file picker. Without a WebChromeClient
    // implementing onShowFileChooser, WebView has no default handling for
    // that click at all — it just does nothing, silently.
    private ActivityResultLauncher<Intent> fileChooserLauncher;
    private ValueCallback<Uri[]> pendingFileChooserCallback;

    // Backs BackupBridge's native "Save As" export (see saveTextFile()
    // below) — used instead of a blob: <a download> link, which WebView
    // doesn't reliably deliver.
    private ActivityResultLauncher<Intent> savePickerLauncher;
    private Consumer<Uri> pendingSaveCallback;
    private String pendingSaveContent;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            PlaybackService.LocalBinder localBinder = (PlaybackService.LocalBinder) binder;
            serviceBound = true;
            if (audioBridge != null) {
                audioBridge.attachService(localBinder.getService());
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Must be registered before the activity is STARTED.
        filePickerLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            List<Uri> uris = new ArrayList<>();
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                Intent data = result.getData();
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    for (int i = 0; i < count; i++) uris.add(data.getClipData().getItemAt(i).getUri());
                } else if (data.getData() != null) {
                    uris.add(data.getData());
                }
            }
            // Individually-picked files only get a transient read grant by
            // default, which is good enough for an immediate import but
            // can be lost if the user assigns book/chapter to a long list
            // of files one at a time and only gets to some of them later
            // (backgrounding the app, a device reboot in between, etc).
            // Persisting the grant here means importOne() can still open
            // the file whenever the user actually gets to it.
            for (Uri u : uris) {
                try {
                    getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                    // Not every provider supports persistable grants (some
                    // cloud-backed ones don't) — the transient grant from
                    // the picker itself still covers this session.
                }
            }
            Consumer<List<Uri>> cb = pendingPickerCallback;
            pendingPickerCallback = null;
            if (cb != null) cb.accept(uris);
        });

        folderPickerLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            Uri treeUri = null;
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                treeUri = result.getData().getData();
                if (treeUri != null) {
                    try {
                        getContentResolver().takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception ignored) { }
                }
            }
            Consumer<Uri> cb = pendingFolderCallback;
            pendingFolderCallback = null;
            if (cb != null) cb.accept(treeUri);
        });

        fileChooserLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (pendingFileChooserCallback == null) return;
            Uri[] results = WebChromeClient.FileChooserParams.parseResult(result.getResultCode(), result.getData());
            pendingFileChooserCallback.onReceiveValue(results);
            pendingFileChooserCallback = null;
        });

        savePickerLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            Uri uri = null;
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                uri = result.getData().getData();
                if (uri != null && pendingSaveContent != null) {
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        if (out != null) {
                            out.write(pendingSaveContent.getBytes(StandardCharsets.UTF_8));
                        } else {
                            uri = null;
                        }
                    } catch (Exception e) {
                        uri = null;
                    }
                }
            }
            pendingSaveContent = null;
            Consumer<Uri> cb = pendingSaveCallback;
            pendingSaveCallback = null;
            if (cb != null) cb.accept(uri);
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            List<String> perms = new ArrayList<>();
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_MEDIA_AUDIO);
            }
            if (!perms.isEmpty()) {
                ActivityCompat.requestPermissions(this, perms.toArray(new String[0]), 1001);
            }
        }

        webView = findViewById(R.id.webview);
        loadingOverlay = findViewById(R.id.loading_overlay);

        // If the page hasn't finished loading after a long while, the most
        // likely cause is one of the large bundled data files hitting a
        // synchronous JS error that froze script execution before it ever
        // got the chance to report anything (a truly frozen JS thread can't
        // run its own error handlers). Rather than leave the spinner
        // spinning forever with zero explanation, say so explicitly.
        final TextView loadingTextView = findViewById(R.id.loading_text);
        loadingOverlay.postDelayed(() -> {
            if (loadingOverlay.getVisibility() == View.VISIBLE && loadingTextView != null) {
                loadingTextView.setText("Still loading... this is taking longer than usual.\nIf this doesn't finish, please restart the app.");
            }
        }, 20_000);

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMediaPlaybackRequiresUserGesture(false);

        // The WebView's actual page/JS engine runs in a separate,
        // OS-managed renderer process, not inside PlaybackService's
        // foreground-service process. That renderer is exactly what drives
        // chapter auto-advance and the lang1->lang2 leg switch (via
        // NativeAudioEvents.onEnded -> AndroidAudio.play()), so if the OS
        // is free to deprioritize/throttle it once the screen turns off or
        // the app is backgrounded, that JS can stop running right at the
        // moment a chapter ends -- audio just stops, even though the
        // foreground service and ExoPlayer are still fine. Explicitly
        // pinning the renderer at IMPORTANT priority, and NOT waiving that
        // when the WebView isn't visible, keeps it scheduled the same way
        // regardless of screen/foreground state.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, /* waivedWhenNotVisible= */ false);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                // The app's own pages load from this host via the asset
                // loader above. Anything else (e.g. Wikidata/Wikipedia
                // links from the Bible Places page) should open in the
                // system browser instead of navigating away inside the
                // app's WebView, where there'd be no way back.
                if ("appassets.androidplatform.net".equals(uri.getHost())) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception e) {
                    // No browser available to handle it; just swallow the
                    // navigation rather than letting the WebView load it.
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // The page's own script tags are large and load
                // synchronously, so onPageFinished only fires once they've
                // actually all run — this is a reliable "app is really
                // ready" signal, not just "HTML markup was parsed".
                if (loadingOverlay != null) {
                    loadingOverlay.setVisibility(View.GONE);
                }
            }
        });

        // Without a WebChromeClient, a WebView has no default handling for
        // <input type="file"> at all — clicking it (e.g. the backup page's
        // "Import" button) just silently does nothing, no picker, no error.
        // This is what wires that up to Android's real file picker.
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                pendingFileChooserCallback = filePathCallback;
                try {
                    fileChooserLauncher.launch(fileChooserParams.createIntent());
                } catch (Exception e) {
                    pendingFileChooserCallback = null;
                    return false;
                }
                return true;
            }
        });

        // Native bridges exposed to the web app:
        //   window.AndroidAudio  -> background/lock-screen/notification playback
        //   window.AndroidImport -> offline-only local file/folder import (no network, no downloads)
        //   window.AndroidBackup -> native "Save As" for the backup/export JSON file
        //   window.AndroidGoogleAuth -> native Google Sign-In (Credential
        //       Manager), because Google blocks its OAuth flow in WebViews
        audioBridge = new AudioBridge(this, webView);
        webView.addJavascriptInterface(audioBridge, "AndroidAudio");
        webView.addJavascriptInterface(new AudioImportBridge(this, webView), "AndroidImport");
        webView.addJavascriptInterface(new BackupBridge(this, webView), "AndroidBackup");
        webView.addJavascriptInterface(new GoogleSignInBridge(this, webView), "AndroidGoogleAuth");
        webView.addJavascriptInterface(new PdfBridge(this), "AndroidPdf");
        webView.addJavascriptInterface(new StreakBridge(this), "AndroidStreak");
        StreakReminders.scheduleAll(this);

        webView.loadUrl("https://appassets.androidplatform.net/assets/www/index.html");

        // Just bind — don't call startForegroundService() here. Media3's
        // MediaSessionService promotes itself to a foreground service
        // automatically the moment playback actually starts. Calling
        // startForegroundService() up front, before anything is playing,
        // means nothing calls startForeground() back in time, which is
        // exactly what was freezing the app with the "wait/close" dialog.
        Intent serviceIntent = new Intent(this, PlaybackService.class);
        bindService(serviceIntent, connection, BIND_AUTO_CREATE);

        requestIgnoreBatteryOptimizations();
    }

    // The single biggest real-world cause of "audio randomly pauses/freezes
    // while listening" on Android is not anything in this app's own
    // playback code — it's the phone's battery manager suspending
    // background work despite a proper foreground service + wake lock
    // (very common on MIUI/Xiaomi, Samsung, Oppo/Vivo). Asking to be
    // exempted, once, is the standard fix Android documents for
    // media-playback apps: it shows the system's own permission dialog, so
    // the user can say no and nothing else about the app changes.
    private void requestIgnoreBatteryOptimizations() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception ignored) {
                // Some OEMs (or a locked-down device policy) block this
                // intent entirely — playback still works, it's just more
                // exposed to that OEM's background-kill behavior.
            }
        }
    }

    /** Lets the user pick one or more audio files already on their phone (e.g. shared by a friend). */
    public void pickAudioFiles(Consumer<List<Uri>> callback) {
        pendingPickerCallback = callback;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        filePickerLauncher.launch(intent);
    }

    /** Lets the user pick a whole folder (e.g. their "KMT Audio Bible" folder) to import in bulk. */
    public void pickAudioFolder(Consumer<Uri> callback) {
        pendingFolderCallback = callback;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        folderPickerLauncher.launch(intent);
    }

    /**
     * Opens Android's native "Save As" dialog (Storage Access Framework)
     * and writes {@code content} to wherever the user picks. Used by
     * BackupBridge for the web app's Export button — a plain
     * blob: + &lt;a download&gt; click (the normal-browser way to do this)
     * isn't reliably delivered by WebView, since there's no
     * DownloadListener wired up for it here.
     *
     * @param callback receives the destination Uri on success, or null if
     *                  the user cancelled or the write failed.
     */
    public void saveTextFile(String suggestedName, String content, Consumer<Uri> callback) {
        pendingSaveCallback = callback;
        pendingSaveContent = content;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, suggestedName != null ? suggestedName : "backup.json");
        try {
            savePickerLauncher.launch(intent);
        } catch (Exception e) {
            pendingSaveCallback = null;
            pendingSaveContent = null;
            callback.accept(null);
        }
    }

    @Override
    protected void onDestroy() {
        if (serviceBound) {
            unbindService(connection);
            serviceBound = false;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
