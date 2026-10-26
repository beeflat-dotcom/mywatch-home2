package jp.beeflat.watchhomeptt;

import android.app.Activity;
import android.app.Dialog;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.core.view.ViewCompat;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/* JADX INFO: loaded from: classes.dex */
final class LegacyQr {
    private static final String CACHE_FILE = "original_home_qr.png";
    private static final String LEGACY_PACKAGE = "com.example.mywatch";

    private LegacyQr() {
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static int dp(Activity activity, int i) {
        return Math.round(activity.getResources().getDisplayMetrics().density * i);
    }

    private static Bitmap bitmap(Activity activity) throws Exception {
        Bitmap bitmap;
        Bitmap bitmapDecodeFile;
        File file = new File(activity.getFilesDir(), CACHE_FILE);
        if (file.isFile() && file.length() > 100 && (bitmapDecodeFile = BitmapFactory.decodeFile(file.getAbsolutePath())) != null) {
            return bitmapDecodeFile;
        }
        Resources resourcesForApplication = activity.getPackageManager().getResourcesForApplication(LEGACY_PACKAGE);
        int identifier = resourcesForApplication.getIdentifier("home_qr", "drawable", LEGACY_PACKAGE);
        if (identifier == 0) {
            throw new IOException("旧ホームのQR画像が見つかりません");
        }
        Drawable drawable = resourcesForApplication.getDrawable(identifier, resourcesForApplication.newTheme());
        if (drawable instanceof BitmapDrawable) {
            bitmap = ((BitmapDrawable) drawable).getBitmap();
        } else {
            int intrinsicWidth = drawable.getIntrinsicWidth();
            int intrinsicHeight = drawable.getIntrinsicHeight();
            if (intrinsicWidth <= 0 || intrinsicHeight <= 0) {
                throw new IOException("旧QR画像を開けません");
            }
            Bitmap bitmapCreateBitmap = Bitmap.createBitmap(intrinsicWidth, intrinsicHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmapCreateBitmap);
            drawable.setBounds(0, 0, intrinsicWidth, intrinsicHeight);
            drawable.draw(canvas);
            bitmap = bitmapCreateBitmap;
        }
        if (bitmap == null) {
            throw new IOException("QR画像が読み込めません");
        }
        FileOutputStream fileOutputStream = new FileOutputStream(file);
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, fileOutputStream)) {
                throw new IOException("QR保存に失敗しました");
            }
            fileOutputStream.close();
            return bitmap;
        } catch (Throwable th) {
            try {
                fileOutputStream.close();
            } catch (Throwable th2) {
                th.addSuppressed(th2);
            }
            throw th;
        }
    }

    static void show(final Activity activity) {
        try {
            Bitmap bitmap = bitmap(activity);
            final Dialog dialog = new Dialog(activity, android.R.style.Theme.Material.NoActionBar);
            LinearLayout linearLayout = new LinearLayout(activity);
            linearLayout.setOrientation(1);
            linearLayout.setBackgroundColor(ViewCompat.MEASURED_STATE_MASK);
            linearLayout.setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12));
            Button button = new Button(activity);
            button.setText("戻る ・ QRコード");
            button.setAllCaps(false);
            button.setOnClickListener(new View.OnClickListener() { // from class: jp.beeflat.watchhomeptt.LegacyQr$$ExternalSyntheticLambda0
                @Override // android.view.View.OnClickListener
                public final void onClick(View view) {
                    dialog.dismiss();
                }
            });
            linearLayout.addView(button, new LinearLayout.LayoutParams(-1, -2));
            ImageView imageView = new ImageView(activity);
            imageView.setBackgroundColor(-1);
            imageView.setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8));
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            imageView.setImageBitmap(bitmap);
            imageView.setContentDescription("旧ホームと同じQRコード");
            linearLayout.addView(imageView, new LinearLayout.LayoutParams(-1, 0, 1.0f));
            imageView.setOnTouchListener(new View.OnTouchListener() { // from class: jp.beeflat.watchhomeptt.LegacyQr.1
                float x;
                float y;

                @Override // android.view.View.OnTouchListener
                public boolean onTouch(View view, MotionEvent motionEvent) {
                    if (motionEvent.getActionMasked() == 0) {
                        this.x = motionEvent.getX();
                        this.y = motionEvent.getY();
                        return true;
                    }
                    if (motionEvent.getActionMasked() == 1 && this.x - motionEvent.getX() > LegacyQr.dp(activity, 60) && this.x - motionEvent.getX() > Math.abs(motionEvent.getY() - this.y) * 1.6f) {
                        dialog.dismiss();
                    }
                    return true;
                }
            });
            dialog.setContentView(linearLayout);
            dialog.show();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setLayout(-1, -1);
            }
        } catch (PackageManager.NameNotFoundException unused) {
            Toast.makeText(activity, "最初のQR復元には旧ホームアプリを残してください", 1).show();
        } catch (Exception e) {
            Toast.makeText(activity, "QRを表示できません: " + e.getMessage(), 1).show();
        }
    }
}
