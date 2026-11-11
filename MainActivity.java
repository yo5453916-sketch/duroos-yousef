package com.duroos.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MainActivity extends Activity {
    static MainActivity inst;
    WebView web;
    final Map<String, OutputStream> outs = new HashMap<>();
    final Map<String, Uri> uris = new HashMap<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        inst = this;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        // الصفحة محلية (file://): نسمح لها تجيب أي موقع من غير قيود CORS، فالتحميل والفحص يشتغلوا مباشر من غير بروكسيات
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                if (!r.isForMainFrame()) return false;
                String u = r.getUrl().toString();
                if (u.startsWith("file:")) return false;
                openExternal(u);
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView v, boolean dialog, boolean user, Message msg) {
                final WebView t = new WebView(MainActivity.this);
                final boolean[] done = {false};
                t.setWebViewClient(new WebViewClient() {
                    void go(String u) {
                        if (done[0] || u == null || u.startsWith("about:")) return;
                        done[0] = true;
                        openExternal(u);
                        t.post(new Runnable() { public void run() { t.destroy(); } });
                    }
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView w, WebResourceRequest r) {
                        go(r.getUrl().toString());
                        return true;
                    }
                    @Override
                    public void onPageStarted(WebView w, String u, android.graphics.Bitmap f) {
                        go(u);
                    }
                });
                ((WebView.WebViewTransport) msg.obj).setWebView(t);
                msg.sendToTarget();
                return true;
            }

            @Override
            public boolean onJsAlert(WebView v, String url, String message, final JsResult r) {
                new AlertDialog.Builder(MainActivity.this).setMessage(message)
                        .setPositiveButton("حسنًا", (d, w) -> r.confirm())
                        .setOnCancelListener(d -> r.cancel()).show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView v, String url, String message, final JsResult r) {
                new AlertDialog.Builder(MainActivity.this).setMessage(message)
                        .setPositiveButton("موافق", (d, w) -> r.confirm())
                        .setNegativeButton("إلغاء", (d, w) -> r.cancel())
                        .setOnCancelListener(d -> r.cancel()).show();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView v, String url, String message, String def, final JsPromptResult r) {
                final EditText et = new EditText(MainActivity.this);
                et.setText(def == null ? "" : def);
                new AlertDialog.Builder(MainActivity.this).setMessage(message).setView(et)
                        .setPositiveButton("موافق", (d, w) -> r.confirm(et.getText().toString()))
                        .setNegativeButton("إلغاء", (d, w) -> r.cancel())
                        .setOnCancelListener(d -> r.cancel()).show();
                return true;
            }
        });
        web.loadUrl("file:///android_asset/index.html");
    }

    void openExternal(String u) {
        try {
            Intent i = u.startsWith("intent:") ? Intent.parseUri(u, Intent.URI_INTENT_SCHEME)
                    : new Intent(Intent.ACTION_VIEW, Uri.parse(u));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) { /* ignore */ }
    }

    static void js(final String code) {
        final MainActivity a = inst;
        if (a == null) return;
        a.runOnUiThread(new Runnable() {
            public void run() { a.web.evaluateJavascript(code, null); }
        });
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else finish();
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) PlayerService.halt(getApplicationContext());
        if (inst == this) inst = null;
        super.onDestroy();
    }

    class Bridge {
        @JavascriptInterface
        public void media(String t, String a, boolean playing, long pos, long dur) {
            PlayerService.update(getApplicationContext(), t, a, playing, pos, dur);
        }

        @JavascriptInterface
        public void stop() { PlayerService.halt(getApplicationContext()); }

        @JavascriptInterface
        public void exit() {
            PlayerService.halt(getApplicationContext());
            runOnUiThread(new Runnable() { public void run() { finish(); } });
        }

        // جلب صفحة من النت بدون قيود CORS (بيرجّع النتيجة async عن طريق __ng)
        @JavascriptInterface
        public void nget(final int id, final String url) {
            new Thread(new Runnable() {
                public void run() {
                    int st = 0;
                    String cs = "utf-8", b64 = "";
                    try {
                        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                        c.setConnectTimeout(12000);
                        c.setReadTimeout(20000);
                        c.setInstanceFollowRedirects(true);
                        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
                        c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                        c.setRequestProperty("Accept-Language", "ar,en;q=0.8");
                        st = c.getResponseCode();
                        InputStream in = st >= 400 ? c.getErrorStream() : c.getInputStream();
                        String ct = c.getContentType();
                        if (ct != null) {
                            Matcher mt = Pattern.compile("charset=([\\w-]+)", Pattern.CASE_INSENSITIVE).matcher(ct);
                            if (mt.find()) cs = mt.group(1).toLowerCase();
                        }
                        if (in != null) {
                            ByteArrayOutputStream bo = new ByteArrayOutputStream();
                            byte[] buf = new byte[16384];
                            int n;
                            while ((n = in.read(buf)) > 0) {
                                bo.write(buf, 0, n);
                                if (bo.size() > 6000000) break;
                            }
                            b64 = Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
                            in.close();
                        }
                        c.disconnect();
                    } catch (Exception e) { /* st يفضل زي ما هو */ }
                    MainActivity.js("window.__ng&&__ng(" + id + "," + st + ",'" + cs + "','" + b64 + "')");
                }
            }).start();
        }

        // ---- حفظ ملف في Music/Duroos على التليفون ----
        @JavascriptInterface
        public String beginSave(String name, String mime) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
                v.put(MediaStore.Audio.Media.MIME_TYPE, mime);
                v.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Duroos");
                v.put(MediaStore.Audio.Media.IS_PENDING, 1);
                Uri u = getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v);
                if (u == null) return "";
                OutputStream o = getContentResolver().openOutputStream(u);
                if (o == null) return "";
                String id = UUID.randomUUID().toString();
                outs.put(id, o);
                uris.put(id, u);
                return id;
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public boolean writeChunk(String id, String b64) {
            try {
                OutputStream o = outs.get(id);
                if (o == null) return false;
                o.write(Base64.decode(b64, Base64.DEFAULT));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public String endSave(String id, boolean ok) {
            Uri u = uris.remove(id);
            OutputStream o = outs.remove(id);
            try { if (o != null) o.close(); } catch (Exception e) { ok = false; }
            if (u == null) return "";
            try {
                if (ok) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Audio.Media.IS_PENDING, 0);
                    getContentResolver().update(u, v, null, null);
                    return u.toString();
                }
                getContentResolver().delete(u, null, null);
            } catch (Exception e) { /* ignore */ }
            return "";
        }

        @JavascriptInterface
        public boolean deleteFile(String uri) {
            try {
                return getContentResolver().delete(Uri.parse(uri), null, null) > 0;
            } catch (Exception e) {
                return false;
            }
        }

        // يرجّع: ok / novlc / err
        @JavascriptInterface
        public String openVlc(String uri, String title) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(Uri.parse(uri), "audio/*");
                i.setPackage("org.videolan.vlc");
                i.putExtra("title", title);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return "ok";
            } catch (ActivityNotFoundException e) {
                openExternal("market://details?id=org.videolan.vlc");
                return "novlc";
            } catch (Exception e) {
                return "err";
            }
        }
    }
}
