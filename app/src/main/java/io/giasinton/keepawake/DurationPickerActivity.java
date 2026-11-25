package io.giasinton.keepawake;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

/**
 * 长按磁贴时由系统控制中心拉起的时长菜单。
 *
 * <p>清单里限定只能被 Quick Settings 启动（BIND_QUICK_SETTINGS_TILE），
 * 没有 LAUNCHER 入口，桌面上不会出现图标，也无法被普通方式打开。
 * 选完时长、对话框关掉即 finish，不留下任何界面。
 *
 * <p>顺带在这里请求一次「显示通知」权限（Android 13+ 之后才需要，
 * 用于显示剩余亮屏时间的常驻通知；系统通常会在首次发通知时自动弹）。
 */
public class DurationPickerActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestNotificationPermissionIfNeeded();

        int checked = Policy.indexOf(Keeper.durationId(this));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.duration_title)
                .setSingleChoiceItems(
                        Policy.durationLabels(),
                        checked,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int which) {
                                Keeper.setDuration(DurationPickerActivity.this, Policy.idAt(which));
                                dialogInterface.dismiss();
                            }
                        })
                .setOnDismissListener(
                        new DialogInterface.OnDismissListener() {
                            @Override
                            public void onDismiss(DialogInterface dialogInterface) {
                                finish();
                            }
                        })
                .create();
        dialog.show();
    }

    /** Android 13+：没给通知权限就请求一次（系统可能已自动弹过，已授权则直接返回）。 */
    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 1);
        }
    }
}
