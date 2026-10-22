package jp.beeflat.beetransceiver;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.Toast;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipFile;

/** Checks official public GitHub releases and installs a SHA-256-verified APK with user consent. */
final class AppUpdater {
    private static final String RELEASES_URL =
            "https://api.github.com/repos/beeflat-dotcom/mywatch-home2/releases?per_page=20";
    private static final String RELEASE_TAG_PREFIX = "bee-ptt-net-v";
    private static final String ASSET_NAME = "BeeTransceiver-NET.apk";
    private static final String DOWNLOAD_PREFIX =
            "https://github.com/beeflat-dotcom/mywatch-home2/releases/download/";
    private static final long MAX_DOWNLOAD_BYTES = 80L * 1024L * 1024L;

    private final Activity activity;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final Button button;
    private File pendingInstall;

    AppUpdater(Activity activity, Button button) {
        this.activity = activity;
        this.button = button;
    }

    void check() {
        if (!busy.compareAndSet(false, true)) return;
        setBusy(true);
        new Thread(() -> {
            try {
                ReleaseInfo latest = findLatestRelease();
                if (latest == null) {
                    message("更新を確認できません", "公式の配布APKがまだ公開されていません。");
                } else if (compareVersions(latest.version, BuildConfig.VERSION_NAME) <= 0) {
                    message("最新版です", "現在のバージョン: " + BuildConfig.VERSION_NAME);
                } else {
                    ui.post(() -> new AlertDialog.Builder(activity)
                            .setTitle("新しいバージョンがあります")
                            .setMessage("現在: v" + BuildConfig.VERSION_NAME + "\n"
                                    + "最新版: v" + latest.version + "\n"
                                    + "ダウンロードして更新しますか？")
                            .setNegativeButton("キャンセル", null)
                            .setPositiveButton("更新する", (dialog, which) -> download(latest))
                            .show());
                }
            } catch (Exception error) {
                BeeLog.write(activity, "app_update_check_error", error.toString());
                message("更新確認エラー", readable(error));
            } finally {
                busy.set(false);
                setBusy(false);
            }
        }, "BeeAppUpdateCheck").start();
    }

    private static final class ReleaseInfo {
        final String version;
        final String url;
        final String sha256;
        ReleaseInfo(String version, String url, String sha256) {
            this.version=version; this.url=url; this.sha256=sha256;
        }
    }

