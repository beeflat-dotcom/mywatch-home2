package jp.beeflat.watchhomeptt;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

final class RecordingUploader {
    private static final String PREFS = "watch_recording";
    private static final String PREF_QUEUE = "upload_queue_v1";
    private static final int JOB_ID = 2811;

    static synchronized void enqueue(Context context, File file) {
        if (file == null || !file.isFile() || file.length() <= 0) return;
        List<String> q = load(context);
        String path = file.getAbsolutePath();
        if (!q.contains(path)) q.add(path);
        save(context, q);
        schedule(context);
    }

    static void schedule(Context context) {
        try {
            JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            JobInfo.Builder b = new JobInfo.Builder(JOB_ID,
                    new ComponentName(context, RecordingUploadJobService.class));
            b.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
            b.setPersisted(true);
            b.setBackoffCriteria(30000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL);
            js.schedule(b.build());
        } catch (Exception e) {
            PttService.logEvent(context, "record upload schedule " + e.getClass().getSimpleName());
        }
    }

    static boolean drain(Context context) {
        String token = context.getSharedPreferences("ptt", 0).getString("token", "").trim();
        if (token.isEmpty()) return false;
        String endpoint = context.getSharedPreferences("ptt", 0).getString("endpoint", "").trim();
        try {
            String discovered = PttService.discover();
            if (discovered != null && discovered.startsWith("https://")) {
                endpoint = discovered;
                context.getSharedPreferences("ptt", 0).edit().putString("endpoint", endpoint).apply();
            }
        } catch (Exception ignored) {}
        if (!endpoint.startsWith("https://")) return false;
        while (endpoint.endsWith("/")) endpoint = endpoint.substring(0, endpoint.length() - 1);

        List<String> snapshot;
        synchronized (RecordingUploader.class) { snapshot = load(context); }
        boolean all = true;
        for (String path : snapshot) {
            File file = new File(path);
            if (!file.isFile()) {
                remove(context, path);
                continue;
            }
            try {
                if (uploadOne(context, endpoint, token, file)) remove(context, path);
                else all = false;
            } catch (Exception e) {
                all = false;
                PttService.logEvent(context, "record upload " + e.getClass().getSimpleName());
            }
        }
        synchronized (RecordingUploader.class) { return all && load(context).isEmpty(); }
    }

    private static boolean uploadOne(Context context, String base, String token, File file) throws Exception {
        String sha = sha256(file);
        long size = file.length();
        HttpURLConnection c = (HttpURLConnection) new URL(base + "/audio-upload").openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Content-Type", "application/octet-stream");
        c.setRequestProperty("X-Bee-Filename-B64", b64(file.getName()));
        c.setRequestProperty("X-Bee-Sha256", sha);
        c.setRequestProperty("X-Bee-Upload-Id", UUID.randomUUID().toString());
        c.setRequestProperty("X-Bee-Device-B64", b64(Build.MODEL == null ? "watch" : Build.MODEL));
        if (size <= Integer.MAX_VALUE) c.setFixedLengthStreamingMode((int) size);
        else c.setFixedLengthStreamingMode(size);
        try (InputStream in = new BufferedInputStream(new FileInputStream(file));
             OutputStream out = c.getOutputStream()) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) if (n > 0) out.write(buf, 0, n);
            out.flush();
        }
        int code = c.getResponseCode();
        InputStream resp = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String text = readText(resp);
        c.disconnect();
        if (code < 200 || code >= 300) return false;
        JSONObject json = new JSONObject(text);
        if (!json.optBoolean("ok", false)) return false;
        if (!sha.equalsIgnoreCase(json.optString("sha256", ""))) return false;
        if (!file.delete()) PttService.logEvent(context, "record local delete failed " + file.getName());
        return true;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] b = new byte[64 * 1024];
            int n;
            while ((n = in.read(b)) >= 0) if (n > 0) md.update(b, 0, n);
        }
        StringBuilder s = new StringBuilder();
        for (byte b : md.digest()) s.append(String.format("%02x", b & 0xff));
        return s.toString();
    }

    private static String b64(String s) {
        return Base64.encodeToString(s.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String readText(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream x = in) {
            byte[] b = new byte[4096];
            StringBuilder s = new StringBuilder();
            int n;
            while ((n = x.read(b)) >= 0) if (n > 0) s.append(new String(b, 0, n, StandardCharsets.UTF_8));
            return s.toString();
        }
    }

    private static synchronized List<String> load(Context context) {
        List<String> out = new ArrayList<>();
        try {
            SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray a = new JSONArray(p.getString(PREF_QUEUE, "[]"));
            for (int i = 0; i < a.length(); i++) {
                String path = a.optString(i, "");
                if (!path.isEmpty()) out.add(path);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static synchronized void save(Context context, List<String> q) {
        JSONArray a = new JSONArray();
        for (String path : q) a.put(path);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(PREF_QUEUE, a.toString()).apply();
    }

    private static synchronized void remove(Context context, String path) {
        List<String> q = load(context);
        q.remove(path);
        save(context, q);
    }

    private RecordingUploader() {}
}
