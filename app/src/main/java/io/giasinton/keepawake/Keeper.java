package io.giasinton.keepawake;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.util.Log;

/**
 * 会话状态（SharedPreferences）与启停入口。
 *
 * 真正保持亮屏的是 {@link KeepAwakeService}：它挂一个 1x1 透明悬浮窗，
 * 窗口带 FLAG_KEEP_SCREEN_ON，并常驻一条显示剩余时间的通知。
 * 本类只负责：状态读写、拉起/停止服务、闹钟兜底、磁贴刷新。
 */
public final class Keeper {

    private static final String TAG = "KeepAwake";
    private static final String PREFS = "keepawake";
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_DURATION = "duration";
    private static final String KEY_END_AT = "end_at";

    private Keeper() {}

    // ---------- 状态读取 ----------

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isActive(Context c) {
        return prefs(c).getBoolean(KEY_ACTIVE, false);
    }

    public static int durationId(Context c) {
        return prefs(c).getInt(KEY_DURATION, Policy.DEFAULT_DURATION);
    }

    public static long endAt(Context c) {
        return prefs(c).getLong(KEY_END_AT, 0L);
    }

    /** 当前会话状态（依据持久化状态推算）。 */
    public static int sessionState(Context c) {
        return Policy.sessionState(isActive(c), endAt(c), System.currentTimeMillis());
    }

    /** 是否已有悬浮窗权限（显示在其他应用上层）。 */
    public static boolean canDrawOverlays(Context c) {
        return Settings.canDrawOverlays(c);
    }

    // ---------- 悬浮窗授权引导 ----------

