package com.mina.timesheet;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * M3 Studio in-app updates (same file in every app; only the package line differs).
 * JS: M3U.info(), M3U.check(), M3U.install(), M3U.openPerm(); results arrive in window.onM3U({...}).
 * Checks GitHub Releases (tag v<run_number> == versionCode) and installs the new APK with PackageInstaller.
 * On Android 12+, once this app has updated itself one time, later updates need no confirmation tap.
 */
public class M3Update {
    static M3Update inst;
    final Activity act;
    final WebView web;
    final String repo;
    volatile String apkUrl;
    volatile long apkSize;
    volatile boolean busy;

    public M3Update(Activity a, WebView w, String repo) {
        this.act = a; this.web = w; this.repo = repo; inst = this;
    }

    int versionCode() {
        try {
            PackageInfo p = act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) p.getLongVersionCode() : p.versionCode;
        } catch (Exception e) { return 0; }
    }

    boolean canInstall() {
        return Build.VERSION.SDK_INT < 26 || act.getPackageManager().canRequestPackageInstalls();
    }

    static void send(String json) {
        final M3Update u = inst;
        if (u == null) return;
        u.act.runOnUiThread(() -> u.web.evaluateJavascript("window.onM3U&&window.onM3U(" + json + ")", null));
    }

    static String q(String s) { return JSONObject.quote(s == null ? "" : s); }

    @JavascriptInterface
    public String info() {
        return "{\"version\":" + versionCode() + ",\"canInstall\":" + canInstall() + ",\"sdk\":" + Build.VERSION.SDK_INT + ",\"busy\":" + busy + "}";
    }

    @JavascriptInterface
    public void check() {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com/repos/minammicheal-web/" + repo + "/releases/latest").openConnection();
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "M3Update/" + act.getPackageName());
                c.setConnectTimeout(15000); c.setReadTimeout(20000);
                int code = c.getResponseCode();
                if (code != 200) { send("{\"type\":\"error\",\"msg\":" + q("HTTP " + code) + "}"); return; }
                InputStream in = c.getInputStream();
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[8192]; int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                in.close();
                JSONObject r = new JSONObject(bo.toString("UTF-8"));
                String tag = r.optString("tag_name", "");
                int latest = 0;
                try { latest = Integer.parseInt(tag.replaceAll("[^0-9]", "")); } catch (Exception ignored) {}
                JSONArray as = r.optJSONArray("assets");
                String url = null; long size = 0;
                if (as != null) for (int i = 0; i < as.length(); i++) {
                    JSONObject a = as.getJSONObject(i);
                    if (a.optString("name", "").toLowerCase().endsWith(".apk")) { url = a.optString("browser_download_url"); size = a.optLong("size"); break; }
                }
                apkUrl = url; apkSize = size;
                send("{\"type\":\"check\",\"current\":" + versionCode() + ",\"latest\":" + latest + ",\"name\":" + q(r.optString("name")) +
                        ",\"size\":" + size + ",\"hasApk\":" + (url != null) + ",\"canInstall\":" + canInstall() + "}");
            } catch (Exception e) {
                send("{\"type\":\"error\",\"msg\":" + q(String.valueOf(e.getMessage())) + "}");
            }
        }).start();
    }

    @JavascriptInterface
    public void openPerm() {
        if (Build.VERSION.SDK_INT < 26) return;
        act.runOnUiThread(() -> {
            try {
                act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.getPackageName())));
            } catch (Exception e) {
                try { act.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS)); } catch (Exception ignored) {}
            }
        });
    }

    @JavascriptInterface
    public void install() {
        if (busy) return;
        if (!canInstall()) { send("{\"type\":\"perm\"}"); openPerm(); return; }
        final String url = apkUrl;
        if (url == null) { send("{\"type\":\"error\",\"msg\":\"no apk\"}"); return; }
        busy = true;
        new Thread(() -> {
            PackageInstaller.Session session = null;
            try {
                PackageInstaller pi = act.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                sp.setAppPackageName(act.getPackageName());
                if (Build.VERSION.SDK_INT >= 31) sp.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "M3Update/" + act.getPackageName());
                c.setConnectTimeout(15000); c.setReadTimeout(30000);
                if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                long total = c.getContentLengthLong(); if (total <= 0) total = apkSize;
                if (total > 0) sp.setSize(total);
                int id = pi.createSession(sp);
                session = pi.openSession(id);
                InputStream in = c.getInputStream();
                OutputStream out = session.openWrite("base.apk", 0, total > 0 ? total : -1);
                byte[] b = new byte[65536]; int n; long done = 0; int lastPct = -1;
                while ((n = in.read(b)) > 0) {
                    out.write(b, 0, n); done += n;
                    int pct = total > 0 ? (int) (done * 100 / total) : -1;
                    if (pct != lastPct) { lastPct = pct; send("{\"type\":\"progress\",\"pct\":" + pct + "}"); }
                }
                session.fsync(out); out.close(); in.close();
                Intent i = new Intent(act, Receiver.class).setAction(act.getPackageName() + ".M3U_STATUS");
                int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent p = PendingIntent.getBroadcast(act, 4711, i, fl);
                send("{\"type\":\"installing\"}");
                session.commit(p.getIntentSender());
                session.close();
            } catch (Exception e) {
                busy = false;
                if (session != null) try { session.abandon(); } catch (Exception ignored) {}
                send("{\"type\":\"error\",\"msg\":" + q(String.valueOf(e.getMessage())) + "}");
            }
        }).start();
    }

    /** Receives the PackageInstaller result; asks the user to confirm when Android needs it. */
    public static class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            int st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { ctx.startActivity(confirm); } catch (Exception ignored) {}
                }
                send("{\"type\":\"confirm\"}");
            } else if (st == PackageInstaller.STATUS_SUCCESS) {
                if (inst != null) inst.busy = false;
                send("{\"type\":\"done\"}");
            } else {
                if (inst != null) inst.busy = false;
                String m = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                send("{\"type\":\"" + (st == PackageInstaller.STATUS_FAILURE_ABORTED ? "cancel" : "error") + "\",\"msg\":" + q(m) + "}");
            }
        }
    }
}
