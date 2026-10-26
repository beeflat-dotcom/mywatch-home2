package jp.beeflat.watchhomeptt;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import kotlin.UByte;
import kotlin.UShort;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes.dex */
public final class PttService extends Service {
    private static final String CHANNEL = "bee_home_ptt";
    private static final String DISCOVERY = "https://raw.githubusercontent.com/beeflat-dotcom/mywatch-home2/main/bee-audio/audio_external_api.json";
    private static final int FRAMES = 320;
    private static final int RATE = 16000;
    static final String START = "jp.beeflat.watchhomeptt.START";
    private static final long TALK_MAX_MS = 60000;
    static final String TALK_START = "jp.beeflat.watchhomeptt.TALK_START";
    static final String TALK_STOP = "jp.beeflat.watchhomeptt.TALK_STOP";
    private static volatile String status = "受信待機中";
    private BeeClipLan beeClipLan;
    private Thread player;
    private Thread sender;
    private volatile WebSocket socket;
    private AudioTrack track;
    private UUID uuid;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private final OkHttpClient client = new OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).retryOnConnectionFailure(true).build();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean talkActive = false;
    private volatile boolean running = true;
    private volatile boolean connecting = false;
    private LinkedBlockingDeque<byte[]> playback = new LinkedBlockingDeque<>(12);
    private String endpoint = "";
    private String token = "";
    private int sequence = 0;
    private long retry = 1500;
    private final Runnable retryTask = new Runnable() { // from class: jp.beeflat.watchhomeptt.PttService$$ExternalSyntheticLambda2
        @Override // java.lang.Runnable
        public final void run() {
            this.f$0.lambda$new$0();
        }
    };
    private final Runnable talkTimeout = new Runnable() { // from class: jp.beeflat.watchhomeptt.PttService$$ExternalSyntheticLambda3
        @Override // java.lang.Runnable
        public final void run() {
            this.f$0.lambda$new$1();
        }
    };
    private final Object soundGuard = new Object();

    @Override // android.app.Service
    public IBinder onBind(Intent intent) {
        return null;
    }

    static String currentState() {
        return status;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$new$1() {
        if (this.talkActive) {
            event("talk auto stop 60000ms");
            stopTalk();
        }
    }

    private static File eventFile(Context context) {
        return new File(context.getFilesDir(), "home_ptt_events.log");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void event(String str) {
        try {
            File fileEventFile = eventFile(this);
            if (fileEventFile.length() > 120000) {
                File file = new File(getFilesDir(), "home_ptt_events.old.log");
                if (file.exists()) {
                    file.delete();
                }
                fileEventFile.renameTo(file);
            }
            FileWriter fileWriter = new FileWriter(fileEventFile, true);
            try {
                fileWriter.write(new Date() + " " + str + "\n");
                fileWriter.close();
            } catch (Throwable th) {
                try {
                    fileWriter.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
                throw th;
            }
        } catch (Exception unused) {
        }
    }

    static String readEvents(Context context) {
        File fileEventFile = eventFile(context);
        if (!fileEventFile.exists()) {
            return "端末ログなし";
        }
        try {
            int iMin = (int) Math.min(15000L, fileEventFile.length());
            byte[] bArr = new byte[iMin];
            RandomAccessFile randomAccessFile = new RandomAccessFile(fileEventFile, "r");
            try {
                randomAccessFile.seek(Math.max(0L, fileEventFile.length() - ((long) iMin)));
                randomAccessFile.readFully(bArr);
                randomAccessFile.close();
                return new String(bArr, StandardCharsets.UTF_8);
            } catch (Throwable th) {
                try {
                    randomAccessFile.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
                throw th;
            }
        } catch (Exception e) {
            return "読込失敗 " + e.getClass().getSimpleName();
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("ptt", 0);
    }

    private static String deviceName(Context context) {
        return "腕時計-" + Build.MODEL;
    }

    static void pair(final Context context, final String str) {
        new Thread(new Runnable() { // from class: jp.beeflat.watchhomeptt.PttService$$ExternalSyntheticLambda0
            @Override // java.lang.Runnable
            public final void run() throws Throwable {
                PttService.lambda$pair$2(context, str);
            }
        }, "PTT-Pair").start();
    }

    static /* synthetic */ void lambda$pair$2(Context context, String str) throws Throwable {
        HttpURLConnection httpURLConnection = null;
        try {
            try {
                SharedPreferences sharedPreferences = context.getSharedPreferences("ptt", 0);
                String strTrim = sharedPreferences.getString("endpoint", "").trim();
                if (strTrim.isEmpty()) {
                    strTrim = discover();
                }
                if (!strTrim.startsWith("https://")) {
                    throw new IOException("接続URLがありません");
                }
                HttpURLConnection httpURLConnection2 = (HttpURLConnection) new URL(strTrim + "/ptt/pair").openConnection();
                try {
                    httpURLConnection2.setRequestMethod("POST");
                    httpURLConnection2.setDoOutput(true);
                    httpURLConnection2.setConnectTimeout(10000);
                    httpURLConnection2.setReadTimeout(10000);
                    httpURLConnection2.setRequestProperty("Content-Type", "application/json");
                    byte[] bytes = new JSONObject().put("code", str).toString().getBytes(StandardCharsets.UTF_8);
                    OutputStream outputStream = httpURLConnection2.getOutputStream();
                    try {
                        outputStream.write(bytes);
                        if (outputStream != null) {
                            outputStream.close();
                        }
                        if (httpURLConnection2.getResponseCode() != 200) {
                            throw new IOException("ペアリングHTTP " + httpURLConnection2.getResponseCode());
                        }
                        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                        InputStream inputStream = httpURLConnection2.getInputStream();
                        try {
                            byte[] bArr = new byte[2048];
                            while (true) {
                                int i = inputStream.read(bArr);
                                if (i == -1 || byteArrayOutputStream.size() >= 10000) {
                                    break;
                                } else {
                                    byteArrayOutputStream.write(bArr, 0, i);
                                }
                            }
                            if (inputStream != null) {
                                inputStream.close();
                            }
                            sharedPreferences.edit().putString("token", new JSONObject(byteArrayOutputStream.toString("UTF-8")).getString("token")).putString("endpoint", strTrim).apply();
                            context.startForegroundService(new Intent(context, (Class<?>) PttService.class).setAction(START));
                            if (httpURLConnection2 != null) {
                                httpURLConnection2.disconnect();
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
                        if (outputStream != null) {
                            try {
                                outputStream.close();
                            } catch (Throwable th4) {
                                th3.addSuppressed(th4);
                            }
                        }
                        throw th3;
                    }
                } catch (Exception e) {
                    e = e;
                    httpURLConnection = httpURLConnection2;
                    status = "ペアリング失敗: " + e.getMessage();
                    if (httpURLConnection != null) {
                        httpURLConnection.disconnect();
                    }
                } catch (Throwable th5) {
                    th = th5;
                    httpURLConnection = httpURLConnection2;
                    if (httpURLConnection != null) {
                        httpURLConnection.disconnect();
                    }
                    throw th;
                }
            } catch (Exception e2) {
                e = e2;
            }
        } catch (Throwable th6) {
            th = th6;
        }
    }

    static String discover() throws Exception {
        HttpURLConnection httpURLConnection = (HttpURLConnection) new URL(DISCOVERY).openConnection();
        httpURLConnection.setConnectTimeout(6000);
        httpURLConnection.setReadTimeout(6000);
        try {
            if (httpURLConnection.getResponseCode() != 200) {
                throw new IOException("接続先取得HTTP " + httpURLConnection.getResponseCode());
            }
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            InputStream inputStream = httpURLConnection.getInputStream();
            try {
                byte[] bArr = new byte[2048];
                while (true) {
                    int i = inputStream.read(bArr);
                    if (i == -1 || byteArrayOutputStream.size() >= 12000) {
                        break;
                    }
                    byteArrayOutputStream.write(bArr, 0, i);
                    httpURLConnection.disconnect();
                    throw th;
                }
                if (inputStream != null) {
                    inputStream.close();
                }
                JSONObject jSONObject = new JSONObject(byteArrayOutputStream.toString("UTF-8"));
                if (!"running".equalsIgnoreCase(jSONObject.optString("phase"))) {
                    throw new IOException("公開先停止中");
                }
                String strReplaceAll = jSONObject.getString("external_api_url").replaceAll("/+$", "");
                httpURLConnection.disconnect();
                return strReplaceAll;
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

    @Override // android.app.Service
    public void onCreate() {
        super.onCreate();
        String string = prefs().getString("uuid", "");
        if (string.isEmpty()) {
            string = newUuid();
        }
        this.uuid = UUID.fromString(string);
        createChannel();
        startForeground(2808, notice("受信待機中"));
        acquireLocks();
        BeeClipLan beeClipLan = new BeeClipLan(this);
        this.beeClipLan = beeClipLan;
        beeClipLan.start();
        startPlayer();
        lambda$new$0();
    }

    private String newUuid() {
        String string = UUID.randomUUID().toString();
        prefs().edit().putString("uuid", string).apply();
        return string;
    }

    @Override // android.app.Service
    public int onStartCommand(Intent intent, int i, int i2) {
        String action = intent == null ? START : intent.getAction();
        if (TALK_START.equals(action)) {
            startTalk();
            return 1;
        }
        if (TALK_STOP.equals(action)) {
            stopTalk();
            return 1;
        }
        if (this.socket != null || this.connecting) {
            return 1;
        }
        lambda$new$0();
        return 1;
    }

    private void createChannel() {
        ((NotificationManager) getSystemService("notification")).createNotificationChannel(new NotificationChannel(CHANNEL, "びー・ホームトランシーバー", 2));
    }

    private Notification notice(String str) {
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("びーホーム PTT").setContentText(str).setOngoing(true).build();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setStatus(String str) {
        status = str;
        ((NotificationManager) getSystemService("notification")).notify(2808, notice(str));
    }

    private void acquireLocks() {
        try {
            WifiManager.WifiLock wifiLockCreateWifiLock = ((WifiManager) getApplicationContext().getSystemService("wifi")).createWifiLock(3, "BeeHomePTT:Wifi");
            this.wifiLock = wifiLockCreateWifiLock;
            wifiLockCreateWifiLock.acquire();
        } catch (Exception e) {
            event("wifi lock " + e);
        }
        try {
            PowerManager.WakeLock wakeLockNewWakeLock = ((PowerManager) getSystemService("power")).newWakeLock(1, "BeeHomePTT:Receive");
            this.wakeLock = wakeLockNewWakeLock;
            wakeLockNewWakeLock.acquire();
        } catch (Exception e2) {
            event("wake lock " + e2);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX INFO: renamed from: connect, reason: merged with bridge method [inline-methods] */
    public void lambda$new$0() {
        if (this.running && !this.connecting && this.socket == null) {
            this.connecting = true;
            new Thread(new Runnable() { // from class: jp.beeflat.watchhomeptt.PttService$$ExternalSyntheticLambda5
                @Override // java.lang.Runnable
                public final void run() {
                    this.f$0.lambda$connect$3();
                }
            }, "PTT-Connect").start();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$connect$3() {
        try {
            String string = prefs().getString("token", "");
            if (string.isEmpty()) {
                this.connecting = false;
                setStatus("初回ペアリングが必要です");
                return;
            }
            String strTrim = prefs().getString("endpoint", "").trim();
            try {
                String strDiscover = discover();
                if (!strDiscover.equals(strTrim)) {
                    prefs().edit().putString("endpoint", strDiscover).apply();
                    event("relay endpoint refreshed");
                }
                strTrim = strDiscover;
            } catch (Exception e) {
                if (strTrim.isEmpty()) {
                    throw e;
                }
                event("relay endpoint discovery unavailable; trying cached endpoint");
            }
            if (!strTrim.startsWith("https://")) {
                throw new IOException("HTTPS URLが必要");
            }
            this.endpoint = strTrim;
            this.token = string;
            this.socket = this.client.newWebSocket(new Request.Builder().url(strTrim.replaceFirst("^https:", "wss:") + "/ptt/ws").header("Authorization", "Bearer " + this.token).build(), new WebSocketListener() { // from class: jp.beeflat.watchhomeptt.PttService.1
                @Override // okhttp3.WebSocketListener
                public void onOpen(WebSocket webSocket, Response response) {
                    PttService.this.connecting = false;
                    PttService.this.retry = 1500L;
                    PttService.this.setStatus("トランシーバー接続中");
                    PttService.this.event("connected");
                }

                @Override // okhttp3.WebSocketListener
                public void onMessage(WebSocket webSocket, ByteString byteString) {
                    PttService.this.enqueue(byteString.toByteArray());
                }

                @Override // okhttp3.WebSocketListener
                public void onClosed(WebSocket webSocket, int i, String str) {
                    PttService.this.gone(webSocket, "closed " + i);
                }

                @Override // okhttp3.WebSocketListener
                public void onFailure(WebSocket webSocket, Throwable th, Response response) {
                    PttService.this.gone(webSocket, "error " + th.getClass().getSimpleName());
                }
            });
            setStatus("接続中…");
        } catch (Exception e2) {
            this.connecting = false;
            setStatus("接続失敗: " + e2.getMessage());
            event("connect " + e2);
            scheduleRetry();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void gone(WebSocket webSocket, String str) {
        if (this.socket != webSocket) {
            return;
        }
        this.socket = null;
        this.connecting = false;
        setStatus("再接続待機中");
        event(str);
        scheduleRetry();
    }

    private void scheduleRetry() {
        if (this.running) {
            this.ui.removeCallbacks(this.retryTask);
            this.ui.postDelayed(this.retryTask, this.retry);
            this.retry = Math.min(30000L, this.retry * 2);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void enqueue(byte[] bArr) {
        try {
            ByteBuffer byteBufferOrder = ByteBuffer.wrap(bArr).order(ByteOrder.BIG_ENDIAN);
            if (byteBufferOrder.remaining() >= 28 && byteBufferOrder.get() == 66 && byteBufferOrder.get() == 80 && byteBufferOrder.get() == 84 && byteBufferOrder.get() == 49 && byteBufferOrder.get() == 1 && !new UUID(byteBufferOrder.getLong(), byteBufferOrder.getLong()).equals(this.uuid)) {
                byteBufferOrder.getInt();
                int i = byteBufferOrder.get() & UByte.MAX_VALUE;
                if (i > byteBufferOrder.remaining() - 2) {
                    return;
                }
                byteBufferOrder.get(new byte[i]);
                int i2 = byteBufferOrder.getShort() & UShort.MAX_VALUE;
                if (i2 > 0 && i2 <= byteBufferOrder.remaining() && i2 % 2 == 0) {
                    byte[] bArr2 = new byte[i2];
                    byteBufferOrder.get(bArr2);
                    if (this.talkActive || this.playback.offerLast(bArr2)) {
                        return;
                    }
                    this.playback.pollFirst();
                    this.playback.offerLast(bArr2);
                }
            }
        } catch (Exception unused) {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$startPlayer$4() {
        AudioTrack audioTrack;
        try {
            AudioTrack audioTrack2 = new AudioTrack(3, RATE, 4, 2, Math.max(AudioTrack.getMinBufferSize(RATE, 4, 2), 5120), 1);
            this.track = audioTrack2;
            audioTrack2.play();
            while (this.running) {
                byte[] bArrPoll = this.playback.poll(1L, TimeUnit.SECONDS);
                if (bArrPoll != null && !this.talkActive) {
                    this.track.write(bArrPoll, 0, bArrPoll.length);
                    BeeClipLan beeClipLan = this.beeClipLan;
                    if (beeClipLan != null) {
                        beeClipLan.forwardPlayback(bArrPoll);
                    } else {
                        continue;
                    }
                }
            }
            audioTrack = this.track;
            if (audioTrack == null) {
                return;
            }
        } catch (Exception e) {
            event("playback " + e);
            audioTrack = this.track;
            if (audioTrack == null) {
                return;
            }
        } finally {
            AudioTrack audioTrack3 = this.track;
            if (audioTrack3 != null) {
                try {
                    audioTrack3.stop();
                } catch (Exception unused) {
                }
                this.track.release();
            }
        }
        try {
            audioTrack.stop();
        } catch (Exception unused2) {
        }
    }

    private void startPlayer() {
        Thread thread = new Thread(new Runnable() { // from class: jp.beeflat.watchhomeptt.PttService$$ExternalSyntheticLambda1
            @Override // java.lang.Runnable
            public final void run() {
                this.f$0.lambda$startPlayer$4();
            }
        }, "PTT-AudioPlayback");
        this.player = thread;
        thread.start();
    }

    private void startTalk() {
        if (this.talkActive) {
            return;
        }
        if (checkSelfPermission("android.permission.RECORD_AUDIO") != 0) {
            setStatus("マイク権限が必要です");
            return;
        }
        if (this.socket == null) {
            setStatus("通信未接続");
            return;
        }
        this.talkActive = true;
        this.ui.removeCallbacks(this.talkTimeout);
        this.ui.postDelayed(this.talkTimeout, TALK_MAX_MS);
        this.playback.clear();
        Thread thread = new Thread(new Runnable() { // from class: jp.beeflat.watchhomeptt.PttService$$ExternalSyntheticLambda4
            @Override // java.lang.Runnable
            public final void run() throws Throwable {
                this.f$0.lambda$startTalk$5();
            }
        }, "PTT-Transmit");
        this.sender = thread;
        thread.start();
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Type inference failed for: r5v0 */
    /* JADX WARN: Type inference failed for: r5v1 */
    /* JADX WARN: Type inference failed for: r5v12 */
    /* JADX WARN: Type inference failed for: r5v13 */
    /* JADX WARN: Type inference failed for: r5v14 */
    /* JADX WARN: Type inference failed for: r5v2, types: [android.media.AudioRecord] */
    /* JADX WARN: Type inference failed for: r5v4 */
    /* JADX WARN: Type inference failed for: r5v6 */
    public void lambda$startTalk$5() {
        AudioRecord record = null;
        try {
            record = new AudioRecord(1, RATE, 16, 2, Math.max(AudioRecord.getMinBufferSize(RATE, 16, 2), 2560));
            if (record.getState() != 1) throw new IOException("マイクを初期化できません");
            record.startRecording();
            setStatus("送信中");
            short[] samples = new short[FRAMES];
            while (this.talkActive) {
                int n = record.read(samples, 0, FRAMES);
                if (n <= 0) continue;
                byte[] name = deviceName(this).getBytes(StandardCharsets.UTF_8);
                if (name.length > 48) name = Arrays.copyOf(name, 48);
                int audioLen = n * 2;
                byte[] audio = new byte[audioLen];
                ByteBuffer pcm = ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < n; i++) pcm.putShort(samples[i]);
                ByteBuffer out = ByteBuffer.allocate(name.length + 28 + audioLen).order(ByteOrder.BIG_ENDIAN);
                out.put(new byte[]{66,80,84,49}).put((byte)1)
                   .putLong(this.uuid.getMostSignificantBits()).putLong(this.uuid.getLeastSignificantBits())
                   .putInt(this.sequence++).put((byte)name.length).put(name).putShort((short)audioLen).put(audio);
                WebSocket ws = this.socket;
                if (ws == null || !ws.send(ByteString.of(out.array()))) break;
            }
        } catch (Exception e) {
            event("transmit " + e);
            setStatus("送信エラー: " + e.getMessage());
        } finally {
            this.talkActive = false;
            if (record != null) {
                try { record.stop(); } catch (Exception ignored) {}
                try { record.release(); } catch (Exception ignored) {}
            }
            if (this.socket != null) setStatus("トランシーバー接続中");
        }
    }

    private void stopTalk() {
        this.ui.removeCallbacks(this.talkTimeout);
        this.talkActive = false;
    }

    @Override // android.app.Service
    public void onDestroy() {
        this.running = false;
        this.talkActive = false;
        this.ui.removeCallbacks(this.talkTimeout);
        this.ui.removeCallbacks(this.retryTask);
        BeeClipLan beeClipLan = this.beeClipLan;
        if (beeClipLan != null) {
            beeClipLan.stop();
        }
        WebSocket webSocket = this.socket;
        if (webSocket != null) {
            webSocket.close(1000, "exit");
        }
        Thread thread = this.player;
        if (thread != null) {
            thread.interrupt();
        }
        WifiManager.WifiLock wifiLock = this.wifiLock;
        if (wifiLock != null && wifiLock.isHeld()) {
            this.wifiLock.release();
        }
        PowerManager.WakeLock wakeLock = this.wakeLock;
        if (wakeLock != null && wakeLock.isHeld()) {
            this.wakeLock.release();
        }
        this.client.dispatcher().executorService().shutdown();
        super.onDestroy();
    }
}
