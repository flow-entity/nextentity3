#!/usr/bin/env bash
#
# 门禁 wrapper：把「本机能跑」变成「这次到底跑了什么」的可核对凭证。
#
# 为什么需要它：项目里所有机制都靠「读 target/ 下的产物」向人汇报，而产物会说谎——
#   · `-DskipITs` 跑完后 `target/failsafe-reports/` 里仍留着上一轮的通过 XML，读它就会
#     报出「530 passed」，而容器通道一条都没跑；`-DskipITs` 本身不产生任何标记；
#   · `.m2` 的 `maven-metadata-local.xml` mtime 是安装时刻，里面那份 jar 可能早两小时。
# 本脚本的唯一职责：让「未执行」和「跑绿」长得不一样。
#
# 用法
#   tools/gate.sh fast              保存/提交前：架构规则 + 快测试（无容器）
#   tools/gate.sh pr                push 前自查：H2 全量 + 遗留 727 条（无容器，-DskipITs）
#   tools/gate.sh ci                CI 权威档：容器通道必须真跑 + 遗留 727 条
#   tools/gate.sh night             夜间：全量含容器 + 遗留 MySQL + PIT×4
#   tools/gate.sh release           发版前：clean verify + 全矩阵 + PIT + api 兼容
#
# 选项
#   --allow-dirty      工作树脏也放行（默认 fast/pr 放行但记入凭证，night/release 拒绝）
#   --require-clean    任何档都要求工作树干净
#   --no-receipt       不写凭证；此时凭证状态记 `unverified`，绝不记 green
#   --keep             保留本次日志（默认保留，删日志靠 tmp/ 清理）
#
# 退出码
#   0  声明的通道全部真跑了且零失败
#   1  有通道缺失 / 不新鲜 / 有失败 / 工作树不合规
#   2  用法或环境错误
#
# 诚实边界：本地凭证可伪造，**CI 重算才是权威**。它的价值是（i）不让自己先被骗，
# （ii）PR 说明里能粘贴一段可核对的证据。
set -uo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$ROOT" || exit 2

# ---------------------------------------------------------------------------
# 环境
# ---------------------------------------------------------------------------

PY=${GATE_PYTHON:-}
if [ -z "$PY" ]; then
    for candidate in python python3 py; do
        if command -v "$candidate" >/dev/null 2>&1; then PY=$candidate; break; fi
    done
fi
if [ -z "$PY" ]; then
    echo "✗ 找不到 python（凭证解析需要它）" >&2
    exit 2
fi

RECEIPT=tools/gate-receipt.py
TIER=""
ALLOW_DIRTY=""
REQUIRE_CLEAN=""
NO_RECEIPT=""

FAILED=()
STEPN=0

usage() {
    # 从 "# 用法" 打到注释块结束，别写死行号（改注释就会错位）。
    awk '/^# 用法/{f=1} f && !/^#/{exit} f{sub(/^# ?/, ""); print}' "${BASH_SOURCE[0]}"
    exit 2
}

while [ $# -gt 0 ]; do
    case "$1" in
        fast|pr|night|release|ci) TIER=$1; shift ;;
        --allow-dirty)   ALLOW_DIRTY=1; shift ;;
        --require-clean) REQUIRE_CLEAN=1; shift ;;
        --no-receipt)    NO_RECEIPT=1; shift ;;
        -h|--help)       usage ;;
        *) echo "✗ 未知参数：$1" >&2; usage ;;
    esac
done
[ -n "$TIER" ] || usage

# night / release 默认要求干净树：这两档的结论要能对上一个 commit。
case "$TIER" in
    night|release) [ -n "$ALLOW_DIRTY" ] || REQUIRE_CLEAN=1 ;;
esac

# ---------------------------------------------------------------------------
# ② 独占工作树
# ---------------------------------------------------------------------------

# 并行 mvn 共用同一个 target/，会造出「jar 里缺刚写好的类」这类假红，也会让下一次构建
# 静默复用被写坏的产物（-am 与 -Dmaven.repo.local 都挡不住，冲突在 target/ 不在仓库）。
# Windows 的 git-bash 没有 flock，所以用 mkdir 的原子性。
LOCK=.mvn/lock.d
ZOMBIE_SECONDS=1800

release_lock() {
    rm -rf "$LOCK" 2>/dev/null
}

