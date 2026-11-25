package io.giasinton.keepawake.test;

import io.giasinton.keepawake.Policy;

/**
 * 主机端单元测试：只测 Policy 纯逻辑，不需要设备、不需要 Android SDK。
 * 由 test/sim.sh 调起。
 */
public final class TestMain {

    private static int failures = 0;

    private static void check(boolean cond, String msg) {
        if (cond) {
            System.out.println("  ok   - " + msg);
        } else {
            failures++;
            System.out.println("  FAIL - " + msg);
        }
    }

    public static void main(String[] args) {
        long now = 1_700_000_000_000L;

        System.out.println("[时长档位]");
        check(Policy.durationMillis(Policy.DURATION_5MIN) == 5 * 60_000L, "5 分钟 = 300000 ms");
        check(Policy.durationMillis(Policy.DURATION_30MIN) == 30 * 60_000L, "30 分钟 = 1800000 ms");
        check(Policy.durationMillis(Policy.DURATION_ALWAYS) == 0, "常亮 = 0（不设超时）");
        check(Policy.durationMillis(999) == 5 * 60_000L, "非法档位回退到默认 5 分钟");
        check(Policy.DEFAULT_DURATION == Policy.DURATION_5MIN, "默认档位是 5 分钟");

        System.out.println("[长按菜单]");
        String[] labels = Policy.durationLabels();
        check(labels.length == 3
                && "5 分钟".equals(labels[0])
                && "30 分钟".equals(labels[1])
                && "常亮".equals(labels[2]),
              "三个选项：5 分钟 / 30 分钟 / 常亮");
        check(Policy.idAt(0) == Policy.DURATION_5MIN
                && Policy.idAt(1) == Policy.DURATION_30MIN
                && Policy.idAt(2) == Policy.DURATION_ALWAYS,
              "菜单 index -> 档位 id");
        check(Policy.indexOf(Policy.DURATION_5MIN) == 0
                && Policy.indexOf(Policy.DURATION_30MIN) == 1
                && Policy.indexOf(Policy.DURATION_ALWAYS) == 2,
              "档位 id -> 菜单 index（默认勾选用）");
        check(Policy.indexOf(Policy.idAt(1)) == 1, "index/id 往返一致");

        System.out.println("[结束时刻]");
        check(Policy.endAt(Policy.DURATION_5MIN, now) == now + 300_000L, "5 分钟后到点");
        check(Policy.endAt(Policy.DURATION_30MIN, now) == now + 1_800_000L, "30 分钟后到点");
        check(Policy.endAt(Policy.DURATION_ALWAYS, now) == 0, "常亮没有结束时刻");

        System.out.println("[会话判定]");
        check(Policy.sessionState(false, 0, now) == Policy.SESSION_NONE, "未开启 = NONE");
        check(Policy.sessionState(true, 0, now) == Policy.SESSION_ALIVE, "常亮 = ALIVE");
        check(Policy.sessionState(true, now + 1, now) == Policy.SESSION_ALIVE, "未到点 = ALIVE");
        check(Policy.sessionState(true, now, now) == Policy.SESSION_EXPIRED, "恰好到点 = EXPIRED");
        check(Policy.sessionState(true, now - 1, now) == Policy.SESSION_EXPIRED, "已过点 = EXPIRED");

        System.out.println("[磁贴副标题]");
        check(Policy.subtitle(false, 0, now) == null, "未开启 = 无副标题");
        check("常亮".equals(Policy.subtitle(true, 0, now)), "常亮 -> 「常亮」");
        check("剩余 5 分钟".equals(Policy.subtitle(true, now + 300_000L, now)), "剩 5 分钟");
        check("剩余 30 分钟".equals(Policy.subtitle(true, now + 1_800_000L, now)), "剩 30 分钟");
        check("剩余 1 分钟".equals(Policy.subtitle(true, now + 1_000L, now)), "不足 1 分钟向上取 1");
        check("剩余 5 分钟".equals(Policy.subtitle(true, now + 5 * 60_000L - 1_000L, now)),
              "剩 4 分 59 秒向上取 5 分钟");
        check("剩余 6 分钟".equals(Policy.subtitle(true, now + 5 * 60_000L + 1_000L, now)),
              "剩 5 分 1 秒向上取 6 分钟");
        check("剩余 11 分钟".equals(Policy.subtitle(true, now + Policy.HEARTBEAT_MS + 60_000L, now)),
              "心跳间隔（10 分钟）+ 1 分钟 = 剩余 11 分钟");

        System.out.println("[通知倒计时]");
        check("常亮中".equals(Policy.countdownText(0, now)), "常亮 -> 「常亮中」");
        check("剩余 04:32".equals(Policy.countdownText(now + 272_000L, now)), "剩 4 分 32 秒");
        check("剩余 00:01".equals(Policy.countdownText(now + 1_000L, now)), "剩 1 秒");
        check("剩余 00:01".equals(Policy.countdownText(now + 999L, now)),
              "剩 0.999 秒向上取整到 01");
        check("剩余 1:00:00".equals(Policy.countdownText(now + 3_600_000L, now)), "整 1 小时带时位");
        check("剩余 1:05:03".equals(Policy.countdownText(now + 3_903_000L, now)),
              "1 小时 5 分 3 秒 -> 1:05:03");
        check("剩余 00:00".equals(Policy.countdownText(now, now)), "恰好到点 -> 00:00");
        check("剩余 00:00".equals(Policy.countdownText(now - 1L, now)), "已过点钳到 00:00");

        System.out.println("[状态栏短文案 chipText]");
        check("常亮".equals(Policy.chipText(0, now)), "常亮 -> 「常亮」");
        check("4:32".equals(Policy.chipText(now + 272_000L, now)), "剩 4 分 32 秒 -> 「4:32」");
        check("5:00".equals(Policy.chipText(now + 300_000L, now)), "整 5 分钟 -> 「5:00」");
        check("0:01".equals(Policy.chipText(now + 1_000L, now)), "剩 1 秒 -> 「0:01」");
        check("1:00:00".equals(Policy.chipText(now + 3_600_000L, now)), "整 1 小时带时位");
        check("0:00".equals(Policy.chipText(now, now)), "恰好到点 -> 「0:00」");
        check("0:00".equals(Policy.chipText(now - 1L, now)), "已过点钳到 「0:00」");
        check(Policy.chipText(now + 1_799_000L, now).length() <= 7,
              "30 分钟档文案不超过 7 字符");
        check(Policy.chipText(0, now).length() <= 7, "常亮文案不超过 7 字符");

        if (failures > 0) {
            System.out.println("\n共 " + failures + " 项失败");
            System.exit(1);
        }
        System.out.println("\n全部通过");
    }
}
