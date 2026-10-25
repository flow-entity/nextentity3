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
#   tools/gate.sh fast              保存/提交前：spotless + 架构规则 + 快测试（无容器）
#   tools/gate.sh pr                push 前自查：H2 全量 + 遗留 727 条（无容器，-DskipITs）
#   tools/gate.sh ci                CI 权威档：容器通道必须真跑 + 遗留 727 条
#   tools/gate.sh night             夜间：全量含容器 + 遗留 MySQL + PIT×4
#   tools/gate.sh release           发版前：clean verify + 全矩阵 + PIT + api 兼容
#
# 选项
#   --allow-dirty      工作树脏也放行；此时凭证记 `dirty`，**永远不会是 green**
#   --require-clean    任何档都要求工作树干净
#   --no-receipt       不写凭证；此时凭证状态记 `unverified`，绝不记 green
#
# 退出码
#   0  声明的通道全部真跑了、零失败、零越预算跳过、工作树干净
#   1  有通道缺失 / 不新鲜 / 有失败 / 跳过超预算 / 工作树不干净
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
# night/release 实测时长（install + 容器 + legacy 双跑 + PIT×4）远超半小时，1800s 会把**正在跑**
# 的夜间档判成僵尸并抢锁；被抢者退出时又无条件 rm -rf，反把新锁删掉，第三个进程即可进来。
# 阈值提到 4h，并让每个步骤刷新 heartbeat，长档不会误判。
ZOMBIE_SECONDS=14400

release_lock() {
    # 只删自己的锁：被抢过锁的旧进程退出时不能删掉新持有者的锁。
    local owner_pid
    owner_pid=$(sed -n 's/^pid=//p' "$LOCK/owner" 2>/dev/null)
    if [ "${owner_pid:-}" = "$$" ]; then
        rm -rf "$LOCK" 2>/dev/null
    fi
}

write_owner() {
    printf 'pid=%s\nstarted=%s\ntier=%s\n' "$$" "$(date +%s)" "$TIER" > "$LOCK/owner"
    date +%s > "$LOCK/heartbeat"
}

refresh_lock() {
    [ -d "$LOCK" ] && date +%s > "$LOCK/heartbeat" 2>/dev/null
}

acquire_lock() {
    mkdir -p .mvn || exit 2
    if mkdir "$LOCK" 2>/dev/null; then
        write_owner
        trap release_lock EXIT INT TERM
        return 0
    fi
    local beat now age
    beat=$(cat "$LOCK/heartbeat" 2>/dev/null)
    [ -n "${beat:-}" ] || beat=$(sed -n 's/^started=//p' "$LOCK/owner" 2>/dev/null)
    now=$(date +%s)
    age=$(( now - ${beat:-$now} ))
    if [ -n "${beat:-}" ] && [ "$age" -gt "$ZOMBIE_SECONDS" ]; then
        echo "⚠ 清理僵尸锁（心跳停了 ${age}s，超过 ${ZOMBIE_SECONDS}s）" >&2
        rm -rf "$LOCK"
        mkdir "$LOCK" 2>/dev/null || { echo "✗ 抢锁失败" >&2; exit 2; }
        write_owner
        trap release_lock EXIT INT TERM
        return 0
    fi
    echo "✗ 另一个门禁正在跑（$LOCK，心跳 ${age}s 前）。" >&2
    echo "  并行 mvn 会共用 target/ 造出假红假绿。等它结束，或确认进程已死后删除该目录。" >&2
    exit 2
}

# ---------------------------------------------------------------------------
# ① 工作树干净
# ---------------------------------------------------------------------------

