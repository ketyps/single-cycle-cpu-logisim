#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 单周期 CPU 电路自测（36 项）
#
#   bash verify.sh                    测仓库自带的成品电路
#   bash verify.sh /路径/改版.circ     测任意文件
#
# 所有路径都以本脚本位置为基准推算，整个仓库拷到任何机器都能直接跑。
# 依赖：JDK 8 或更高（java + javac）。首次运行会自动编译测试工具。
# 另需三个第三方文件（logisim.jar / cs3410.jar / mips-probe.jar），见 README。
# ─────────────────────────────────────────────────────────────
set -u

HERE="$(cd "$(dirname "$0")" && pwd -P)"      # test/
ROOT="$(cd "$HERE/.." && pwd -P)"             # 仓库根
CIRC_DIR="$ROOT/circuits"
SIM="$ROOT/simulator/logisim.jar"

command -v java >/dev/null 2>&1 || {
    printf "\n  未找到 java。请先安装 JDK 8 或更高版本。\n\n"; exit 2; }

# 这三个文件是课程/第三方提供的，受版权限制不随本仓库分发，需要自己放进来。
# 缺任何一个都跑不起来，所以先统一检查再报错，不要等 Java 抛 NotFound。
need=""
[ -f "$SIM" ]                  || need="$need simulator/logisim.jar"
[ -f "$CIRC_DIR/cs3410.jar" ]  || need="$need circuits/cs3410.jar"
[ -f "$CIRC_DIR/mips-probe.jar" ] || need="$need circuits/mips-probe.jar"
if [ -n "$need" ]; then
    printf "\n  缺少必要的第三方文件：\n"
    for f in $need; do printf "    %s\n" "$f"; done
    printf "\n  这些文件版权归原作者，不随本仓库分发。请从课程资料获取后放到上述路径，\n"
    printf "  详见 README 的「准备依赖」一节。\n\n"
    exit 2
fi

# 仓库不提交 .class。首次运行、或改过 .java 之后，这里自动编译一次。
need_build=""
for n in Vfy Fp Step Run; do
    if [ ! -f "$HERE/$n.class" ] || [ "$HERE/$n.java" -nt "$HERE/$n.class" ]; then
        need_build="$n"
    fi
done
if [ -n "$need_build" ]; then
    printf "\n  测试工具还没编译，先跑一次 build.sh ...\n"
    bash "$HERE/build.sh" || exit 2
fi

# Git Bash / MSYS 下 Java 不认 POSIX 路径，classpath 分隔符也是 ';'
SEP=":"
winpath() { printf '%s' "$1"; }
case "$(uname -s 2>/dev/null)" in
    MINGW*|MSYS*|CYGWIN*)
        SEP=";"
        if command -v cygpath >/dev/null 2>&1; then
            winpath() { cygpath -w "$1"; }
        fi
        ;;
esac

# Logisim 解析 .circ 里的 jar# / file# 引用，是以 .circ 文件所在目录为基准的，
# 所以被依赖的 jar 和子电路必须和主电路放在同一个目录（这里就是 circuits/）。
CIRC="${1:-$CIRC_DIR/cpu-completed.circ}"
if [ ! -f "$CIRC" ] && [ -f "$CIRC_DIR/$CIRC" ]; then
    CIRC="$CIRC_DIR/$CIRC"
fi
if [ ! -f "$CIRC" ]; then
    printf "\n  找不到电路文件: %s\n  用法: bash verify.sh [文件.circ]\n\n" "$CIRC"; exit 2
fi
CIRC_ABS="$(cd "$(dirname "$CIRC")" && pwd -P)/$(basename "$CIRC")"

# 外部文件要先搬进 circuits/，否则它找不到 cs3410.jar 与两个子电路库
WORK="$CIRC_ABS"
CLEAN=""
if [ "$(dirname "$CIRC_ABS")" != "$(cd "$CIRC_DIR" && pwd -P)" ]; then
    WORK="$CIRC_DIR/_verify_tmp.circ"
    cp "$CIRC_ABS" "$WORK"
    CLEAN="$WORK"
fi

# 本机偏好目录：不指定的话 Logisim 会往 $HOME 写，也可能因无权限刷警告
PREF="$HERE/.prefs"
mkdir -p "$PREF" 2>/dev/null
CP="$(winpath "$SIM")$SEP$(winpath "$CIRC_DIR/cs3410.jar")$SEP$(winpath "$CIRC_DIR/mips-probe.jar")$SEP$(winpath "$HERE")"
jrun() { java -Djava.util.prefs.userRoot="$(winpath "$PREF")" -cp "$CP" "$@"; }

PASS=0
FAIL=0
FAILED_NAMES=""

