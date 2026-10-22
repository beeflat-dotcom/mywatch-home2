package jp.beeflat.beetransceiver;



import android.Manifest;

import android.app.Notification;

import android.app.NotificationChannel;

import android.app.NotificationManager;

import android.app.PendingIntent;

import android.app.Service;

import android.content.Context;

import android.content.Intent;

import android.content.pm.PackageManager;

import android.content.pm.ServiceInfo;

import android.media.AudioFormat;

import android.media.AudioRecord;

import android.media.MediaRecorder;

import android.os.Build;

import android.os.IBinder;

import android.os.PowerManager;

import java.net.DatagramPacket;

import java.net.InetAddress;

import java.net.MulticastSocket;

import java.nio.ByteBuffer;

import java.nio.ByteOrder;

import java.nio.charset.StandardCharsets;

import java.util.UUID;

import java.util.concurrent.atomic.AtomicBoolean;



public class SupportRecordingService extends Service {

    public static final String ACTION_START="jp.beeflat.beetransceiver.action.SUPPORT_START";

    public static final String ACTION_STOP="jp.beeflat.beetransceiver.action.SUPPORT_STOP";

    public static final String ACTION_PTT_START="jp.beeflat.beetransceiver.action.SUPPORT_PTT_START";

    public static final String ACTION_PTT_STOP="jp.beeflat.beetransceiver.action.SUPPORT_PTT_STOP";

    public static final String ACTION_STATE="jp.beeflat.beetransceiver.action.SUPPORT_STATE";

    public static final String EXTRA_RUNNING="running",EXTRA_DEVICE_NAME="device_name";

    private static final String CHANNEL_ID="support_recording"; private static final int NOTIFICATION_ID=2206;

    private static final int SAMPLE_RATE=16000,SAMPLES_PER_PACKET=320;

    private static final long SEGMENT_SAMPLES=SAMPLE_RATE*300L;

    private static final byte[] MAGIC=new byte[]{'B','P','T','1'}; private static final String GROUP="239.255.42.99"; private static final int PORT=45454;

    private static final AtomicBoolean running=new AtomicBoolean(false),pttActive=new AtomicBoolean(false);

    private Thread worker; private volatile String deviceName="device"; private PowerManager.WakeLock wakeLock;

    public static boolean isRunning(){return running.get();}

    public static void start(Context c,String n){Intent i=new Intent(c,SupportRecordingService.class).setAction(ACTION_START).putExtra(EXTRA_DEVICE_NAME,n);if(Build.VERSION.SDK_INT>=26)c.startForegroundService(i);else c.startService(i);}

    public static void stop(Context c){c.startService(new Intent(c,SupportRecordingService.class).setAction(ACTION_STOP));}

    public static void pttStart(Context c,String n){c.startService(new Intent(c,SupportRecordingService.class).setAction(ACTION_PTT_START).putExtra(EXTRA_DEVICE_NAME,n));}

    public static void pttStop(Context c){c.startService(new Intent(c,SupportRecordingService.class).setAction(ACTION_PTT_STOP));}

    @Override public void onCreate(){super.onCreate();createChannel();}

    @Override public int onStartCommand(Intent intent,int flags,int startId){

        String action=intent==null?null:intent.getAction();

        if(ACTION_STOP.equals(action)){pttActive.set(false);running.set(false);BeeLog.write(this,"support_stop","user");return START_NOT_STICKY;}

        if(ACTION_PTT_START.equals(action)){if(intent!=null)deviceName=safe(intent.getStringExtra(EXTRA_DEVICE_NAME));if(running.get())pttActive.set(true);return START_STICKY;}

        if(ACTION_PTT_STOP.equals(action)){pttActive.set(false);return START_STICKY;}

        if(intent!=null&&intent.hasExtra(EXTRA_DEVICE_NAME)){
            deviceName=safe(intent.getStringExtra(EXTRA_DEVICE_NAME));
            getSharedPreferences(UploadConfig.PREFS,MODE_PRIVATE).edit().putString("device_name",deviceName).apply();
        }else{
            deviceName=safe(getSharedPreferences(UploadConfig.PREFS,MODE_PRIVATE).getString("device_name","device"));
        }
        if(!running.compareAndSet(false,true))return START_STICKY;

        Notification n=buildNotification("支援時間を録音中 / 画面OFF対応 / 5分ごとに自動送信");

        if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION_ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);else startForeground(NOTIFICATION_ID,n);

