#!/usr/bin/env python3
"""门禁凭证：把「这次到底跑了哪些通道、跑了几条、跳过几条」变成机器可核对的 JSON。

为什么需要它：`-DskipITs` **不产生任何标记**，而 `target/failsafe-reports/` 里上一轮的
通过记录会原地留存。任何"读产物判绿"的脚本都会报出上一轮的结论——实测过：某轮
`test -DskipITs` 跑在 16:41:15–16:41:40，而 `failsafe-reports/` 里 530 条通过 XML 的
mtime 是 16:41:14，读它就会报「530 passed」，而容器通道一条没跑。

所以本工具的判据不是「报告存在」，而是「报告的 mtime 晚于本轮开始时间」。

子命令
    snapshot  跑完一步之后立刻快照：本步产出了哪些通道、是否新鲜、条数、跳过清单
    merge     把一次 gate 运行的所有 step 快照合并成一份凭证（给人看，也给 CI 读）

退出码
    0  本步声明的必需通道全部新鲜且零失败
    1  有必需通道缺失 / 不新鲜 / 有失败
    2  用法或环境错误
"""

from __future__ import annotations

import argparse
import fnmatch
import glob
import hashlib
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

# ---------------------------------------------------------------------------
# 扫描
# ---------------------------------------------------------------------------

#: 判"新鲜"的容差（秒）。mtime 取浮点秒，同一秒内起跑不会误判成陈旧。
FRESH_TOLERANCE = 0.0


def modules(root: str) -> list[str]:
    """仓库下的 nextentity-* 模块目录。"""
    return sorted(
        os.path.basename(p)
        for p in glob.glob(os.path.join(root, "nextentity-*"))
        if os.path.isdir(p)
    )


def _scan_xml_reports(root: str, kind: str, since: float) -> dict[str, dict]:
    """扫 surefire / failsafe 报告，按模块聚合。

    聚合的是 `<testsuite>` 的四个计数属性 + 跳过/失败用例的**具名清单**——
    只有数字没有名字的跳过报表，等于没有报表（AGENTS「方言跳过必须可数」要求点名）。

    **按文件 mtime 过滤**（不是只比聚合 mtime）：`target/` 会留着上一轮的 53 个报告，
    只跑 `-Dtest=OneTest` 时若不逐文件过滤，本步会报「564 条、绿」——数字全是旧的。
    全陈旧的通道保留一条 mtime<since 的空壳记录，好让调用方区分
    「报告在但不新鲜」与「压根没产出报告」。
    """
    out: dict[str, dict] = {}
    for module in modules(root):
        files = glob.glob(os.path.join(root, module, "target", f"{kind}-reports", "TEST-*.xml"))
        if not files:
            continue
        agg = {
            "kind": kind,
            "module": module,
            "classes": 0,
            "tests": 0,
            "failures": 0,
            "errors": 0,
            "skipped": 0,
            "mtime": 0.0,
            "stale_files": 0,
            "skipped_tests": [],
            "failed_tests": [],
            "unparsable": [],
        }
        for path in files:
            try:
                file_mtime = os.stat(path).st_mtime
            except OSError:
                continue
            if file_mtime < since - FRESH_TOLERANCE:
                agg["stale_files"] += 1
                agg["mtime"] = max(agg["mtime"], file_mtime)
                continue
            try:
                suite = ET.parse(path).getroot()
            except ET.ParseError:
                agg["unparsable"].append(os.path.basename(path))
                continue
            agg["classes"] += 1
            agg["tests"] += int(suite.get("tests") or 0)
            agg["failures"] += int(suite.get("failures") or 0)
            agg["errors"] += int(suite.get("errors") or 0)
            agg["skipped"] += int(suite.get("skipped") or 0)
            agg["mtime"] = max(agg["mtime"], os.stat(path).st_mtime)
            for case in suite.iter("testcase"):
                name = "%s#%s" % (case.get("classname", "?"), case.get("name", "?"))
                skip = case.find("skipped")
                if skip is not None:
                    agg["skipped_tests"].append(
                        {"test": name, "reason": (skip.get("message") or "").strip()[:240]}
                    )
                if case.find("failure") is not None or case.find("error") is not None:
                    agg["failed_tests"].append(name)
        out["%s:%s" % (kind, module)] = agg
    return out