# 按显示宽度补空格（中文占 2 列；bash 的 ${#s} 与 %-Ns 都按字符/字节算，都会错位）
pad() {
    local s="$1" want="$2" b c w
    b=$(printf '%s' "$s" | wc -c)      # 字节数
    c=$(printf '%s' "$s" | wc -m)      # 字符数
    w=$((c + (b - c) / 2))             # 每个非 ASCII 字符多占 1 列
    printf '%s%*s' "$s" $((want > w ? want - w : 1)) ''
}

# check <说明> <子电路> <输入> <输出端口> <期望值>
check() {
    local desc="$1" sub="$2" args="$3" port="$4" want="$5"
    local got
    got=$(jrun Vfy "$WORK" "$sub" $args 2>/dev/null | tail -1 \
          | grep -o "$port = [0-9a-f]*" | awk '{print $3}')
    if [ "$got" = "$want" ]; then
        printf "  \033[32m✓\033[0m %s \033[36m%s\033[0m = %s\n" "$(pad "$desc" 34)" "$port" "$got"
        PASS=$((PASS + 1))
    else
        printf "  \033[31m✗\033[0m %s \033[36m%s\033[0m = \033[31m%s\033[0m (应为 %s)\n" \
               "$(pad "$desc" 34)" "$port" "${got:-空}" "$want"
        FAIL=$((FAIL + 1))
        FAILED_NAMES="$FAILED_NAMES $desc;"
    fi
}

printf "\n\033[1m单周期 CPU 电路自测\033[0m\n"
printf "  文件: %s\n" "$(basename "$CIRC_ABS")"
printf "  大小: %s\n" "$(du -h "$CIRC_ABS" | cut -f1)"

echo
echo "── 一、NPC 全部编码组合 ──────────────────────────"
table() {
    local op=$1 z=$2 want=$3 bin=$4
    check "NPCOp=$bin Zero=$z" NPC \
        "PC=10000000 NPCOp=$op Zero=$z Imm16=1 Imm26=1 RA=deadbeef" NPC "$want"
}
# 000 顺序 | 001 beq | 010 jal | 011 jr | 100 bne
table 0 0 10000004 000
table 0 1 10000004 000
table 1 0 10000004 001
table 1 1 10000008 001
table 2 0 10000008 010
table 2 1 10000008 010
table 3 0 deadbeef 011
table 3 1 deadbeef 011
table 4 0 10000008 100
table 4 1 10000004 100
table 5 0 10000008 101
table 5 1 10000004 101
table 6 0 10000008 110
table 6 1 10000008 110
table 7 0 deadbeef 111
table 7 1 deadbeef 111

echo "── 二、NPC 边界值 ────────────────────────────────"
check "Imm16=ffff 负偏移回退" NPC \
    "PC=10000000 NPCOp=1 Zero=1 Imm16=ffff Imm26=1 RA=0" NPC 10000000
check "Imm26=3ffffff 顶端" NPC \
    "PC=10000000 NPCOp=2 Zero=0 Imm16=1 Imm26=3ffffff RA=0" NPC 20000000
check "PC=fffffffc 地址回绕" NPC \
    "PC=fffffffc NPCOp=0 Zero=0 Imm16=1 Imm26=1 RA=0" NPC 00000000

echo "── 三、EXT 扩展器 ────────────────────────────────"
check "in=0001 ExtOp=0"    EXT "in=0001 ExtOp=0" out 00000001
check "in=0001 ExtOp=1"    EXT "in=0001 ExtOp=1" out 00000001
check "in=7fff ExtOp=0"    EXT "in=7fff ExtOp=0" out 00007fff
check "in=7fff ExtOp=1"    EXT "in=7fff ExtOp=1" out 00007fff
check "in=8000 ExtOp=0"    EXT "in=8000 ExtOp=0" out 00008000
check "in=8000 ExtOp=1 符号位起点" EXT "in=8000 ExtOp=1" out ffff8000
check "in=8001 ExtOp=0"    EXT "in=8001 ExtOp=0" out 00008001
check "in=8001 ExtOp=1"    EXT "in=8001 ExtOp=1" out ffff8001
check "in=ffff ExtOp=1"    EXT "in=ffff ExtOp=1" out ffffffff
check "in=0000 ExtOp=1"    EXT "in=0 ExtOp=1"    out 00000000

echo "── 四、顶层数据通路（第一条 addi \$s0,\$zero,-1）──"
TOP=$(jrun Vfy "$WORK" "单周期MIPS CPU" 2>/dev/null | tail -1)
for pair in "IR=2010ffff" "RDin=ffffffff" "RegWrite=1"; do
    if echo "$TOP" | grep -q "$pair"; then
        printf "  \033[32m✓\033[0m %s\n" "$pair"
        PASS=$((PASS + 1))
    else
        printf "  \033[31m✗\033[0m 缺少 %s\n       实际: %s\n" "$pair" "$TOP"
        FAIL=$((FAIL + 1))
        FAILED_NAMES="$FAILED_NAMES 顶层$pair;"
    fi
