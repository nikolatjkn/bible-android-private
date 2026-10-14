package com.biblestudy.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shows one of the bundled PDFs page by page, exactly as the original file
 * (Android's own PdfRenderer, no text conversion, so nothing can be lost or
 * mis-spelled). Remembers the last page and zoom per book.
 */
public class PdfViewerActivity extends AppCompatActivity {

    static final String EXTRA_KEY = "key";
    private static final float[] ZOOMS = {1f, 1.5f, 2f};

    private String key;
    private String assetName;
    private String title;

    private PdfRenderer renderer;
    private ParcelFileDescriptor pfd;
    private final Object renderLock = new Object();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean destroyed = false;

    private RecyclerView list;
    private LinearLayoutManager layoutManager;
    private PageAdapter adapter;
    private TextView status;
    private TextView totalLabel;
    private EditText pageInput;
    private TextView zoomLabel;

    private SharedPreferences prefs;
    private int pageCount = 0;
    private int zoomIndex = 0;
    private int screenW;
    private double aspect = 1.414;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        key = getIntent().getStringExtra(EXTRA_KEY);
        if ("intro".equals(key)) {
            assetName = "tamil_bible_intro.pdf";
            title = "வேதாகம புத்தகங்களின் அறிமுகம் (Bible Introduction)";
        } else if ("thngv".equals(key)) {
            assetName = "tamil_bible_thngv.pdf";
            title = "Tamil Bible – THNGV (Hebrew Names of God Version)";
        } else if ("hngv".equals(key)) {
            assetName = "tamil_english_hngv.pdf";
            title = "Tamil – English Hebrew Names of God Bible (HNGV)";
        } else if ("torah".equals(key)) {
            assetName = "tamil_torah_tail.pdf";
            title = "மகா பரிசுத்த வேதாகமம் – TORAH TAIL";
        } else {
            finish();
            return;
        }
        prefs = getSharedPreferences("pdf_viewer", Context.MODE_PRIVATE);
        zoomIndex = Math.max(0, Math.min(ZOOMS.length - 1, prefs.getInt("zoom_" + key, 0)));
        screenW = getResources().getDisplayMetrics().widthPixels;