def _pit_threshold(root: str, module: str) -> float | None:
    """读模块 pom 的 `mutationThreshold`，凭证用它交叉核对 PIT 自己的分数。

    PIT 的退出码才是权威门禁（`mvn ... pitest:mutationCoverage` 不达阈值即非零退出）；
    这里读一遍是为了让凭证里的百分比和 HTML 报表对得上，而不是各算一套。
    """
    pom = os.path.join(root, module, "pom.xml")
    try:
        text = open(pom, encoding="utf-8").read()
    except OSError:
        return None
    match = re.search(r"<mutationThreshold>\s*([0-9.]+)\s*</mutationThreshold>", text)
    return float(match.group(1)) if match else None


def _scan_pit(root: str) -> dict[str, dict]:
    """扫 PIT 报告。`failWhenNoMutations=false` 下「零变异 + 阈值不校验」是已知假绿通道，
    所以这里把 `mutations` 计数单独提出来，`0` 会被 verify 当成缺口。

    分数按 **PIT 自己的口径**算：`coverage = detected / (detected + survived + no_coverage)`，
    `detected` 含 `TIMED_OUT`（PIT 把超时算检出）。`NON_VIABLE` 不进分母——它既不是活下来
    的变异也不是被检出的变异。旧实现用 `killed / generated`，把 `NON_VIABLE` 算进分母，
    于是凭证里的百分比永远比 PIT HTML 低几个点，两边对不上。
    """
    out: dict[str, dict] = {}
    for module in modules(root):
        path = os.path.join(root, module, "target", "pit-reports", "mutations.xml")
        if not os.path.isfile(path):
            continue
        try:
            suite = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        generated = 0
        status_counts: dict[str, int] = {}
        survived: list[str] = []
        for mutation in suite.iter("mutation"):
            generated += 1
            status = mutation.get("status", "") or "UNKNOWN"
            status_counts[status] = status_counts.get(status, 0) + 1
            detected = mutation.get("detected") == "true" or status in ("KILLED", "TIMED_OUT")
            if not detected:
                survived.append(
                    "%s:%s:%s(%s)"
                    % (
                        mutation.findtext("mutatedClass") or "?",
                        mutation.findtext("lineNumber") or "?",
                        (mutation.findtext("mutator") or "?").rsplit(".", 1)[-1],
                        status,
                    )
                )
        detected_n = sum(n for s, n in status_counts.items() if s in ("KILLED", "TIMED_OUT"))
        effective = sum(n for s, n in status_counts.items() if s in ("KILLED", "TIMED_OUT", "SURVIVED", "NO_COVERAGE"))
        # 保留未取整值：先 round 再和阈值比，94.95% 会被抬成 95.0% 判绿。
        coverage_raw = 100.0 * detected_n / effective if effective else None
        out["pit:%s" % module] = {
            "kind": "pit",
            "module": module,
            "mutations": generated,
            "detected": detected_n,
            "effective": effective,
            "statuses": status_counts,
            "survived": survived,
            "coverage": round(coverage_raw, 1) if coverage_raw is not None else None,
            "coverage_raw": coverage_raw,
            "run_errors": status_counts.get("RUN_ERROR", 0),
            "non_viable": status_counts.get("NON_VIABLE", 0),
            "test_strength": round(100.0 * detected_n / (detected_n + status_counts.get("SURVIVED", 0)), 1)
            if detected_n + status_counts.get("SURVIVED", 0)
            else None,
            "threshold": _pit_threshold(root, module),
            "mtime": os.stat(path).st_mtime,
        }
    return out


