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
#   tools/gate.sh fast              保存/提交前：格式检查 + 架构规则 + api/core 快测试（无容器）
#   tools/gate.sh pr                push 前自查：H2 全量 + 遗留 727 条（无容器，-DskipITs）
#   tools/gate.sh ci                CI 权威档：容器通道必须真跑 + 遗留 727 条
#   tools/gate.sh night             夜间：全量含容器 + 遗留 MySQL + PIT×4
#   tools/gate.sh release           发版前：clean verify + 全矩阵 + PIT + api 兼容
#
# 选项
#   --allow-dirty      工作树脏也放行；此时凭证记 `dirty`，**永远不会是 green**。
#                      night/release 不接受它：那两档的结论必须对得上一个 commit。
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

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# GATE_ROOT 让脚本能跑在「工作树之外」：CI 要把基线分支的 gate.sh / gate-receipt.py 下到
# $RUNNER_TEMP 再执行，判据才不是由被判对象提供的。若像以前那样 `git show > tools/…` 覆盖
# 工作树，树立刻变脏，紧接着的 --require-clean 必然 exit 1——等于让任何修改门禁脚本的 PR 永远红。
ROOT=${GATE_ROOT:-$(cd "$SCRIPT_DIR/.." && pwd)}
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

# 解析器与 wrapper 同目录，**不是**工作树里的那份：CI 用基线分支的副本执行时，判据也必须
# 是基线那份，否则「改一行 problems=[] 把自己判绿」照样成立。--root . 仍指向工作树（读它的 target/）。
RECEIPT=$SCRIPT_DIR/gate-receipt.py
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
# 且这两档**不给逃生口**：--allow-dirty 只对 fast/pr/ci 有效，否则「夜间绿」可以是对不上
# commit 的树跑出来的，凭证里的 dirty 只会让它非 0 退出，但会先跑掉几小时。
case "$TIER" in
    night|release)
        if [ -n "$ALLOW_DIRTY" ]; then
            echo "✗ $TIER 档不接受 --allow-dirty：该档结论必须对得上一个 commit。" >&2
            exit 2
        fi
        REQUIRE_CLEAN=1
        ;;
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
    # 只刷自己的锁：被抢锁后心跳会写进新持有者的目录，污染它判僵尸的判据。
    [ -d "$LOCK" ] || return 0
    [ "$(sed -n 's/^pid=//p' "$LOCK/owner" 2>/dev/null)" = "$$" ] || return 0
    date +%s > "$LOCK/heartbeat" 2>/dev/null
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
    local dirty err
    # stderr 单独收：并进 $dirty 会让 git 的 warning（如 could not open directory）被当成
    # 「工作树脏」，从而在干净树上判红。退出码仍取 git status 的。
    err=$(mktemp)
    dirty=$(git status --porcelain --untracked-files=all -- . ':(exclude)tmp' 2>"$err")
    local rc=$?
    if [ $rc -ne 0 ]; then
        # 吞掉 git 报错的后果是 dirty 为空 → 当干净树放行。宁可红。
        echo "✗ git status 失败，无法确认工作树状态：$(cat "$err")" >&2
        rm -f "$err"
        exit 2
    fi
    if [ -s "$err" ]; then
        echo "⚠ git status 有告警（不影响判定）：$(head -3 "$err" | tr '\n' ' ')" >&2
    fi
    rm -f "$err"
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
    #
    # 用 `read -a` 而不是 `for _spec in ${STEP_MAX_SKIP:-}`：后者是未加引号的参数展开，
    # 会对每个词做路径名展开。申报里带 `*` 白名单模式，一旦仓库根出现能匹配它的文件名，
    # spec 就会被改写成文件名而静默失效（当前只因 spec 含 `:` `#` 才侥幸不匹配）。
    if [ -n "${STEP_MAX_SKIP:-}" ]; then
        local -a specs=()
        read -r -a specs <<< "$STEP_MAX_SKIP"
        local _spec
        for _spec in "${specs[@]}"; do
            skip_args+=(--max-skip "$_spec")
        done
    fi
    STEP_MAX_SKIP=""

    # STEP_MIN_TESTS：每通道用例数**下限**（空格分隔多条 `通道=N`）。
    # 只钉「方言通道条数 == 基础通道条数」不够——两条一起被缩到只剩基线那几条时相等仍成立。
    # 下限是单调的：加用例不用改，只有删用例才撞红，正好是要人解释的那个动作。
    local -a floor_args=()
    if [ -n "${STEP_MIN_TESTS:-}" ]; then
        local -a floors=()
        read -r -a floors <<< "$STEP_MIN_TESTS"
        local _floor
        for _floor in "${floors[@]}"; do
            floor_args+=(--min-tests "$_floor")
        done
    fi
    STEP_MIN_TESTS=""

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

    local -a warns=()
    # 预期失败基线：只对本步「按设计就会红」的方言通道生效（当前只有 PG 兼容模式）。
    # 必须显式点名，且基线文件里每条都带 type=、issue= 与 expires=（见 tools/expected-failures/）。
    local -a baseline=()
    if [ -n "${STEP_EXPECTED_FAILURES:-}" ]; then
        if [ ! -f "$STEP_EXPECTED_FAILURES" ]; then
            # 基线文件不存在时不能静默跳过——那会把「8 条已知方言差异」变回 8 条新红，
            # 或者更糟（若通道也没声明）让整步变成无检查的绿。
            echo "✗ [$label] 申报的基线文件不存在：$STEP_EXPECTED_FAILURES" >&2
            FAILED+=("$label(baseline-missing)")
            reject_step "$label" "预期失败基线文件不存在：$STEP_EXPECTED_FAILURES"
            STEP_EXPECTED_FAILURES=""
            return 1
        fi
        baseline+=(--expected-failures "$STEP_EXPECTED_FAILURES")
        # 「测试红但不中断生命周期」这件事**由 pom 的 pg-dialect execution 自己承担**
        # （nextentity-tests-jpa/pom.xml 里 <testFailureIgnore>true</testFailureIgnore>），
        # 不在这里加 `-Dmaven.test.failure.ignore=true`：那是全局 user property，会把同一次
        # 反应堆里 api / core / jpa / spring 的 surefire 失败一并吞掉。那些模块的通道
        # 即便写进 --require 也只会看到「0 失败」，等于把真回归静默变绿（实测过）。
        # 绑在 execution 上，只有 PG 兼容模式那一遍忽略失败，其余模块照旧 fail-fast。
        warns+=(--warn "$label：本步申报了预期失败基线——PG 兼容模式那一遍由 pom 的 testFailureIgnore 放行，判定权在凭证的基线逐条比对")
    fi
    STEP_EXPECTED_FAILURES=""

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

    if grep -q 'Nothing to compile - all classes are up to date' "$log" 2>/dev/null; then
        warns+=(--warn "$label：日志出现 Nothing to compile，本次复用了前序产物")
    fi
    if grep -q 'COMPILATION ERROR' "$log" 2>/dev/null; then
        warns+=(--warn "$label：日志出现 COMPILATION ERROR")
    fi

    local -a noreq=()
    # 空 require 的快照会跳过所有通道检查、必然绿。只有确实不产出测试报告的步骤才许豁免，
    # 且豁免必须**点名**——早先版本是「require 为空就自动加 --allow-no-require」，等于把
    # parser 侧「空 require 且未豁免 → 红」这条判据在 wrapper 层永久短路：任何新加的、
    # 忘写 --require 的步骤一律自动绿，而「漏写 require」恰恰是最容易犯的错。
    if [ -z "$require" ]; then
        case "$label" in
            # 纯准备/纯静态检查：不产出 surefire/failsafe/pit 报告，没有通道可要求。
            fast-format | night-install | release-install | release-compat)
                noreq+=(--allow-no-require)
                ;;
            *)
                echo "✗ [$label] 本步没有声明必需通道（--require 为空），且不在豁免清单内。" >&2
                echo "  产出测试报告的步骤必须声明通道；确无报告的步骤请显式加进本 case 分支。" >&2
                FAILED+=("$label(no require)")
                reject_step "$label" "步骤未声明必需通道且不在豁免清单内"
                return 1
                ;;
        esac
    fi

    vrc=0
    "$PY" "$RECEIPT" snapshot --root . --since "$since" --label "$label" \
        --require "$require" --out "$RUNDIR/step-$(printf '%02d' "$STEPN")-$label.json" \
        ${noreq[@]+"${noreq[@]}"} \
        ${skip_args[@]+"${skip_args[@]}"} ${floor_args[@]+"${floor_args[@]}"} \
        ${baseline[@]+"${baseline[@]}"} ${warns[@]+"${warns[@]}"} || vrc=$?

    if [ "$rc" -ne 0 ]; then
        # 申报了预期失败基线时，mvn 的非零退出是**预期内**的：基线里的用例就是会红的，
        # 而 surefire 只要有用例失败就让整个 verify 失败。此时权威判据是 receipt 的
        # 「实际失败集 == 基线」逐条比对结果，不是 mvn 的退出码。
        #
        # 但不能无条件放行，要同时满足两条：
        #   a) receipt 也判绿（vrc==0）——声明通道都新鲜、条数>0、跳过不越预算、失败集与基线相等。
        #      编译错误会让报告陈旧、通道缺失，那些路径 receipt 一定判红，退不进来。
        #   b) 日志里**所有** `Failed to execute goal` 都指向 maven-surefire-plugin。
        #      这条不能省：本步是 `verify`，checkstyle/PMD/SpotBugs/japicmp 都在同一生命周期里。
        #      若它们失败而测试恰好跑完且全绿，只查 (a) 会把静态检查的红当成「预期内失败」吞掉。
        local other_goals
        other_goals=$(grep -o 'Failed to execute goal [^:]*:[^:]*:[^:]*' "$log" 2>/dev/null \
            | grep -v 'maven-surefire-plugin' | sort -u)
        if [ "${#baseline[@]}" -gt 0 ] && [ "$vrc" -eq 0 ] && [ -z "$other_goals" ] \
            && grep -q 'Failed to execute goal' "$log" 2>/dev/null; then
            echo "  ⚠ [$label] mvn 退出码 $rc，失败只来自 surefire，且 receipt 已逐条核对：" >&2
            echo "    失败集与预期失败基线完全相等，按基线解释为预期内失败（凭证里已记下这条告警）。" >&2
        else
            echo "  ✗ [$label] mvn 退出码 $rc；日志尾部：" >&2
            if [ -n "$other_goals" ]; then
                echo "    失败不（只）来自 surefire，不能按预期失败基线解释：" >&2
                echo "$other_goals" | sed 's/^/      /' >&2
            fi
            tail -n 25 "$log" | sed 's/^/      /' >&2
            FAILED+=("$label(exit=$rc)")
        fi
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
    # 必须是 check 不是 apply：apply 会在 check_tree 之后改写工作树，而 merge 用的是开跑前的
    # TREE_DIRTY——干净树开跑、跑到一半把自己弄脏，凭证仍记 clean/green，正是本机制要堵的假绿。
    step fast-format "" ./mvnw -B -q spotless:check
    # `-am` 反应堆除 api/core/arch 外还会经 arch 的依赖带上 jpa/spring，它们的 surefire 报告
    # 同样落盘；不列进 --require 的话，那三个模块的失败在凭证里不存在（同一处假绿，见 ci-container 注释）。
    STEP_MIN_TESTS="surefire:nextentity-api=147 surefire:nextentity-core=267 surefire:nextentity-jpa=73 surefire:nextentity-spring=14 surefire:nextentity-arch=24"
    step fast \
        "surefire:nextentity-api,surefire:nextentity-core,surefire:nextentity-jpa,surefire:nextentity-spring,surefire:nextentity-arch" \
        ./mvnw -B -pl nextentity-core,nextentity-arch -am test
}

