package io.giasinton.keepawake;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * 前台服务：保持亮屏的执行者。
 *
 * 1) 挂一个 1x1 全透明悬浮窗，窗口带 FLAG_KEEP_SCREEN_ON——
 *    只要窗口可见，系统就不会让屏幕超时熄灭（官方推荐做法）。
 * 2) 常驻一条通知，秒级显示剩余亮屏时间，带「关闭」按钮。
 * 3) 每秒 tick 一次：读最新状态刷新通知；到点（或状态被别处改掉）就收摊。
 *
 * 服务是 START_STICKY：被系统杀掉会自动重启并按 prefs 恢复现场；
 * onDestroy 负责撤窗口（窗口一撤，屏幕立即恢复正常超时）。
 */
public class KeepAwakeService extends Service {

    private static final String TAG = "KeepAwake";
    private static final int NOTIF_ID = 1001;
    private static final String CHANNEL_ID = "keepawake";

    public static final String ACTION_START = "io.giasinton.keepawake.action.SERVICE_START";

    /** 进程内标志：磁贴/接收器用它判断服务是否在跑（同一个进程）。 */
    private static volatile boolean sRunning;

    private Handler mHandler;
    private WindowManager mWindowManager;
    private NotificationManager mNotifManager;
    private View mOverlay;

    static boolean isRunning() {
        return sRunning;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sRunning = true;
        mHandler = new Handler(Looper.getMainLooper());
        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        mNotifManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    }

    /**
     * 任何进入路径都按当前 prefs 恢复现场（点磁贴、心跳、到点前的续期、
     * 系统粘性重启传 null intent 都走这里）。
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 任何 startForegroundService 都要求 5 秒内进入前台状态，所以无条件先执行
        enterForeground();
        if (Keeper.sessionState(this) != Policy.SESSION_ALIVE) {
            // 状态已经无效：直接收摊（onDestroy 会移除通知）
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!addOverlay()) {
            // 悬浮窗加不上（权限被撤/系统拒绝）→ 收摊，磁贴下次对齐时会反映真实状态
            Keeper.stopSession(this);
            Keeper.syncTile(this);
            stopSelf();
            return START_NOT_STICKY;
        }
        startTicking();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
        }
        try {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } catch (RuntimeException ignored) {
            // 服务已不在前台状态时无害
        }
        removeOverlay();
        sRunning = false;
        super.onDestroy();
    }

    // ---------- 前台通知 ----------

    private void enterForeground() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            // API 34+ 必须显式声明类型，且要与清单里的 foregroundServiceType 一致
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private Notification buildNotification() {
        ensureChannel();
        long now = System.currentTimeMillis();
        long endAt = Keeper.endAt(this);
        String countdown = Policy.countdownText(endAt, now);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(countdown)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false);

        // 「关闭」按钮 → 内部广播（PendingIntent 以本应用身份发送，非导出接收器也能收到）
        Intent stop = new Intent(this, InternalReceiver.class)
                .setAction(InternalReceiver.ACTION_NOTIFICATION_STOP);
        PendingIntent stopPi = PendingIntent.getBroadcast(
                this, 3, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_tile),
                getString(R.string.notif_stop),
                stopPi).build());

        // 上岛/上胶囊扩展（小米超级岛 + Android 16 实时更新）
        Capsule.apply(builder, this, countdown, endAt, now);
        Notification notification = builder.build();
        Capsule.logDiagnosticsOnce(this, notification);
        return notification;
    }

    private void ensureChannel() {
        if (mNotifManager.getNotificationChannel(CHANNEL_ID) == null) {
            // IMPORTANCE_LOW：不响铃、不弹横幅，安静地常驻
            mNotifManager.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW));
        }
    }

    private void updateNotification() {
        try {
            mNotifManager.notify(NOTIF_ID, buildNotification());
        } catch (RuntimeException e) {
            Log.w(TAG, "notify failed", e);
        }
    }

    // ---------- 1x1 悬浮窗 ----------

    private boolean addOverlay() {
        if (mOverlay != null) {
            return true;
        }
        if (!Settings.canDrawOverlays(this)) {
            return false;
        }
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.START | Gravity.TOP;
        params.setTitle("keepawake-overlay");
        View view = new View(this);
        try {
            mWindowManager.addView(view, params);
            mOverlay = view;
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "addView failed", e);
            return false;
        }
    }

    private void removeOverlay() {
        if (mOverlay == null) {
            return;
        }
        try {
            mWindowManager.removeView(mOverlay);
        } catch (RuntimeException ignored) {
            // 窗口可能已被系统移除
        }
        mOverlay = null;
    }

    // ---------- 秒级 tick ----------

    private void startTicking() {
        mHandler.removeCallbacks(mTick);
        mHandler.post(mTick);
    }

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            try {
                if (Keeper.sessionState(KeepAwakeService.this) != Policy.SESSION_ALIVE) {
                    // 到点或状态被别处改掉：收尾
                    Keeper.stopSession(KeepAwakeService.this);
                    Keeper.syncTile(KeepAwakeService.this);
                    stopSelf();
                    return;
                }
                updateNotification();
            } catch (RuntimeException e) {
                // 单轮失败不能弄断 tick 链，否则倒计时通知会永远冻结在旧值
                Log.w(TAG, "tick failed", e);
            }
            mHandler.postDelayed(this, 1000);
        }
    };
}
