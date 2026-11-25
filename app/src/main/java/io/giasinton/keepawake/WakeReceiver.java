package io.giasinton.keepawake;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 系统广播（exported=true，但只订阅系统保护广播）：
 * 开机 / 覆盖安装后恢复会话现场。
 * 系统广播不保留闹钟，所以 recover() 里会重新排闹钟。
 */
public class WakeReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        switch (intent.getAction()) {
            case Intent.ACTION_BOOT_COMPLETED:
            case Intent.ACTION_MY_PACKAGE_REPLACED:
                if (Keeper.recover(context)) {
                    Keeper.syncTile(context);
                }
                break;
            default:
                break;
        }
    }
}