def scan(root: str, since: float = 0.0) -> dict[str, dict]:
    channels: dict[str, dict] = {}
    channels.update(_scan_xml_reports(root, "surefire", since))
    channels.update(_scan_xml_reports(root, "failsafe", since))
    channels.update(_scan_pit(root))
    return channels


# ---------------------------------------------------------------------------
# 陈旧产物检查（只告警，不当门禁：mtime 受 checkout / 时钟影响，会有假阳）
# ---------------------------------------------------------------------------


def _git_commit_epoch(root: str, path: str) -> int | None:
    try:
        out = subprocess.run(
            ["git", "log", "-1", "--format=%ct", "--", path],
            cwd=root,
            capture_output=True,
            text=True,
            check=False,
        ).stdout.strip()
        return int(out) if out else None
    except (OSError, ValueError):
        return None


def _project_gav(root: str) -> tuple[str, str]:
    """从根 pom 读 groupId / version，别把坐标写死在脚本里。

    写死的后果实测过：方案里按 `io.github.nextentity:3.1.0-SNAPSHOT` 找 jar，而仓库实际是
    `io.github.flow-entity:3.0.0-SNAPSHOT` —— 路径不存在 → 一个文件都找不到 → 陈旧产物检查
    静默变成"全绿"。
    """
    group, version = "io.github.flow-entity", ""
    pom = os.path.join(root, "pom.xml")
    try:
        tree = ET.parse(pom).getroot()
    except (OSError, ET.ParseError):
        return group, version
    ns = "{http://maven.apache.org/POM/4.0.0}"
    for tag in ("groupId", "version"):
        node = tree.find(ns + tag)
        if node is not None and (node.text or "").strip():
            if tag == "groupId":
                group = node.text.strip()
            else:
                version = node.text.strip()
    return group, version


def stale_artifacts(root: str) -> list[str]:
    """`~/.m2` 里的 SNAPSHOT 是否落后于源码。

    判据用 **jar 自身 mtime**，不用 `maven-metadata-local.xml`——实测 metadata 的 mtime
    是安装时刻，而里面那份 jar 内容是两小时前的，metadata 会谎报新鲜度。
    """
    home = os.path.expanduser("~")
    group, version = _project_gav(root)
    if not version:
        return []
    warnings: list[str] = []
    for module in modules(root):
        jar = os.path.join(home, ".m2", "repository", *group.split("."), module,
                           version, "%s-%s.jar" % (module, version))
        if not os.path.isfile(jar):
            continue
        jar_mtime = os.stat(jar).st_mtime
        # 下界只用「最后改动该模块源码的 commit 时间」，**不用 target/classes 的 mtime**：
        # 每次 -am 构建都会刷新 target/classes，把它算进去会让这条告警在每次构建都成立，
        # 变成永远亮着的噪音。真正要防的是 PIT 那条不带 -am 的路径——它从 ~/.m2 拿依赖模块。
        floor = _git_commit_epoch(root, os.path.join(module, "src")) or 0
        if floor and jar_mtime < floor:
            warnings.append(
                "%s: ~/.m2 的 jar 早于源码/产物（jar %.0f < 源 %.0f）——不带 -am 的调用会读到旧语义"
                % (module, jar_mtime, floor)
            )
    return warnings


# ---------------------------------------------------------------------------
# 子命令
# ---------------------------------------------------------------------------