check_tree() {
    local dirty
    dirty=$(git status --porcelain --untracked-files=all -- . ':(exclude)tmp' 2>&1)
    if [ $? -ne 0 ]; then
        # 吞掉 git 报错的后果是 dirty 为空 → 当干净树放行。宁可红。
        echo "✗ git status 失败，无法确认工作树状态：$dirty" >&2
        exit 2
    fi
    TREE_DIRTY="$dirty"
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

# 守卫拦下某步时也要留一份红快照：否则 merge 只看「已存在的快照全绿」，
# 「容器通道一步没跑」会以绿凭证的姿态出现（实测过）。
reject_step() {
    local label="$1" msg="$2"
    local out="$RUNDIR/step-$(printf '%02d' "$STEPN")-$label.json"
    printf '{"step": "%s", "since": 0.0, "required": [], "channels": {}, "stale_channels": {}, "problems": ["%s"], "verdict": "red"}\n' \
        "$label" "$msg" > "$out"
}

step() {
    local label="$1" require="$2"; shift 2
    STEPN=$((STEPN + 1))
    refresh_lock
    local -a cmd=("$@")
    local joined=" ${cmd[*]} "
    local -a skip_args=()
    # STEP_MAX_SKIP 支持空格分隔多条申报（如 "surefire:nextentity-tests-jpa=1"）：
    # 未点名的通道一律用 gate-receipt.py 的默认预算 0，任何跳过都要显式申报。
    local _spec
    for _spec in ${STEP_MAX_SKIP:-}; do
        skip_args+=(--max-skip "$_spec")
    done
    STEP_MAX_SKIP=""

    # ③ 拒绝缺 -am 的调用：它会从 ~/.m2 解析依赖模块，陈旧快照会让结论变成「旧产物能跑」。
    # 两个例外：PIT 目标（-am 会把 PIT 拖到上游模块上跑），以及紧跟在 `install -DskipTests`
    # 之后的步骤——那时 ~/.m2 里就是当前 HEAD，缺 -am 不再失真。
    if [[ "$joined" == *" -pl "* && "$joined" != *" -am "* && "${FRESH_SNAPSHOT:-0}" != "1" ]]; then
        if [[ "$joined" != *"pitest:mutationCoverage"* ]]; then
            echo "✗ [$label] 带 -pl 却没有 -am：会从 ~/.m2 解析依赖模块，陈旧产物会让结论失真。" >&2
            echo "  （若本步之前刚跑过 install -DskipTests，用一个独立的 install 步骤把它标成新鲜快照）" >&2
            FAILED+=("$label(no -am)")
            reject_step "$label" "带 -pl 没有 -am，且没有前置 install 刷新 ~/.m2，本步未执行"
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
    refresh_lock

    # 整仓 install 成功后，后续 -pl 步骤可以从 ~/.m2 取当前 HEAD 的产物。
    if [ "$rc" -eq 0 ] && [[ "$joined" == *" install "* ]]; then
        FRESH_SNAPSHOT=1
    fi

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
        ${skip_args[@]+"${skip_args[@]}"} ${warns[@]+"${warns[@]}"} || vrc=$?

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
    EXPECT_STEPS=(fast-format fast)
    step fast-format "" ./mvnw -B -q spotless:apply
    step fast \
        "surefire:nextentity-api,surefire:nextentity-core,surefire:nextentity-arch" \
        ./mvnw -B -pl nextentity-core,nextentity-arch -am test
}

tier_ci() {
    # CI 权威通道。与本地 pr 档的唯一区别：容器通道**必须真跑**，Docker 缺失即红。
    # 这一档存在的理由：本地 pr 档用 -DskipITs 换取 30s，而 -DskipITs 不留任何标记——
    # 只有把「权威结论只在 ci 档产生」写死，才不会被本地那份省事的绿骗过去。
    EXPECT_STEPS=(ci-fast ci-container ci-legacy)
    step ci-fast "surefire:nextentity-arch" \
        ./mvnw -B -pl nextentity-arch -am test
    STEP_MAX_SKIP="surefire:nextentity-tests-jpa=1 failsafe:nextentity-tests-jpa=1"
    step ci-container "surefire:nextentity-tests-jpa,failsafe:nextentity-tests-jpa" \
        ./mvnw -B -pl nextentity-tests-jpa -am verify -Dnextentity.requireContainer=true
    step ci-legacy "surefire:nextentity-tests-legacy" \
        ./mvnw -B -pl nextentity-tests-legacy -am test
}

tier_pr() {    # 容器通道在本档明确不跑（-DskipITs）。凭证会把 failsafe 记为「未声明」而不是「skip=0」。
    EXPECT_STEPS=(pr-h2 pr-legacy)
    STEP_MAX_SKIP="surefire:nextentity-tests-jpa=1"
    step pr-h2 "surefire:nextentity-tests-jpa" \
        ./mvnw -B -pl nextentity-tests-jpa -am verify -DskipITs
    # 727 条遗留回归此前完全不在 CI 里：CI 两条命令的 -am 反应堆都不含该模块。
    step pr-legacy "surefire:nextentity-tests-legacy" \
        ./mvnw -B -pl nextentity-tests-legacy -am test
}

tier_night() {
    # PIT 不带 -am，依赖从 ~/.m2 解析；先整仓 install 把快照刷到当前 HEAD。
    EXPECT_STEPS=(night-install night-container night-legacy night-pit-api night-pit-core night-pit-jpa night-pit-spring)
    step night-install "" ./mvnw -B -q install -DskipTests
    # 本步刻意不带 -am：紧邻的 night-install 已把当前 HEAD 装进 ~/.m2，守卫据此放行。
    STEP_MAX_SKIP="surefire:nextentity-tests-jpa=1 failsafe:nextentity-tests-jpa=1"
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
    EXPECT_STEPS=(release-verify release-legacy release-install release-pit-api release-pit-core release-pit-jpa release-pit-spring release-compat)
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
            ./mvnw -B -Papi-compat -Djapicmp.oldVersion="$OLD_VERSION" -pl nextentity-api -am verify
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
TREE_DIRTY=""
EXPECT_STEPS=()

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

expect_args=()
for label in ${EXPECT_STEPS[@]+"${EXPECT_STEPS[@]}"}; do
    expect_args+=(--expect-step "$label")
done

"$PY" "$RECEIPT" merge --root . --dir "$RUNDIR" --head "$HEAD_SHA" --tier "$TIER" \
    --tree "$TREE_DIRTY" ${expect_args[@]+"${expect_args[@]}"} \
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