        broadcastState(true);AudioUploader.schedule(this);startWorker();return START_STICKY;

    }

    private void startWorker(){worker=new Thread(()->{

        AudioRecord record=null;AacM4aWriter writer=null;AudioStorage.Target target=null;MulticastSocket sock=null;int part=1;long pcmSamples=0;

        try{

            if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)throw new IllegalStateException("マイク権限がありません");

            acquireWakeLock();int min=AudioRecord.getMinBufferSize(SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);

            record=new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min,SAMPLES_PER_PACKET*2*8));

            if(record.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("マイクを初期化できません");

            sock=new MulticastSocket();sock.setTimeToLive(1);InetAddress group=InetAddress.getByName(GROUP);UUID id=DeviceIdentity.get(this);short[]samples=new short[SAMPLES_PER_PACKET];int seq=0;

            record.startRecording();

            while(running.get()){

                int n=record.read(samples,0,samples.length,AudioRecord.READ_BLOCKING);if(n<=0)continue;

                if(writer==null){target=AudioStorage.create(this,deviceName,"支援",part++);writer=target.openWriter();pcmSamples=0;}

                byte[]audio=new byte[n*2];ByteBuffer pcm=ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN);for(int x=0;x<n;x++)pcm.putShort(samples[x]);

                writer.writePcm(audio,audio.length);pcmSamples+=n;

                if(pttActive.get()){byte[]name=trimUtf8(deviceName,48);ByteBuffer out=ByteBuffer.allocate(4+1+16+4+1+name.length+2+audio.length).order(ByteOrder.BIG_ENDIAN);out.put(MAGIC).put((byte)1).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).putInt(seq++).put((byte)name.length).put(name).putShort((short)audio.length).put(audio);byte[]packet=out.array();sock.send(new DatagramPacket(packet,packet.length,group,PORT));InternetPttRelay.sendActive(packet);}

                if(pcmSamples>=SEGMENT_SAMPLES){writer.close();writer=null;AudioUploader.Item item=target.finish();target=null;AudioUploader.enqueue(this,item);pcmSamples=0;}

            }

        }catch(Exception e){
            boolean retry=running.get();
            BeeLog.write(this,"support_error",msg(e));
            showError("支援時間録音エラー: "+msg(e)+(retry?" / 5秒後に自動復旧":""));
            if(record!=null){try{record.stop();}catch(Exception ignored){}try{record.release();}catch(Exception ignored){}record=null;}
            if(sock!=null)try{sock.close();}catch(Exception ignored){}
            if(writer!=null){try{writer.close();writer=null;if(target!=null){AudioUploader.enqueue(this,target.finish());target=null;}}catch(Exception closeError){if(target!=null)target.abort();BeeLog.write(this,"support_close_error",msg(closeError));}}
            else if(target!=null)target.abort();
            releaseWakeLock();AudioUploader.schedule(this);
            if(retry&&running.get()){
                BeeLog.write(this,"support_retry","5000ms");
                new android.os.Handler(getMainLooper()).postDelayed(()->{if(running.get())startWorker();},5000L);
            }else{
                running.set(false);pttActive.set(false);broadcastState(false);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
            }
            return;
        }

        finally{
            if(record!=null){try{record.stop();}catch(Exception ignored){}try{record.release();}catch(Exception ignored){}}
            if(sock!=null)try{sock.close();}catch(Exception ignored){}
            if(writer!=null){try{writer.close();if(target!=null){AudioUploader.enqueue(this,target.finish());target=null;}}catch(Exception e){if(target!=null)target.abort();showError("支援時間ファイル終了エラー: "+msg(e));}}
            else if(target!=null)target.abort();
            releaseWakeLock();AudioUploader.schedule(this);
            if(!running.get()){
                pttActive.set(false);broadcastState(false);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
            }
        }

    },"BeeSupportRecording");worker.start();}

    private void createChannel(){if(Build.VERSION.SDK_INT>=26){NotificationChannel c=new NotificationChannel(CHANNEL_ID,"支援時間録音",NotificationManager.IMPORTANCE_LOW);c.setDescription("画面OFFでも継続する支援時間録音と5分分割送信");getSystemService(NotificationManager.class).createNotificationChannel(c);}}

    private Notification buildNotification(String text){PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,SupportRecordingService.class).setAction(ACTION_STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL_ID):new Notification.Builder(this);return b.setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("びー・トランシーバー").setContentText(text).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause,"録音終了",stop).build()).build();}

    private void showError(String m){((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID,buildNotification(m));}

    private void broadcastState(boolean v){sendBroadcast(new Intent(ACTION_STATE).setPackage(getPackageName()).putExtra(EXTRA_RUNNING,v));}

    private void acquireWakeLock(){PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"BeeTransceiver:SupportRecording");wakeLock.acquire();}

    private void releaseWakeLock(){if(wakeLock!=null&&wakeLock.isHeld())try{wakeLock.release();}catch(Exception ignored){}}

    private static String safe(String n){return n==null||n.trim().isEmpty()?"device":n.trim();}

    private static byte[] trimUtf8(String s,int max){byte[]raw=s.getBytes(StandardCharsets.UTF_8);if(raw.length<=max)return raw;int end=max;while(end>0){String x=new String(raw,0,end,StandardCharsets.UTF_8);byte[]b=x.getBytes(StandardCharsets.UTF_8);if(b.length<=max)return b;end--;}return new byte[0];}

    private static String msg(Exception e){String m=e.getMessage();return m==null||m.trim().isEmpty()?e.getClass().getSimpleName():m;}

    @Override public IBinder onBind(Intent intent){return null;}

    @Override public void onDestroy(){BeeLog.write(this,"support_destroy","sticky_restart="+running.get());running.set(false);pttActive.set(false);super.onDestroy();}

}