tier_ci() {
    # CI 权威通道。与本地 pr 档的唯一区别：容器通道**必须真跑**，Docker 缺失即红。
    # 这一档存在的理由：本地 pr 档用 -DskipITs 换取 30s，而 -DskipITs 不留任何标记——
    # 只有把「权威结论只在 ci 档产生」写死，才不会被本地那份省事的绿骗过去。
    EXPECT_STEPS=(ci-fast ci-container ci-legacy)
    STEP_MIN_TESTS="surefire:nextentity-api=147 surefire:nextentity-core=267 surefire:nextentity-jpa=73 surefire:nextentity-spring=14 surefire:nextentity-arch=24"
    step ci-fast \
        "surefire:nextentity-api,surefire:nextentity-core,surefire:nextentity-jpa,surefire:nextentity-spring,surefire:nextentity-arch" \
        ./mvnw -B -pl nextentity-arch -am test
    # 预算带上用例名白名单：只限条数的话，未来任何一条与 PG 无关的无理由跳过都会被这 1 条预算吞掉。
    # 方言矩阵也在本步：`-Pdialect-pg` 让 PG 兼容模式在同一个 verify 里跑第二遍。
    #
    # 必需通道必须**列全反应堆里每一个产出 surefire 报告的模块**：`-am` 会把 api/core/jpa/spring
    # 一起拉进来跑测试，只点名 tests-jpa 的话，那四个模块的失败在凭证里根本不存在
    # （它们不进 required，`if info["failures"]` 那段就不会被执行），而 mvn 退出码又被
    # 基线降级分支当成「预期内」——真回归由此静默变绿。
    STEP_MAX_SKIP="surefire:nextentity-tests-jpa=1:*TemporalWriteTest#insertAndReloadLoseExactlyOneJvmOffsetOnPostgresCompatibleMode* failsafe:nextentity-tests-jpa=1:*MySqlTemporalWriteIntegrationTest#insertAndReloadLoseExactlyOneJvmOffsetOnPostgresCompatibleMode* surefire-pg:nextentity-tests-jpa=1:*TemporalWriteTest#insertAndReloadPreserveTheInstant*"
    STEP_MIN_TESTS="surefire:nextentity-api=147 surefire:nextentity-core=267 surefire:nextentity-jpa=73 surefire:nextentity-spring=14 surefire:nextentity-tests-jpa=564 failsafe:nextentity-tests-jpa=536 surefire-pg:nextentity-tests-jpa=564"
    STEP_EXPECTED_FAILURES="tools/expected-failures/h2-postgresql.txt"
    step ci-container \
        "surefire:nextentity-api,surefire:nextentity-core,surefire:nextentity-jpa,surefire:nextentity-spring,surefire:nextentity-tests-jpa,failsafe:nextentity-tests-jpa,surefire-pg:nextentity-tests-jpa" \
        ./mvnw -B -pl nextentity-tests-jpa -am verify -Dnextentity.requireContainer=true -Pdialect-pg
    STEP_MIN_TESTS="surefire:nextentity-tests-legacy=727 surefire:nextentity-api=147 surefire:nextentity-core=267 surefire:nextentity-jpa=73"
    step ci-legacy \
        "surefire:nextentity-api,surefire:nextentity-core,surefire:nextentity-jpa,surefire:nextentity-tests-legacy" \
        ./mvnw -B -pl nextentity-tests-legacy -am test
}

