package jp.beeflat.beetransceiver;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Keeps one authenticated internet relay socket alive for foreground PTT.
 * LAN multicast remains independent and works without the internet relay.
 */
final class InternetPttRelay {
    static final String ACTION_STATUS = "jp.beeflat.beetransceiver.PTT_INTERNET_STATUS";
    static final String EXTRA_STATUS = "status";

    interface Receiver {
        void receive(byte[] packet);
    }

    private static volatile InternetPttRelay active;
    static boolean sendActive(byte[] data) {InternetPttRelay relay=active;return relay!=null && relay.send(data);}
    private final Context context;
    private final Receiver receiver;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService resolver = Executors.newSingleThreadExecutor();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicInteger generation = new AtomicInteger(0);
    private volatile WebSocket socket;
    private volatile boolean online = false;
    private volatile long lastFailureLog = 0;

    InternetPttRelay(Context context, Receiver receiver) {
        this.context = context.getApplicationContext();
        this.receiver = receiver;
    }

    void start() {
        if (running.compareAndSet(false, true)) {active=this;connect();}
    }

    void reconnect() {
        if (!running.get()) {
            start();
            return;
        }
        generation.incrementAndGet();
        WebSocket old = socket;
        socket = null;
        online = false;
        connecting.set(false);
        handler.removeCallbacksAndMessages(null);
        if (old != null) old.cancel();
        connect();
    }

    void stop() {
        running.set(false);
        if(active==this)active=null;
        generation.incrementAndGet();
        handler.removeCallbacksAndMessages(null);
        WebSocket old = socket;
        socket = null;
        online = false;
        if (old != null) old.cancel();
        resolver.shutdownNow();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    boolean send(byte[] packet) {
        WebSocket current = socket;
        if (!online || current == null || packet == null || packet.length > 1450) return false;
        if (current.queueSize() > 96 * 1024L) return false;
        try {
            return current.send(ByteString.of(packet, 0, packet.length));
        } catch (RuntimeException e) {
            return false;
        }
    }

    boolean isOnline() {
        return online;
    }

    private void connect() {
        if (!running.get() || !connecting.compareAndSet(false, true)) return;
        final int requestGeneration = generation.incrementAndGet();
        resolver.execute(() -> {
            try {
                String token = UploadConfig.token(context);
                if (token.isEmpty()) {
                    connecting.set(false);
                    status("外部通話: 端末登録が必要です");
                    scheduleRetry(15000);
                    return;
                }
                String endpoint = EndpointDiscovery.resolve(context);
                if (!endpoint.startsWith("https://")) {
                    connecting.set(false);
                    status("外部通話: 接続先待ち");
                    scheduleRetry(8000);
                    return;
                }
                if (!running.get() || requestGeneration != generation.get()) {
                    connecting.set(false);
                    return;
                }
                String url = "wss://" + endpoint.substring("https://".length()) + "/ptt/ws";
                Request request = new Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer " + token)
                        .build();
                WebSocket newSocket = client.newWebSocket(request, new WebSocketListener() {
                    @Override public void onOpen(WebSocket webSocket, Response response) {
                        if (!running.get() || generation.get() != requestGeneration) {
                            webSocket.close(1000, "stale");
                            return;
                        }
                        socket = webSocket;
                        connecting.set(false);
                        online = true;
                        BeeLog.write(context, "ptt_internet_connected", "OK");
                        status("外部通話: インターネット接続済み");
                    }

                    @Override public void onMessage(WebSocket webSocket, ByteString bytes) {
                        if (online && generation.get() == requestGeneration) {
                            byte[] packet = bytes.toByteArray();
                            if (packet.length >= 30 && packet.length <= 1450) receiver.receive(packet);
                        }
                    }

                    @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                        int responseCode = response == null ? 0 : response.code();
                        disconnected(requestGeneration, "failure_" + responseCode);
                    }

                    @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                        disconnected(requestGeneration, "closed_" + code);
                    }
                });
                if (!running.get() || generation.get() != requestGeneration) {
                    newSocket.cancel();
                    connecting.set(false);
                } else {
                    socket = newSocket;
                }
            } catch (Exception e) {
                disconnected(requestGeneration, e.getClass().getSimpleName());
            }
        });
    }

    private void disconnected(int requestGeneration, String reason) {
        if (generation.get() != requestGeneration || !running.get()) return;
        online = false;
        socket = null;
        connecting.set(false);
        EndpointDiscovery.invalidate(context);  // Quick Tunnel may have rotated.
        long now = System.currentTimeMillis();
        if (now - lastFailureLog > 60000L) {
            lastFailureLog = now;
            BeeLog.write(context, "ptt_internet_disconnected", reason);
        }
        status("外部通話: 再接続中");
        scheduleRetry(5000);
    }

    private void scheduleRetry(long delayMs) {
        if (running.get()) handler.postDelayed(this::connect, delayMs);
    }

    private void status(String text) {
        context.sendBroadcast(new Intent(ACTION_STATUS)
                .setPackage(context.getPackageName())
                .putExtra(EXTRA_STATUS, text));
    }
}