def cmd_snapshot(args: argparse.Namespace) -> int:
    channels = scan(args.root, args.since)
    fresh, stale, missing = {}, {}, []
    for name, info in channels.items():
        if info["mtime"] >= args.since - FRESH_TOLERANCE:
            fresh[name] = info
        else:
            stale[name] = info

    required = [r for r in (args.require or "").split(",") if r]
    absent = [r for r in required if r not in fresh]
    missing = absent

    # 每通道的 skip 预算：`--max-skip name=N` 或 `name=N:TestPattern`（可重复）。默认 0——
    # **任何跳过都要显式申报**，否则「容器通道 44 条全 skip」会以 tests=44/skipped=44 判绿（实测过）。
    # 只比条数不够：那 1 条预算会把**未来任何**新增的无理由跳过静默吸收。带 `:Pattern` 时，
    # 每条跳过的用例名都必须匹配白名单，否则红。
    budgets: dict[str, int] = {}
    skip_allow: dict[str, list[str]] = {}
    for spec in args.max_skip or []:
        key, _, value = spec.partition("=")
        key = key.strip()
        count, _, pattern = value.partition(":")
        budgets[key] = int(count) if count.strip() else 0
        if pattern.strip():
            skip_allow.setdefault(key, []).append(pattern.strip())
    default_budget = args.max_skip_default

    problems: list[str] = []
    warnings_extra: list[str] = []
    # E1：`--require` 为空时，下面的检查一条都不跑，snapshot 必绿。除了显式声明
    # 「本步不产出通道」（格式化类），空 require 就是漏写，必须报错。
    if not required and not getattr(args, "allow_no_require", False):
        problems.append(
            "本步没有声明任何 --require 通道：这样所有通道检查都不执行，快照必然绿。"
            "若本步确实不产出测试报告，请显式传 --allow-no-require"
        )
    for name in missing:
        if name in stale:
            problems.append(
                "%s：报告存在但不新鲜（mtime %.0f < 本轮开始 %.0f，另有 %d 个陈旧报告文件）"
                "——本步没真跑，上一轮的记录还在 target/ 下，别读它"
                % (name, stale[name]["mtime"], args.since, stale[name].get("stale_files", 0))
            )
        else:
            problems.append("%s：本步没有产出任何报告" % name)
    for name in required:
        info = fresh.get(name)
        if not info:
            continue
        if info.get("kind") == "pit":
            if not info["mutations"]:
                problems.append(
                    "%s：零变异。`failWhenNoMutations=false` 下这会变成「BUILD SUCCESS 且零证据」" % name
                )
                continue
            threshold = info.get("threshold")
            # 用未取整值比阈值：先 round 会把 94.95 抬成 95.0 判绿。
            coverage = info.get("coverage_raw")
            if threshold is not None and coverage is not None and coverage + 1e-9 < threshold:
                problems.append(
                    "%s：变异覆盖率 %.2f%% < pom 阈值 %.1f%%（detected %d / effective %d，状态 %s）"
                    % (name, coverage, threshold, info["detected"], info["effective"], info["statuses"])
                )
            # RUN_ERROR 既不进分子也不进分母：某个变异体让测试 JVM 崩掉时会被静默忽略。
            if info.get("run_errors"):
                problems.append(
                    "%s：%d 个变异体 RUN_ERROR（测试 JVM 崩了），这部分既不计分母也不计分子，不能算过"
                    % (name, info["run_errors"])
                )
            continue
        if info["failures"] or info["errors"]:
            problems.append(
                "%s：失败 %d / 错误 %d（%s）"
                % (name, info["failures"], info["errors"], ", ".join(info["failed_tests"][:5]))
            )
        if info["tests"] == 0:
            problems.append("%s：报告存在但 tests=0（没有任何用例被执行）" % name)
        if info["unparsable"]:
            problems.append(
                "%s：%d 个报告无法解析（%s）——截断的 XML 不能算通过"
                % (name, len(info["unparsable"]), ", ".join(info["unparsable"][:3]))
            )
        budget = budgets.get(name, default_budget)
        if info["skipped"] > budget:
            named = ", ".join("%s(%s)" % (s["test"], s["reason"] or "无理由") for s in info["skipped_tests"][:5])
            problems.append(
                "%s：跳过 %d 条 > 预算 %d（%s）——跳过就是没跑，没跑不能记绿"
                % (name, info["skipped"], budget, named or "未点名")
            )
        # 预算只限条数：申报了 `:Pattern` 时，每条被跳过的用例名都必须落在白名单里，
        # 否则「多出一条与申报无关的跳过」会被那条预算静默吸收。
        allowed = skip_allow.get(name)
        if allowed and info["skipped"]:
            for item in info["skipped_tests"]:
                if not any(fnmatch.fnmatch(item["test"], pat) for pat in allowed):
                    problems.append(
                        "%s：跳过用例 %s 不在申报白名单 %s 内——方言跳过必须点名，不能借预算夹带"
                        % (name, item["test"], allowed)
                    )

    # 申报了预算却不在 --require 里的通道：预算被静默忽略，会让人误以为「已申报即可跳」。
    for key in budgets:
        if key not in required:
            warnings_extra.append("%s：申报了 --max-skip 但不在 --require 里，预算未生效" % key)

    receipt = {
        "step": args.label,
        "since": args.since,
        "required": required,
        "channels": fresh,
        "stale_channels": {
            k: {f: v.get(f) for f in ("tests", "mutations", "mtime")} for k, v in stale.items()
        },
        "warnings": list(args.warn or []) + warnings_extra,
        "problems": problems,
        "verdict": "green" if not problems else "red",
    }
    if args.out:
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        with open(args.out, "w", encoding="utf-8") as handle:
            json.dump(receipt, handle, ensure_ascii=False, indent=2)

    _print_step(receipt)
    return 0 if not problems else 1