tier_pr() {    # 容器通道在本档明确不跑（-DskipITs）。凭证会把 failsafe 记为「未声明」而不是「skip=0」。
    EXPECT_STEPS=(pr-h2 pr-legacy)
    STEP_MAX_SKIP="surefire:nextentity-tests-jpa=1:*TemporalWriteTest#insertAndReloadLoseExactlyOneJvmOffsetOnPostgresCompatibleMode*"
    # `-Pdialect-pg` 在同一次 verify 里加第二个 surefire execution（PG 兼容模式），报告落
    # `target/surefire-pg-reports/`，凭证按目录认成独立通道 `surefire-pg:...`。
    # skip 预算也要单独申报：两个模式的跳过项**恰好相反**——默认模式跳「PG 专属那条」，
    # PG 模式跳「Instant 严格相等那条」（H2 的 PG 兼容模式绑 Instant 会丢偏移）。
    STEP_MAX_SKIP="$STEP_MAX_SKIP surefire-pg:nextentity-tests-jpa=1:*TemporalWriteTest#insertAndReloadPreserveTheInstant*"
    # 必需通道列全反应堆（理由同 ci-container）：`-am` 把 api/core/jpa/spring 一起跑，
    # 漏点名就等于那几个模块的失败不进凭证。
    STEP_MIN_TESTS="surefire:nextentity-api=147 surefire:nextentity-core=267 surefire:nextentity-jpa=73 surefire:nextentity-spring=14 surefire:nextentity-tests-jpa=564 surefire-pg:nextentity-tests-jpa=564"
    STEP_EXPECTED_FAILURES="tools/expected-failures/h2-postgresql.txt"
    step pr-h2 \
        "surefire:nextentity-api,surefire:nextentity-core,surefire:nextentity-jpa,surefire:nextentity-spring,surefire:nextentity-tests-jpa,surefire-pg:nextentity-tests-jpa" \
        ./mvnw -B -pl nextentity-tests-jpa -am verify -DskipITs -Pdialect-pg
    # 727 条遗留回归此前完全不在 CI 里：CI 两条命令的 -am 反应堆都不含该模块。
    STEP_MIN_TESTS="surefire:nextentity-api=147 surefire:nextentity-core=267 surefire:nextentity-jpa=73 surefire:nextentity-tests-legacy=727"
    step pr-legacy \
        "surefire:nextentity-api,surefire:nextentity-core,surefire:nextentity-jpa,surefire:nextentity-tests-legacy" \
        ./mvnw -B -pl nextentity-tests-legacy -am test
}