done

echo "── 五、整机推进时钟，跑真实指令 ──────────────────"
STEP=$(jrun Step "$WORK" "单周期MIPS CPU" 10 2>/dev/null | tail -12)
PCS=$(echo "$STEP" | awk 'NR>1 {print $3}' | uniq | tr '\n' ' ')
if [ "$PCS" = "00000000 00000004 00000008 0000000c 00000010 00000014 " ]; then
    printf "  \033[32m✓\033[0m PC 按 +4 顺序推进\n"; PASS=$((PASS + 1))
else
    printf "  \033[31m✗\033[0m PC 推进异常: %s\n" "${PCS:-<空>}"; FAIL=$((FAIL + 1))
    FAILED_NAMES="$FAILED_NAMES PC不推进;"
fi
for probe in "ae300200:取到 sw 并置 MemWrite" "22100001:取到 addi \$s0,\$s0,1"; do
    if echo "$STEP" | grep -q "${probe%%:*}"; then
        printf "  \033[32m✓\033[0m %s\n" "${probe#*:}"; PASS=$((PASS + 1))
    else
        printf "  \033[31m✗\033[0m %s\n" "${probe#*:}"; FAIL=$((FAIL + 1))
        FAILED_NAMES="$FAILED_NAMES ${probe#*:};"
    fi
done

echo "── 六、功能指纹对比（抓「静悄悄改坏」）───────────"
BASE="$HERE/baseline.txt"
TMPFP="$HERE/.fp_now.txt"
if jrun Fp "$WORK" "$TMPFP" >/dev/null 2>&1 && [ -s "$TMPFP" ]; then
    if [ ! -f "$BASE" ]; then
        cp "$TMPFP" "$BASE"
        printf "  \033[33m·\033[0m 首次运行，已把当前版本存为基准\n"
    elif diff -q "$BASE" "$TMPFP" >/dev/null; then
        printf "  \033[32m✓\033[0m 全部子电路输入输出关系与基准完全一致\n"
        PASS=$((PASS + 1))
    else
        printf "  \033[31m✗\033[0m 功能与基准不同，差异如下（前 6 行）:\n"
        diff "$BASE" "$TMPFP" | grep '^[<>]' | head -6 | sed 's/^/       /'
        AFFECTED=$(diff "$BASE" "$TMPFP" | grep '^[<>]' \
                   | sed 's/^[<>] //' | cut -d'|' -f1 | sort -u | tr '\n' ' ')
        printf "       涉及子电路: \033[33m%s\033[0m\n" "$AFFECTED"
        FAIL=$((FAIL + 1))
        FAILED_NAMES="$FAILED_NAMES 功能指纹变化($AFFECTED);"
    fi
else
    printf "  \033[31m✗\033[0m 指纹生成失败 —— 电路可能已无法正常加载\n"
    FAIL=$((FAIL + 1))
    FAILED_NAMES="$FAILED_NAMES 电路加载失败;"
fi
rm -f "$TMPFP"
[ -n "$CLEAN" ] && rm -f "$CLEAN"

echo
echo "═════════════════════════════════════════════════"
if [ "$FAIL" -eq 0 ]; then
    printf "  \033[42;30m  没改坏  \033[0m  %d 项全部通过\n" "$PASS"
    cp "$CIRC_ABS" "$HERE/.last-good.circ" 2>/dev/null \
        && printf "  已存为最后确认版本: test/.last-good.circ\n"
    echo
    exit 0
else
    printf "  \033[41;37m  有 %d 项挂了  \033[0m  %d 项通过\n\n" "$FAIL" "$PASS"
    printf "  挂了 %d 项: %s\n\n" "$FAIL" \
        "$(echo "$FAILED_NAMES" | tr ';' '\n' | sed '/^$/d;s/^ */· /' \
           | head -3 | tr '\n' ' ')…"
    echo "  常见原因:"
    echo "    · 拖断一根线          → 该路输出变 x（Logisim 里显示红线/蓝线）"
    echo "    · 子电路 Pin 被拖到别处 → 顶层实例端口错位，第四节整机测试挂"
    echo "    · Mux 的 In0/In1 接反、扩展器上下颠倒 → 逻辑整体翻转"
    echo "    · 删掉某个 Tunnel 的源端 → 只有接收端的标签恒为 x"
    echo
    if [ -f "$HERE/.last-good.circ" ]; then
        printf "  退回上次通过的版本:\n"
        printf "    cp \"%s\" \"%s\"\n\n" "$HERE/.last-good.circ" "$CIRC_ABS"
    fi
    exit 1
fi