def _print_step(receipt: dict) -> None:
    print("\n── 凭证 [%s] ─────────────────────────────────────────" % receipt["step"])
    for name in receipt["required"]:
        info = receipt["channels"].get(name)
        if info is None:
            print("  ✗ %-46s 未执行" % name)
            continue
        if info.get("kind") == "pit":
            print(
                "  ✓ %-46s 变异 %3d / 杀 %3d / %s%%（PIT 口径 %s%%，阈值 %s）"
                % (
                    name,
                    info["mutations"],
                    info["detected"],
                    info["coverage"],
                    info["coverage"],
                    info["threshold"],
                )
            )
            continue
        print(
            "  ✓ %-46s %4d 条  skip %d  fail %d"
            % (name, info["tests"], info["skipped"], info["failures"] + info["errors"])
        )
    for name, info in receipt["channels"].items():
        if name in receipt["required"]:
            continue
        if info.get("kind") == "pit":
            print(
                "    %-46s 变异 %3d / 杀 %3d / %s%%"
                % (name, info["mutations"], info["detected"], info["coverage"])
            )
        else:
            print("    %-46s %4d 条  skip %d" % (name, info["tests"], info["skipped"]))
    for warning in receipt["warnings"]:
        print("  ⚠ %s" % warning)
    for problem in receipt["problems"]:
        print("  ✗ %s" % problem)
    print("  判定：%s" % receipt["verdict"].upper())


