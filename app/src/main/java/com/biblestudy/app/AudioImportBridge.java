package com.biblestudy.app;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Exposed to the web page as window.AndroidImport. Fully offline: this
 * class never opens a network connection.
 *
 * "Import" here means *link*, not copy: picking a file or a whole folder
 * grants a persisted read permission (see MainActivity's
 * takePersistableUriPermission calls) and this class just remembers which
 * content:// URI answers for which (language, book:chapter) key. Playback
 * (AudioBridge/ExoPlayer) opens that URI directly from wherever it already
 * lives on the device. No audio bytes are ever duplicated into the app's
 * own storage, so importing a large Audio Bible folder costs the app only
 * a few KB of bookkeeping, not a second copy of the whole folder.
 *
 * Chapters imported by an older version of this app (which did copy bytes
 * into app-private storage) keep working exactly as before — see the
 * legacy fallback in getLocalFilePath()/getDownloadedBytes() — so nobody
 * has to re-import anything after updating.
 */
public class AudioImportBridge {

    private final MainActivity activity;
    private final WebView webView;
    private List<Uri> pickedUris = new ArrayList<>();
    private final ExecutorService importExecutor = Executors.newSingleThreadExecutor();

    AudioImportBridge(MainActivity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    private static String safeName(String key) {
        return key.replaceAll("[^a-zA-Z0-9_.-]", "_") + ".audio";
    }

    /** Legacy copy-based storage location — only ever read now, never written to. */
    private File langDir(String lang) {
        File base = activity.getExternalFilesDir(null);
        if (base == null) {
            // getExternalFilesDir() can return null if external/shared
            // storage isn't currently mounted (rare, but happens on some
            // devices/USB-storage states).
            base = activity.getFilesDir();
        }
        File dir = new File(base, "audio_offline/" + lang);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private File manifestFile(String lang) {
        return new File(langDir(lang), "_manifest.json");
    }

    private String readTextFile(File f) throws Exception {
        byte[] buf = new byte[(int) f.length()];
        try (FileInputStream fis = new FileInputStream(f)) {
            int off = 0, n;
            while (off < buf.length && (n = fis.read(buf, off, buf.length - off)) != -1) off += n;
        }
        return new String(buf, "UTF-8");
    }

    // ---- Link manifest: { "Book:Chapter": "content://..." } per language ----
    // A value of "" is a migrated legacy entry — that chapter was imported
    // by the old copy-based version of this app, so it resolves via the
    // physical file in langDir() instead of a URI (see getLocalFilePath).

    private synchronized JSONObject readLinkMap(String lang) {
        try {
            File manifest = manifestFile(lang);
            if (!manifest.exists()) return new JSONObject();
            String text = readTextFile(manifest).trim();
            if (text.isEmpty()) return new JSONObject();
            if (text.startsWith("[")) {
                // Old format: a plain JSON array of already-copied keys.
                // Convert it in memory to the new map shape (untouched on
                // disk until the next write) so both formats read the same.
                JSONArray arr = new JSONArray(text);
                JSONObject map = new JSONObject();
                for (int i = 0; i < arr.length(); i++) map.put(arr.getString(i), "");
                return map;
            }
            return new JSONObject(text);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private synchronized void writeLinkMap(String lang, JSONObject map) {
        try (FileOutputStream fos = new FileOutputStream(manifestFile(lang))) {
            fos.write(map.toString().getBytes("UTF-8"));
        } catch (Exception ignored) { }
    }

    private synchronized void linkKeyInManifest(String lang, String key, String uriString) {
        JSONObject map = readLinkMap(lang);
        try { map.put(key, uriString); } catch (Exception ignored) { }
        writeLinkMap(lang, map);
    }

    // ---- Manage what's already imported ----

    @JavascriptInterface
    public void deleteDownload(String lang) {
        // Removes both the link manifest and any leftover legacy copied
        // files for this language — after this, nothing under langDir()
        // resolves, whether it was linked or copied.
        deleteRecursive(langDir(lang));
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File c : kids) deleteRecursive(c);
        }
        f.delete();
    }

    /**
     * Returns a fully-qualified, ready-to-play URI string for (lang, key):
     * a content:// URI for a chapter linked in place (the normal case now),
     * or a file:// URI for a chapter a previous app version copied into
     * app storage. Returns "" if nothing is available for this chapter.
     */
    @JavascriptInterface
    public String getLocalFilePath(String lang, String key) {
        JSONObject map = readLinkMap(lang);
        String linked = map.optString(key, null);
        if (linked != null && !linked.isEmpty()) return linked;
        File f = new File(langDir(lang), safeName(key));
        return (f.exists() && f.length() > 0) ? ("file://" + f.getAbsolutePath()) : "";
    }

    @JavascriptInterface
    public String getDownloadedKeys(String lang) {
        JSONObject map = readLinkMap(lang);
        JSONArray out = new JSONArray();
        Iterator<String> it = map.keys();
        while (it.hasNext()) out.put(it.next());
        return out.toString();
    }

    /**
     * Best-effort total size of everything imported for this language.
     * Linked chapters aren't copied (so they cost the app itself ~0
     * bytes), but this still reports their real size on-device by querying
     * each URI's length via the content provider (metadata only — no bytes
     * are read), so the "how much audio is imported" figure stays accurate.
     */
    @JavascriptInterface
    public long getDownloadedBytes(String lang) {
        long total = 0;
        File[] files = langDir(lang).listFiles();
        if (files != null) for (File f : files) if (f.getName().endsWith(".audio")) total += f.length();
        JSONObject map = readLinkMap(lang);
        Iterator<String> it = map.keys();
        while (it.hasNext()) {
            String uri = map.optString(it.next(), "");
            if (uri.isEmpty()) continue;
            try {
                DocumentFile df = DocumentFile.fromSingleUri(activity, Uri.parse(uri));
                if (df != null) total += df.length();
            } catch (Exception ignored) { }
        }
        return total;
    }

    // ---- Import: pick individual file(s) ----

    /** Opens Android's file picker (multi-select, audio files only). Result comes back via onFilesPicked. */
    @JavascriptInterface
    public void pickAudioFiles() {
        activity.runOnUiThread(() -> activity.pickAudioFiles(uris -> {
            pickedUris = uris;
            JSONArray names = new JSONArray();
            for (Uri u : uris) names.put(queryDisplayName(u));
            String js = "window.NativeImportEvents && window.NativeImportEvents.onFilesPicked(" + names.toString() + ", null)";
            webView.evaluateJavascript(js, null);
        }));
    }

    // ---- Import: pick a whole folder, recursively ----

    /** Opens Android's folder picker and recursively collects every audio file under it. Result also comes back via onFilesPicked. */
    @JavascriptInterface
    public void pickFolder() {
        activity.runOnUiThread(() -> activity.pickAudioFolder(treeUri -> {
            if (treeUri == null) {
                webView.evaluateJavascript("window.NativeImportEvents && window.NativeImportEvents.onFilesPicked([], null)", null);
                return;
            }
            importExecutor.execute(() -> {
                List<Uri> uris = new ArrayList<>();
                List<String> names = new ArrayList<>();
                String scanError = null;
                try {
                    DocumentFile root = DocumentFile.fromTreeUri(activity, treeUri);
                    if (root == null) {
                        scanError = "Could not open that folder.";
                    } else {
                        walkDir(root, uris, names);
                        if (uris.isEmpty()) {
                            scanError = "No audio files (mp3/m4a/wav/aac/ogg/flac) were found in that folder.";
                        }
                    }
                } catch (SecurityException e) {
                    // The most common real-world cause: the folder grant
                    // was for a previous pick and is no longer valid (e.g.
                    // the user picked a *different* folder root last time,
                    // or the grant didn't survive a device reboot because
                    // it wasn't persisted — see takePersistableUriPermission
                    // in MainActivity). Telling the user this plainly beats
                    // a silent "nothing happened".
                    scanError = "Lost permission to read that folder — please pick it again.";
                } catch (Exception e) {
                    scanError = "Could not scan that folder: " + (e.getMessage() == null ? "unknown error" : e.getMessage());
                }
                pickedUris = uris;
                JSONArray namesArr = new JSONArray();
                for (String n : names) namesArr.put(n);
                final String err = scanError;
                String js = "window.NativeImportEvents && window.NativeImportEvents.onFilesPicked(" + namesArr.toString()
                        + ", " + (err == null ? "null" : JSONObject.quote(err)) + ")";
                activity.runOnUiThread(() -> webView.evaluateJavascript(js, null));
            });
        }));
    }

    private void walkDir(DocumentFile dir, List<Uri> outUris, List<String> outNames) {
        DocumentFile[] children;
        try {
            children = dir.listFiles();
        } catch (Exception e) {
            return; // this one subfolder is unreadable — skip it, keep scanning the rest of the tree
        }
        if (children == null) return;
        for (DocumentFile child : children) {
            try {
                if (child.isDirectory()) {
                    walkDir(child, outUris, outNames);
                } else {
                    String name = child.getName();
                    if (name != null && isAudioFile(name)) {
                        outUris.add(child.getUri());
                        outNames.add(name);
                    }
                }
            } catch (Exception ignored) {
                // One bad entry (e.g. a broken/unreadable document) shouldn't
                // abort the whole folder scan.
            }
        }
    }

    private boolean isAudioFile(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".wav")
                || lower.endsWith(".aac") || lower.endsWith(".ogg") || lower.endsWith(".flac");
    }

    private String queryDisplayName(Uri uri) {
        String name = uri.getLastPathSegment();
        try (Cursor c = activity.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String n = c.getString(idx);
                    if (n != null) name = n;
                }
            }
        } catch (Exception ignored) { }
        return name == null ? "file" : name;
    }

    // ---- Link a picked file into the manifest (no byte copy) ----

    /**
     * Records the file picked earlier at `index` as (lang, key) — the same
     * key format used everywhere else ("Book:Chapter") — so playback can
     * open it directly from wherever it already lives on the device.
     * Returns "" on success, or a human-readable error message.
     */
    @JavascriptInterface
    public String importPickedFile(int index, String lang, String key) {
        return linkOne(index, lang, key);
    }

    /**
     * Links many picked files at once in the background (used by "Import
     * Whole Folder", which can mean thousands of files) and reports
     * progress via onImportProgress/onImportComplete instead of blocking
     * the page. assignmentsJson: [{"index":0,"lang":"my","key":"Genesis:1"}, ...]
     * onImportComplete gets a JSON array of the failures only — each
     * {"index","lang","key","name","error"} — so the UI can show exactly
     * which chapters didn't make it in and why, instead of just a count.
     */
    @JavascriptInterface
    public void importPickedFilesBatch(String assignmentsJson) {
        importExecutor.execute(() -> {
            int done = 0, failed = 0, total = 0;
            JSONArray failures = new JSONArray();
            try {
                JSONArray arr = new JSONArray(assignmentsJson);
                total = arr.length();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject item = arr.getJSONObject(i);
                    int index = item.getInt("index");
                    String lang = item.getString("lang");
                    String key = item.getString("key");
                    String err = linkOne(index, lang, key);
                    if (err.isEmpty()) {
                        done++;
                    } else {
                        failed++;
                        try {
                            JSONObject f = new JSONObject();
                            f.put("index", index);
                            f.put("lang", lang);
                            f.put("key", key);
                            f.put("name", (index >= 0 && index < pickedUris.size()) ? queryDisplayName(pickedUris.get(index)) : key);
                            f.put("error", err);
                            failures.put(f);
                        } catch (Exception ignored) { }
                    }
                    final int d = done, f = failed, t = total;
                    activity.runOnUiThread(() -> webView.evaluateJavascript(
                            "window.NativeImportEvents && window.NativeImportEvents.onImportProgress(" + d + "," + f + "," + t + ")", null));
                }
            } catch (Exception ignored) { }
            final String failuresJson = failures.toString();
            activity.runOnUiThread(() -> webView.evaluateJavascript(
                    "window.NativeImportEvents && window.NativeImportEvents.onImportComplete(" + failuresJson + ")", null));
        });
    }

    /**
     * Links pickedUris[index] to (lang, key) without copying its bytes.
     * Verifies the file is actually readable first — catches a revoked or
     * never-persisted grant immediately (as an error the UI can show),
     * rather than the chapter silently failing much later when the user
     * actually tries to play it.
     */
    private String linkOne(int index, String lang, String key) {
        if (index < 0 || index >= pickedUris.size()) {
            return "That file selection expired — please choose the file(s)/folder again.";
        }
        Uri uri = pickedUris.get(index);
        long size;
        try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
            if (in == null) return "Could not open that file.";
            size = queryFileSize(uri);
        } catch (SecurityException e) {
            // The read grant for this specific file is gone — most often
            // because it was never persisted (see MainActivity's
            // takePersistableUriPermission) and either the app process was
            // restarted or the device rebooted since it was picked.
            return "Permission to read this file was lost — please re-select it and import again.";
        } catch (Exception e) {
            return "Could not access that file: " + (e.getMessage() == null ? "unknown error" : e.getMessage());
        }
        if (size >= 0 && size < 5000) { // too small to plausibly be a real chapter recording
            return "That file looks too small to be a real chapter — import skipped.";
        }
        // A lone ACTION_OPEN_DOCUMENT pick only gets its persistable grant
        // taken in MainActivity at pick time; a whole-folder pick already
        // covers every child under the tree. Trying again here is a
        // harmless no-op in the folder case and a safety net in the other.
        try { activity.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { }
        linkKeyInManifest(lang, key, uri.toString());
        // If this chapter was previously imported the old copy-into-app-
        // storage way, drop that copy now that a direct link exists —
        // no reason to keep a second copy once it's linked.
        File oldCopy = new File(langDir(lang), safeName(key));
        if (oldCopy.exists()) oldCopy.delete();
        return "";
    }

    private long queryFileSize(Uri uri) {
        try (Cursor c = activity.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx);
            }
        } catch (Exception ignored) { }
        return -1; // unknown — don't block the import just because size wasn't reported
    }

    @JavascriptInterface
    public void clearPickedFiles() {
        pickedUris = new ArrayList<>();
    }
}