    /**
     * 跳到系统「显示在其他应用上层」授权页。只能在 TileService 里调用。
     * API 34+ 必须用 PendingIntent 版的 startActivityAndCollapse（Intent 版会抛异常），
     * 更老的系统则只有 Intent 版。
     */
    public static void launchOverlaySettings(TileService tile) {
        Intent i = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + tile.getPackageName()));
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                PendingIntent pi = PendingIntent.getActivity(
                        tile, 0, i,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                tile.startActivityAndCollapse(pi);
            } else {
                tile.startActivityAndCollapse(i);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "failed to open overlay settings", e);
            try {
                tile.startActivityAndCollapse(
                        new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (RuntimeException e2) {
                Log.w(TAG, "fallback to overlay settings list failed", e2);
            }
        }
    }

    // ---------- 会话启停 ----------

    /**
     * 以当前档位开启会话：写状态 + 拉起前台服务。
     * 前台服务起不来（后台启动受限等）会回滚状态，让磁贴显示保持诚实。
     */
    public static synchronized boolean startSession(Context c) {
        if (!canDrawOverlays(c)) {
            return false;
        }
        long endAt = Policy.endAt(durationId(c), System.currentTimeMillis());
        prefs(c).edit()
                .putBoolean(KEY_ACTIVE, true)
                .putLong(KEY_END_AT, endAt)
                .apply();
        try {
            c.startForegroundService(new Intent(c, KeepAwakeService.class)
                    .setAction(KeepAwakeService.ACTION_START));
        } catch (RuntimeException e) {
            Log.w(TAG, "startForegroundService failed", e);
            prefs(c).edit()
                    .putBoolean(KEY_ACTIVE, false)
                    .putLong(KEY_END_AT, 0L)
                    .apply();
            return false;
        }
        scheduleAlarms(c, endAt);
        Log.d(TAG, "session started, endAt=" + endAt);
        return true;
    }

    /**
     * 结束会话：清状态、停服务（服务负责撤掉悬浮窗和通知）、取消闹钟。
     * 磁贴刷新由调用方负责（磁贴自己会 pushTileState，广播接收器在 changed 时刷）。
     */
    public static synchronized void stopSession(Context c) {
        boolean wasActive = isActive(c);
        prefs(c).edit()
                .putBoolean(KEY_ACTIVE, false)
                .putLong(KEY_END_AT, 0L)
                .apply();
        c.stopService(new Intent(c, KeepAwakeService.class));
        cancelAlarms(c);
        if (wasActive) {
            Log.d(TAG, "session stopped");
        }
    }

    /**
     * 长按菜单改档位：存档位；会话进行中则重算结束时刻。
     * 服务的秒级 tick 直接读 prefs，1 秒内自动生效，无需重启服务。
     */
    public static synchronized void setDuration(Context c, int newDurationId) {
        SharedPreferences.Editor editor = prefs(c).edit().putInt(KEY_DURATION, newDurationId);
        if (sessionState(c) == Policy.SESSION_ALIVE) {
            editor.putLong(KEY_END_AT, Policy.endAt(newDurationId, System.currentTimeMillis()));
        }
        editor.apply();
        if (recover(c)) {
            syncTile(c);
        }
    }

    // ---------- 自愈 ----------

    /**
     * 自愈核心。多个时机都会调它：每次面板展开（磁贴 onStartListening）、
     * 心跳/到点闹钟、开机、覆盖安装。
     *
     * @return true 表示有状态被清理过，调用方应刷新磁贴显示
     */
    public static synchronized boolean recover(Context c) {
        int state = sessionState(c);
        if (state == Policy.SESSION_EXPIRED) {
            stopSession(c);
            return true;
        }
        if (state == Policy.SESSION_NONE) {
            if (KeepAwakeService.isRunning()) {
                c.stopService(new Intent(c, KeepAwakeService.class));
            }
            cancelAlarms(c);
            return false;
        }
        // SESSION_ALIVE
        if (!canDrawOverlays(c)) {
            // 悬浮窗权限被撤回，无法继续，收摊（下次点磁贴会重新引导授权）
            Log.w(TAG, "overlay permission revoked; stopping session");
            stopSession(c);
            return true;
        }
        // 无论服务在不在跑都发一次 START：
        //  · 不在跑 → 拉起并恢复现场；
        //  · 在跑   → onStartCommand 会重挂 tick 链、刷新通知（自愈 tick 卡死的情况）。
        try {
            c.startForegroundService(new Intent(c, KeepAwakeService.class)
                    .setAction(KeepAwakeService.ACTION_START));
        } catch (RuntimeException e) {
            Log.w(TAG, "recover: start service failed", e);
        }
        scheduleAlarms(c, endAt(c));
        return false;
    }

    // ---------- 闹钟（服务万一没被拉起时的兜底） ----------

    static void scheduleAlarms(Context c, long endAt) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) {
            return;
        }
        // 到点兜底：只在状态真的过期时收尾
        am.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                endAt > 0 ? endAt : System.currentTimeMillis() + Policy.HEARTBEAT_MS,
                pending(c, InternalReceiver.ACTION_DEADLINE, 1));
        // 心跳：进程被回收后把它拉回来（服务是 START_STICKY，心跳是第二道保险）
        am.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + Policy.HEARTBEAT_MS,
                pending(c, InternalReceiver.ACTION_HEARTBEAT, 2));
    }

    static void cancelAlarms(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) {
            return;
        }
        am.cancel(pending(c, InternalReceiver.ACTION_DEADLINE, 1));
        am.cancel(pending(c, InternalReceiver.ACTION_HEARTBEAT, 2));
    }

    private static PendingIntent pending(Context c, String action, int requestCode) {
        Intent i = new Intent(c, InternalReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(
                c, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ---------- 磁贴刷新 ----------

    /** 让系统在下次绘制时回调磁贴的 onStartListening（由它 push 最新状态）。 */
    public static void syncTile(Context c) {
        try {
            TileService.requestListeningState(c, new android.content.ComponentName(c, KeepAwakeTileService.class));
        } catch (RuntimeException e) {
            Log.w(TAG, "requestListeningState failed", e);
        }
    }
}
