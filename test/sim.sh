#!/usr/bin/env bash
#
# 主机端自检（不需要设备、不改任何环境）：
#   1) Policy 纯逻辑单元测试（用环境里的 JDK 直接 javac + java）
#   2) 清单静态检查：磁贴 / 长按菜单入口 / 无桌面图标 / 权限
#
# 用法： ./test/sim.sh
#
set -euo pipefail

PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_DIR="${KEEPAWAKE_ENV:-${COUNTDAY_ENV:-$HOME/.countday-env}}"

if [ -x "$ENV_DIR/jdk/bin/javac" ]; then
  JAVAC="$ENV_DIR/jdk/bin/javac"
  JAVA="$ENV_DIR/jdk/bin/java"
else
  JAVAC="$(command -v javac)" || { echo "找不到 javac（$ENV_DIR/jdk 不在）" >&2; exit 1; }
  JAVA="$(command -v java)"
fi

echo "==> 1/2 Policy 纯逻辑单元测试"
OUT="$PROJ/build/sim"
rm -rf "$OUT"
mkdir -p "$OUT"
"$JAVAC" -encoding UTF-8 -d "$OUT" \
  "$PROJ/app/src/main/java/io/giasinton/keepawake/Policy.java" \
  "$PROJ/test/TestMain.java"
"$JAVA" -cp "$OUT" io.giasinton.keepawake.test.TestMain

echo
echo "==> 2/2 清单静态检查"
MANIFEST="$PROJ/app/src/main/AndroidManifest.xml"
fails=0

check() { # 描述 条件命令...
  local desc="$1"; shift
  if "$@" >/dev/null 2>&1; then
    echo "  ok   - $desc"
  else
    echo "  FAIL - $desc"
    fails=$((fails + 1))
  fi
}
missing() { ! grep -q "$1" "$MANIFEST"; }

check "声明了控制中心磁贴 (QS_TILE)" grep -q 'android.service.quicksettings.action.QS_TILE"' "$MANIFEST"
check "声明了长按菜单入口 (QS_TILE_PREFERENCES)" grep -q 'QS_TILE_PREFERENCES' "$MANIFEST"
check "没有桌面入口 (无 LAUNCHER)" missing 'LAUNCHER'
check "没有 MAIN 启动入口" missing 'intent.action.MAIN'
check "申请了悬浮窗权限 (SYSTEM_ALERT_WINDOW)" grep -q 'android.permission.SYSTEM_ALERT_WINDOW' "$MANIFEST"
check "声明了前台服务权限 (FOREGROUND_SERVICE)" grep -q 'android.permission.FOREGROUND_SERVICE"' "$MANIFEST"
check "声明了 specialUse 类型权限" grep -q 'FOREGROUND_SERVICE_SPECIAL_USE' "$MANIFEST"
check "声明了通知权限 (POST_NOTIFICATIONS)" grep -q 'POST_NOTIFICATIONS' "$MANIFEST"
check "声明了实时更新权限 (POST_PROMOTED_NOTIFICATIONS)" grep -q 'POST_PROMOTED_NOTIFICATIONS' "$MANIFEST"
check "前台服务类型为 specialUse" grep -q 'foregroundServiceType="specialUse"' "$MANIFEST"
check "声明了开机恢复 (RECEIVE_BOOT_COMPLETED)" grep -q 'RECEIVE_BOOT_COMPLETED' "$MANIFEST"
check "没有申请 WRITE_SETTINGS" missing 'WRITE_SETTINGS'
check "没有申请 WAKE_LOCK（旧方案已废弃）" missing 'WAKE_LOCK'
check "长按菜单限定了 BIND_QUICK_SETTINGS_TILE" grep -q 'BIND_QUICK_SETTINGS_TILE' "$MANIFEST"

if [ "$fails" -gt 0 ]; then
  echo
  echo "共 $fails 项失败"
  exit 1
fi

echo
echo "全部通过"
