package com.mina.timesheet;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/** M3 Studio customer support bridge (window.M3): app info, http without CORS, and voice recording. Same file in every app. */
public class M3Feedback {
    private final Activity act;
    private final WebView web;
    private MediaRecorder rec;
    private File recFile;
    private long recStart;

    public M3Feedback(Activity a, WebView w) { act = a; web = w; }

    private void js(String s) { act.runOnUiThread(() -> web.evaluateJavascript(s, null)); }

    @JavascriptInterface
    public String info() {
        try {
            PackageInfo p = act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
            JSONObject o = new JSONObject();
            o.put("ver", p.versionName);
            o.put("code", Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode);
            o.put("pkg", act.getPackageName());
            o.put("device", Build.MANUFACTURER + " " + Build.MODEL);
            o.put("android", Build.VERSION.RELEASE);
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    @JavascriptInterface
    public void http(final int id, final String method, final String url, final String headers, final String body) {
        new Thread(() -> {
            int status = 0;
            String text;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(60000);
                c.setRequestMethod(method);
                JSONObject h = new JSONObject(headers == null || headers.isEmpty() ? "{}" : headers);
                for (Iterator<String> it = h.keys(); it.hasNext(); ) { String k = it.next(); c.setRequestProperty(k, h.getString(k)); }
                if (body != null && !body.isEmpty()) {
                    c.setDoOutput(true);
                    try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
                }
                status = c.getResponseCode();
                InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                if (in != null) { byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) b.write(buf, 0, n); in.close(); }
                text = b.toString("UTF-8");
            } catch (Exception e) {
                text = String.valueOf(e.getMessage());
            }
            js("window.m3OnHttp&&window.m3OnHttp(" + id + "," + status + "," + JSONObject.quote(text) + ")");
        }).start();
    }

    private boolean mic() { return act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED; }

    @JavascriptInterface
    public String micPerm() { return mic() ? "yes" : "no"; }

    @JavascriptInterface
    public void askMic() { act.runOnUiThread(() -> act.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7301)); }

    @JavascriptInterface
    public synchronized String recStart() {
        if (!mic()) return "perm";
        recCancel();
        try {
            recFile = new File(act.getCacheDir(), "m3voice.m4a");
            rec = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(act) : new MediaRecorder();
            rec.setAudioSource(MediaRecorder.AudioSource.MIC);
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            rec.setAudioChannels(1);
            rec.setAudioSamplingRate(22050);
            rec.setAudioEncodingBitRate(32000);
            rec.setOutputFile(recFile.getAbsolutePath());
            rec.prepare();
            rec.start();
            recStart = System.currentTimeMillis();
            return "ok";
        } catch (Exception e) {
            recCancel();
            return "err:" + e.getMessage();
        }
    }

    @JavascriptInterface
    public synchronized String recStop() {
        try {
            if (rec == null) return "{}";
            long ms = System.currentTimeMillis() - recStart;
            try { rec.stop(); } catch (Exception ignored) { }
            rec.release();
            rec = null;
            byte[] b = new byte[(int) recFile.length()];
            try (FileInputStream in = new FileInputStream(recFile)) { int off = 0, n; while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n; }
            recFile.delete();
            return new JSONObject().put("b64", Base64.encodeToString(b, Base64.NO_WRAP)).put("ms", ms).toString();
        } catch (Exception e) { return "{}"; }
    }

    @JavascriptInterface
    public synchronized void recCancel() {
        if (rec != null) { try { rec.stop(); } catch (Exception ignored) { } try { rec.release(); } catch (Exception ignored) { } rec = null; }
        if (recFile != null) recFile.delete();
    }
}