acquire_lock() {
    mkdir -p .mvn || exit 2
    if mkdir "$LOCK" 2>/dev/null; then
        printf 'pid=%s\nstarted=%s\ntier=%s\n' "$$" "$(date +%s)" "$TIER" > "$LOCK/owner"
        trap release_lock EXIT INT TERM
        return 0
    fi
    local owner_started age
    owner_started=$(sed -n 's/^started=//p' "$LOCK/owner" 2>/dev/null)
    age=$(( $(date +%s) - ${owner_started:-0} ))
    if [ "${owner_started:-}" != "" ] && [ "$age" -gt "$ZOMBIE_SECONDS" ]; then
        echo "⚠ 清理僵尸锁（${age}s 未释放，超过 ${ZOMBIE_SECONDS}s）" >&2
        rm -rf "$LOCK"
        mkdir "$LOCK" 2>/dev/null || { echo "✗ 抢锁失败" >&2; exit 2; }
        printf 'pid=%s\nstarted=%s\ntier=%s\n' "$$" "$(date +%s)" "$TIER" > "$LOCK/owner"
        trap release_lock EXIT INT TERM
        return 0
    fi
    echo "✗ 另一个门禁正在跑（$LOCK，已 ${age}s）。" >&2
    echo "  并行 mvn 会共用 target/ 造出假红假绿。等它结束，或确认进程已死后删除该目录。" >&2
    exit 2
}

# ---------------------------------------------------------------------------
# ① 工作树干净
# ---------------------------------------------------------------------------

check_tree() {
    local dirty
    dirty=$(git status --porcelain --untracked-files=all -- . ':(exclude)tmp' 2>/dev/null)
    if [ -z "$dirty" ]; then
        TREE_STATE="clean"
        return 0
    fi
    TREE_STATE="dirty"
    local count
    count=$(printf '%s\n' "$dirty" | wc -l | tr -d ' ')
    if [ -n "$REQUIRE_CLEAN" ]; then
        echo "✗ 工作树有 $count 处改动，本档要求干净树（结论要对得上一个 commit）：" >&2
        printf '%s\n' "$dirty" | head -20 | sed 's/^/    /' >&2
        echo "  先提交，或加 --allow-dirty 明确接受「这份结论对不上 commit」。" >&2
        exit 1
    fi
    echo "⚠ 工作树有 $count 处改动：本次凭证会记 dirty，结论对不上 commit hash" >&2
}

# ---------------------------------------------------------------------------
# 步骤执行
# ---------------------------------------------------------------------------

step() {
    local label="$1" require="$2"; shift 2
    STEPN=$((STEPN + 1))
    local -a cmd=("$@")
    local joined=" ${cmd[*]} "

    # ③ 拒绝缺 -am 的调用：它会从 ~/.m2 解析依赖模块，陈旧快照会让结论变成「旧产物能跑」。
    if [[ "$joined" == *" -pl "* && "$joined" != *" -am "* ]]; then
        if [[ "$joined" != *"pitest:mutationCoverage"* ]]; then
            echo "✗ [$label] 带 -pl 却没有 -am：会从 ~/.m2 解析依赖模块，陈旧产物会让结论失真。" >&2
            FAILED+=("$label(no -am)")
            return 1
        fi
        echo "  · [$label] PIT 例外路径：依赖从 ~/.m2 解析，已由 install 步骤刷新" >&2
    fi

    local since log rc vrc
    since=$("$PY" -c 'import time; print(repr(time.time()))')
    log="$RUNDIR/$label.log"
    printf '\n▶ [%s] 必需通道：%s\n  $ %s\n' "$label" "${require:-（无，纯准备）}" "${cmd[*]}" >&2

    rc=0
    "${cmd[@]}" >"$log" 2>&1 || rc=$?

    local -a warns=()
    if grep -q 'Nothing to compile - all classes are up to date' "$log" 2>/dev/null; then
        warns+=(--warn "$label：日志出现 Nothing to compile，本次复用了前序产物")
    fi
    if grep -q 'COMPILATION ERROR' "$log" 2>/dev/null; then
        warns+=(--warn "$label：日志出现 COMPILATION ERROR")
    fi

    vrc=0
    "$PY" "$RECEIPT" snapshot --root . --since "$since" --label "$label" \
        --require "$require" --out "$RUNDIR/step-$(printf '%02d' "$STEPN")-$label.json" \
        ${warns[@]+"${warns[@]}"} || vrc=$?

    if [ "$rc" -ne 0 ]; then
        echo "  ✗ [$label] mvn 退出码 $rc；日志尾部：" >&2
        tail -n 25 "$log" | sed 's/^/      /' >&2
        FAILED+=("$label(exit=$rc)")
    fi
    if [ "$vrc" -ne 0 ]; then
        FAILED+=("$label(receipt)")
    fi
    return 0
}

# ---------------------------------------------------------------------------
# 各档步骤
# ---------------------------------------------------------------------------

tier_fast() {
    step fast \
        "surefire:nextentity-api,surefire:nextentity-core,surefire:nextentity-arch" \
        ./mvnw -B -pl nextentity-core,nextentity-arch -am test
}

tier_ci() {
    # CI 权威通道。与本地 pr 档的唯一区别：容器通道**必须真跑**，Docker 缺失即红。
    # 这一档存在的理由：本地 pr 档用 -DskipITs 换取 30s，而 -DskipITs 不留任何标记——
    # 只有把「权威结论只在 ci 档产生」写死，才不会被本地那份省事的绿骗过去。
    step ci-fast "surefire:nextentity-arch" \
        ./mvnw -B -pl nextentity-arch -am test
    step ci-container "surefire:nextentity-tests-jpa,failsafe:nextentity-tests-jpa" \
        ./mvnw -B -pl nextentity-tests-jpa -am verify -Dnextentity.requireContainer=true
    step ci-legacy "surefire:nextentity-tests-legacy" \
        ./mvnw -B -pl nextentity-tests-legacy -am test
}

