package io.giasinton.keepawake;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.lang.reflect.Method;

/**
 * 让常驻通知「上岛 / 上胶囊」的两套扩展数据：
 *
 * 1) Android 16 实时更新（Live Updates，promoted ongoing notification）：
 *    OPPO ColorOS 16 流体云、Pixel 状态栏 chip、三星 Now Bar 都遵循这套
 *    公开规范——通知满足条件（ongoing、有标题、标准样式、低重要度渠道……
 *    我们都满足）并携带 android.requestPromotedOngoing 扩展，系统就会提升展示。
 *    编译目标是 API 34，所以用字符串 key + 反射调用 API 36 的方法。
 *
 * 2) 小米超级岛（HyperOS 焦点通知 · 客户端路径）：
 *    往通知 extras 里塞 miui.focus.param JSON（官方开发指南的接入方式）。
 *    能否真的上岛取决于系统有没有给本应用开焦点通知权限（白名单）；没权限
 *    时 filterWhenNoPermission 默认 false，通知照常显示、只是不上岛，所以
 *    无条件附带是安全的。
 *
 * 两套扩展对不认识它们的系统而言只是无害的额外 extras。
 */
final class Capsule {

    private static final String TAG = "KeepAwake";

    /** Notification.EXTRA_REQUEST_PROMOTED_ONGOING（API 36.1 才有常量，这里用字符串 key）。 */
    private static final String KEY_REQUEST_PROMOTED = "android.requestPromotedOngoing";

    private static boolean sDiagLogged;

    private Capsule() {}

    /** 给通知挂上岛/胶囊所需的扩展数据（在 builder.build() 之前调用）。 */
    static void apply(Notification.Builder builder, Context ctx,
                      String countdown, long endAt, long now) {
        boolean timed = endAt > 0;

        // ---- 实时更新：ColorOS 16 流体云 / Pixel chip / 三星 Now Bar ----
        builder.addExtras(promotedExtras());
        if (timed) {
            // chip 里可以直接倒数（when 用结束时刻，setShowWhen(false) 不影响 shade 显示控制）
            builder.setWhen(endAt).setUsesChronometer(true).setChronometerCountDown(true);
        }
        setShortCriticalText(builder, Policy.chipText(endAt, now));

        // ---- 小米超级岛 ----
        if (isXiaomiFamily()) {
            Bundle focus = focusBundle(ctx, countdown, endAt, now, timed);
            if (focus != null) {
                builder.addExtras(focus);
            }
        }
    }

    // ---------- Android 16 实时更新 ----------

    private static Bundle promotedExtras() {
        Bundle extras = new Bundle();
        extras.putBoolean(KEY_REQUEST_PROMOTED, true);
        return extras;
    }

    /** Builder#setShortCriticalText（API 36）：状态栏 chip 的短文案，反射调用。 */
    private static void setShortCriticalText(Notification.Builder builder, String text) {
        if (Build.VERSION.SDK_INT < 36) {
            return;
        }
        try {
            Method m = Notification.Builder.class.getMethod("setShortCriticalText", String.class);
            m.invoke(builder, text);
        } catch (Throwable t) {
            Log.d(TAG, "setShortCriticalText unavailable: " + t);
        }
    }

    // ---------- 小米超级岛 ----------

    private static boolean isXiaomiFamily() {
        String m = Build.MANUFACTURER;
        return m != null && m.equalsIgnoreCase("xiaomi");
    }

