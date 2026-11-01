#!/usr/bin/env bash
# 停止模拟服务、恢复 /etc/hosts。后端需按正常方式（带真实 Key 环境变量）重新启动。
HERE="$(cd "$(dirname "$0")" && pwd)"
[ -f "$HERE/mock.pid" ] && kill "$(cat "$HERE/mock.pid")" 2>/dev/null; rm -f "$HERE/mock.pid"
sed -i '/# libramind-mock$/d' /etc/hosts
echo "已移除 aihubmix.com 的本地映射。请用 scripts/stop.sh 和 scripts/start.sh 重启后端。"
