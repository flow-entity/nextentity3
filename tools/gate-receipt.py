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
import glob
import json
import os
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


def _scan_xml_reports(root: str, kind: str) -> dict[str, dict]:
    """扫 surefire / failsafe 报告，按模块聚合。

    聚合的是 `<testsuite>` 的四个计数属性 + 跳过/失败用例的**具名清单**——
    只有数字没有名字的跳过报表，等于没有报表（AGENTS「方言跳过必须可数」要求点名）。
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
            "skipped_tests": [],
            "failed_tests": [],
            "unparsable": [],
        }
        for path in files:
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


def _scan_pit(root: str) -> dict[str, dict]:
    """扫 PIT 报告。`failWhenNoMutations=false` 下「零变异 + 阈值不校验」是已知假绿通道，
    所以这里把 `mutations` 计数单独提出来，`0` 会被 verify 当成缺口。"""
    out: dict[str, dict] = {}
    for module in modules(root):
        path = os.path.join(root, module, "target", "pit-reports", "mutations.xml")
        if not os.path.isfile(path):
            continue
        try:
            suite = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        generated = killed = 0
        survived: list[str] = []
        for mutation in suite.iter("mutation"):
            generated += 1
            status = mutation.get("status", "")
            if mutation.get("detected") == "true" or status == "KILLED":
                killed += 1
            else:
                survived.append(
                    "%s:%s:%s"
                    % (
                        mutation.findtext("mutatedClass") or "?",
                        mutation.findtext("lineNumber") or "?",
                        (mutation.findtext("mutator") or "?").rsplit(".", 1)[-1],
                    )
                )
        out["pit:%s" % module] = {
            "kind": "pit",
            "module": module,
            "mutations": generated,
            "killed": killed,
            "survived": survived,
            "score": round(100.0 * killed / generated, 1) if generated else None,
            "mtime": os.stat(path).st_mtime,
        }
    return out


def scan(root: str) -> dict[str, dict]:
    channels: dict[str, dict] = {}
    channels.update(_scan_xml_reports(root, "surefire"))
    channels.update(_scan_xml_reports(root, "failsafe"))
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
    channels = scan(args.root)
    fresh, stale, missing = {}, {}, []
    for name, info in channels.items():
        if info["mtime"] >= args.since - FRESH_TOLERANCE:
            fresh[name] = info
        else:
            stale[name] = info

    required = [r for r in (args.require or "").split(",") if r]
    absent = [r for r in required if r not in fresh]
    missing = absent

    problems: list[str] = []
    for name in missing:
        if name in stale:
            problems.append(
                "%s：报告存在但不新鲜（mtime %.0f < 本轮开始 %.0f）——本步没真跑，"
                "上一轮的 %d 条记录还在 target/ 下，别读它"
                % (name, stale[name]["mtime"], args.since, stale[name]["tests"])
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
        if info["failures"] or info["errors"]:
            problems.append(
                "%s：失败 %d / 错误 %d（%s）"
                % (name, info["failures"], info["errors"], ", ".join(info["failed_tests"][:5]))
            )
        if info["tests"] == 0:
            problems.append("%s：报告存在但 tests=0（没有任何用例被执行）" % name)

    receipt = {
        "step": args.label,
        "since": args.since,
        "required": required,
        "channels": fresh,
        "stale_channels": {
            k: {f: v.get(f) for f in ("tests", "mutations", "mtime")} for k, v in stale.items()
        },
        "warnings": list(args.warn or []),
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
                "  ✓ %-46s 变异 %3d / 杀 %3d / %s%%"
                % (name, info["mutations"], info["killed"], info["score"])
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
            print("    %-46s 变异 %3d / %s%%" % (name, info["mutations"], info["score"]))
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

    receipt = {
        "head": args.head,
        "tier": args.tier,
        "steps": [
            {"step": s["step"], "verdict": s["verdict"], "problems": s["problems"]} for s in steps
        ],
        "skip_report": {"total": len(skips), "entries": skips},
        "stale_artifacts": stale_artifacts(args.root),
        "verdict": "green" if all(s["verdict"] == "green" for s in steps) else "red",
    }
    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(receipt, handle, ensure_ascii=False, indent=2)

    print("\n════ 凭证汇总 %s / %s ════" % (args.tier, args.head))
    for step in receipt["steps"]:
        print("  %-28s %s" % (step["step"], step["verdict"].upper()))
    print("  skip 总数：%d" % len(skips))
    for entry in skips:
        print("    - [%s] %s  %s" % (entry["channel"], entry["test"], entry["reason"]))
    for warning in receipt["stale_artifacts"]:
        print("  ⚠ %s" % warning)
    print("  总判定：%s" % receipt["verdict"].upper())
    print("  凭证：%s" % args.out)
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
    snap.add_argument("--warn", action="append", default=[], help="可重复的告警行")
    snap.add_argument("--out")
    snap.set_defaults(func=cmd_snapshot)

    merge = sub.add_parser("merge", help="合并一次 gate 运行的全部 step 快照")
    merge.add_argument("--root", default=".")
    merge.add_argument("--dir", required=True)
    merge.add_argument("--head", required=True)
    merge.add_argument("--tier", required=True)
    merge.add_argument("--out", required=True)
    merge.set_defaults(func=cmd_merge)

    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
