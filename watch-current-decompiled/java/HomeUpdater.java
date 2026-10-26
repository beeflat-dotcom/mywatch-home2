package jp.beeflat.watchhomeptt;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

final class HomeUpdater {
    private static final long LIMIT = 64L * 1024L * 1024L;
    private final Activity owner;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean busy;
    private volatile boolean cancelled;
    private File pending;

    HomeUpdater(Activity activity) { this.owner = activity; }

    void begin(String manifestUrl) {
        if (busy) return;
        if (manifestUrl == null || !manifestUrl.startsWith("https://")) {
            toast("更新情報のHTTPS URLを設定してください");
            return;
        }
        final String token = owner.getSharedPreferences("ptt", 0).getString("token", "").trim();
        if (token.isEmpty()) {
            toast("先に6桁コードで接続登録してください");
            return;
        }
        busy = true;
        cancelled = false;
        AlertDialog dialog = new AlertDialog.Builder(owner)
                .setTitle("アプリ更新")
                .setMessage("更新を確認しています。")
                .setCancelable(false)
                .setNegativeButton("中止", (d, w) -> cancelled = true)
                .create();
        dialog.show();

        worker.execute(() -> {
            File candidate = null;
            String error = null;
            try {
                JSONObject meta = new JSONObject(readText(manifestUrl, 128 * 1024, token));
                int versionCode = meta.getInt("version_code");
                PackageInfo installed = owner.getPackageManager().getPackageInfo(owner.getPackageName(), PackageManager.GET_SIGNATURES);
                if (versionCode <= installed.versionCode) throw new AlreadyUpdated("すでに最新版です (v" + installed.versionName + ")");

                String apkUrl = meta.getString("apk_url");
                String expectedSha = meta.getString("sha256");
                if (apkUrl.startsWith("/")) {
                    URL base = new URL(manifestUrl);
                    apkUrl = new URL(base.getProtocol(), base.getHost(), base.getPort(), apkUrl).toString();
                }
                if (!apkUrl.startsWith("https://") || !expectedSha.matches("[0-9a-fA-F]{64}")) {
                    throw new IOException("配布情報が不正です");
                }

                candidate = new File(new File(owner.getCacheDir(), "updates"), "candidate.apk");
                File parent = candidate.getParentFile();
                if (!parent.exists() && !parent.mkdirs()) throw new IOException("保存領域がありません");
                download(apkUrl, candidate, expectedSha, token);
                verify(candidate);
            } catch (AlreadyUpdated e) {
                error = e.getMessage();
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final File result = candidate;
            final String finalError = error;
            owner.runOnUiThread(() -> {
                dialog.dismiss();
                busy = false;
                if (cancelled) {
                    if (result != null) result.delete();
                    return;
                }
                if (finalError != null) {
                    if (result != null) result.delete();
                    toast(finalError.startsWith("すでに最新版") ? finalError : "更新不可: " + finalError);
                    return;
                }
                pending = result;
                requestInstall();
            });
        });
    }

    void resume() {
        if (pending != null && Build.VERSION.SDK_INT >= 26 && owner.getPackageManager().canRequestPackageInstalls()) {
            launchInstaller();
        }
    }

    private void requestInstall() {
        if (pending == null || !pending.isFile()) return;
        if (Build.VERSION.SDK_INT >= 26 && !owner.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(owner)
                    .setTitle("初回のインストール許可")
                    .setMessage("設定を開き、このホームアプリからのインストールを許可してください。")
                    .setPositiveButton("設定を開く", (d, w) -> {
                        try {
                            owner.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + owner.getPackageName())));
                        } catch (RuntimeException e) {
                            toast("端末設定からこのアプリのインストール許可を有効にしてください");
                        }
                    })
                    .setNegativeButton("後で", null)
                    .show();
            return;
        }
        launchInstaller();
    }

    private void launchInstaller() {
        File apk = pending;
        if (apk == null || !apk.isFile()) return;
        pending = null;
        try {
            Uri uri = FileProvider.getUriForFile(owner, owner.getPackageName() + ".updates", apk);
            owner.startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (RuntimeException e) {
            toast("Androidのインストール画面を開けませんでした");
        }
    }

    private String readText(String url, int max, String token) throws Exception {
        HttpURLConnection c = open(url, token);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (n == 0) continue;
                    if (out.size() + n > max) throw new IOException("情報が大きすぎます");
                    out.write(buf, 0, n);
                }
                return out.toString("UTF-8");
            }
        } finally { c.disconnect(); }
    }

    private void download(String url, File file, String expectedSha, String token) throws Exception {
        HttpURLConnection c = open(url, token);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("APK HTTP " + code);
            long size = c.getContentLengthLong();
            if (size > LIMIT) throw new IOException("APKが大きすぎます");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(file)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (cancelled) throw new IOException("中止しました");
                    if (n == 0) continue;
                    total += n;
                    if (total > LIMIT) throw new IOException("APKが大きすぎます");
                    out.write(buf, 0, n);
                    md.update(buf, 0, n);
                }
            }
            if (size > 0 && total != size) throw new IOException("APK転送が不完全です");
            String actual = hex(md.digest());
            if (!expectedSha.equalsIgnoreCase(actual)) throw new IOException("SHA-256が一致しません");
        } finally { c.disconnect(); }
    }

    private void verify(File apk) throws Exception {
        PackageManager pm = owner.getPackageManager();
        PackageInfo installed = pm.getPackageInfo(owner.getPackageName(), PackageManager.GET_SIGNATURES);
        PackageInfo candidate = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNATURES);
        if (candidate == null || candidate.applicationInfo == null) throw new IOException("APKを解析できません");
        if (!installed.packageName.equals(candidate.packageName)) throw new IOException("旧ホームと異なるアプリIDです");
        if (candidate.versionCode <= installed.versionCode) throw new IOException("新しいバージョンではありません");
        if (candidate.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT) throw new IOException("Android 8.1非対応です");
        if (installed.signatures == null || candidate.signatures == null ||
                !Arrays.equals(installed.signatures, candidate.signatures)) {
            throw new IOException("APKの署名が現在のホームと一致しません");
        }
    }

    private HttpURLConnection open(String url, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(false);
        c.setUseCaches(false);
        c.setRequestProperty("Authorization", "Bearer " + token);
        return c;
    }

    private static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) s.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return s.toString();
    }

    private void toast(String text) {
        owner.runOnUiThread(() -> Toast.makeText(owner, text, Toast.LENGTH_LONG).show());
    }

    private static final class AlreadyUpdated extends IOException {
        AlreadyUpdated(String message) { super(message); }
    }
}
