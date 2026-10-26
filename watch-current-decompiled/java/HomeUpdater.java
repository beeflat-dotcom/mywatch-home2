package jp.beeflat.watchhomeptt;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
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
import kotlin.UByte;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes.dex */
final class HomeUpdater {
    private static final long LIMIT = 67108864;
    private volatile boolean busy;
    private final Activity owner;
    private File pending;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    HomeUpdater(Activity activity) {
        this.owner = activity;
    }

    boolean isBusy() {
        return this.busy;
    }

    void begin(final String str) {
        if (this.busy) {
            return;
        }
        if (str == null || !str.startsWith("https://")) {
            toast("更新情報のHTTPS URLを設定してください");
            return;
        }
        final String strTrim = this.owner.getSharedPreferences("ptt", 0).getString("token", "").trim();
        if (strTrim.isEmpty()) {
            toast("先に6桁コードで接続登録してください");
            return;
        }
        this.busy = true;
        final AlertDialog alertDialogCreate = new AlertDialog.Builder(this.owner).setTitle("アプリ更新").setMessage("更新を確認しています。").setCancelable(false).create();
        alertDialogCreate.setButton(-2, "中止", new DialogInterface.OnClickListener() { // from class: jp.beeflat.watchhomeptt.HomeUpdater$$ExternalSyntheticLambda0
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i) {
                HomeUpdater.this.lambda$begin$0(dialogInterface, i);
            }
        });
        alertDialogCreate.show();
        this.worker.execute(new Runnable() { // from class: jp.beeflat.watchhomeptt.HomeUpdater$$ExternalSyntheticLambda1
            @Override // java.lang.Runnable
            public final void run() {
                HomeUpdater.this.lambda$begin$2(str, strTrim, alertDialogCreate);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$begin$0(DialogInterface dialogInterface, int i) {
        this.busy = false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$begin$2(String str, String str2, final AlertDialog alertDialog) {
        String simpleName;
        File file = null;
        final String str3 = null;
        file = null;
        try {
            JSONObject jSONObject = new JSONObject(readText(str, 131072, str2));
            int i = jSONObject.getInt("version_code");
            PackageInfo packageInfo = this.owner.getPackageManager().getPackageInfo(this.owner.getPackageName(), 0);
            if (i <= packageInfo.versionCode) {
                throw new AlreadyUpdated("すでに最新版です (v" + packageInfo.versionName + ")");
            }
            String string = jSONObject.getString("apk_url");
            String string2 = jSONObject.getString("sha256");
            if (string.startsWith("/")) {
                URL url = new URL(str);
                string = new URL(url.getProtocol(), url.getHost(), url.getPort(), string).toString();
            }
            if (!string.startsWith("https://") || !string2.matches("[0-9a-fA-F]{64}")) {
                throw new IOException("配布情報が不正です");
            }
            final File file2 = new File(new File(this.owner.getCacheDir(), "updates"), "candidate.apk");
            try {
                if (!file2.getParentFile().exists() && !file2.getParentFile().mkdirs()) {
                    throw new IOException("保存領域がありません");
                }
                download(string, file2, string2, str2);
                verify(file2);
            } catch (AlreadyUpdated e) {
                file = file2;
                e = e;
                simpleName = e.getMessage();
                File file3 = file;
                str3 = simpleName;
                file2 = file3;
            } catch (Exception e2) {
                file = file2;
                e = e2;
                simpleName = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                File file4 = file;
                str3 = simpleName;
                file2 = file4;
            }
            this.owner.runOnUiThread(new Runnable() { // from class: jp.beeflat.watchhomeptt.HomeUpdater$$ExternalSyntheticLambda2
                @Override // java.lang.Runnable
                public final void run() {
                    HomeUpdater.this.lambda$begin$1(alertDialog, file2, str3);
                }
            });
        } catch (AlreadyUpdated e3) {
            e = e3;
        } catch (Exception e4) {
            e = e4;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$begin$1(AlertDialog alertDialog, File file, String str) {
        alertDialog.dismiss();
        if (!this.busy) {
            if (file != null) {
                file.delete();
                return;
            }
            return;
        }
        this.busy = false;
        if (str == null) {
            this.pending = file;
            install();
            return;
        }
        if (file != null) {
            file.delete();
        }
        if (!str.startsWith("すでに最新版")) {
            str = "更新不可: " + str;
        }
        toast(str);
    }

    private static final class AlreadyUpdated extends IOException {
        AlreadyUpdated(String str) {
            super(str);
        }
    }

    private String readText(String str, int i, String str2) throws Exception {
        HttpURLConnection httpURLConnection = (HttpURLConnection) new URL(str).openConnection();
        httpURLConnection.setConnectTimeout(12000);
        httpURLConnection.setReadTimeout(60000);
        httpURLConnection.setInstanceFollowRedirects(false);
        httpURLConnection.setRequestProperty("Authorization", "Bearer " + str2);
        try {
            if (httpURLConnection.getResponseCode() != 200) {
                throw new IOException("HTTP " + httpURLConnection.getResponseCode());
            }
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            InputStream inputStream = httpURLConnection.getInputStream();
            try {
                byte[] bArr = new byte[8192];
                while (true) {
                    int i2 = inputStream.read(bArr);
                    if (i2 == -1) {
                        if (inputStream != null) {
                            inputStream.close();
                        }
                        String string = byteArrayOutputStream.toString("UTF-8");
                        httpURLConnection.disconnect();
                        return string;
                    }
                    if (byteArrayOutputStream.size() + i2 > i) {
                        throw new IOException("情報が大きすぎます");
                    }
                    byteArrayOutputStream.write(bArr, 0, i2);
                    httpURLConnection.disconnect();
                    throw th;
                }
            } catch (Throwable th) {
                if (inputStream != null) {
                    try {
                        inputStream.close();
                    } catch (Throwable th2) {
                        th.addSuppressed(th2);
                    }
                }
                throw th;
            }
        } catch (Throwable th3) {
            httpURLConnection.disconnect();
            throw th3;
        }
    }

    /* JADX WARN: Code duplicated, block: B:64:0x00e3 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Code duplicated, block: B:74:? A[Catch: all -> 0x010a, SYNTHETIC, TryCatch #3 {all -> 0x010a, blocks: (B:3:0x0034, B:5:0x003c, B:8:0x0049, B:21:0x007f, B:27:0x008d, B:28:0x0094, B:29:0x0095, B:31:0x00a1, B:32:0x00bb, B:36:0x00cb, B:37:0x00d2, B:54:0x00ec, B:53:0x00e9, B:55:0x00ed, B:56:0x00f2, B:57:0x00f3, B:58:0x0109, B:49:0x00e3, B:9:0x0053, B:19:0x007a, B:45:0x00de, B:44:0x00db), top: B:68:0x0034, inners: #1, #4 }] */
    private void download(String str, File file, String str2, String str3) throws Exception {
        HttpURLConnection httpURLConnection = (HttpURLConnection) new URL(str).openConnection();
        httpURLConnection.setConnectTimeout(12000);
        httpURLConnection.setReadTimeout(60000);
        httpURLConnection.setInstanceFollowRedirects(false);
        httpURLConnection.setRequestProperty("Authorization", "Bearer " + str3);
        try {
            if (httpURLConnection.getResponseCode() != 200) {
                throw new IOException("APK HTTP " + httpURLConnection.getResponseCode());
            }
            long contentLengthLong = httpURLConnection.getContentLengthLong();
            if (contentLengthLong > LIMIT) {
                throw new IOException("APKが大きすぎます");
            }
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            InputStream inputStream = httpURLConnection.getInputStream();
            try {
                FileOutputStream fileOutputStream = new FileOutputStream(file);
                try {
                    byte[] bArr = new byte[16384];
                    long j = 0;
                    while (true) {
                        int i = inputStream.read(bArr);
                        if (i == -1) {
                            fileOutputStream.close();
                            if (inputStream != null) {
                                inputStream.close();
                            }
                            if (contentLengthLong > 0 && j != contentLengthLong) {
                                throw new IOException("APK転送が不完全です");
                            }
                            StringBuilder sb = new StringBuilder();
                            for (byte b : messageDigest.digest()) {
                                sb.append(String.format(Locale.ROOT, "%02x", Integer.valueOf(b & UByte.MAX_VALUE)));
                            }
                            if (!str2.equalsIgnoreCase(sb.toString())) {
                                throw new IOException("SHA-256が一致しません");
                            }
                            httpURLConnection.disconnect();
                            return;
                        }
                        j += (long) i;
                        if (j > LIMIT) {
                            throw new IOException("APKが大きすぎます");
                        }
                        fileOutputStream.write(bArr, 0, i);
                        messageDigest.update(bArr, 0, i);
                        if (inputStream != null) {
                            throw th;
                        }
                        try {
                            inputStream.close();
                            throw th;
                        } catch (Throwable th) {
                            th.addSuppressed(th);
                            throw th;
                        }
                    }
                } catch (Throwable th2) {
                    try {
                        fileOutputStream.close();
                        throw th2;
                    } catch (Throwable th3) {
                        th2.addSuppressed(th3);
                        throw th2;
                    }
                }
            } catch (Throwable th4) {
                if (inputStream != null) {
                    throw th4;
                }
                inputStream.close();
                throw th4;
            }
        } catch (Throwable th5) {
            httpURLConnection.disconnect();
            throw th5;
        }
    }

    private void verify(File file) throws Exception {
        PackageManager packageManager = this.owner.getPackageManager();
        PackageInfo packageInfo = packageManager.getPackageInfo(this.owner.getPackageName(), 64);
        PackageInfo packageArchiveInfo = packageManager.getPackageArchiveInfo(file.getAbsolutePath(), 64);
        if (packageArchiveInfo == null || packageArchiveInfo.applicationInfo == null) {
            throw new IOException("APKを解析できません");
        }
        if (!packageInfo.packageName.equals(packageArchiveInfo.packageName)) {
            throw new IOException("旧ホームと異なるアプリIDです。別アプリとして初回導入が必要です");
        }
        if (packageArchiveInfo.versionCode <= packageInfo.versionCode) {
            throw new IOException("新しいバージョンではありません");
        }
        if (packageArchiveInfo.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT) {
            throw new IOException("Android 8.1非対応です");
        }
        if (packageInfo.signatures == null || packageArchiveInfo.signatures == null || !Arrays.equals(packageInfo.signatures, packageArchiveInfo.signatures)) {
            throw new IOException("署名が異なります。旧ホームと同じ鍵でビルドしてください");
        }
    }

    void resume() {
        if (this.pending == null || !this.owner.getPackageManager().canRequestPackageInstalls()) {
            return;
        }
        install();
    }

    private void install() {
        if (this.pending == null) {
            return;
        }
        if (!this.owner.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this.owner).setTitle("インストール許可").setMessage("このホームアプリからのインストールを許可してください。").setPositiveButton("設定を開く", new DialogInterface.OnClickListener() { // from class: jp.beeflat.watchhomeptt.HomeUpdater$$ExternalSyntheticLambda3
                @Override // android.content.DialogInterface.OnClickListener
                public final void onClick(DialogInterface dialogInterface, int i) {
                    HomeUpdater.this.lambda$install$3(dialogInterface, i);
                }
            }).setNegativeButton("後で", (DialogInterface.OnClickListener) null).show();
            return;
        }
        File file = this.pending;
        this.pending = null;
        this.owner.startActivity(new Intent("android.intent.action.VIEW").setDataAndType(FileProvider.getUriForFile(this.owner, this.owner.getPackageName() + ".updates", file), "application/vnd.android.package-archive").addFlags(1));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$install$3(DialogInterface dialogInterface, int i) {
        this.owner.startActivity(new Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES").setData(Uri.parse("package:" + this.owner.getPackageName())));
    }

    private void toast(String str) {
        Toast.makeText(this.owner, str, 1).show();
    }
}