    private ReleaseInfo findLatestRelease() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(RELEASES_URL).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(12000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "BeeTransceiver-NET-Updater");
        try {
            if (connection.getResponseCode() != 200) {
                throw new IOException("GitHub API HTTP " + connection.getResponseCode());
            }
            JSONArray releases = new JSONArray(readSmall(connection.getInputStream(), 1024*1024));
            ReleaseInfo newest=null;
            for (int i=0;i<releases.length();i++) {
                JSONObject rel=releases.getJSONObject(i);
                if (rel.optBoolean("draft", false) || rel.optBoolean("prerelease", false)) continue;
                String tag=rel.optString("tag_name", "");
                if (!tag.startsWith(RELEASE_TAG_PREFIX)) continue;
                String version=tag.substring(RELEASE_TAG_PREFIX.length());
                if (!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) continue;
                JSONArray assets=rel.optJSONArray("assets");
                if (assets==null) continue;
                for (int j=0;j<assets.length();j++) {
                    JSONObject asset=assets.getJSONObject(j);
                    if (!ASSET_NAME.equals(asset.optString("name"))) continue;
                    String uri=asset.optString("browser_download_url","");
                    String digest=asset.optString("digest","");
                    if (!uri.startsWith(DOWNLOAD_PREFIX) || !digest.matches("sha256:[0-9a-fA-F]{64}")) {
                        throw new IOException("公式APKの検証情報が不完全です");
                    }
                    ReleaseInfo candidate=new ReleaseInfo(version,uri,digest.substring(7).toLowerCase(Locale.ROOT));
                    if (newest==null || compareVersions(candidate.version,newest.version)>0) newest=candidate;
                }
            }
            return newest;
        } finally {
            connection.disconnect();
        }
    }

    private void download(ReleaseInfo release) {
        if (!busy.compareAndSet(false, true)) return;
        setBusy(true);
        new Thread(() -> {
            File temp=null;
            try {
                File folder=new File(activity.getExternalFilesDir(null),"updates");
                if (!folder.exists() && !folder.mkdirs()) throw new IOException("更新保存先を作れません");
                File apk=new File(folder,"BeeTransceiver-NET-" + release.version + ".apk");
                temp=new File(folder,"new-version.part");
                if (temp.exists() && !temp.delete()) throw new IOException("前回の一時ファイルを削除できません");
                MessageDigest md=MessageDigest.getInstance("SHA-256");
                HttpURLConnection conn=(HttpURLConnection)new URL(release.url).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent","BeeTransceiver-NET-Updater");
                long total=0;
                try {
                    if (conn.getResponseCode()!=200) throw new IOException("APK取得 HTTP "+conn.getResponseCode());
                    if (!"https".equalsIgnoreCase(conn.getURL().getProtocol())) throw new IOException("HTTPS接続ではありません");
                    try(InputStream input=conn.getInputStream();FileOutputStream out=new FileOutputStream(temp)) {
                        byte[] buffer=new byte[32768];int count;
                        while ((count=input.read(buffer))!=-1) {
                            total+=count;
                            if (total>MAX_DOWNLOAD_BYTES) throw new IOException("APKのサイズが大きすぎます");
                            md.update(buffer,0,count);
                            out.write(buffer,0,count);
                        }
                        out.getFD().sync();
                    }
                } finally { conn.disconnect(); }
                if (total<1024) throw new IOException("APKが不完全です");
                StringBuilder actual=new StringBuilder();
                for(byte b:md.digest()) actual.append(String.format(Locale.ROOT,"%02x",b&255));
                if (!release.sha256.equals(actual.toString())) throw new IOException("APKのSHA256が一致しません");
                try(ZipFile zip=new ZipFile(temp)) {
                    if (zip.getEntry("AndroidManifest.xml")==null) throw new IOException("APK形式ではありません");
                }
                if (apk.exists() && !apk.delete()) throw new IOException("旧APKを置き換えられません");
                if (!temp.renameTo(apk)) throw new IOException("更新ファイルを保存できません");
                pendingInstall=apk;
                ui.post(this::launchInstallOrSettings);
            } catch (Exception error) {
                if (temp!=null) temp.delete();
                BeeLog.write(activity,"app_update_download_error",error.toString());
                message("更新失敗", readable(error));
            } finally {
                busy.set(false);
                setBusy(false);
            }
        }, "BeeAppUpdateDownload").start();
    }

    void onResume() {
        if (pendingInstall!=null && Build.VERSION.SDK_INT>=26
                && activity.getPackageManager().canRequestPackageInstalls()) {
            ui.post(this::launchInstallOrSettings);
        }
    }

    private void launchInstallOrSettings() {
        File apk=pendingInstall;
        if (apk==null || !apk.isFile() || activity.isFinishing()) return;
        if (Build.VERSION.SDK_INT>=26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity)
                    .setTitle("更新のインストールを許可")
                    .setMessage("このアプリからAPKをインストールする許可が必要です。設定画面で許可した後、アプリに戻ってください。")
                    .setNegativeButton("あとで",null)
                    .setPositiveButton("設定を開く",(d,w)-> {
                        Intent i=new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:"+activity.getPackageName()));
                        activity.startActivity(i);
                    }).show();
            return;
        }
        try {
            Uri uri=FileProvider.getUriForFile(activity,
                    activity.getPackageName()+".fileprovider",apk);
            Intent install=new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri,"application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(install);
            pendingInstall=null;
        } catch (Exception e) {
            BeeLog.write(activity,"app_update_install_error",e.toString());
            message("インストールを開始できません",readable(e));
        }
    }

    private static int compareVersions(String a,String b) {
        String[] x=a.split("\\."),y=b.split("\\.");
        for(int i=0;i<3;i++) {
            long left=i<x.length?Long.parseLong(x[i]):0;
            long right=i<y.length?Long.parseLong(y[i]):0;
            if(left!=right)return left>right?1:-1;
        }
        return 0;
    }

    private static String readSmall(InputStream stream,int max) throws Exception {
        try(InputStream in=stream;java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int n;
            while((n=in.read(buffer))!=-1) {
                if(out.size()+n>max) throw new IOException("応答データが大きすぎます");
                out.write(buffer,0,n);
            }
            return out.toString("UTF-8");
        }
    }

    private void setBusy(boolean isBusy) {
        ui.post(()->{
            button.setEnabled(!isBusy);
            button.setText(isBusy?"更新処理中…":"アップデート確認");
        });
    }
    private void message(String title,String text) {
        ui.post(()->{
            if(!activity.isFinishing())new AlertDialog.Builder(activity)
                    .setTitle(title).setMessage(text).setPositiveButton("OK",null).show();
        });
    }
    private static String readable(Exception e) {
        String text=e.getMessage();
        return text==null||text.isEmpty()?e.getClass().getSimpleName():text;
    }
}
