package com.mina.timesheet;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.ApiException;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int PICK = 1;
    private static final int AUTH = 2;
    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private volatile String pendingAction = "";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        web.addJavascriptInterface(new Bridge(), "Android");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try {
                    startActivityForResult(Intent.createChooser(i, "Choose file"), PICK);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        if (DriveSync.prefs(this).getBoolean("on", false)) DriveSync.schedule(this);

        if (state != null) web.restoreState(state);
        else web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == AUTH) {
            try {
                AuthorizationResult r = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data);
                afterToken(r.getAccessToken());
            } catch (Exception e) {
                fail(e);
            }
            return;
        }
        if (req != PICK || fileCallback == null) return;
        Uri[] out = null;
        if (res == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                out = new Uri[n];
                for (int k = 0; k < n; k++) out[k] = data.getClipData().getItemAt(k).getUri();
            } else if (data.getData() != null) {
                out = new Uri[]{ data.getData() };
            }
        }
        fileCallback.onReceiveValue(out);
        fileCallback = null;
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    /* ---------- Google Drive ---------- */

    private void send(JSONObject o) {
        final String code = "window.onDrive&&window.onDrive(" + o.toString() + ")";
        runOnUiThread(() -> web.evaluateJavascript(code, null));
    }

    private JSONObject status() {
        JSONObject o = new JSONObject();
        try {
            SharedPreferences p = DriveSync.prefs(this);
            o.put("type", "status").put("on", p.getBoolean("on", false)).put("email", p.getString("email", ""))
                    .put("last", p.getLong("last", 0)).put("error", p.getString("error", ""));
        } catch (Exception ignored) { }
        return o;
    }

    private void startAuth(String action) {
        pendingAction = action;
        runOnUiThread(() -> Identity.getAuthorizationClient(this).authorize(DriveSync.request())
                .addOnSuccessListener(r -> {
                    if (r.hasResolution()) {
                        try {
                            startIntentSenderForResult(r.getPendingIntent().getIntentSender(), AUTH, null, 0, 0, 0);
                        } catch (Exception e) {
                            fail(e);
                        }
                    } else {
                        afterToken(r.getAccessToken());
                    }
                })
                .addOnFailureListener(this::fail));
    }

    private void afterToken(String token) {
        final String action = pendingAction;
        new Thread(() -> {
            try {
                if (token == null) throw new Exception("Google did not return access.");
                if ("connect".equals(action)) {
                    String email = DriveSync.email(token);
                    DriveSync.prefs(this).edit().putBoolean("on", true).putString("email", email).putString("error", "").apply();
                    DriveSync.schedule(this);
                    if (DriveSync.snapshot(this).exists()) DriveSync.upload(this, token);
                    send(status().put("justConnected", true));
                } else if ("backup".equals(action)) {
                    DriveSync.upload(this, token);
                    send(status().put("message", "Backed up to Google Drive."));
                } else if ("restore".equals(action)) {
                    String data = DriveSync.latest(token);
                    send(new JSONObject().put("type", "restore").put("data", data));
                }
            } catch (Exception e) {
                fail(e);
            }
        }).start();
    }

    private void fail(Exception e) {
        String msg;
        if (e instanceof ApiException) {
            int code = ((ApiException) e).getStatusCode();
            if (code == 10) msg = "Google sign-in isn't set up for this app yet. Finish the Google Cloud steps, then try again.";
            else if (code == 16 || code == 12501) msg = "Sign-in was cancelled.";
            else if (code == 7) msg = "No internet connection.";
            else msg = "Google error " + code + ". Please try again.";
        } else {
            msg = e.getMessage() == null ? "Something went wrong." : e.getMessage();
        }
        DriveSync.prefs(this).edit().putString("error", msg).apply();
        try { send(new JSONObject().put("type", "error").put("message", msg)); } catch (Exception ignored) { }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (web != null) web.evaluateJavascript("window.onDeviceTheme&&window.onDeviceTheme()", null);
    }

    /** Functions the page can call as Android.xxx(). */
    private class Bridge {
        /** Paints the phone's status and navigation bars like the page and picks light or dark icons. */
        @JavascriptInterface
        public void setBars(final String color, final boolean darkIcons) {
            runOnUiThread(() -> {
                try {
                    android.view.Window w = getWindow();
                    int c = android.graphics.Color.parseColor(color);
                    w.setStatusBarColor(c);
                    w.setNavigationBarColor(c);
                    if (Build.VERSION.SDK_INT >= 23) {
                        android.view.View d = w.getDecorView();
                        int f = d.getSystemUiVisibility();
                        f = darkIcons ? (f | android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) : (f & ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
                        if (Build.VERSION.SDK_INT >= 26) {
                            f = darkIcons ? (f | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR) : (f & ~android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
                        }
                        d.setSystemUiVisibility(f);
                    }
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public boolean isDark() {
            int m = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return m == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        }

        @JavascriptInterface
        public String saveFile(String name, String text) {
            try {
                byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    cv.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                    cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    try (OutputStream o = getContentResolver().openOutputStream(u)) { o.write(bytes); }
                    return "Downloads";
                } else {
                    File f = new File(getExternalFilesDir(null), name);
                    try (FileOutputStream o = new FileOutputStream(f)) { o.write(bytes); }
                    return f.getParent();
                }
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void storeBackup(String json) {
            try { DriveSync.saveSnapshot(MainActivity.this, json); } catch (Exception ignored) { }
        }

        /** Replaces the list of upcoming reminders/alarms: JSON array of {t, id, k, title, text}. */
        @JavascriptInterface
        public void setPlan(String json) {
            try { Reminders.setPlan(MainActivity.this, json); } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public String notifStatus() {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                return "denied";
            }
            return "granted";
        }

        @JavascriptInterface
        public void askNotifications() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33
                        && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 77);
                }
            });
        }

        /** Downloads a web page as text (for the holiday calendar) and hands it to window.onNetText(id, ok, text). */
        @JavascriptInterface
        public void fetchText(final String id, final String url) {
            new Thread(() -> {
                String out = "";
                boolean ok = false;
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(25000);
                    c.setInstanceFollowRedirects(true);
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) TimeSheet");
                    int code = c.getResponseCode();
                    if (code >= 200 && code < 300) {
                        try (InputStream in = c.getInputStream(); ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                            out = new String(bo.toByteArray(), StandardCharsets.UTF_8);
                            ok = true;
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    if (c != null) c.disconnect();
                }
                final String js = "window.onNetText&&window.onNetText(" + JSONObject.quote(id) + "," + ok + "," + JSONObject.quote(out) + ")";
                runOnUiThread(() -> web.evaluateJavascript(js, null));
            }).start();
        }

        @JavascriptInterface
        public String driveStatus() { return status().toString(); }

        @JavascriptInterface
        public void driveConnect() { startAuth("connect"); }

        @JavascriptInterface
        public void driveBackupNow() { startAuth("backup"); }

        @JavascriptInterface
        public void driveRestore() { startAuth("restore"); }

        @JavascriptInterface
        public void driveDisconnect() {
            DriveSync.unschedule(MainActivity.this);
            DriveSync.prefs(MainActivity.this).edit().clear().apply();
            send(status());
        }
    }
}
