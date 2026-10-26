package jp.beeflat.watchhomeptt;

import android.content.Context;
import android.content.Intent;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Base64;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes.dex */
final class BeeClipLan {
    static final String ACTION_TEXT_CHANGED = "jp.beeflat.watchhomeptt.BEECLIP_TEXT_CHANGED";
    static final String DISCOVERY_MAGIC = "BEECLIP_DISCOVER_V1";
    static final int DISCOVERY_PORT = 8766;
    static final int PORT = 8765;
    private final Context context;
    private volatile DatagramSocket discoverySocket;
    private Socket phoneSocket;
    private BufferedWriter phoneWriter;
    private volatile ServerSocket serverSocket;
    private volatile boolean running = true;
    private final Object phoneLock = new Object();
    private final AtomicReference<String> lastText = new AtomicReference<>("");
    private volatile boolean audioDropLogged = false;
    private final LinkedBlockingDeque<byte[]> playbackAudio = new LinkedBlockingDeque<>(64);
    private volatile boolean phoneAudioActive = false;

    BeeClipLan(Context context) {
        this.context = context.getApplicationContext();
    }

    private void log(String str) {
        try {
            FileWriter fileWriter = new FileWriter(new File(this.context.getFilesDir(), "home_ptt_events.log"), true);
            try {
                fileWriter.write(new Date() + " beeclip " + str + "\n");
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

    void start() {
        new Thread(this::discoveryLoop, "BeeClipWatchDiscovery").start();
        new Thread(this::textServerLoop, "BeeClipWatchTextServer").start();
        new Thread(this::phoneAudioLoop, "BeeClipWatchPhoneAudio").start();
    }

    void stop() {
        this.running = false;
        try {
            if (this.serverSocket != null) {
                this.serverSocket.close();
            }
        } catch (Exception unused) {
        }
        try {
            if (this.discoverySocket != null) {
                this.discoverySocket.close();
            }
        } catch (Exception unused2) {
        }
        this.playbackAudio.clear();
        closePhone();
    }

    String currentText() {
        return this.lastText.get();
    }

    private String deviceId() {
        String string = this.context.getSharedPreferences("beeclip_watch", 0).getString("device_id", "");
        if (string == null || string.isEmpty()) {
            string = Settings.Secure.getString(this.context.getContentResolver(), "android_id");
            if (string == null || string.isEmpty()) {
                string = UUID.randomUUID().toString();
            }
            this.context.getSharedPreferences("beeclip_watch", 0).edit().putString("device_id", string).apply();
        }
        return string;
    }

    private String deviceName() {
        return "腕時計-" + Build.MODEL;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Code duplicated, block: B:38:0x00cd  */
    public void discoveryLoop() {
        while (this.running) {
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket((SocketAddress) null);
                socket.setReuseAddress(true);
                socket.setBroadcast(true);
                socket.bind(new InetSocketAddress(DISCOVERY_PORT));
                this.discoverySocket = socket;
                byte[] buffer = new byte[1024];
                while (this.running) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    String query = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8).trim();
                    if (!DISCOVERY_MAGIC.equals(query)) continue;
                    JSONObject reply = new JSONObject();
                    reply.put("type", "beeclip_device");
                    reply.put("device_type", "watch");
                    reply.put("device_id", deviceId());
                    reply.put("device_name", deviceName());
                    reply.put("model", Build.MODEL == null ? "" : Build.MODEL);
                    reply.put("tcp_port", PORT);
                    reply.put("version", "watch-1");
                    byte[] bytes = reply.toString().getBytes(StandardCharsets.UTF_8);
                    socket.send(new DatagramPacket(bytes, bytes.length, packet.getAddress(), packet.getPort()));
                }
            } catch (Exception ignored) {
            } finally {
                if (socket != null) try { socket.close(); } catch (Exception ignored) {}
                if (this.discoverySocket == socket) this.discoverySocket = null;
            }
            if (this.running) {
                try { Thread.sleep(1000L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void textServerLoop() {
        ServerSocket serverSocket;
        while (this.running) {
            try {
                try {
                    this.serverSocket = new ServerSocket(PORT);
                    this.serverSocket.setReuseAddress(true);
                    while (this.running) {
                        final Socket socketAccept = this.serverSocket.accept();
                        new Thread(new Runnable() { // from class: jp.beeflat.watchhomeptt.BeeClipLan$$ExternalSyntheticLambda0
                            @Override // java.lang.Runnable
                            public final void run() {
                                BeeClipLan.this.lambda$textServerLoop$0(socketAccept);
                            }
                        }, "BeeClipWatchSurface").start();
                    }
                    try {
                        if (this.serverSocket != null) {
                            serverSocket = this.serverSocket;
                            serverSocket.close();
                        }
                    } catch (Exception unused) {
                    }
                } catch (Throwable th) {
                    try {
                        if (this.serverSocket != null) {
                            this.serverSocket.close();
                        }
                    } catch (Exception unused2) {
                    }
                    this.serverSocket = null;
                    throw th;
                }
            } catch (Exception unused3) {
                if (this.running) {
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException unused4) {
                    }
                }
                if (this.serverSocket != null) {
                    serverSocket = this.serverSocket;
                }
            }
            this.serverSocket = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX INFO: renamed from: handleSurfaceClient, reason: merged with bridge method [inline-methods] */
    public void lambda$textServerLoop$0(Socket socket) {
        String line;
        try {
            try {
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                try {
                    BufferedWriter bufferedWriter = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                    try {
                        JSONObject jSONObject = new JSONObject();
                        jSONObject.put("type", "device_hello");
                        jSONObject.put("source", "watch");
                        jSONObject.put("device_id", deviceId());
                        jSONObject.put("device_name", deviceName());
                        jSONObject.put("model", Build.MODEL == null ? "" : Build.MODEL);
                        bufferedWriter.write(jSONObject.toString());
                        bufferedWriter.write("\n");
                        bufferedWriter.flush();
                        while (this.running && (line = bufferedReader.readLine()) != null) {
                            if (!line.trim().isEmpty()) {
                                JSONObject jSONObject2 = new JSONObject(line);
                                if ("surface".equals(jSONObject2.optString("source"))) {
                                    String strOptString = jSONObject2.optString("type");
                                    if ("keyboard_text".equals(strOptString)) {
                                        setText(this.lastText.get() + jSONObject2.optString("text", ""));
                                    } else if ("clipboard".equals(strOptString)) {
                                        setText(jSONObject2.optString("text", ""));
                                    } else if ("keyboard_backspace".equals(strOptString)) {
                                        String strSubstring = this.lastText.get();
                                        int iMax = Math.max(1, jSONObject2.optInt("count", 1));
                                        while (true) {
                                            int i = iMax - 1;
                                            if (iMax <= 0 || strSubstring.isEmpty()) {
                                                break;
                                            }
                                            strSubstring = strSubstring.substring(0, strSubstring.offsetByCodePoints(strSubstring.length(), -1));
                                            iMax = i;
                                        }
                                        setText(strSubstring);
                                    } else if ("keyboard_enter".equals(strOptString)) {
                                        setText(this.lastText.get() + "\n");
                                    } else if ("keyboard_tab".equals(strOptString)) {
                                        setText(this.lastText.get() + "\t");
                                    }
                                }
                            }
                        }
                        bufferedWriter.close();
                        bufferedReader.close();
                        if (socket != null) {
                            socket.close();
                        }
                    } catch (Throwable th) {
                        try {
                            bufferedWriter.close();
                        } catch (Throwable th2) {
                            th.addSuppressed(th2);
                        }
                        throw th;
                    }
                } catch (Throwable th3) {
                    try {
                        bufferedReader.close();
                    } catch (Throwable th4) {
                        th3.addSuppressed(th4);
                    }
                    throw th3;
                }
            } catch (Throwable th5) {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (Throwable th6) {
                        th5.addSuppressed(th6);
                    }
                }
                throw th5;
            }
        } catch (Exception unused) {
        }
    }

    private void setText(String str) {
        if (str == null) {
            str = "";
        }
        if (str.length() > 1000) {
            str = str.substring(str.length() - 1000);
        }
        this.lastText.set(str);
        Intent intent = new Intent(ACTION_TEXT_CHANGED);
        intent.setPackage(this.context.getPackageName());
        intent.putExtra("text", str);
        this.context.sendBroadcast(intent);
    }

    void forwardPlayback(byte[] bArr) {
        if (bArr == null || bArr.length == 0) {
            return;
        }
        byte[] bArr2 = (byte[]) bArr.clone();
        if (this.playbackAudio.offerLast(bArr2)) {
            return;
        }
        this.playbackAudio.pollFirst();
        this.playbackAudio.offerLast(bArr2);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void phoneAudioLoop() {
        long j = 0;
        long j2 = 0;
        while (this.running) {
            try {
                byte[] bArrPoll = this.playbackAudio.poll(250L, TimeUnit.MILLISECONDS);
                long jCurrentTimeMillis = System.currentTimeMillis();
                synchronized (this.phoneLock) {
                    if (bArrPoll != null) {
                        try {
                            if (!ensurePhoneLocked()) {
                                this.phoneAudioActive = false;
                            } else {
                                if (!this.phoneAudioActive) {
                                    JSONObject jSONObject = new JSONObject();
                                    jSONObject.put("type", "audio_start");
                                    jSONObject.put("source", "watch");
                                    jSONObject.put("sample_rate", 16000);
                                    jSONObject.put("channels", 1);
                                    jSONObject.put("encoding", "pcm_s16le");
                                    sendPhoneLocked(jSONObject);
                                    this.phoneAudioActive = true;
                                    log("playback audio start sent");
                                }
                                JSONObject jSONObject2 = new JSONObject();
                                jSONObject2.put("type", "audio_pcm");
                                jSONObject2.put("source", "watch");
                                jSONObject2.put("data_b64", Base64.encodeToString(bArrPoll, 2));
                                sendPhoneLocked(jSONObject2);
                                j = jCurrentTimeMillis;
                            }
                        } catch (Throwable th) {
                            throw th;
                        }
                    } else {
                        if (this.phoneAudioActive && jCurrentTimeMillis - j >= 500) {
                            JSONObject jSONObject3 = new JSONObject();
                            jSONObject3.put("type", "audio_stop");
                            jSONObject3.put("source", "watch");
                            sendPhoneLocked(jSONObject3);
                            this.phoneAudioActive = false;
                            log("playback audio stop sent");
                        }
                        Socket socket = this.phoneSocket;
                        if ((socket == null || socket.isClosed() || this.phoneWriter == null) && jCurrentTimeMillis >= j2) {
                            j2 = 3000 + jCurrentTimeMillis;
                            ensurePhoneLocked();
                        }
                    }
                }
            } catch (InterruptedException unused) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                synchronized (this.phoneLock) {
                    this.phoneAudioActive = false;
                    closePhoneLocked();
                    log("playback audio send failed " + e.getClass().getSimpleName());
                }
            }
        }
    }

    void audioStart() {
        this.audioDropLogged = false;
        new Thread(new Runnable() { // from class: jp.beeflat.watchhomeptt.BeeClipLan$$ExternalSyntheticLambda4
            @Override // java.lang.Runnable
            public final void run() {
                BeeClipLan.this.lambda$audioStart$1();
            }
        }, "BeeClipWatchPhoneConnect").start();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$audioStart$1() {
        synchronized (this.phoneLock) {
            if (!ensurePhoneLocked()) {
                log("phone discovery failed");
                return;
            }
            try {
                JSONObject jSONObject = new JSONObject();
                jSONObject.put("type", "audio_start");
                jSONObject.put("source", "watch");
                jSONObject.put("sample_rate", 16000);
                jSONObject.put("channels", 1);
                jSONObject.put("encoding", "pcm_s16le");
                sendPhoneLocked(jSONObject);
                log("audio start sent");
            } catch (Exception e) {
                log("audio start send failed " + e.getClass().getSimpleName());
                closePhoneLocked();
            }
        }
    }

    void audioFrame(byte[] bArr) {
        if (bArr == null || bArr.length == 0) {
            return;
        }
        synchronized (this.phoneLock) {
            if (this.phoneWriter == null) {
                if (!this.audioDropLogged) {
                    this.audioDropLogged = true;
                    log("audio frame dropped: phone not connected");
                }
                return;
            }
            try {
                JSONObject jSONObject = new JSONObject();
                jSONObject.put("type", "audio_pcm");
                jSONObject.put("source", "watch");
                jSONObject.put("data_b64", Base64.encodeToString(bArr, 2));
                sendPhoneLocked(jSONObject);
            } catch (Exception e) {
                log("audio frame send failed " + e.getClass().getSimpleName());
                closePhoneLocked();
            }
        }
    }

    void audioStop() {
        synchronized (this.phoneLock) {
            if (this.phoneWriter == null) {
                return;
            }
            try {
                JSONObject jSONObject = new JSONObject();
                jSONObject.put("type", "audio_stop");
                jSONObject.put("source", "watch");
                sendPhoneLocked(jSONObject);
                log("audio stop sent");
            } catch (Exception unused) {
            }
        }
    }

    boolean isPhoneConnected() {
        boolean z;
        synchronized (this.phoneLock) {
            Socket socket = this.phoneSocket;
            z = (socket == null || !socket.isConnected() || this.phoneSocket.isClosed() || this.phoneWriter == null) ? false : true;
        }
        return z;
    }

    private boolean ensurePhoneLocked() {
        Socket socket = this.phoneSocket;
        if (socket != null && socket.isConnected() && !this.phoneSocket.isClosed() && this.phoneWriter != null) {
            return true;
        }
        closePhoneLocked();
        InetSocketAddress inetSocketAddressDiscoverPhone = discoverPhone();
        if (inetSocketAddressDiscoverPhone == null) {
            return false;
        }
        try {
            Socket socket2 = new Socket();
            socket2.connect(inetSocketAddressDiscoverPhone, 1200);
            socket2.setTcpNoDelay(true);
            this.phoneSocket = socket2;
            this.phoneWriter = new BufferedWriter(new OutputStreamWriter(socket2.getOutputStream(), StandardCharsets.UTF_8));
            JSONObject jSONObject = new JSONObject();
            jSONObject.put("type", "client_hello");
            jSONObject.put("source", "watch");
            jSONObject.put("device_name", deviceName());
            jSONObject.put("device_id", deviceId());
            sendPhoneLocked(jSONObject);
            log("phone connected " + inetSocketAddressDiscoverPhone.getAddress().getHostAddress() + ":" + inetSocketAddressDiscoverPhone.getPort());
            return true;
        } catch (Exception e) {
            log("phone connect failed " + e.getClass().getSimpleName());
            closePhoneLocked();
            return false;
        }
    }

    /* JADX WARN: Code duplicated, block: B:43:0x0133  */
    private InetSocketAddress discoverPhone() {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            socket.setSoTimeout(250);
            byte[] magic = DISCOVERY_MAGIC.getBytes(StandardCharsets.UTF_8);
            String cached = this.context.getSharedPreferences("beeclip_watch", 0).getString("phone_host", "");
            if (cached != null && !cached.trim().isEmpty()) {
                try { sendDiscovery(socket, InetAddress.getByName(cached.trim()), magic); } catch (Exception ignored) {}
            }
            InetAddress gateway = wifiGatewayAddress();
            if (gateway != null) sendDiscovery(socket, gateway, magic);
            sendDiscovery(socket, InetAddress.getByName("255.255.255.255"), magic);
            InetAddress broadcast = wifiBroadcastAddress();
            if (broadcast != null) sendDiscovery(socket, broadcast, magic);

            long end = System.currentTimeMillis() + 1200L;
            byte[] buffer = new byte[2048];
            while (System.currentTimeMillis() < end) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                    JSONObject json = new JSONObject(new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8));
                    if ("beeclip_device".equals(json.optString("type")) && "phone".equals(json.optString("device_type"))) {
                        int port = json.optInt("tcp_port", PORT);
                        String host = packet.getAddress().getHostAddress();
                        this.context.getSharedPreferences("beeclip_watch", 0).edit().putString("phone_host", host).apply();
                        log("phone discovered " + host + ":" + port);
                        return new InetSocketAddress(packet.getAddress(), port);
                    }
                } catch (SocketTimeoutException ignored) {
                }
            }
        } catch (Exception e) {
            log("phone discovery error " + e.getClass().getSimpleName());
        } finally {
            if (socket != null) socket.close();
        }
        return null;
    }

    private void sendDiscovery(DatagramSocket datagramSocket, InetAddress inetAddress, byte[] bArr) {
        try {
            datagramSocket.send(new DatagramPacket(bArr, bArr.length, inetAddress, DISCOVERY_PORT));
        } catch (Exception unused) {
        }
    }

    private InetAddress wifiGatewayAddress() {
        try {
            WifiManager wifiManager = (WifiManager) this.context.getApplicationContext().getSystemService("wifi");
            DhcpInfo dhcpInfo = wifiManager == null ? null : wifiManager.getDhcpInfo();
            if (dhcpInfo != null && dhcpInfo.gateway != 0) {
                int i = dhcpInfo.gateway;
                byte[] bArr = new byte[4];
                for (int i2 = 0; i2 < 4; i2++) {
                    bArr[i2] = (byte) ((i >> (i2 * 8)) & 255);
                }
                return InetAddress.getByAddress(bArr);
            }
            return null;
        } catch (Exception unused) {
            return null;
        }
    }

    private InetAddress wifiBroadcastAddress() {
        try {
            WifiManager wifiManager = (WifiManager) this.context.getApplicationContext().getSystemService("wifi");
            DhcpInfo dhcpInfo = wifiManager == null ? null : wifiManager.getDhcpInfo();
            if (dhcpInfo == null) {
                return null;
            }
            int i = (~dhcpInfo.netmask) | (dhcpInfo.ipAddress & dhcpInfo.netmask);
            byte[] bArr = new byte[4];
            for (int i2 = 0; i2 < 4; i2++) {
                bArr[i2] = (byte) ((i >> (i2 * 8)) & 255);
            }
            return InetAddress.getByAddress(bArr);
        } catch (Exception unused) {
            return null;
        }
    }

    private void sendPhoneLocked(JSONObject jSONObject) throws Exception {
        BufferedWriter bufferedWriter = this.phoneWriter;
        if (bufferedWriter == null) {
            throw new IllegalStateException("phone not connected");
        }
        bufferedWriter.write(jSONObject.toString());
        this.phoneWriter.write("\n");
        this.phoneWriter.flush();
    }

    private void closePhone() {
        synchronized (this.phoneLock) {
            closePhoneLocked();
        }
    }

    private void closePhoneLocked() {
        try {
            BufferedWriter bufferedWriter = this.phoneWriter;
            if (bufferedWriter != null) {
                bufferedWriter.close();
            }
        } catch (Exception unused) {
        }
        try {
            Socket socket = this.phoneSocket;
            if (socket != null) {
                socket.close();
            }
        } catch (Exception unused2) {
        }
        this.phoneWriter = null;
        this.phoneSocket = null;
        this.phoneAudioActive = false;
    }
}