tier_night() {
    # PIT 不带 -am，依赖从 ~/.m2 解析；先整仓 install 把快照刷到当前 HEAD。
    EXPECT_STEPS=(night-install night-container night-legacy night-pit-api night-pit-core night-pit-jpa night-pit-spring)
    step night-install "" ./mvnw -B -q install -DskipTests
    STEP_MAX_SKIP="surefire:nextentity-tests-jpa=1:*TemporalWriteTest#insertAndReloadLoseExactlyOneJvmOffsetOnPostgresCompatibleMode* failsafe:nextentity-tests-jpa=1:*MySqlTemporalWriteIntegrationTest#insertAndReloadLoseExactlyOneJvmOffsetOnPostgresCompatibleMode* surefire-pg:nextentity-tests-jpa=1:*TemporalWriteTest#insertAndReloadPreserveTheInstant*"
    STEP_MIN_TESTS="surefire:nextentity-api=147 surefire:nextentity-core=267 surefire:nextentity-jpa=73 surefire:nextentity-spring=14 surefire:nextentity-tests-jpa=564 failsafe:nextentity-tests-jpa=536 surefire-pg:nextentity-tests-jpa=564"
    STEP_EXPECTED_FAILURES="tools/expected-failures/h2-postgresql.txt"
    # 带 -am：`-pl` 不带 `-am` 时反应堆里只有 tests-jpa，api/core/jpa/spring 的单测与静态检查
    # 本档就一条都不跑（night 是最强档，不能比 pr 弱）。night-install 仍然保留——PIT 步骤
    # 不带 -am，要从 ~/.m2 解析当前 HEAD 的产物。
    step night-container \
        "surefire:nextentity-api,surefire:nextentity-core,surefire:nextentity-jpa,surefire:nextentity-spring,surefire:nextentity-tests-jpa,failsafe:nextentity-tests-jpa,surefire-pg:nextentity-tests-jpa" \
        ./mvnw -B -pl nextentity-tests-jpa -am verify -Dnextentity.requireContainer=true -Pdialect-pg
    STEP_MIN_TESTS="surefire:nextentity-api=147 surefire:nextentity-core=267 surefire:nextentity-jpa=73 surefire:nextentity-tests-legacy=727"
    step night-legacy \
        "surefire:nextentity-api,surefire:nextentity-core,surefire:nextentity-jpa,surefire:nextentity-tests-legacy" \
        ./mvnw -B -pl nextentity-tests-legacy -am test -Ddbc=h2,mysql
    local module
    for module in api core jpa spring; do
        step "night-pit-$module" "pit:nextentity-$module" \
            ./mvnw -B -pl "nextentity-$module" test-compile pitest:mutationCoverage
    done
}

