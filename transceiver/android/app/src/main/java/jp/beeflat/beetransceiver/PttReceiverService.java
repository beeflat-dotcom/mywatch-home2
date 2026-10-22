package jp.beeflat.beetransceiver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class PttReceiverService extends Service {
    public static final String ACTION_START="jp.beeflat.beetransceiver.action.PTT_RX_START";
    public static final String ACTION_RESTART_INTERNET="jp.beeflat.beetransceiver.action.PTT_INTERNET_RESTART";
    public static final String ACTION_STOP="jp.beeflat.beetransceiver.action.PTT_RX_STOP";
    private static final String PREF_ENABLED="ptt_receiver_enabled";
    private static final String CHANNEL_ID="ptt_receiver";
    private static final int NOTIFICATION_ID=2207;
    private static final String GROUP="239.255.42.99";
    private static final int PORT=45454,SAMPLE_RATE=16000,BYTES_PER_PACKET=640;
    private static final long TALKER_HOLD_MS=500;
    private static final AtomicBoolean running=new AtomicBoolean(false);
    private Thread worker; private MulticastSocket socket;
    private WifiManager.MulticastLock multicastLock; private WifiManager.WifiLock wifiLock; private PowerManager.WakeLock wakeLock;
    private InternetPttRelay internetRelay;
    private AudioTrack internetTrack;
    private final Object internetAudioLock=new Object();
    private UUID localId,activeRemoteSender; private long activeRemoteLastMs; private String activeRemoteName="";

    public static void start(Context c){
        c.getSharedPreferences(UploadConfig.PREFS,Context.MODE_PRIVATE).edit().putBoolean(PREF_ENABLED,true).apply();
        Intent i=new Intent(c,PttReceiverService.class).setAction(ACTION_START);
        if(Build.VERSION.SDK_INT>=26)c.startForegroundService(i);else c.startService(i);
    }
    public static void restartInternet(Context c){c.startService(new Intent(c,PttReceiverService.class).setAction(ACTION_RESTART_INTERNET));}
    public static void stop(Context c){
        c.getSharedPreferences(UploadConfig.PREFS,Context.MODE_PRIVATE).edit().putBoolean(PREF_ENABLED,false).apply();
        c.startService(new Intent(c,PttReceiverService.class).setAction(ACTION_STOP));
    }
    private boolean enabled(){return getSharedPreferences(UploadConfig.PREFS,MODE_PRIVATE).getBoolean(PREF_ENABLED,true);}

    @Override public void onCreate(){super.onCreate();localId=DeviceIdentity.get(this);createChannel();BeeLog.write(this,"ptt_rx_create","");}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String action=intent==null?null:intent.getAction();
        if(ACTION_RESTART_INTERNET.equals(action)){if(internetRelay!=null)internetRelay.reconnect();return START_STICKY;}
        if(ACTION_STOP.equals(action)){running.set(false);if(internetRelay!=null)internetRelay.stop();closeSocket();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY;}
        if(!enabled()){stopSelf();return START_NOT_STICKY;}
        Notification n=notification("トランシーバー受信待機中 / 画面OFFでも受信");
        if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION_ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);else startForeground(NOTIFICATION_ID,n);
        if(running.compareAndSet(false,true)){acquireLocks();startInternet();startWorker();}
        return START_STICKY;
    }
    private void startInternet(){
        try{
            internetRelay=new InternetPttRelay(this,this::playInternet);
            internetRelay.start();
        }catch(Exception e){BeeLog.write(this,"ptt_internet_start_error",msg(e));}
    }
    private void playInternet(byte[] packet){
        try{
            if(packet.length<28 || packet[0]!='B'||packet[1]!='P'||packet[2]!='T'||packet[3]!='1')return;
            java.nio.ByteBuffer b=java.nio.ByteBuffer.wrap(packet).order(java.nio.ByteOrder.BIG_ENDIAN);
            b.position(5);
            UUID sender=new UUID(b.getLong(),b.getLong());
            if(sender.equals(localId))return;
            b.getInt();int nameLength=b.get()&255;
            if(nameLength>b.remaining()-2)return;
            byte[] nameBytes=new byte[nameLength];b.get(nameBytes);
            int len=b.getShort()&65535;
            if(len<=0||len>b.remaining())return;
            byte[] pcm=new byte[len];b.get(pcm);
            synchronized(internetAudioLock){
                if(internetTrack==null){
                    int min=AudioTrack.getMinBufferSize(SAMPLE_RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
                    internetTrack=new AudioTrack(AudioManager.STREAM_MUSIC,SAMPLE_RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min,640*12),AudioTrack.MODE_STREAM);
                    if(internetTrack.getState()!=AudioTrack.STATE_INITIALIZED){internetTrack.release();internetTrack=null;return;}
                    internetTrack.play();
                }
                internetTrack.write(pcm,0,len);
            }
        }catch(Exception e){BeeLog.write(this,"ptt_internet_play_error",msg(e));}
    }
    private void startWorker(){worker=new Thread(()->{
        int failures=0;
        try{
            while(running.get()&&enabled()){
                try{receiveLoop();failures=0;}
                catch(Exception e){failures++;BeeLog.write(this,"ptt_rx_error",msg(e));closeSocket();if(!running.get()||!enabled())break;SystemClock.sleep(Math.min(10000L,1000L*failures));}
            }
        }finally{running.set(false);closeSocket();releaseLocks();if(!enabled()){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}}
    },"BeePTT-Receiver-Service");worker.start();}

    private void receiveLoop()throws Exception{
        AudioTrack track=null;
        try{
            int min=AudioTrack.getMinBufferSize(SAMPLE_RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
            AudioFormat format=new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
            AudioAttributes attrs=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            track=new AudioTrack(attrs,format,Math.max(min,BYTES_PER_PACKET*12),AudioTrack.MODE_STREAM,AudioManager.AUDIO_SESSION_ID_GENERATE);
            if(track.getState()!=AudioTrack.STATE_INITIALIZED)throw new IllegalStateException("音声出力を初期化できません");
            track.play();
            InetAddress group=InetAddress.getByName(GROUP);
            socket=new MulticastSocket(PORT);socket.setReuseAddress(true);socket.joinGroup(group);socket.setSoTimeout(1000);
            byte[]buf=new byte[1400];DatagramPacket p=new DatagramPacket(buf,buf.length);
            while(running.get()&&enabled()){
                try{p.setLength(buf.length);socket.receive(p);handlePacket(track,p.getData(),p.getLength());}
                catch(java.net.SocketTimeoutException e){if(activeRemoteSender!=null&&System.currentTimeMillis()-activeRemoteLastMs>TALKER_HOLD_MS){activeRemoteSender=null;activeRemoteName="";}}
            }
        }finally{closeSocket();if(track!=null){try{track.stop();}catch(Exception ignored){}try{track.release();}catch(Exception ignored){}}}
    }
    private void handlePacket(AudioTrack track,byte[]data,int length){
        try{
            if(length<28||data[0]!='B'||data[1]!='P'||data[2]!='T'||data[3]!='1')return;
            ByteBuffer b=ByteBuffer.wrap(data,4,length-4).order(ByteOrder.BIG_ENDIAN);
            if((b.get()&0xff)!=1)return;
            UUID sender=new UUID(b.getLong(),b.getLong());b.getInt();
            int nameLen=b.get()&0xff;if(nameLen>b.remaining())return;byte[]nb=new byte[nameLen];b.get(nb);String name=new String(nb,StandardCharsets.UTF_8);
            int audioLen=b.getShort()&0xffff;if(audioLen<=0||audioLen>b.remaining()||sender.equals(localId))return;
            long now=System.currentTimeMillis();
            boolean newTalker=activeRemoteSender==null||now-activeRemoteLastMs>TALKER_HOLD_MS;
            if(newTalker){activeRemoteSender=sender;activeRemoteName=name;((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID,notification("受信中: "+name));}
            if(!sender.equals(activeRemoteSender))return;
            activeRemoteLastMs=now;byte[]audio=new byte[audioLen];b.get(audio);track.write(audio,0,audio.length,AudioTrack.WRITE_BLOCKING);
        }catch(Exception ignored){}
    }
    @SuppressWarnings("deprecation") private void acquireLocks(){
        try{WifiManager wifi=(WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE);if(wifi!=null){
            multicastLock=wifi.createMulticastLock("bee-rx-multicast");multicastLock.setReferenceCounted(false);multicastLock.acquire();
            wifiLock=wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"bee-rx-wifi");wifiLock.setReferenceCounted(false);wifiLock.acquire();}}catch(Exception e){BeeLog.write(this,"ptt_rx_wifi_lock",msg(e));}
        try{PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"BeeTransceiver:PttReceiver");wakeLock.setReferenceCounted(false);wakeLock.acquire();}catch(Exception e){BeeLog.write(this,"ptt_rx_wake_lock",msg(e));}
    }
    private void releaseLocks(){if(multicastLock!=null&&multicastLock.isHeld())try{multicastLock.release();}catch(Exception ignored){}if(wifiLock!=null&&wifiLock.isHeld())try{wifiLock.release();}catch(Exception ignored){}if(wakeLock!=null&&wakeLock.isHeld())try{wakeLock.release();}catch(Exception ignored){}}
    private synchronized void closeSocket(){if(socket!=null){try{socket.close();}catch(Exception ignored){}socket=null;}}
    private void createChannel(){if(Build.VERSION.SDK_INT>=26){NotificationChannel c=new NotificationChannel(CHANNEL_ID,"トランシーバー受信",NotificationManager.IMPORTANCE_LOW);c.setDescription("画面OFFでもトランシーバー音声を受信");getSystemService(NotificationManager.class).createNotificationChannel(c);}}
    private Notification notification(String text){
        PendingIntent stop=PendingIntent.getService(this,11,new Intent(this,PttReceiverService.class).setAction(ACTION_STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open=PendingIntent.getActivity(this,12,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL_ID):new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_lock_silent_mode_off).setContentTitle("びー・トランシーバー").setContentText(text).setContentIntent(open).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,"受信停止",stop).build()).build();
    }
    @Override public void onTaskRemoved(Intent rootIntent){BeeLog.write(this,"ptt_rx_task_removed","enabled="+enabled());super.onTaskRemoved(rootIntent);}
    @Override public void onDestroy(){running.set(false);if(internetRelay!=null)internetRelay.stop();synchronized(internetAudioLock){if(internetTrack!=null){try{internetTrack.stop();}catch(Exception ignored){}internetTrack.release();internetTrack=null;}}closeSocket();releaseLocks();BeeLog.write(this,"ptt_rx_destroy","enabled="+enabled());super.onDestroy();}
    private static String msg(Exception e){String m=e.getMessage();return m==null||m.trim().isEmpty()?e.getClass().getSimpleName():m;}
    @Override public IBinder onBind(Intent intent){return null;}
}
