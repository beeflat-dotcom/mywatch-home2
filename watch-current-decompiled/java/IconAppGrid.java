package jp.beeflat.watchhomeptt;

import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.view.ViewCompat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.function.Predicate;

/* JADX INFO: loaded from: classes.dex */
final class IconAppGrid {
    private static final String PAYMENT_COMPONENT = "payment_component";
    private static final String PREFS = "launcher";

    private IconAppGrid() {
    }

    private static int dp(Activity activity, int i) {
        return Math.round(activity.getResources().getDisplayMetrics().density * i);
    }

    private static void message(Activity activity, String str) {
        Toast.makeText(activity, str, 1).show();
    }

    static void launchPayment(Activity activity) {
        ComponentName componentNameUnflattenFromString = ComponentName.unflattenFromString(activity.getSharedPreferences(PREFS, 0).getString(PAYMENT_COMPONENT, ""));
        if (componentNameUnflattenFromString == null) {
            message(activity, "最初に楽天ペイのアイコンを選んでください");
            show(activity, true);
        } else {
            try {
                activity.startActivity(new Intent("android.intent.action.MAIN").addCategory("android.intent.category.LAUNCHER").setComponent(componentNameUnflattenFromString).addFlags(270532608));
            } catch (RuntimeException unused) {
                message(activity, "登録したアプリを開けません。楽天ペイを選び直してください");
                show(activity, true);
            }
        }
    }

    static void show(final Activity activity, final boolean z) {
        PackageManager packageManager = activity.getPackageManager();
        ArrayList<ResolveInfo> arrayList = new ArrayList(packageManager.queryIntentActivities(new Intent("android.intent.action.MAIN").addCategory("android.intent.category.LAUNCHER"), 0));
        arrayList.removeIf(new Predicate() { // from class: jp.beeflat.watchhomeptt.IconAppGrid$$ExternalSyntheticLambda0
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return activity.getPackageName().equals(((ResolveInfo) obj).activityInfo.packageName);
            }
        });
        Collections.sort(arrayList, new ResolveInfo.DisplayNameComparator(packageManager));
        if (arrayList.isEmpty()) {
            message(activity, "起動できるアプリがありません");
            return;
        }
        final Dialog dialog = new Dialog(activity, android.R.style.Theme.Material.NoActionBar);
        LinearLayout linearLayout = new LinearLayout(activity);
        int i = 1;
        linearLayout.setOrientation(1);
        linearLayout.setBackgroundColor(ViewCompat.MEASURED_STATE_MASK);
        linearLayout.setPadding(dp(activity, 10), dp(activity, 10), dp(activity, 10), dp(activity, 10));
        Button button = new Button(activity);
        button.setAllCaps(false);
        button.setText(z ? "戻る ・ 楽天ペイ選択" : "戻る ・ アプリ一覧");
        button.setOnClickListener(new View.OnClickListener() { // from class: jp.beeflat.watchhomeptt.IconAppGrid$$ExternalSyntheticLambda1
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                dialog.dismiss();
            }
        });
        linearLayout.addView(button, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scrollView = new ScrollView(activity);
        GridLayout gridLayout = new GridLayout(activity);
        gridLayout.setColumnCount(3);
        scrollView.addView(gridLayout);
        linearLayout.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1.0f));
        for (final ResolveInfo resolveInfo : arrayList) {
            LinearLayout linearLayout2 = new LinearLayout(activity);
            linearLayout2.setOrientation(i);
            linearLayout2.setGravity(17);
            linearLayout2.setPadding(dp(activity, 3), dp(activity, 7), dp(activity, 3), dp(activity, 7));
            ImageView imageView = new ImageView(activity);
            imageView.setImageDrawable(resolveInfo.loadIcon(packageManager));
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            linearLayout2.addView(imageView, new LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44)));
            TextView textView = new TextView(activity);
            textView.setText(resolveInfo.loadLabel(packageManager));
            textView.setTextColor(-1);
            textView.setTextSize(11.0f);
            textView.setGravity(17);
            textView.setMaxLines(2);
            textView.setEllipsize(TextUtils.TruncateAt.END);
            linearLayout2.addView(textView, new LinearLayout.LayoutParams(-1, dp(activity, 38)));
            GridLayout.LayoutParams layoutParams = new GridLayout.LayoutParams();
            layoutParams.width = 0;
            layoutParams.height = dp(activity, 103);
            layoutParams.columnSpec = GridLayout.spec(Integer.MIN_VALUE, 1.0f);
            gridLayout.addView(linearLayout2, layoutParams);
            linearLayout2.setContentDescription(resolveInfo.loadLabel(packageManager));
            linearLayout2.setOnClickListener(new View.OnClickListener() { // from class: jp.beeflat.watchhomeptt.IconAppGrid$$ExternalSyntheticLambda2
                @Override // android.view.View.OnClickListener
                public final void onClick(View view) {
                    IconAppGrid.lambda$show$2(resolveInfo, activity, z, dialog, view);
                }
            });
            i = 1;
        }
        dialog.setContentView(linearLayout);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(-1, -1);
        }
    }

    static /* synthetic */ void lambda$show$2(ResolveInfo resolveInfo, Activity activity, boolean z, Dialog dialog, View view) {
        ComponentName componentName = new ComponentName(resolveInfo.activityInfo.packageName, resolveInfo.activityInfo.name);
        try {
            activity.startActivity(new Intent("android.intent.action.MAIN").addCategory("android.intent.category.LAUNCHER").setComponent(componentName).addFlags(270532608));
            if (z) {
                activity.getSharedPreferences(PREFS, 0).edit().putString(PAYMENT_COMPONENT, componentName.flattenToString()).apply();
            }
            dialog.dismiss();
        } catch (RuntimeException unused) {
            message(activity, "このアプリを起動できませんでした");
        }
    }
}
