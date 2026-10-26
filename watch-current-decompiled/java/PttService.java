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
import java.text.SimpleDateFormat;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
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
    static final String RECORD_START = "jp.beeflat.watchhomeptt.RECORD_START";
    static final String RECORD_STOP = "jp.beeflat.watchhomeptt.RECORD_STOP";
    private static final long RECORD_SEGMENT_SAMPLES = RATE * 300L;
    private static volatile boolean recordingState = false;
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
    private volatile boolean recordActive = false;
    private volatile boolean captureRunning = false;
    private final Object captureGuard = new Object();
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
            PttService.this.lambda$new$0();
        }
    };
    private final Runnable talkTimeout = new Runnable() { // from class: jp.beeflat.watchhomeptt.PttService$$ExternalSyntheticLambda3
        @Override // java.lang.Runnable
        public final void run() {
            PttService.this.lambda$new$1();
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

    static boolean isRecording() {
        return recordingState;
    }

    static void logEvent(Context context, String text) {
        try {
            File file = eventFile(context);
            try (FileWriter writer = new FileWriter(file, true)) {
                writer.write(new Date() + " " + text + "\n");
            }
        } catch (Exception ignored) {}
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

    static void pair(final Context context, final String code) {
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                SharedPreferences prefs = context.getSharedPreferences("ptt", 0);
                String base = prefs.getString("endpoint", "").trim();
                if (base.isEmpty()) base = discover();
                if (!base.startsWith("https://")) throw new IOException("接続URLがありません");
                c = (HttpURLConnection) new URL(base + "/ptt/pair").openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                c.setRequestProperty("Content-Type", "application/json");
                byte[] body = new JSONObject().put("code", code).toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = c.getOutputStream()) { out.write(body); }
                int http = c.getResponseCode();
                if (http != 200) throw new IOException("ペアリングHTTP " + http);
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) {
                    byte[] b = new byte[2048];
                    int n;
                    while ((n = in.read(b)) >= 0 && buffer.size() < 10000) {
                        if (n > 0) buffer.write(b, 0, n);
                    }
                }
                String token = new JSONObject(buffer.toString("UTF-8")).getString("token");
                prefs.edit().putString("token", token).putString("endpoint", base).apply();
                status = "接続登録完了";
                context.startForegroundService(new Intent(context, PttService.class).setAction(START));
            } catch (Exception e) {
                status = "ペアリング失敗: " + e.getMessage();
            } finally {
                if (c != null) c.disconnect();
            }
        }, "PTT-Pair").start();
    }

    static String discover() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(DISCOVERY).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        c.setUseCaches(false);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("接続先取得HTTP " + code);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] b = new byte[2048];
                int n;
                while ((n = in.read(b)) >= 0) {
                    if (n == 0) continue;
                    if (out.size() + n > 12000) throw new IOException("接続先情報が大きすぎます");
                    out.write(b, 0, n);
                }
            }
            JSONObject json = new JSONObject(out.toString("UTF-8"));
            if (!"running".equalsIgnoreCase(json.optString("phase"))) throw new IOException("公開先停止中");
            String endpoint = json.getString("external_api_url").replaceAll("/+$", "");
            if (!endpoint.startsWith("https://")) throw new IOException("HTTPS公開先がありません");
            return endpoint;
        } finally {
            c.disconnect();
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
        if (RECORD_START.equals(action)) {
            startRecordingOnly();
            return 1;
        }
        if (RECORD_STOP.equals(action)) {
            stopRecordingOnly();
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
                    PttService.this.lambda$connect$3();
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
                PttService.this.lambda$startPlayer$4();
            }
        }, "PTT-AudioPlayback");
        this.player = thread;
        thread.start();
    }

    private void startTalk() {
        if (this.talkActive) return;
        if (checkSelfPermission("android.permission.RECORD_AUDIO") != 0) {
            setStatus("マイク権限が必要です");
            return;
        }
        if (this.socket == null) {
            setStatus(this.recordActive ? "録音中 / 通信未接続" : "通信未接続");
            return;
        }
        this.talkActive = true;
        this.ui.removeCallbacks(this.talkTimeout);
        this.ui.postDelayed(this.talkTimeout, TALK_MAX_MS);
        this.playback.clear();
        setStatus(this.recordActive ? "録音中・送信中" : "送信中");
        ensureCapture();
    }

    private void stopTalk() {
        this.ui.removeCallbacks(this.talkTimeout);
        this.talkActive = false;
        if (this.recordActive) setStatus("録音中");
        else if (this.socket != null) setStatus("トランシーバー接続中");
    }

    private void startRecordingOnly() {
        if (this.recordActive) return;
        if (checkSelfPermission("android.permission.RECORD_AUDIO") != 0) {
            setStatus("マイク権限が必要です");
            return;
        }
        this.recordActive = true;
        recordingState = true;
        setStatus(this.talkActive ? "録音中・送信中" : "録音中");
        event("record start");
        ensureCapture();
        RecordingUploader.schedule(this);
    }

    private void stopRecordingOnly() {
        if (!this.recordActive) return;
        this.recordActive = false;
        recordingState = false;
        event("record stop");
        if (this.talkActive) setStatus("送信中");
        else if (this.socket != null) setStatus("トランシーバー接続中");
        else setStatus("受信待機中");
    }

    private void ensureCapture() {
        synchronized (this.captureGuard) {
            if (this.captureRunning) return;
            this.captureRunning = true;
            Thread thread = new Thread(this::captureLoop, "PTT-SharedCapture");
            this.sender = thread;
            thread.start();
        }
    }

    private void captureLoop() {
        AudioRecord record = null;
        AacM4aWriter writer = null;
        File recordingFile = null;
        long segmentSamples = 0L;
        int part = 1;
        try {
            record = new AudioRecord(1, RATE, 16, 2,
                    Math.max(AudioRecord.getMinBufferSize(RATE, 16, 2), 2560));
            if (record.getState() != 1) throw new IOException("マイクを初期化できません");
            record.startRecording();
            short[] samples = new short[FRAMES];

            while (this.running && (this.talkActive || this.recordActive)) {
                int n = record.read(samples, 0, FRAMES);
                if (n <= 0) continue;

                byte[] audio = new byte[n * 2];
                ByteBuffer pcm = ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < n; i++) pcm.putShort(samples[i]);

                if (this.recordActive) {
                    if (writer == null) {
                        recordingFile = newRecordingFile(part++);
                        writer = new AacM4aWriter(recordingFile);
                        segmentSamples = 0L;
                        event("record file " + recordingFile.getName());
                    }
                    writer.writePcm(audio, audio.length);
                    segmentSamples += n;
                    if (segmentSamples >= RECORD_SEGMENT_SAMPLES) {
                        finishRecordingSegment(writer, recordingFile);
                        writer = null;
                        recordingFile = null;
                        segmentSamples = 0L;
                    }
                } else if (writer != null) {
                    finishRecordingSegment(writer, recordingFile);
                    writer = null;
                    recordingFile = null;
                    segmentSamples = 0L;
                }

                if (this.talkActive) sendTalkFrame(audio);
            }
        } catch (Exception e) {
            event("capture " + e);
            if (this.recordActive) setStatus("録音エラー: " + e.getMessage());
            else if (this.talkActive) setStatus("送信エラー: " + e.getMessage());
        } finally {
            if (writer != null) {
                try { finishRecordingSegment(writer, recordingFile); }
                catch (Exception e) { event("record close " + e); }
            }
            if (record != null) {
                try { record.stop(); } catch (Exception ignored) {}
                try { record.release(); } catch (Exception ignored) {}
            }
            synchronized (this.captureGuard) { this.captureRunning = false; }
            if (this.running && (this.talkActive || this.recordActive)) ensureCapture();
        }
    }

    private void sendTalkFrame(byte[] audio) {
        try {
            WebSocket ws = this.socket;
            if (ws == null) return;
            byte[] name = deviceName(this).getBytes(StandardCharsets.UTF_8);
            if (name.length > 48) name = Arrays.copyOf(name, 48);
            ByteBuffer out = ByteBuffer.allocate(name.length + 28 + audio.length).order(ByteOrder.BIG_ENDIAN);
            out.put(new byte[]{66,80,84,49}).put((byte)1)
                    .putLong(this.uuid.getMostSignificantBits()).putLong(this.uuid.getLeastSignificantBits())
                    .putInt(this.sequence++).put((byte)name.length).put(name)
                    .putShort((short)audio.length).put(audio);
            if (!ws.send(ByteString.of(out.array()))) event("talk send failed");
        } catch (Exception e) {
            event("talk frame " + e.getClass().getSimpleName());
        }
    }

    private File newRecordingFile(int part) throws IOException {
        File base = new File(getExternalFilesDir(null), "BeeRecording");
        if (!base.exists() && !base.mkdirs()) throw new IOException("録音保存先を作成できません");
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.JAPAN).format(new Date());
        String model = Build.MODEL == null ? "watch" : Build.MODEL.replaceAll("[^0-9A-Za-z_-]", "_");
        return new File(base, stamp + "_腕時計-" + model + "_支援_" + String.format(Locale.US, "%03d", part) + ".m4a");
    }

    private void finishRecordingSegment(AacM4aWriter writer, File file) throws Exception {
        writer.close();
        if (file != null && file.isFile() && file.length() > 0) {
            event("record saved " + file.getName() + " " + file.length());
            RecordingUploader.enqueue(this, file);
        }
    }

    @Override // android.app.Service
    public void onDestroy() {
        this.running = false;
        this.talkActive = false;
        this.recordActive = false;
        recordingState = false;
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
        if (thread != null) thread.interrupt();
        Thread capture = this.sender;
        if (capture != null) capture.interrupt();
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