tier_release() {
    EXPECT_STEPS=(release-verify release-legacy release-install release-pit-api release-pit-core release-pit-jpa release-pit-spring release-compat)
    # 与 ci/night 同一条申报：`TemporalWriteContract` 那对用例按 H2 兼容模式互斥，
    # 默认参数下必有 1 条跳过。此前漏了这一档，导致 AGENTS「发版前 ./tools/gate.sh release」
    # 恒红——本机制的主题是「无理由跳过不能被预算静默吸收」，那就每一档都得说清这 1 条是谁。
    STEP_MAX_SKIP="surefire:nextentity-tests-jpa=1:*TemporalWriteTest#insertAndReloadLoseExactlyOneJvmOffsetOnPostgresCompatibleMode* failsafe:nextentity-tests-jpa=1:*MySqlTemporalWriteIntegrationTest#insertAndReloadLoseExactlyOneJvmOffsetOnPostgresCompatibleMode* surefire-pg:nextentity-tests-jpa=1:*TemporalWriteTest#insertAndReloadPreserveTheInstant*"
    # 发版前必须把方言矩阵也跑一遍：`clean verify` 会先删 `target/`，PG 通道在
    # release 档若缺席，那 8 条已知差异就永远不会被重新核对，基线静默烂掉。
    STEP_EXPECTED_FAILURES="tools/expected-failures/h2-postgresql.txt"
    # `clean verify` 是整仓反应堆，除 tests-jpa 外还跑 api/core/arch/jpa/spring 与 tests-legacy，
    # 全部产能报告都要进 --require（否则那几处的失败不进凭证，退出码又被基线降级分支吸收）。
    STEP_MIN_TESTS="surefire:nextentity-api=147 surefire:nextentity-core=267 surefire:nextentity-arch=24 surefire:nextentity-jpa=73 surefire:nextentity-spring=14 surefire:nextentity-tests-jpa=564 surefire:nextentity-tests-legacy=727 failsafe:nextentity-tests-jpa=536 surefire-pg:nextentity-tests-jpa=564"
    step release-verify \
        "surefire:nextentity-api,surefire:nextentity-core,surefire:nextentity-arch,surefire:nextentity-jpa,surefire:nextentity-spring,surefire:nextentity-tests-jpa,surefire:nextentity-tests-legacy,failsafe:nextentity-tests-jpa,surefire-pg:nextentity-tests-jpa" \
        ./mvnw -B clean verify -Dnextentity.requireContainer=true -Pdialect-pg
    STEP_MIN_TESTS="surefire:nextentity-api=147 surefire:nextentity-core=267 surefire:nextentity-jpa=73 surefire:nextentity-tests-legacy=727"
    step release-legacy \
        "surefire:nextentity-api,surefire:nextentity-core,surefire:nextentity-jpa,surefire:nextentity-tests-legacy" \
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

# ① 收尾复检工作树：开跑前干净、跑到一半变脏，此前会原样把 clean 交给 merge。
# 那是结构性的，堵单个 `spotless:apply` 只是堵了一种触发方式；任何写工作树的插件/步骤
# （japicmp 生成报告、shade、某个新加的 codegen）都会把它原样复发。这里比对一次即可永久关闭。
PRE_TREE_DIRTY="$TREE_DIRTY"
check_tree
if [ "$TREE_DIRTY" != "$PRE_TREE_DIRTY" ]; then
    echo "" >&2
    echo "✗ 工作树在门禁过程中发生变化：开跑时=「${PRE_TREE_DIRTY:-clean}」 结束时=「${TREE_DIRTY:-clean}」" >&2
    echo "  某些步骤写入了工作树，本轮所有「干净树」结论都不成立。" >&2
    if [ -n "$TREE_DIRTY" ] && [ -z "$PRE_TREE_DIRTY" ]; then
        TREE_DIRTY="门禁过程中被写脏：$TREE_DIRTY"
    fi
    FAILED+=("tree-changed-during-run")
fi

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