tier_pr() {    # 容器通道在本档明确不跑（-DskipITs）。凭证会把 failsafe 记为「未声明」而不是「skip=0」。
    step pr-h2 "surefire:nextentity-tests-jpa" \
        ./mvnw -B -pl nextentity-tests-jpa -am verify -DskipITs
    # 727 条遗留回归此前完全不在 CI 里：CI 两条命令的 -am 反应堆都不含该模块。
    step pr-legacy "surefire:nextentity-tests-legacy" \
        ./mvnw -B -pl nextentity-tests-legacy -am test
}

tier_night() {
    # PIT 不带 -am，依赖从 ~/.m2 解析；先整仓 install 把快照刷到当前 HEAD。
    step night-install "" ./mvnw -B -q install -DskipTests
    step night-container "surefire:nextentity-tests-jpa,failsafe:nextentity-tests-jpa" \
        ./mvnw -B -pl nextentity-tests-jpa verify -Dnextentity.requireContainer=true
    step night-legacy "surefire:nextentity-tests-legacy" \
        ./mvnw -B -pl nextentity-tests-legacy -am test -Ddbc=h2,mysql
    local module
    for module in api core jpa spring; do
        step "night-pit-$module" "pit:nextentity-$module" \
            ./mvnw -B -pl "nextentity-$module" test-compile pitest:mutationCoverage
    done
}

tier_release() {
    step release-verify \
        "surefire:nextentity-api,surefire:nextentity-core,surefire:nextentity-arch,surefire:nextentity-jpa,surefire:nextentity-spring,surefire:nextentity-tests-jpa,failsafe:nextentity-tests-jpa" \
        ./mvnw -B clean verify -Dnextentity.requireContainer=true
    step release-legacy "surefire:nextentity-tests-legacy" \
        ./mvnw -B -pl nextentity-tests-legacy -am test -Ddbc=h2,mysql
    step release-install "" ./mvnw -B -q install -DskipTests
    local module
    for module in api core jpa spring; do
        step "release-pit-$module" "pit:nextentity-$module" \
            ./mvnw -B -pl "nextentity-$module" test-compile pitest:mutationCoverage
    done
    if [ -n "${OLD_VERSION:-}" ]; then
        step release-compat "" \
            ./mvnw -B -Papi-compat -Djapicmp.oldVersion="$OLD_VERSION" -pl nextentity-api verify
    else
        echo "⚠ 未设 OLD_VERSION，跳过 api 兼容检查（发版前必须设成上一发布版）" >&2
        FAILED+=("release-compat(未执行：OLD_VERSION 未设)")
    fi
}

# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

acquire_lock

HEAD_SHA=$(git rev-parse HEAD)
SHORT_SHA=$(git rev-parse --short HEAD)
STAMP=$(date +%Y%m%d-%H%M%S)
RUNDIR="tmp/gate/$SHORT_SHA-$STAMP"
mkdir -p "$RUNDIR" || exit 2
TREE_STATE="clean"

check_tree

printf '\n════ 门禁 %s @ %s（%s）════\n' "$TIER" "$SHORT_SHA" "$TREE_STATE"
printf '  日志与凭证：%s\n' "$RUNDIR"

case "$TIER" in
    fast)    tier_fast ;;
    ci)      tier_ci ;;
    pr)      tier_pr ;;
    night)   tier_night ;;
    release) tier_release ;;
esac

# ---------------------------------------------------------------------------
# ⑤ 汇总
# ---------------------------------------------------------------------------

VERDICT_RC=0
if [ -n "$NO_RECEIPT" ]; then
    echo ""
    echo "════ 凭证已按要求关闭：status=unverified ════"
    echo "  未写 green。任何「绿」的结论都缺少机器载体，PR 里不许引用本轮。"
    exit 1
fi

"$PY" "$RECEIPT" merge --root . --dir "$RUNDIR" --head "$HEAD_SHA" --tier "$TIER" \
    --out "$RUNDIR/receipt.json" || VERDICT_RC=1

if [ "${#FAILED[@]}" -gt 0 ]; then
    echo ""
    echo "════ 失败步骤 ════"
    printf '  ✗ %s\n' "${FAILED[@]}"
    VERDICT_RC=1
fi

echo ""
if [ "$VERDICT_RC" -eq 0 ]; then
    echo "════ 判定 GREEN @ $SHORT_SHA ════"
    echo "  报绿三件套：commit=$SHORT_SHA · git status=$TREE_STATE · 凭证=$RUNDIR/receipt.json"
else
    echo "════ 判定 RED @ $SHORT_SHA ════"
    echo "  凭证=$RUNDIR/receipt.json"
fi
exit "$VERDICT_RC"
