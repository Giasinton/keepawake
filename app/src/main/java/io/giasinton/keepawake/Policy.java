package io.giasinton.keepawake;

/**
 * 纯逻辑：时长档位、菜单项、到期判定、磁贴副标题文案。
 * 不依赖任何 Android API，可以在主机上直接 javac 编译测试（见 test/sim.sh）。
 */
public final class Policy {

    private Policy() {}

    // ---- 三个时长档位 ----
    public static final int DURATION_5MIN = 0;
    public static final int DURATION_30MIN = 1;
    public static final int DURATION_ALWAYS = 2;

    /** 首次安装的默认档位：5 分钟。 */
    public static final int DEFAULT_DURATION = DURATION_5MIN;

    public static final long MINUTE = 60_000L;

    /** 心跳间隔：进程被系统回收后，靠它把前台服务（悬浮窗+通知）补回来。 */
    public static final long HEARTBEAT_MS = 10 * MINUTE;

    /** 会话状态 ---- */
    /** 没有进行中的会话。 */
    public static final int SESSION_NONE = 0;
    /** 会话有效，悬浮窗应当挂着。 */
    public static final int SESSION_ALIVE = 1;
    /** 定时已到，该收尾（清状态、撤窗口）了。 */
    public static final int SESSION_EXPIRED = 2;

    /** 档位 -> 毫秒时长；常亮返回 0（表示不设超时）。非法值回退默认档。 */
    public static long durationMillis(int durationId) {
        switch (durationId) {
            case DURATION_5MIN:
                return 5 * MINUTE;
            case DURATION_30MIN:
                return 30 * MINUTE;
            case DURATION_ALWAYS:
                return 0L;
            default:
                return durationMillis(DEFAULT_DURATION);
        }
    }

    /** 长按菜单里的显示文案。 */
    public static String durationLabel(int durationId) {
        switch (durationId) {
            case DURATION_5MIN:
                return "5 分钟";
            case DURATION_30MIN:
                return "30 分钟";
            case DURATION_ALWAYS:
                return "常亮";
            default:
                return durationLabel(DEFAULT_DURATION);
        }
    }

    /** 长按菜单的全部选项（按 index 顺序）。 */
    public static String[] durationLabels() {
        return new String[] {
            durationLabel(DURATION_5MIN),
            durationLabel(DURATION_30MIN),
            durationLabel(DURATION_ALWAYS),
        };
    }

    /** 菜单 index -> 档位 id。 */
    public static int idAt(int index) {
        switch (index) {
            case 0:
                return DURATION_5MIN;
            case 1:
                return DURATION_30MIN;
            case 2:
                return DURATION_ALWAYS;
            default:
                return DEFAULT_DURATION;
        }
    }

    /** 档位 id -> 菜单 index（用于默认勾选）。 */
    public static int indexOf(int durationId) {
        switch (durationId) {
            case DURATION_30MIN:
                return 1;
            case DURATION_ALWAYS:
                return 2;
            case DURATION_5MIN:
            default:
                return 0;
        }
    }

    /**
     * 会话结束的绝对时刻（墙钟毫秒）；常亮返回 0（永不到点）。
     */
    public static long endAt(int durationId, long now) {
        long millis = durationMillis(durationId);
        return millis > 0 ? now + millis : 0L;
    }

    /** 依据持久化状态判断会话处于哪种情况。 */
    public static int sessionState(boolean active, long endAt, long now) {
        if (!active) {
            return SESSION_NONE;
        }
        if (endAt > 0 && now >= endAt) {
            return SESSION_EXPIRED;
        }
        return SESSION_ALIVE;
    }

    /**
     * 磁贴副标题：未开启返回 null；常亮显示「常亮」；
     * 定时显示「剩余 N 分钟」（不足 1 分钟按 1 分钟算，向上取整）。
     */
    public static String subtitle(boolean active, long endAt, long now) {
        if (!active) {
            return null;
        }
        if (endAt <= 0) {
            return "常亮";
        }
        long remain = endAt - now;
        long minutes = (remain + MINUTE - 1) / MINUTE;
        if (minutes < 1) {
            minutes = 1;
        }
        return "剩余 " + minutes + " 分钟";
    }

    /** 常驻通知正文：常亮显示「常亮中」；定时显示秒级倒计时「剩余 04:32」。 */
    public static String countdownText(long endAt, long now) {
        if (endAt <= 0) {
            return "常亮中";
        }
        long remain = endAt - now;
        if (remain < 0) {
            remain = 0;
        }
        // 向上取整到秒：还剩 4.6 秒时显示 05 而不是 04
        long totalSec = (remain + 999) / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) {
            return "剩余 " + h + ":" + pad2(m) + ":" + pad2(s);
        }
        return "剩余 " + pad2(m) + ":" + pad2(s);
    }

    /**
     * 状态栏小胶囊（Android 16 实时更新 chip / 各家灵动岛短文案）：
     * 控制在 7 字符以内（超了系统可能只显示图标）。
     * 常亮 -> 「常亮」；定时 -> 「29:58」这种无前缀倒计时。
     */
    public static String chipText(long endAt, long now) {
        if (endAt <= 0) {
            return "常亮";
        }
        long remain = endAt - now;
        if (remain < 0) {
            remain = 0;
        }
        long totalSec = (remain + 999) / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) {
            return h + ":" + pad2(m) + ":" + pad2(s);
        }
        return m + ":" + pad2(s);
    }

    private static String pad2(long v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }
}
