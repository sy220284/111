#!/usr/bin/env python3
"""Classification of Jelly points-to candidates against original AST evidence.

All grades remain STATIC CANDIDATES. Even a unique target matching the
recovered function index is not proof of executed dispatch.
"""
import csv
import json
from collections import Counter, defaultdict
from pathlib import Path


def classify_candidates(report, graph, callsites):
    report = Path(report)
    unresolved = {(e["file"], int(e["line"]), int(e["column"])): e
                  for e in graph["unresolvedCallsites"]}
    indexed = {}
    for file, functions in callsites.items():
        for fn in functions:
            location = (file, int(fn["line"]), int(fn["column"]))
            indexed.setdefault(location, set()).add(fn["name"])
    candidate_file = report / "Jelly跨分析器调用候选.tsv"
    groups = defaultdict(set)
    all_candidates = set()
    with candidate_file.open("r", encoding="utf8", newline="") as file:
        reader = csv.DictReader(file, delimiter="\t")
        required = {"调用模块", "调用行", "调用列", "候选目标模块", "候选目标行",
                    "候选目标列", "与原有111索引关系", "静态来源", "已运行确权"}
        if not required.issubset(reader.fieldnames or []):
            raise ValueError("Jelly evidence fields missing")
        for e in reader:
            if e["静态来源"] != "jelly_static_points_to" or e["已运行确权"] != "否":
                raise ValueError("Jelly candidate evidence misclassified as executed")
            key = (e["调用模块"], int(e["调用行"]), int(e["调用列"]))
            target = (e["候选目标模块"], int(e["候选目标行"]), int(e["候选目标列"]))
            full = key + target
            if full in all_candidates:
                # Multiple intervals can identify the same function. Keep only
                # unique target starts for triage, raw interval evidence stays intact.
                continue
            all_candidates.add(full)
            if e["与原有111索引关系"] == "111_unresolved_exact_callsite":
                if key not in unresolved:
                    raise ValueError("Invalid unresolved callsite tag")
                groups[key].add(target)
            elif e["与原有111索引关系"] != "not_in_111_unresolved":
                raise ValueError("Unknown cross-analyzer classification")
    reason_counts = Counter()
    grades = Counter()
    match_targets = 0
    matched_keys = 0
    by_module = Counter()
    unresolved_count = int(graph["summary"]["unresolvedCallExpressions"])
    if len(unresolved) != unresolved_count:
        raise ValueError("Original AST inventory has collisions or is incomplete")
    with (report / "Jelly未解析调用候选分级.tsv").open("w", encoding="utf8", newline="") as f, \
         (report / "Jelly唯一索引候选待验收.tsv").open("w", encoding="utf8", newline="") as focused:
        w = csv.writer(f, delimiter="\t", lineterminator="\n")
        fw = csv.writer(focused, delimiter="\t", lineterminator="\n")
        header = ("源文件","调用行","调用列","所属函数ID","AST未解析原因","调用语法",
                  "不同候选目标数","命中111函数索引目标数","证据等级",
                  "候选目标位置列表","运行时确权")
        w.writerow(header)
        fw.writerow(header)
        for key in sorted(groups):
            targets = sorted(groups[key])
            match = [t for t in targets if t in indexed]
            matched_keys += 1
            match_targets += len(match)
            e = unresolved[key]
            grade = ("multiple_static_targets" if len(targets) > 1 else
                     "single_indexed_static_candidate" if match else
                     "single_unindexed_static_candidate")
            grades[grade] += 1
            reason_counts[(e["reason"], grade)] += 1
            by_module[key[0]] += 1
            record = (key[0], key[1], key[2], e["owner"], e["reason"],
                      e["callee"], len(targets), len(match), grade,
                      "|".join(f"{x[0]}:{x[1]}:{x[2]}" for x in targets),
                      "否")
            w.writerow(record)
            if grade == "single_indexed_static_candidate":
                fw.writerow(record)
    if sum(grades.values()) != len(groups):
        raise ValueError("Jelly triage inventory mismatch")
    with (report / "Jelly候选分级与未解析原因汇总.tsv").open("w", encoding="utf8", newline="") as f:
        w = csv.writer(f, delimiter="\t", lineterminator="\n")
        w.writerow(("原AST未解析原因","静态证据等级","不同调用点数","运行时确权"))
        for (reason, grade), n in sorted(reason_counts.items()):
            w.writerow((reason, grade, n, "否"))
    with (report / "Jelly候选最多的模块.tsv").open("w", encoding="utf8", newline="") as f:
        w = csv.writer(f, delimiter="\t", lineterminator="\n")
        w.writerow(("模块","原未解析调用中已有Jelly候选的调用点数"))
        w.writerows(sorted(by_module.items(), key=lambda x: (-x[1], x[0])))
    summary = {
        "method": "Jelly independent approximate static callsite->function; single target is not runtime proof",
        "distinctPreviouslyUnresolvedCallsitesWithCandidates": len(groups),
        "singleIndexedStaticCandidateCallsites": grades["single_indexed_static_candidate"],
        "singleUnindexedStaticCandidateCallsites": grades["single_unindexed_static_candidate"],
        "multipleStaticTargetsCallsites": grades["multiple_static_targets"],
        "indexedTargetAppearancesIncludingAmbiguous": match_targets,
        "notYetHavingJellyCandidates": unresolved_count - len(groups),
        "existingASTUnresolvedCountUnchanged": unresolved_count,
        "runtimeCallsConfirmed": 0,
        "gradeCounts": dict(sorted(grades.items())),
        "limitations": "File/line/column matches only, not source-map equivalence, exhaustive dispatch, or observed execution. Batches ignore other dependencies."
    }
    (report / "Jelly候选分级核验.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf8")
    return summary
