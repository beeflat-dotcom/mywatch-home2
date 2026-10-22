package jp.beeflat.beetransceiver;



import android.Manifest;

import android.app.Activity;
import android.app.AlertDialog;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import android.content.BroadcastReceiver;

import android.content.Context;

import android.content.Intent;

import android.content.IntentFilter;

import android.content.SharedPreferences;

import android.content.pm.PackageManager;

import android.graphics.Color;

import android.media.AudioFormat;

import android.media.AudioManager;

import android.media.AudioRecord;

import android.media.AudioTrack;

import android.media.MediaRecorder;

import android.net.wifi.WifiManager;

import android.os.Build;

import android.os.Bundle;

import android.os.Handler;

import android.os.Looper;

import android.view.Gravity;

import android.view.MotionEvent;

import android.view.inputmethod.EditorInfo;

import android.widget.Button;

import android.widget.CheckBox;

import android.widget.EditText;

import android.widget.LinearLayout;

import android.widget.TextView;

import android.widget.Toast;

import java.net.DatagramPacket;

import java.net.InetAddress;

import java.net.MulticastSocket;

import java.nio.ByteBuffer;

import java.nio.ByteOrder;

import java.nio.charset.StandardCharsets;

import java.util.UUID;

import java.util.concurrent.atomic.AtomicBoolean;



public class MainActivity extends Activity {

    private static final int REQ_RECORD_AUDIO=1001;

    private static final String PREFS="bee_transceiver",PREF_NAME="device_name";

    private final Handler mainHandler=new Handler(Looper.getMainLooper());

    private AudioEngine engine;private TextView statusView;private Button pttButton,supportButton;private EditText nameEdit,serverUrlEdit;private CheckBox mobileUploadCheck;private SharedPreferences prefs;

