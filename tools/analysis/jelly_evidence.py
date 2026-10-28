#!/usr/bin/env python3
"""Import independent Jelly 0.13.0 evidence without mislabeling static edges.

A source SHA256 guard, complete corpus inventory and exact callsite matching
make the second analyzer auditable. It never modifies the primary AST graph.
"""
import csv
import hashlib
import json
import shutil
from collections import Counter
from pathlib import Path


def sha256(file):
    h = hashlib.sha256()
    with Path(file).open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def append_evidence(root, evidence_root, report, graph):
    root, evidence_root, report = map(Path, (root, evidence_root, report))
    unresolved = {(e["file"], int(e["line"]), int(e["column"])): e
                  for e in graph["unresolvedCallsites"]}
    modules = {m["file"] for m in graph["modules"] if m["file"].startswith("")
               and m["file"].endswith(".js")}
    manifests = sorted(evidence_root.glob("111-jelly-corpus-shard-*/summary.json"))
    if not manifests:
        raise ValueError("Jelly 全仓分片分析报告未找到，禁止宣称完成")
    declarations = []
    shards = {}
    for path in manifests:
        m = json.loads(path.read_text(encoding="utf8"))
        if m.get("schema") != 1 or m.get("kind") != "111-independent-points-to-corpus":
            raise ValueError("未知 Jelly 分片格式：" + str(path))
        shard = m["shard"]
        if shard in shards or m["shards"] != len(manifests):
            raise ValueError("重复分片或缺少 Jelly 分片")
        if m["sourceModuleTotal"] != len(modules) or m.get("runtimeCallsConfirmed") != 0:
            raise ValueError("Jelly 源码总量不匹配或不可信的运行时确权")
        if m["selected"] != len(m["sources"]) or m["attempted"] != m["selected"]:
            raise ValueError("Jelly 分片未完整盘点其源码")
        shards[shard] = (path.parent, m)
        declarations.extend(m["sources"])
    if set(shards) != set(range(len(manifests))):
        raise ValueError("Jelly 分片序号不连续")
    if len(declarations) != len(modules) or {s["file"] for s in declarations} != modules:
        raise ValueError("Jelly 扫描输入未覆盖全量源码或重复")
    statuses = []
    for s in declarations:
        file = root / s["file"]
        if not file.is_file() or sha256(file) != s["sha256"]:
            raise ValueError("Jelly 与纯净源码指纹不一致：" + s["file"])
        statuses.append((s["file"], s["status"], s["sha256"], s["batch"]))
    with (report / "Jelly全模块覆盖与缺口.tsv").open("w", encoding="utf8", newline="") as f:
        w = csv.writer(f, delimiter="\t", lineterminator="\n")
        w.writerow(("源码模块", "扫描状态", "SHA256", "所在批次"))
        w.writerows(sorted(statuses))

    out = report / "Jelly跨分析器调用候选.tsv"
    counts = Counter()
    matched_calls = set()
    with out.open("w", encoding="utf8", newline="") as f:
        w = csv.writer(f, delimiter="\t", lineterminator="\n")
        w.writerow(("调用模块", "调用行", "调用列", "调用结束行", "调用结束列",
                    "候选目标模块", "候选目标行", "候选目标列",
                    "候选目标结束行", "候选目标结束列", "与原有111索引关系", "原有未解析原因",
                    "静态来源", "已运行确权"))
        for shard in sorted(shards):
            folder, m = shards[shard]
            graph_file = folder / "call2fun-candidates.tsv"
            if not graph_file.is_file():
                raise ValueError("Jelly 分片缺失调用候选文件：" + str(graph_file))
            with graph_file.open("r", encoding="utf8", newline="") as c:
                reader = csv.reader(c, delimiter="\t")
                next(reader)
                for fields in reader:
                    if len(fields) != 10:
                        raise ValueError("Jelly 候选行格式错误")
                    src, line, col, end_line, end_col, dst, dest_line, dest_col, dest_end_line, dest_end_col = fields
                    if src not in modules or dst not in modules:
                        raise ValueError("Jelly 候选引用了不存在或不应包含的模块")
                    numbers = (line, col, end_line, end_col, dest_line, dest_col, dest_end_line, dest_end_col)
                    if not all(s.isdecimal() and int(s) >= 1 for s in numbers):
                        raise ValueError("Jelly 位置无效")
                    key = (src, int(line), int(col))
                    original = unresolved.get(key)
                    status = "111_unresolved_exact_callsite" if original else "not_in_111_unresolved"
                    reason = original["reason"] if original else ""
                    counts[status] += 1
                    if original:
                        matched_calls.add(key)
                    w.writerow((*fields, status, reason, "jelly_static_points_to", "否"))
    source_success = sum(s[1] == "analyzed_static_candidates" for s in statuses)
    total_edges = sum(m["candidateEdges"] for _, m in shards.values())
    total_unique = sum(m["uniqueCandidateEdges"] for _, m in shards.values())
    if sum(counts.values()) != total_unique:
        raise ValueError("Jelly 原生候选与归一化关系总量不匹配")
    if source_success + sum(s[1] != "analyzed_static_candidates" for s in statuses) != len(modules):
        raise ValueError("Jelly 覆盖状态无法核验")

    # Preserve per-batch native graphs and complete error messages in a separate
    # directory instead of blending them with the original AST graph.
    detail = report / "Jelly原生分片证据"
    detail.mkdir()
    for shard in sorted(shards):
        folder, _ = shards[shard]
        target = detail / ("shard-" + str(shard))
        target.mkdir()
        shutil.copy2(folder / "summary.json", target / "summary.json")
        raw = folder / "raw"
        if raw.exists():
            for item in raw.iterdir():
                if item.is_file() and item.name.endswith((".json.gz", ".partial")):
                    shutil.copy2(item, target / item.name)

    result = {
        "method": "Jelly flow-insensitive approximate points-to, batched --ignore-dependencies",
        "fullCorpusScansAttempted": len(modules),
        "modulesSuccessfullyAnalyzed": source_success,
        "modulesFailedOrPartial": len(modules) - source_success,
        "shards": len(shards),
        "nativeCallToFunctionEdges": total_edges,
        "normalizedUniqueCandidateEdges": total_unique,
        "candidateEdgesMatchingPreviouslyUnresolvedSites": counts["111_unresolved_exact_callsite"],
        "distinctPreviouslyUnresolvedCallsitesWithJellyCandidates": len(matched_calls),
        "candidateEdgesNotMatchingPreviouslyUnresolvedSites": counts["not_in_111_unresolved"],
        "runtimeCallsConfirmed": 0,
        "priorASTGraphUnresolvedUnchanged": graph["summary"]["unresolvedCallExpressions"],
        "crossModuleCoverageCaveat": "Only modules in each batch are analyzed; other imported dependencies are ignored. No whole-program resolution claimed.",
    }
    (report / "Jelly分析校验.json").write_text(
        json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf8")
    return result
