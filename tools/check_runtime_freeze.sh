#!/usr/bin/env bash
# runtime/ 契约层冻结(V2 docs/DEVICE_SELF_DESCRIPTION_DESIGN.md 第 7 节):不再往里加文件,
# 改到哪一块顺手收拢哪一块,不单独开一次大拆。
#
# 文件数只许减不许增:多了 CI 红;少了也红,提醒把下面的基线一起改小(同一个提交),
# 这样收掉的位置不会被新文件悄悄补回去。
set -euo pipefail

BASELINE=227

DIR="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/java/com/ufo/galaxy/runtime"
n=$(find "$DIR" -type f | wc -l | tr -d ' ')

if [ "$n" -gt "$BASELINE" ]; then
  echo "runtime/ 有 $n 个文件,超过冻结基线 $BASELINE:契约层不再加文件,新代码放进它该在的包里。" >&2
  exit 1
fi
if [ "$n" -lt "$BASELINE" ]; then
  echo "runtime/ 少到 $n 个文件了:把 tools/check_runtime_freeze.sh 里的 BASELINE 改成 $n,一起提交。" >&2
  exit 1
fi
echo "runtime/ 契约层 $n 个文件,与冻结基线一致。"