    /**
     * 构建 miui.focus.param（JSON 字符串）+ miui.focus.pics（图标 Bundle）。
     * 字段名依据小米 HyperOS 官方《超级岛开发指南》与社区实现（HyperFocusApi）。
     */
    private static Bundle focusBundle(Context ctx, String countdown,
                                      long endAt, long now, boolean timed) {
        try {
            String title = ctx.getString(R.string.notif_title);

            // 大岛 A 区：「保持亮屏」文字
            JSONObject textInfo = new JSONObject()
                    .put("title", title)
                    .put("frontTitle", "")
                    .put("showHighlightColor", true)
                    .put("turnAnim", false)
                    .put("isTitleDigit", false)
                    .put("narrowFont", false);
            JSONObject big = new JSONObject()
                    .put("imageTextInfoLeft", new JSONObject()
                            .put("type", 1)
                            .put("textInfo", textInfo));
            if (timed) {
                // 大岛 B 区：等宽数字倒计时（系统按 timerWhen 自己走秒）
                big.put("sameWidthDigitInfo", new JSONObject()
                        .put("content", "")
                        .put("showHighlightColor", true)
                        .put("turnAnim", false)
                        .put("timerInfo", new JSONObject()
                                .put("timerType", -1)              // -1 = 倒计时开始
                                .put("timerWhen", endAt)            // 比 current 大即为倒计时
                                .put("timerSystemCurrent", now)));
            }

            // 小岛（摘要态）：应用图标
            JSONObject small = new JSONObject()
                    .put("picInfo", new JSONObject()
                            .put("type", 1)
                            .put("pic", "miui.focus.pic_ticker"));

            JSONObject v2 = new JSONObject()
                    .put("protocol", 1)
                    .put("business", "timer")
                    .put("enableFloat", false)          // 每秒刷新，绝不能每次都弹
                    .put("updatable", true)
                    .put("ticker", title + " " + countdown)   // OS2 状态栏必填
                    .put("tickerPic", "miui.focus.pic_ticker")
                    .put("aodTitle", title + " " + countdown) // 息屏显示
                    .put("timeout", 720)                // 焦点通知消失时间（min）
                    .put("baseInfo", new JSONObject()
                            .put("title", title)
                            .put("content", countdown)
                            .put("type", 1))
                    .put("param_island", new JSONObject()
                            .put("islandProperty", 1)
                            .put("islandTimeout", 7200)  // 岛自动消失（s）
                            .put("dismissIsland", false)
                            .put("bigIslandArea", big)
                            .put("smallIslandArea", small));

            JSONObject param = new JSONObject()
                    .put("title", title)
                    .put("content", countdown)
                    .put("isShowNotification", true)
                    .put("param_v2", v2);

            Bundle pics = new Bundle();
            pics.putParcelable("miui.focus.pic_ticker",
                    Icon.createWithResource(ctx, R.drawable.ic_tile));

            Bundle out = new Bundle();
            out.putString("miui.focus.param", param.toString());
            out.putBundle("miui.focus.pics", pics);
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "build focus param failed", t);
            return null;
        }
    }

    // ---------- 诊断（进程内只打一次） ----------

    /**
     * 打一行诊断日志，说明「为什么没上岛 / 没上胶囊」：
     *  - 小米焦点通知协议版本 + 本应用是否有焦点权限（须以应用身份查，shell 查不了）
     *  - 实时更新：用户是否允许提升（canPostPromotedNotifications）
     *                + 通知本身是否合格（hasPromotableCharacteristics）
     */
    static void logDiagnosticsOnce(Context ctx, Notification notification) {
        if (sDiagLogged) {
            return;
        }
        sDiagLogged = true;

        try {
            int protocol = Settings.System.getInt(
                    ctx.getContentResolver(), "notification_focus_protocol", 0);
            boolean canShowFocus = false;
            try {
                Bundle arg = new Bundle();
                arg.putString("package", ctx.getPackageName());
                Bundle r = ctx.getContentResolver().call(
                        Uri.parse("content://miui.statusbar.notification.public"),
                        "canShowFocus", null, arg);
                canShowFocus = r != null && r.getBoolean("canShowFocus", false);
            } catch (Throwable t) {
                Log.i(TAG, "canShowFocus query failed: " + t);
            }
            Log.i(TAG, "island: focusProtocol=" + protocol
                    + " canShowFocus=" + canShowFocus);
        } catch (Throwable t) {
            Log.w(TAG, "focus diagnostics failed", t);
        }

        if (Build.VERSION.SDK_INT >= 36) {
            try {
                NotificationManager nm = (NotificationManager)
                        ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                Method canPost = NotificationManager.class
                        .getMethod("canPostPromotedNotifications");
                Method hasChars = Notification.class
                        .getMethod("hasPromotableCharacteristics");
                Log.i(TAG, "promoted: canPost=" + canPost.invoke(nm)
                        + " hasCharacteristics=" + hasChars.invoke(notification));
            } catch (Throwable t) {
                Log.w(TAG, "promoted diagnostics failed", t);
            }
        }
    }
}
