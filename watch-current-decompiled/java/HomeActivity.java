package jp.beeflat.watchhomeptt;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes.dex */
public class HomeActivity extends Activity {
    private static final int AUDIO_REQUEST = 71;
    private static final long TALK_MAX_MS = 60000;
    private TextView beeClipText;
    private TextClock clockHeader;
    private boolean gestureConsumed;
    private long gestureDownTime;
    private float gestureDownX;
    private float gestureDownY;
    private boolean gestureFromPtt;
    private ScrollView homeScroll;
    private boolean multiplePointers;
    private Runnable pendingGesture;
    private boolean pressing;
    private TextView state;
    private Button talk;
    private Button recordOnly;
    private HomeUpdater updater;
    private final PaymentSwipe paymentSwipe = new PaymentSwipe();
    private final BroadcastReceiver beeClipReceiver = new BroadcastReceiver() { // from class: jp.beeflat.watchhomeptt.HomeActivity.1
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            if (HomeActivity.this.beeClipText == null) {
                return;
            }
            String stringExtra = intent.getStringExtra("text");
            if (stringExtra == null) {
                stringExtra = "";
            }
            HomeActivity.this.beeClipText.setText(stringExtra.isEmpty() ? "Surface文字: なし" : "Surface文字:\n" + stringExtra);
        }
    };
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable talkTimeout = new Runnable() { // from class: jp.beeflat.watchhomeptt.HomeActivity$$ExternalSyntheticLambda4
        @Override // java.lang.Runnable
        public final void run() {
            HomeActivity.this.stopTalking();
        }
    };
    private final Runnable refresh = new Runnable() { // from class: jp.beeflat.watchhomeptt.HomeActivity.2
        @Override // java.lang.Runnable
        public void run() {
            if (HomeActivity.this.state != null) {
                HomeActivity.this.state.setText(PttService.currentState());
            }
            HomeActivity.this.refreshRecordingButton();
            HomeActivity.this.handler.postDelayed(this, 1000L);
        }
    };

    static /* synthetic */ void lambda$onCreate$3() {
    }

    private boolean touchInside(View view, float f, float f2) {
        if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0) {
            return false;
        }
        int[] iArr = new int[2];
        view.getLocationOnScreen(iArr);
        int i = iArr[0];
        if (f < i || f >= i + view.getWidth()) {
            return false;
        }
        int i2 = iArr[1];
        return f2 >= ((float) i2) && f2 < ((float) (i2 + view.getHeight()));
    }

    @Override // android.app.Activity, android.view.Window.Callback
    public boolean dispatchTouchEvent(MotionEvent motionEvent) {
        Runnable runnable;
        int actionMasked = motionEvent.getActionMasked();
        if (actionMasked == 0) {
            this.gestureDownX = motionEvent.getX();
            this.gestureDownY = motionEvent.getY();
            this.gestureDownTime = motionEvent.getEventTime();
            this.gestureConsumed = false;
            this.multiplePointers = false;
            this.pendingGesture = null;
            this.gestureFromPtt = touchInside(this.talk, motionEvent.getRawX(), motionEvent.getRawY());
            ScrollView scrollView = this.homeScroll;
            this.paymentSwipe.begin(this.gestureDownX, this.gestureDownY, this.gestureDownTime, scrollView != null && scrollView.getScrollY() == 0 && touchInside(this.clockHeader, motionEvent.getRawX(), motionEvent.getRawY()), ViewConfiguration.get(this).getScaledTouchSlop(), dp(90));
        }
        if (actionMasked == 5) {
            this.multiplePointers = true;
            this.paymentSwipe.cancel();
            this.pendingGesture = null;
        }
        if (actionMasked == 3) {
            this.paymentSwipe.cancel();
            this.pendingGesture = null;
        }
        if (actionMasked == 2) {
            this.paymentSwipe.move(motionEvent.getX(), motionEvent.getY());
        }
        if (this.gestureConsumed) {
            if (actionMasked == 1 && !this.multiplePointers && (runnable = this.pendingGesture) != null) {
                this.pendingGesture = null;
                this.handler.post(runnable);
            }
            return true;
        }
        if (actionMasked == 2 && !this.gestureFromPtt && !this.multiplePointers && motionEvent.getEventTime() - this.gestureDownTime <= 650) {
            final float x = motionEvent.getX() - this.gestureDownX;
            float y = motionEvent.getY() - this.gestureDownY;
            if (Math.abs(x) > dp(65) && Math.abs(x) > Math.abs(y) * 1.6f) {
                this.paymentSwipe.cancel();
                MotionEvent motionEventObtain = MotionEvent.obtain(motionEvent);
                motionEventObtain.setAction(3);
                super.dispatchTouchEvent(motionEventObtain);
                motionEventObtain.recycle();
                this.gestureConsumed = true;
                this.pendingGesture = new Runnable() { // from class: jp.beeflat.watchhomeptt.HomeActivity$$ExternalSyntheticLambda5
                    @Override // java.lang.Runnable
                    public final void run() {
                        HomeActivity.this.lambda$dispatchTouchEvent$0(x);
                    }
                };
                return true;
            }
        }
        if (actionMasked == 1 && this.paymentSwipe.release(motionEvent.getX(), motionEvent.getY(), motionEvent.getEventTime()) && !this.multiplePointers) {
            MotionEvent motionEventObtain2 = MotionEvent.obtain(motionEvent);
            motionEventObtain2.setAction(3);
            super.dispatchTouchEvent(motionEventObtain2);
            motionEventObtain2.recycle();
            TextClock textClock = this.clockHeader;
            if (textClock != null) {
                textClock.performHapticFeedback(1);
            }
            this.handler.post(new Runnable() { // from class: jp.beeflat.watchhomeptt.HomeActivity$$ExternalSyntheticLambda6
                @Override // java.lang.Runnable
                public final void run() {
                    HomeActivity.this.lambda$dispatchTouchEvent$1();
                }
            });
            return true;
        }
        return super.dispatchTouchEvent(motionEvent);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$dispatchTouchEvent$0(float f) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (f < 0.0f) {
            IconAppGrid.show(this, false);
        } else {
            LegacyQr.show(this);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$dispatchTouchEvent$1() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        IconAppGrid.launchPayment(this);
    }

    private int dp(int i) {
        return Math.round(getResources().getDisplayMetrics().density * i);
    }

    private TextView text(String str, int i) {
        TextView textView = new TextView(this);
        textView.setText(str);
        textView.setTextColor(-1);
        textView.setTextSize(i);
        textView.setGravity(17);
        textView.setPadding(dp(4), dp(9), dp(4), dp(9));
        return textView;
    }

    private Button button(LinearLayout linearLayout, String str, final Runnable runnable) {
        Button button = new Button(this);
        button.setText(str);
        button.setAllCaps(false);
        linearLayout.addView(button, new LinearLayout.LayoutParams(-1, -2));
        button.setOnClickListener(new View.OnClickListener() { // from class: jp.beeflat.watchhomeptt.HomeActivity$$ExternalSyntheticLambda3
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                runnable.run();
            }
        });
        return button;
    }

    private void send(String str) {
        startForegroundService(new Intent(this, (Class<?>) PttService.class).setAction(str));
    }

    private void startReceiver() {
        send("jp.beeflat.watchhomeptt.START");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void stopTalking() {
        this.handler.removeCallbacks(this.talkTimeout);
        if (this.pressing) {
            this.pressing = false;
            send("jp.beeflat.watchhomeptt.TALK_STOP");
            Button button = this.talk;
            if (button != null) {
                button.setText("押して話す");
            }
        }
    }

    private void toggleTalking() {
        if (this.pressing) {
            stopTalking();
            return;
        }
        if (checkSelfPermission("android.permission.RECORD_AUDIO") != 0) {
            requestPermissions(new String[]{"android.permission.RECORD_AUDIO"}, AUDIO_REQUEST);
            return;
        }
        this.pressing = true;
        send("jp.beeflat.watchhomeptt.TALK_START");
        Button button = this.talk;
        if (button != null) {
            button.setText("送信中…\nもう一度押すと停止");
        }
        this.handler.removeCallbacks(this.talkTimeout);
        this.handler.postDelayed(this.talkTimeout, TALK_MAX_MS);
    }

    @Override // android.app.Activity
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        ScrollView scrollView = new ScrollView(this);
        this.homeScroll = scrollView;
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.rgb(7, 10, 15));
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(1);
        linearLayout.setPadding(dp(12), dp(12), dp(12), dp(12));
        linearLayout.setGravity(1);
        scrollView.addView(linearLayout);
        setContentView(scrollView);
        TextClock textClock = new TextClock(this);
        this.clockHeader = textClock;
        textClock.setFormat24Hour("HH:mm");
        textClock.setFormat12Hour("HH:mm");
        textClock.setTextSize(43.0f);
        textClock.setTextColor(-1);
        textClock.setGravity(17);
        linearLayout.addView(textClock);
        TextClock textClock2 = new TextClock(this);
        textClock2.setFormat24Hour("M月d日 E");
        textClock2.setFormat12Hour("M月d日 E");
        textClock2.setTextSize(18.0f);
        textClock2.setTextColor(-3355444);
        textClock2.setGravity(17);
        linearLayout.addView(textClock2);
        TextView textViewText = text("← アプリ一覧   → QR   時計を下フリック: 楽天ペイ", 10);
        textViewText.setTextColor(-3355444);
        linearLayout.addView(textViewText);
        TextView textViewText2 = text("接続準備中", 14);
        this.state = textViewText2;
        linearLayout.addView(textViewText2);
        TextView textViewText3 = text("Surface文字: なし", 12);
        this.beeClipText = textViewText3;
        textViewText3.setTextColor(-3355444);
        linearLayout.addView(this.beeClipText);
        Button button = button(linearLayout, "押して話す", new Runnable() { // from class: jp.beeflat.watchhomeptt.HomeActivity$$ExternalSyntheticLambda12
            @Override // java.lang.Runnable
            public final void run() {
                HomeActivity.lambda$onCreate$3();
            }
        });
        this.talk = button;
        button.setTextSize(25.0f);
        this.talk.setMinHeight(dp(130));
        this.talk.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(23, 132, 88)));
        this.talk.setOnClickListener(new View.OnClickListener() { // from class: jp.beeflat.watchhomeptt.HomeActivity$ExternalSyntheticLambda13
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                HomeActivity.this.lambda$onCreate$4(view);
            }
        });
        Button recordButton = button(linearLayout, "録音のみ", new Runnable() {
            @Override public void run() {
                HomeActivity.this.toggleRecordingOnly();
            }
        });
        this.recordOnly = recordButton;
        recordButton.setTextSize(25.0f);
        recordButton.setMinHeight(dp(130));
        recordButton.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(23, 132, 88)));
        refreshRecordingButton();
        button(linearLayout, "ログ確認", new Runnable() { // from class: jp.beeflat.watchhomeptt.HomeActivity$$ExternalSyntheticLambda14
            @Override // java.lang.Runnable
            public final void run() {
                HomeActivity.this.showLogs();
            }
        });
        button(linearLayout, "アプリ更新", new Runnable() { // from class: jp.beeflat.watchhomeptt.HomeActivity$$ExternalSyntheticLambda15
            @Override // java.lang.Runnable
            public final void run() {
                HomeActivity.this.checkUpdate();
            }
        });
        button(linearLayout, "アプリ一覧", new Runnable() { // from class: jp.beeflat.watchhomeptt.HomeActivity$$ExternalSyntheticLambda16
            @Override // java.lang.Runnable
            public final void run() {
                HomeActivity.this.showApps();
            }
        });
        button(linearLayout, "楽天ペイを選び直す", new Runnable() { // from class: jp.beeflat.watchhomeptt.HomeActivity$$ExternalSyntheticLambda17
            @Override // java.lang.Runnable
            public final void run() {
                HomeActivity.this.lambda$onCreate$5();
            }
        });
        button(linearLayout, "接続・ペアリング設定", new Runnable() { // from class: jp.beeflat.watchhomeptt.HomeActivity$$ExternalSyntheticLambda1
            @Override // java.lang.Runnable
            public final void run() {
                HomeActivity.this.showPairing();
            }
        });
        button(linearLayout, "ホームアプリの変更", new Runnable() { // from class: jp.beeflat.watchhomeptt.HomeActivity$$ExternalSyntheticLambda2
            @Override // java.lang.Runnable
            public final void run() {
                HomeActivity.this.lambda$onCreate$6();
            }
        });
        if (checkSelfPermission("android.permission.RECORD_AUDIO") != 0) {
            requestPermissions(new String[]{"android.permission.RECORD_AUDIO"}, AUDIO_REQUEST);
        }
        this.updater = new HomeUpdater(this);
        registerReceiver(this.beeClipReceiver, new IntentFilter("jp.beeflat.watchhomeptt.BEECLIP_TEXT_CHANGED"));
        startReceiver();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreate$4(View view) {
        toggleTalking();
    }

    private void toggleRecordingOnly() {
        if (checkSelfPermission("android.permission.RECORD_AUDIO") != 0) {
            requestPermissions(new String[]{"android.permission.RECORD_AUDIO"}, AUDIO_REQUEST);
            return;
        }
        boolean recording = PttService.isRecording();
        Intent intent = new Intent(this, PttService.class)
                .setAction(recording ? PttService.RECORD_STOP : PttService.RECORD_START);
        startForegroundService(intent);
        this.handler.postDelayed(this::refreshRecordingButton, 150L);
    }

    private void refreshRecordingButton() {
        Button button = this.recordOnly;
        if (button == null) return;
        boolean recording = PttService.isRecording();
        button.setText(recording ? "録音中（押して終了）" : "録音のみ");
        button.setBackgroundTintList(ColorStateList.valueOf(
                recording ? Color.rgb(180, 48, 48) : Color.rgb(23, 132, 88)));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreate$5() {
        IconAppGrid.show(this, true);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreate$6() {
        startActivity(new Intent("android.settings.HOME_SETTINGS"));
    }

    @Override // android.app.Activity
    protected void onResume() {
        super.onResume();
        this.handler.removeCallbacks(this.refresh);
        this.handler.post(this.refresh);
        HomeUpdater homeUpdater = this.updater;
        if (homeUpdater != null) {
            homeUpdater.resume();
        }
    }

    @Override // android.app.Activity
    protected void onPause() {
        stopTalking();
        this.paymentSwipe.cancel();
        this.pendingGesture = null;
        this.handler.removeCallbacks(this.refresh);
        super.onPause();
    }

    @Override // android.app.Activity
    protected void onDestroy() {
        try {
            unregisterReceiver(this.beeClipReceiver);
        } catch (Exception unused) {
        }
        super.onDestroy();
    }

    @Override // android.app.Activity
    public void onRequestPermissionsResult(int i, String[] strArr, int[] iArr) {
        super.onRequestPermissionsResult(i, strArr, iArr);
        if (i == AUDIO_REQUEST && iArr.length > 0 && iArr[0] == 0) {
            startReceiver();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void checkUpdate() {
        String string = getSharedPreferences("ptt", 0).getString("update_manifest_url", "");
        if (!string.isEmpty()) {
            this.updater.begin(string);
        } else {
            Toast.makeText(this, "公開先を確認しています", 0).show();
            new Thread(new Runnable() { // from class: jp.beeflat.watchhomeptt.HomeActivity$$ExternalSyntheticLambda9
                @Override // java.lang.Runnable
                public final void run() {
                    HomeActivity.this.lambda$checkUpdate$8();
                }
            }, "HomeUpdateDiscovery").start();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$checkUpdate$8() {
        String found;
        try {
            found = PttService.discover() + "/watch-home/latest.json";
        } catch (Exception unused) {
            found = "";
        }
        final String manifestUrl = found;
        runOnUiThread(() -> HomeActivity.this.lambda$checkUpdate$7(manifestUrl));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$checkUpdate$7(String str) {
        if (str.isEmpty()) {
            Toast.makeText(this, "更新先が見つかりません。PCの公開状態を確認してください", 1).show();
        } else {
            this.updater.begin(str);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showLogs() {
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(1);
        final TextView textViewText = text("読み込み中…", 13);
        linearLayout.addView(textViewText);
        button(linearLayout, "最新ログ取得", new Runnable() { // from class: jp.beeflat.watchhomeptt.HomeActivity$$ExternalSyntheticLambda8
            @Override // java.lang.Runnable
            public final void run() {
                HomeActivity.this.lambda$showLogs$9(textViewText);
            }
        });
        new AlertDialog.Builder(this).setTitle("PCログ・端末ログ").setView(linearLayout).setPositiveButton("閉じる", (DialogInterface.OnClickListener) null).show();
        lambda$showLogs$9(textViewText);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX INFO: renamed from: loadLogs, reason: merged with bridge method [inline-methods] */
    public void lambda$showLogs$9(final TextView textView) {
        textView.setText("PCログ取得中…\n\n端末ログ:\n" + PttService.readEvents(this));
        new Thread(() -> {
            final String pc = fetchPcLog();
            runOnUiThread(() -> textView.setText(pc + "\n\n端末ログ:\n" + PttService.readEvents(this)));
        }, "WatchLogFetch").start();
    }

    private String fetchPcLog() {
        SharedPreferences prefs = getSharedPreferences("ptt", 0);
        String token = prefs.getString("token", "");
        if (token.isEmpty()) return "ログ未接続：先に「接続・ペアリング設定」で6桁コードを登録してください。";
        HttpURLConnection c = null;
        try {
            String base = prefs.getString("endpoint", "").trim();
            if (base.isEmpty()) base = PttService.discover();
            if (!base.startsWith("https://")) return "ログ取得先をHTTPSで確認できません。";
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            c = (HttpURLConnection) new URL(base + "/watch-home/logs").openConnection();
            c.setConnectTimeout(7000);
            c.setReadTimeout(7000);
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Authorization", "Bearer " + token);
            int code = c.getResponseCode();
            if (code == 401 || code == 403) return "ログの認証に失敗しました。ペアリングをやり直してください。";
            if (code == 404) return "PC側にログ中継機能がまだ登録されていません。";
            if (code == 503) return "PC側のログ接続設定がまだ完了していません。";
            if (code == 502) return "PC側の旧ログ配信（8765番）に接続できません。PC側を確認してください。";
            if (code != 200) return "PCログの取得に失敗しました。HTTP " + code;

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (n == 0) continue;
                    if (out.size() + n > 524288) throw new IOException("ログ受信サイズ超過");
                    out.write(buf, 0, n);
                }
            }
            JSONObject json = new JSONObject(out.toString("UTF-8"));
            String state = json.optString("state", "ok");
            if (!"ok".equals(state) && !"missing".equals(state)) return "PC側ログ読込エラー";
            JSONArray lines = json.optJSONArray("lines");
            StringBuilder b = new StringBuilder(json.optString("date", "") + "\n");
            if ("missing".equals(state)) {
                b.append("本日のログはまだありません");
            } else if (lines == null || lines.length() == 0) {
                b.append("本日のログは空です");
            } else {
                for (int i = lines.length() - 1; i >= 0; i--) b.append(lines.optString(i)).append("\n\n");
            }
            return b.toString();
        } catch (Exception e) {
            return "PCログ取得失敗: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showPairing() {
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(1);
        linearLayout.addView(text("PCで Bee_PTT_Pair.cmd を起動し、表示された6桁コードを入力してください。ログ接続キーやIPアドレスの入力は不要です。", 14), new LinearLayout.LayoutParams(-1, -2));
        final EditText editText = new EditText(this);
        editText.setSingleLine(true);
        editText.setInputType(2);
        editText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        editText.setHint("初回6桁コード");
        linearLayout.addView(editText, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle("トランシーバー・ログ接続").setView(linearLayout).setNegativeButton("取消", (DialogInterface.OnClickListener) null).setPositiveButton("接続", new DialogInterface.OnClickListener() { // from class: jp.beeflat.watchhomeptt.HomeActivity$$ExternalSyntheticLambda11
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i) {
                HomeActivity.this.lambda$showPairing$12(editText, dialogInterface, i);
            }
        }).show();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$showPairing$12(EditText editText, DialogInterface dialogInterface, int i) {
        String strTrim = editText.getText().toString().trim();
        if (!strTrim.matches("[0-9]{6}")) {
            Toast.makeText(this, "6桁のコードを入力してください", 1).show();
        } else {
            getSharedPreferences("ptt", 0).edit().remove("endpoint").apply();
            PttService.pair(this, strTrim);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showApps() {
        IconAppGrid.show(this, false);
    }
}