def cmd_merge(args: argparse.Namespace) -> int:
    steps = []
    for path in sorted(glob.glob(os.path.join(args.dir, "step-*.json"))):
        with open(path, encoding="utf-8") as handle:
            steps.append(json.load(handle))
    if not steps:
        print("没有 step 快照：%s/step-*.json" % args.dir, file=sys.stderr)
        return 2

    # 缺步骤就是缺证据：`step()` 的 -am 守卫 `return 1` 时不落快照，若只看「已存在的快照
    # 全绿」就会把「容器通道一步没跑」判成绿。期望清单由档位自己声明，缺一个即红。
    present = {s["step"] for s in steps}
    absent_steps = [label for label in (args.expect_step or []) if label not in present]

    skips: list[dict] = []
    seen: set[str] = set()
    for step in steps:
        for name, info in step["channels"].items():
            if info.get("kind") == "pit":
                continue
            for entry in info.get("skipped_tests", []):
                key = "%s|%s" % (name, entry["test"])
                if key in seen:
                    continue
                seen.add(key)
                skips.append({"channel": name, "test": entry["test"], "reason": entry["reason"]})

    tree = (args.tree or "").strip()
    verdict = "green"
    if absent_steps or any(s["verdict"] != "green" for s in steps):
        verdict = "red"
    elif tree:
        # 干净树是「凭证能对上 commit」的前提。脏树不是「红」（代码可能没问题），
        # 但也绝不能叫 green：它只能证明「工作树里这份未提交的东西能跑」。
        verdict = "dirty"

    receipt = {
        "head": args.head,
        "tier": args.tier,
        "tree": {"clean": not tree, "status": tree},
        "tree_digest": hashlib.sha256(tree.encode("utf-8")).hexdigest()[:16],
        "steps": [
            {"step": s["step"], "verdict": s["verdict"], "problems": s["problems"]} for s in steps
        ],
        "absent_steps": absent_steps,
        "skip_report": {"total": len(skips), "entries": skips},
        "stale_artifacts": stale_artifacts(args.root),
        "verdict": verdict,
    }
    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(receipt, handle, ensure_ascii=False, indent=2)
    digest = hashlib.sha256(open(args.out, "rb").read()).hexdigest()
    receipt["receipt_digest"] = digest
    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(receipt, handle, ensure_ascii=False, indent=2)

    print("\n════ 凭证汇总 %s / %s ════" % (args.tier, args.head))
    for step in receipt["steps"]:
        print("  %-28s %s" % (step["step"], step["verdict"].upper()))
    for label in absent_steps:
        print("  %-28s MISSING（本档声明要跑，但没有快照）" % label)
    if tree:
        print("  ⚠ 工作树不干净（%d 行改动），本次凭证不记绿" % len(tree.splitlines()))
    print("  skip 总数：%d" % len(skips))
    for entry in skips:
        print("    - [%s] %s  %s" % (entry["channel"], entry["test"], entry["reason"]))
    for warning in receipt["stale_artifacts"]:
        print("  ⚠ %s" % warning)
    print("  总判定：%s" % receipt["verdict"].upper())
    print("  凭证：%s（sha256 %s…）" % (args.out, digest[:16]))
    return 0 if receipt["verdict"] == "green" else 1


def main(argv: list[str]) -> int:
    # Windows 控制台默认 GBK，中文告警会变成乱码——凭证是给人读的证据，先修编码。
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, OSError):
            pass

    parser = argparse.ArgumentParser(description="门禁凭证")
    sub = parser.add_subparsers(dest="cmd", required=True)

    snap = sub.add_parser("snapshot", help="跑完一步之后立刻快照")
    snap.add_argument("--root", default=".")
    snap.add_argument("--since", type=float, required=True, help="本步开始 epoch 秒")
    snap.add_argument("--label", required=True)
    snap.add_argument("--require", default="", help="逗号分隔的必需通道，如 surefire:nextentity-core")
    snap.add_argument(
        "--allow-no-require",
        action="store_true",
        help="显式声明本步不产出测试报告（如格式化检查）；不传且 --require 为空则判红",
    )
    snap.add_argument(
        "--max-skip",
        action="append",
        default=[],
        metavar="CHANNEL=N",
        help="该通道允许的跳过条数（可重复）；不写则用 --max-skip-default",
    )
    snap.add_argument(
        "--max-skip-default",
        type=int,
        default=0,
        help="未单独申报的通道允许的跳过条数，默认 0（任何跳过都要显式申报）",
    )
    snap.add_argument("--warn", action="append", default=[], help="可重复的告警行")
    snap.add_argument("--out")
    snap.set_defaults(func=cmd_snapshot)

    merge = sub.add_parser("merge", help="合并一次 gate 运行的全部 step 快照")
    merge.add_argument("--root", default=".")
    merge.add_argument("--dir", required=True)
    merge.add_argument("--head", required=True)
    merge.add_argument("--tier", required=True)
    merge.add_argument("--out", required=True)
    merge.add_argument(
        "--expect-step",
        action="append",
        default=[],
        metavar="LABEL",
        help="本档必须出现的步骤名（可重复）：缺一步即判红，防止「守卫拦下某步 → 没快照 → 仍判绿」",
    )
    merge.add_argument(
        "--tree",
        default="",
        help="工作树状态（git status --short 原文，空表示干净）：写进凭证，让 dirty 可发现",
    )
    merge.set_defaults(func=cmd_merge)

    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
