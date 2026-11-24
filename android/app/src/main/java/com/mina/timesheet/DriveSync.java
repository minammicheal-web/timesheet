package com.mina.timesheet;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Tasks;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Google Drive backup: a "Time Sheet Backups" folder in the user's own Drive, newest 30 kept. */
final class DriveSync {
    static final String SCOPE = "https://www.googleapis.com/auth/drive.file";
    static final String FOLDER = "Time Sheet Backups";
    static final String API = "https://www.googleapis.com/drive/v3/files";
    static final int KEEP = 30;

    private DriveSync() { }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("drive", Context.MODE_PRIVATE); }

    static File snapshot(Context c) { return new File(c.getFilesDir(), "latest-backup.json"); }

    static AuthorizationRequest request() {
        return AuthorizationRequest.builder()
                .setRequestedScopes(Collections.singletonList(new Scope(SCOPE)))
                .build();
    }

    /** Background thread only. Returns null when the user has to sign in again. */
    static String tokenSilently(Context c) throws Exception {
        AuthorizationResult r = Tasks.await(Identity.getAuthorizationClient(c).authorize(request()), 60, TimeUnit.SECONDS);
        if (r.hasResolution()) return null;
        return r.getAccessToken();
    }

    static void saveSnapshot(Context c, String json) throws IOException {
        File tmp = new File(c.getFilesDir(), "latest-backup.tmp");
        try (OutputStream o = new FileOutputStream(tmp)) { o.write(json.getBytes(StandardCharsets.UTF_8)); }
        if (!tmp.renameTo(snapshot(c))) throw new IOException("Could not save backup copy");
    }

    static byte[] readFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) { return readAll(in); }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    static String http(String method, String url, String token, String type, byte[] body) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setRequestMethod(method);
        h.setConnectTimeout(20000);
        h.setReadTimeout(60000);
        h.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) {
            h.setDoOutput(true);
            h.setRequestProperty("Content-Type", type);
            try (OutputStream o = h.getOutputStream()) { o.write(body); }
        }
        int code = h.getResponseCode();
        InputStream in = code < 400 ? h.getInputStream() : h.getErrorStream();
        String text = in == null ? "" : new String(readAll(in), StandardCharsets.UTF_8);
        h.disconnect();
        if (code >= 400) throw new IOException("Google Drive error " + code + ": " + text);
        return text;
    }

    static String q(String s) throws IOException { return URLEncoder.encode(s, "UTF-8"); }

    static String email(String token) throws Exception {
        JSONObject r = new JSONObject(http("GET", "https://www.googleapis.com/drive/v3/about?fields=user(emailAddress)", token, null, null));
        return r.getJSONObject("user").optString("emailAddress", "");
    }

    static String folderId(String token) throws Exception {
        String query = "name='" + FOLDER + "' and mimeType='application/vnd.google-apps.folder' and trashed=false";
        JSONArray f = new JSONObject(http("GET", API + "?spaces=drive&fields=files(id)&q=" + q(query), token, null, null)).getJSONArray("files");
        if (f.length() > 0) return f.getJSONObject(0).getString("id");
        JSONObject meta = new JSONObject().put("name", FOLDER).put("mimeType", "application/vnd.google-apps.folder");
        return new JSONObject(http("POST", API + "?fields=id", token, "application/json; charset=UTF-8",
                meta.toString().getBytes(StandardCharsets.UTF_8))).getString("id");
    }

    static JSONArray listBackups(String token, String folder, int size) throws Exception {
        String query = "'" + folder + "' in parents and trashed=false";
        return new JSONObject(http("GET", API + "?spaces=drive&orderBy=createdTime%20desc&pageSize=" + size
                + "&fields=files(id,name,createdTime)&q=" + q(query), token, null, null)).getJSONArray("files");
    }

    static void upload(Context c, String token) throws Exception {
        File s = snapshot(c);
        if (!s.exists()) throw new IOException("Nothing to back up yet — add an entry first.");
        byte[] data = readFile(s);
        String folder = folderId(token);
        String name = "timesheet-backup-" + new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(new Date()) + ".json";
        String b = "timesheet" + System.currentTimeMillis();
        JSONObject meta = new JSONObject().put("name", name).put("parents", new JSONArray().put(folder));
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + b + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + meta
                + "\r\n--" + b + "\r\nContent-Type: application/json\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(data);
        body.write(("\r\n--" + b + "--\r\n").getBytes(StandardCharsets.UTF_8));
        http("POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id", token,
                "multipart/related; boundary=" + b, body.toByteArray());
        JSONArray all = listBackups(token, folder, 100);
        for (int i = KEEP; i < all.length(); i++) {
            try { http("DELETE", API + "/" + all.getJSONObject(i).getString("id"), token, null, null); } catch (IOException ignored) { }
        }
        prefs(c).edit().putLong("last", System.currentTimeMillis()).putString("error", "").apply();
    }

    static String latest(String token) throws Exception {
        JSONArray f = listBackups(token, folderId(token), 1);
        if (f.length() == 0) throw new IOException("No backups found in your Google Drive yet.");
        return http("GET", API + "/" + f.getJSONObject(0).getString("id") + "?alt=media", token, null, null);
    }

    static void schedule(Context c) {
        PeriodicWorkRequest w = new PeriodicWorkRequest.Builder(BackupWorker.class, 24, TimeUnit.HOURS)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build();
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("daily-drive-backup", ExistingPeriodicWorkPolicy.KEEP, w);
    }

    static void unschedule(Context c) { WorkManager.getInstance(c).cancelUniqueWork("daily-drive-backup"); }
}