    private final BroadcastReceiver internetStateReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){String status=i.getStringExtra(InternetPttRelay.EXTRA_STATUS);if(statusView!=null&&status!=null)statusView.setText(status);}};
    private final BroadcastReceiver supportStateReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){if(SupportRecordingService.ACTION_STATE.equals(i.getAction()))updateSupportButton(i.getBooleanExtra(SupportRecordingService.EXTRA_RUNNING,false));}};

    @Override protected void onCreate(Bundle b){super.onCreate(b);setVolumeControlStream(AudioManager.STREAM_MUSIC);prefs=getSharedPreferences(PREFS,MODE_PRIVATE);buildUi();engine=new AudioEngine(this,new AudioEngine.Listener(){public void onStatus(String t){mainHandler.post(()->statusView.setText(t));}public void onSaved(String p){mainHandler.post(()->Toast.makeText(MainActivity.this,"音声ログ保存・送信待ち: "+p,Toast.LENGTH_LONG).show());}public void onError(String m){mainHandler.post(()->{statusView.setText("エラー: "+m);Toast.makeText(MainActivity.this,m,Toast.LENGTH_LONG).show();});}});try{PttReceiverService.start(this);}catch(RuntimeException e){BeeLog.write(this,"ptt_start_error",e.toString());statusView.setText("受信起動エラー: "+e.getClass().getSimpleName());}AudioUploader.schedule(this);}

    private void buildUi(){int pad=dp(18);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(pad,pad,pad,pad);root.setGravity(Gravity.CENTER_HORIZONTAL);root.setBackgroundColor(Color.rgb(250,250,250));

        TextView title=new TextView(this);title.setText("びー・トランシーバー");title.setTextSize(26);title.setTextColor(Color.BLACK);title.setGravity(Gravity.CENTER);root.addView(title,new LinearLayout.LayoutParams(-1,-2));
        Button pairButton=new Button(this);pairButton.setText(UploadConfig.token(this).isEmpty()?"ネット通話の端末登録（6桁）":"ネット通話の端末登録をやり直す");root.addView(pairButton,new LinearLayout.LayoutParams(-1,-2));pairButton.setOnClickListener(v->pairDevice());

        TextView sub=new TextView(this);sub.setText("画面OFFでもトランシーバー受信 / 支援録音は5分ごとにPCへ自動送信");sub.setTextSize(13);sub.setGravity(Gravity.CENTER);LinearLayout.LayoutParams slp=new LinearLayout.LayoutParams(-1,-2);slp.topMargin=dp(6);root.addView(sub,slp);

        nameEdit=new EditText(this);nameEdit.setSingleLine(true);nameEdit.setText(prefs.getString(PREF_NAME,defaultDeviceName()));nameEdit.setHint("端末名 例: 今店1");nameEdit.setImeOptions(EditorInfo.IME_ACTION_DONE);LinearLayout.LayoutParams nlp=new LinearLayout.LayoutParams(-1,-2);nlp.topMargin=dp(14);root.addView(nameEdit,nlp);nameEdit.setOnFocusChangeListener((v,h)->{if(!h)saveSettings();});

        serverUrlEdit=new EditText(this);serverUrlEdit.setSingleLine(true);serverUrlEdit.setText(prefs.getString(UploadConfig.PREF_SERVER_URL,""));serverUrlEdit.setHint("外部API URL（空欄なら同一Wi-FiでPC自動検出）");serverUrlEdit.setTextSize(13);serverUrlEdit.setVisibility(android.view.View.GONE);root.addView(serverUrlEdit,new LinearLayout.LayoutParams(-1,-2));serverUrlEdit.setOnFocusChangeListener((v,h)->{if(!h)saveSettings();});

        mobileUploadCheck=new CheckBox(this);mobileUploadCheck.setText("モバイル通信でも音声ログを送信する");mobileUploadCheck.setChecked(prefs.getBoolean(UploadConfig.PREF_MOBILE_UPLOAD,false));mobileUploadCheck.setChecked(true);mobileUploadCheck.setVisibility(android.view.View.GONE);mobileUploadCheck.setOnCheckedChangeListener((v,c)->AudioUploader.schedule(this));root.addView(mobileUploadCheck,new LinearLayout.LayoutParams(-1,-2));

        statusView=new TextView(this);statusView.setText("受信準備中…");statusView.setTextSize(19);statusView.setTextColor(Color.DKGRAY);statusView.setGravity(Gravity.CENTER);LinearLayout.LayoutParams stlp=new LinearLayout.LayoutParams(-1,dp(62));stlp.topMargin=dp(12);root.addView(statusView,stlp);

        supportButton=new Button(this);supportButton.setTextSize(21);supportButton.setAllCaps(false);root.addView(supportButton,new LinearLayout.LayoutParams(-1,-2));supportButton.setOnClickListener(v->toggleSupportRecording());updateSupportButton(SupportRecordingService.isRunning());

        pttButton=new Button(this);pttButton.setText("押して話す");pttButton.setTextSize(28);pttButton.setAllCaps(false);pttButton.setGravity(Gravity.CENTER);LinearLayout.LayoutParams plp=new LinearLayout.LayoutParams(-1,0,1f);plp.topMargin=dp(12);plp.bottomMargin=dp(10);root.addView(pttButton,plp);

        TextView note=new TextView(this);note.setText("画面を消したりロックしても、トランシーバー受信と支援録音は継続します。支援録音は5分ごとに分割し、PCがSHA256確認したファイルだけ端末から削除します。端末自体の電源OFF中は受信できません。");note.setTextSize(12);note.setGravity(Gravity.CENTER);root.addView(note,new LinearLayout.LayoutParams(-1,-2));

        pttButton.setOnTouchListener((v,e)->{switch(e.getActionMasked()){case MotionEvent.ACTION_DOWN:if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQ_RECORD_AUDIO);return true;}saveSettings();pttButton.setText("送信中…");if(SupportRecordingService.isRunning())SupportRecordingService.pttStart(this,currentDeviceName());else engine.startTransmit(currentDeviceName());return true;case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:if(SupportRecordingService.isRunning())SupportRecordingService.pttStop(this);else engine.stopTransmit();pttButton.setText("押して話す");return true;}return false;});setContentView(root);}

    private void pairDevice(){
        EditText pin=new EditText(this);pin.setSingleLine(true);pin.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        pin.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(6)});
        pin.setHint("Windows PCに表示された6桁の番号");
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("インターネット通話の端末登録")
                .setMessage("PCで Bee_PTT_Pair.cmd を実行し、表示された番号を入力")
                .setView(pin).setPositiveButton("登録",(d,w)->{
                    String value=pin.getText().toString();
                    if(!value.matches("[0-9]{6}")){Toast.makeText(this,"6桁で入力してください",Toast.LENGTH_LONG).show();return;}
                    new Thread(()->{
                        try{
                            String endpoint=EndpointDiscovery.resolve(getApplicationContext());
                            if(!endpoint.startsWith("https://"))throw new IllegalStateException("外部接続先を取得できません");
                            HttpURLConnection c=(HttpURLConnection)new URL(endpoint+"/ptt/pair").openConnection();
                            c.setRequestMethod("POST");c.setConnectTimeout(10000);c.setReadTimeout(10000);
                            c.setRequestProperty("Content-Type","application/json");
                            c.setDoOutput(true);
                            byte[] payload=new JSONObject().put("code",value).toString().getBytes(StandardCharsets.UTF_8);
                            try(OutputStream out=c.getOutputStream()){out.write(payload);}
                            int result=c.getResponseCode();
                            if(result!=200)throw new IllegalStateException("登録エラー HTTP "+result);
                            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
                            try(java.io.InputStream in=c.getInputStream()){byte[] buf=new byte[1024];int n;while((n=in.read(buf))!=-1)bytes.write(buf,0,n);}
                            String token=new JSONObject(bytes.toString("UTF-8")).getString("token");
                            if(token.isEmpty())throw new IllegalStateException("認証情報が返されませんでした");
                            UploadConfig.saveToken(this,token);
                            AudioUploader.schedule(this);
                            runOnUiThread(()->{Toast.makeText(this,"ネット通話の端末登録完了",Toast.LENGTH_LONG).show();
                                PttReceiverService.restartInternet(this);});
                        }catch(Exception ex){runOnUiThread(()->Toast.makeText(this,"端末登録失敗: "+ex.getMessage(),Toast.LENGTH_LONG).show());}
                    },"BeePttPair").start();
                }).setNegativeButton("キャンセル",null).create();
        dialog.show();
    }

    private void toggleSupportRecording(){if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQ_RECORD_AUDIO);return;}saveSettings();if(SupportRecordingService.isRunning()){SupportRecordingService.stop(this);updateSupportButton(false);}else{engine.stopTransmit();pttButton.setText("押して話す");SupportRecordingService.start(this,currentDeviceName());updateSupportButton(true);}}

    private void updateSupportButton(boolean r){if(supportButton==null)return;supportButton.setText(r?"支援時間 録音中（押して終了）":"支援時間 開始");if(statusView!=null)statusView.setText(r?"支援時間を録音中 / 約4MBで自動分割":"受信待機中");}

    private void saveSettings(){prefs.edit().putString(PREF_NAME,currentDeviceName()).apply();AudioUploader.schedule(this);}

    private String currentDeviceName(){String s=nameEdit.getText().toString().trim();return s.isEmpty()?defaultDeviceName():s;}private String defaultDeviceName(){return"端末-"+Build.MODEL;}private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}

    @Override protected void onStart(){super.onStart();IntentFilter nf=new IntentFilter(InternetPttRelay.ACTION_STATUS);if(Build.VERSION.SDK_INT>=33)registerReceiver(internetStateReceiver,nf,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(internetStateReceiver,nf);IntentFilter f=new IntentFilter(SupportRecordingService.ACTION_STATE);if(Build.VERSION.SDK_INT>=33)registerReceiver(supportStateReceiver,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(supportStateReceiver,f);updateSupportButton(SupportRecordingService.isRunning());}

    @Override protected void onStop(){try{unregisterReceiver(supportStateReceiver);}catch(Exception ignored){}try{unregisterReceiver(internetStateReceiver);}catch(Exception ignored){}saveSettings();super.onStop();}

    @Override public void onRequestPermissionsResult(int r,String[]p,int[]g){super.onRequestPermissionsResult(r,p,g);if(r==REQ_RECORD_AUDIO)Toast.makeText(this,g.length>0&&g[0]==PackageManager.PERMISSION_GRANTED?"マイクを許可しました。もう一度ボタンを押してください。":"トランシーバーにはマイク権限が必要です。",Toast.LENGTH_LONG).show();}

    @Override protected void onDestroy(){if(engine!=null)engine.close();super.onDestroy();}



    static final class AudioEngine {

        interface Listener{void onStatus(String t);void onSaved(String p);void onError(String m);}

        private static final String GROUP="239.255.42.99";private static final int PORT=45454,SAMPLE_RATE=16000,SAMPLES_PER_PACKET=320,BYTES_PER_PACKET=640;private static final byte[]MAGIC=new byte[]{'B','P','T','1'};private static final long TALKER_HOLD_MS=500;

        private final Context context;private final Listener listener;private final UUID localId;private final AtomicBoolean receiverRunning=new AtomicBoolean(false),transmitting=new AtomicBoolean(false);private Thread receiverThread,transmitThread;private MulticastSocket receiveSocket;private WifiManager.MulticastLock multicastLock;private UUID activeRemoteSender;private long activeRemoteLastMs;private String activeRemoteName="";

        AudioEngine(Context c,Listener l){context=c.getApplicationContext();listener=l;localId=DeviceIdentity.get(context);}

        void startReceiver(){if(!receiverRunning.compareAndSet(false,true))return;receiverThread=new Thread(()->{AudioTrack track=null;try{WifiManager wifi=(WifiManager)context.getSystemService(Context.WIFI_SERVICE);if(wifi!=null){multicastLock=wifi.createMulticastLock("bee-transceiver-rx");multicastLock.setReferenceCounted(false);multicastLock.acquire();}int min=AudioTrack.getMinBufferSize(SAMPLE_RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);track=new AudioTrack(AudioManager.STREAM_MUSIC,SAMPLE_RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min,BYTES_PER_PACKET*8),AudioTrack.MODE_STREAM);track.play();InetAddress group=InetAddress.getByName(GROUP);receiveSocket=new MulticastSocket(PORT);receiveSocket.setReuseAddress(true);receiveSocket.joinGroup(group);receiveSocket.setSoTimeout(1000);listener.onStatus("受信待機中");byte[]buf=new byte[1400];DatagramPacket p=new DatagramPacket(buf,buf.length);while(receiverRunning.get()){try{p.setLength(buf.length);receiveSocket.receive(p);handlePacket(track,p.getData(),p.getLength());}catch(java.net.SocketTimeoutException e){if(!transmitting.get()&&System.currentTimeMillis()-activeRemoteLastMs>TALKER_HOLD_MS&&activeRemoteSender!=null){activeRemoteSender=null;activeRemoteName="";listener.onStatus("受信待機中");}}}}catch(Exception e){if(receiverRunning.get())listener.onError("受信開始に失敗: "+msg(e));}finally{if(receiveSocket!=null)try{receiveSocket.close();}catch(Exception ignored){}if(track!=null){try{track.stop();}catch(Exception ignored){}try{track.release();}catch(Exception ignored){}}if(multicastLock!=null&&multicastLock.isHeld())try{multicastLock.release();}catch(Exception ignored){}}},"BeePTT-Receiver");receiverThread.start();}

        private void handlePacket(AudioTrack track,byte[]data,int length){try{if(length<28||data[0]!='B'||data[1]!='P'||data[2]!='T'||data[3]!='1')return;ByteBuffer b=ByteBuffer.wrap(data,4,length-4).order(ByteOrder.BIG_ENDIAN);int ver=b.get()&0xff;if(ver!=1)return;UUID sender=new UUID(b.getLong(),b.getLong());int seq=b.getInt();int nameLen=b.get()&0xff;if(nameLen>b.remaining())return;byte[]nb=new byte[nameLen];b.get(nb);String name=new String(nb,StandardCharsets.UTF_8);int audioLen=b.getShort()&0xffff;if(audioLen<=0||audioLen>b.remaining())return;if(sender.equals(localId))return;long now=System.currentTimeMillis();if(activeRemoteSender==null||now-activeRemoteLastMs>TALKER_HOLD_MS)activeRemoteSender=sender;if(!sender.equals(activeRemoteSender))return;activeRemoteLastMs=now;activeRemoteName=name;byte[]audio=new byte[audioLen];b.get(audio);track.write(audio,0,audio.length);if(!transmitting.get())listener.onStatus("受信中: "+name);}catch(Exception ignored){}}

        void startTransmit(String deviceName){if(!transmitting.compareAndSet(false,true))return;transmitThread=new Thread(()->{AudioRecord record=null;MulticastSocket sock=null;AacM4aWriter writer=null;AudioStorage.Target target=null;try{int min=AudioRecord.getMinBufferSize(SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);record=new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min,BYTES_PER_PACKET*8));if(record.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("マイクを初期化できません");sock=new MulticastSocket();sock.setTimeToLive(1);InetAddress group=InetAddress.getByName(GROUP);target=AudioStorage.create(context,deviceName,"PTT",0);writer=target.openWriter();short[]samples=new short[SAMPLES_PER_PACKET];int seq=0;record.startRecording();listener.onStatus("送信中: "+deviceName);while(transmitting.get()){int n=record.read(samples,0,samples.length,AudioRecord.READ_BLOCKING);if(n<=0)continue;byte[]audio=new byte[n*2];ByteBuffer pcm=ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<n;i++)pcm.putShort(samples[i]);writer.writePcm(audio,audio.length);byte[]name=trimUtf8(deviceName,48);ByteBuffer out=ByteBuffer.allocate(4+1+16+4+1+name.length+2+audio.length).order(ByteOrder.BIG_ENDIAN);out.put(MAGIC).put((byte)1).putLong(localId.getMostSignificantBits()).putLong(localId.getLeastSignificantBits()).putInt(seq++).put((byte)name.length).put(name).putShort((short)audio.length).put(audio);byte[]packet=out.array();sock.send(new DatagramPacket(packet,packet.length,group,PORT));InternetPttRelay.sendActive(packet);}}catch(Exception e){listener.onError("送信に失敗: "+msg(e));}finally{transmitting.set(false);if(record!=null){try{record.stop();}catch(Exception ignored){}try{record.release();}catch(Exception ignored){}}if(sock!=null)try{sock.close();}catch(Exception ignored){}if(writer!=null){try{writer.close();if(target!=null){AudioUploader.Item item=target.finish();AudioUploader.enqueue(context,item);listener.onSaved(item.fileName);target=null;}}catch(Exception e){if(target!=null)target.abort();}}listener.onStatus("受信待機中");}},"BeePTT-Transmit");transmitThread.start();}

        void stopTransmit(){transmitting.set(false);}void close(){transmitting.set(false);receiverRunning.set(false);if(receiveSocket!=null)receiveSocket.close();}

        private static byte[] trimUtf8(String s,int max){byte[]raw=s.getBytes(StandardCharsets.UTF_8);if(raw.length<=max)return raw;int end=max;while(end>0){String x=new String(raw,0,end,StandardCharsets.UTF_8);byte[]b=x.getBytes(StandardCharsets.UTF_8);if(b.length<=max)return b;end--;}return new byte[0];}

        private static String msg(Exception e){String m=e.getMessage();return m==null||m.trim().isEmpty()?e.getClass().getSimpleName():m;}

    }

}