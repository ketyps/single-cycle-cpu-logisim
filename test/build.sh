#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 重新编译 test/ 下的 Java 工具
#
# 仓库不提交 .class（编译产物不进版本库），所以 clone 下来要编一次。
# 正常不用手动跑：verify.sh 发现没有 .class 或 .java 比 .class 新，会自己调它。
# 目标字节码固定为 Java 8，JDK 8 / 11 / 17 / 21 / 25 编出来都能在 JDK 8 上跑。
# ─────────────────────────────────────────────────────────────
set -eu

HERE="$(cd "$(dirname "$0")" && pwd -P)"
ROOT="$(cd "$HERE/.." && pwd -P)"
SIM="$ROOT/simulator/logisim.jar"

command -v javac >/dev/null 2>&1 || {
    printf "未找到 javac。需要 JDK 8+（含 javac），只装 JRE 不够。\n"; exit 2; }
[ -f "$SIM" ] || { printf "找不到 %s\n" "$SIM"; exit 2; }

# 目标字节码固定为 Java 8，这样别人用 JDK 8 也能跑。
# JDK 9+ 用 --release；JDK 8 的 javac 没有这个选项，退回 -source/-target。
JV="$(javac -version 2>&1 | sed 's/^javac //' | cut -d. -f1)"
case "$JV" in
    ''|*[!0-9]*) REL="--release 8" ;;   # 版本号形如 1.8 时取到 1，走下面分支
    1)           REL="-source 8 -target 8" ;;
    *)           if [ "$JV" -ge 9 ]; then REL="--release 8"; else REL="-source 8 -target 8"; fi ;;
esac

printf "编译中（目标 Java 8）...\n"
cd "$HERE"
javac $REL -encoding UTF-8 -nowarn -cp "$SIM" -d "$HERE" ./*.java

printf "完成。当前字节码版本：\n"
for f in ./*.class; do
    case "$f" in *'$'*) continue ;; esac
    printf "  %-12s major %s\n" "$(basename "$f")" \
        "$(javap -v "$f" 2>/dev/null | awk '/major version/{print $3; exit}')"
done
printf "\n跑一遍确认没改坏：bash verify.sh\n"