        buildUi();

        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    File f = ensureCopied();
                    synchronized (renderLock) {
                        pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                        renderer = new PdfRenderer(pfd);
                        pageCount = renderer.getPageCount();
                        if (pageCount > 0) {
                            PdfRenderer.Page p = renderer.openPage(0);
                            try {
                                if (p.getWidth() > 0) aspect = (double) p.getHeight() / p.getWidth();
                            } finally {
                                p.close();
                            }
                        }
                    }
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!destroyed) showPages();
                        }
                    });
                } catch (final Throwable t) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!destroyed) status.setText("Could not open this book:\n" + t);
                        }
                    });
                }
            }
        });
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView button(String text, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        t.setOnClickListener(l);
        return t;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(34, 34, 34));

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        row1.setBackgroundColor(Color.rgb(27, 27, 47));
        row1.addView(button("←", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        }));
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(14);
        titleView.setMaxLines(2);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        row1.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row1, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        row2.setBackgroundColor(Color.rgb(27, 27, 47));
        row2.addView(button("−", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeZoom(-1);
            }
        }));
        zoomLabel = new TextView(this);
        zoomLabel.setTextColor(Color.WHITE);
        zoomLabel.setTextSize(14);
        row2.addView(zoomLabel);
        row2.addView(button("+", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeZoom(1);
            }
        }));
        View spacer = new View(this);
        row2.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView pageWord = new TextView(this);
        pageWord.setText("Page ");
        pageWord.setTextColor(Color.WHITE);
        row2.addView(pageWord);
        pageInput = new EditText(this);
        pageInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        pageInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        pageInput.setSingleLine(true);
        pageInput.setTextColor(Color.WHITE);
        pageInput.setGravity(Gravity.CENTER);
        pageInput.setMinEms(3);
        pageInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                jumpToTypedPage();
                return true;
            }
        });
        row2.addView(pageInput);
        totalLabel = new TextView(this);
        totalLabel.setTextColor(Color.WHITE);
        row2.addView(totalLabel);
        row2.addView(button("Go", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                jumpToTypedPage();
            }
        }));
        root.addView(row2, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout body = new FrameLayout(this);
        list = new RecyclerView(this);
        layoutManager = new LinearLayoutManager(this);
        list.setLayoutManager(layoutManager);
        list.setItemViewCacheSize(1);
        list.setVisibility(View.GONE);
        body.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        status = new TextView(this);
        status.setText("Preparing book… (first time only, please wait)");
        status.setTextColor(Color.WHITE);
        status.setGravity(Gravity.CENTER);
        status.setTextSize(16);
        body.addView(status, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        updateZoomLabel();
    }

    private void updateZoomLabel() {
        zoomLabel.setText(" " + Math.round(ZOOMS[zoomIndex] * 100) + "% ");
    }

    private void showPages() {
        status.setVisibility(View.GONE);
        list.setVisibility(View.VISIBLE);
        totalLabel.setText(" / " + pageCount);
        adapter = new PageAdapter();
        list.setAdapter(adapter);
        int start = Math.max(1, Math.min(pageCount, prefs.getInt("page_" + key, 1)));
        layoutManager.scrollToPositionWithOffset(start - 1, 0);
        pageInput.setText(String.valueOf(start));
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                int p = layoutManager.findFirstVisibleItemPosition();
                if (p >= 0) {
                    if (!pageInput.hasFocus()) pageInput.setText(String.valueOf(p + 1));
                    prefs.edit().putInt("page_" + key, p + 1).apply();
                }
            }
        });
    }

    private void jumpToTypedPage() {
        if (pageCount <= 0) return;
        int p;
        try {
            p = Integer.parseInt(pageInput.getText().toString().trim());
        } catch (Exception e) {
            return;
        }
        p = Math.max(1, Math.min(pageCount, p));
        layoutManager.scrollToPositionWithOffset(p - 1, 0);
        pageInput.setText(String.valueOf(p));
        pageInput.clearFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(pageInput.getWindowToken(), 0);
    }

    private void changeZoom(int delta) {
        int n = Math.max(0, Math.min(ZOOMS.length - 1, zoomIndex + delta));
        if (n == zoomIndex || adapter == null) return;
        int cur = Math.max(0, layoutManager.findFirstVisibleItemPosition());
        zoomIndex = n;
        prefs.edit().putInt("zoom_" + key, zoomIndex).apply();
        updateZoomLabel();
        adapter.notifyDataSetChanged();
        layoutManager.scrollToPositionWithOffset(cur, 0);
    }

    /** Copies the bundled PDF out of the APK once so PdfRenderer can seek in it. */
    private File ensureCopied() throws IOException {
        File dir = new File(getFilesDir(), "pdfs");
        dir.mkdirs();
        File out = new File(dir, assetName);
        long assetLen = -1;
        try {
            AssetFileDescriptor afd = getAssets().openFd("pdfs/" + assetName);
            assetLen = afd.getLength();
            afd.close();
        } catch (IOException ignored) {
            // Asset stored compressed: length unknown, fall back to "exists and non-empty".
        }
        if (out.exists() && out.length() > 0 && (assetLen < 0 || out.length() == assetLen)) {
            return out;
        }
        File tmp = new File(dir, assetName + ".tmp");
        InputStream in = getAssets().open("pdfs/" + assetName);
        OutputStream os = new FileOutputStream(tmp);
        try {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        } finally {
            in.close();
            os.close();
        }
        if (out.exists()) out.delete();
        if (!tmp.renameTo(out)) throw new IOException("Could not save " + assetName);
        return out;
    }

    private class PageHolder extends RecyclerView.ViewHolder {
        final HorizontalScrollView scroll;
        final ImageView image;
        Bitmap bitmap;

        PageHolder(HorizontalScrollView s, ImageView i) {
            super(s);
            scroll = s;
            image = i;
        }
    }

    private class PageAdapter extends RecyclerView.Adapter<PageHolder> {
        @Override
        public PageHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            HorizontalScrollView sv = new HorizontalScrollView(PdfViewerActivity.this);
            sv.setHorizontalScrollBarEnabled(false);
            sv.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            sv.setPadding(0, 0, 0, dp(6));
            ImageView iv = new ImageView(PdfViewerActivity.this);
            iv.setBackgroundColor(Color.WHITE);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            sv.addView(iv, new FrameLayout.LayoutParams(screenW, (int) (screenW * aspect)));
            return new PageHolder(sv, iv);
        }

        @Override
        public void onBindViewHolder(final PageHolder holder, final int position) {
            final int w = (int) (screenW * ZOOMS[zoomIndex]);
            ViewGroup.LayoutParams lp = holder.image.getLayoutParams();
            lp.width = w;
            lp.height = (int) (w * aspect);
            holder.image.setLayoutParams(lp);
            holder.image.setImageDrawable(null);
            if (holder.bitmap != null) {
                holder.bitmap.recycle();
                holder.bitmap = null;
            }
            holder.image.setTag(Integer.valueOf(position));
            holder.scroll.scrollTo(0, 0);
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    if (destroyed) return;
                    if (!Integer.valueOf(position).equals(holder.image.getTag())) return;
                    Bitmap bmp = null;
                    try {
                        synchronized (renderLock) {
                            if (renderer == null) return;
                            PdfRenderer.Page p = renderer.openPage(position);
                            try {
                                int bh = Math.max(1, (int) ((long) w * p.getHeight() / p.getWidth()));
                                bmp = Bitmap.createBitmap(w, bh, Bitmap.Config.ARGB_8888);
                                bmp.eraseColor(Color.WHITE);
                                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                            } finally {
                                p.close();
                            }
                        }
                    } catch (Throwable t) {
                        return;
                    }
                    final Bitmap done = bmp;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed || !Integer.valueOf(position).equals(holder.image.getTag())) {
                                done.recycle();
                                return;
                            }
                            ViewGroup.LayoutParams l2 = holder.image.getLayoutParams();
                            if (l2.height != done.getHeight() || l2.width != done.getWidth()) {
                                l2.height = done.getHeight();
                                l2.width = done.getWidth();
                                holder.image.setLayoutParams(l2);
                            }
                            holder.image.setImageBitmap(done);
                            holder.bitmap = done;
                        }
                    });
                }
            });
        }

        @Override
        public void onViewRecycled(PageHolder holder) {
            holder.image.setImageDrawable(null);
            holder.image.setTag(null);
            if (holder.bitmap != null) {
                holder.bitmap.recycle();
                holder.bitmap = null;
            }
        }

        @Override
        public int getItemCount() {
            return pageCount;
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        executor.shutdownNow();
        synchronized (renderLock) {
            try {
                if (renderer != null) renderer.close();
            } catch (Throwable ignored) { }
            renderer = null;
            try {
                if (pfd != null) pfd.close();
            } catch (Throwable ignored) { }
            pfd = null;
        }
        super.onDestroy();
    }
}
