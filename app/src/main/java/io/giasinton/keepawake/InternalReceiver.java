package io.giasinton.keepawake;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 应用内部广播（清单里 exported=false，只有本应用的 PendingIntent 能送达）：
 *
 * · HEARTBEAT：10 分钟心跳，进程被回收后把前台服务补回来；
 * · DEADLINE：到点兜底，服务万一没跑起来就把状态收掉；
 * · NOTIFICATION_STOP：常驻通知上的「关闭」按钮。
 */
public class InternalReceiver extends BroadcastReceiver {

    static final String ACTION_HEARTBEAT = "io.giasinton.keepawake.action.HEARTBEAT";
    static final String ACTION_DEADLINE = "io.giasinton.keepawake.action.DEADLINE";
    static final String ACTION_NOTIFICATION_STOP = "io.giasinton.keepawake.action.NOTIFICATION_STOP";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) {
            return;
        }
        switch (action) {
            case ACTION_NOTIFICATION_STOP:
                Keeper.stopSession(context);
                Keeper.syncTile(context);
                break;
            case ACTION_HEARTBEAT:
            case ACTION_DEADLINE:
                if (Keeper.recover(context)) {
                    Keeper.syncTile(context);
                }
                break;
            default:
                break;
        }
    }
}
