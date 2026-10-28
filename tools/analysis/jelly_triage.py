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
    # TS AST permits nested calls with the same start position: f()() is
    # two call expressions with one file:line:column. A dictionary assignment
    # silently overwrites the second call, so distinguish ambiguous positions.
    original_entries = list(graph["unresolvedCallsites"])
    unresolved = defaultdict(list)
    for entry in original_entries:
        key = (entry["file"], int(entry["line"]), int(entry["column"]))
        unresolved[key].append(entry)
    indexed = {}
    index_normalized = 0
    for file, functions in callsites.items():
        # scripts/source-function-index.cjs indexes root src files as
        # "chunk-abc.js", whereas Jelly / code-graph use "src/chunk-abc.js".
        # A nested worker already has its "src/" prefix.
        normalized_file = file if file.startswith("src/") else "src/" + file
        if (not normalized_file.startswith("src/") or
                ".." in normalized_file.split("/") or
                "\\\\" in normalized_file or
                not normalized_file.endswith(".js")):
            raise ValueError("Invalid recovered function index file: " + file)
        if normalized_file != file:
            index_normalized += 1
        for fn in functions:
            location = (normalized_file, int(fn["line"]), int(fn["column"]))
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
            tagged = e["与原有111索引关系"]
            if tagged == "111_unresolved_exact_callsite":
                if key not in unresolved:
                    raise ValueError("Invalid unresolved callsite tag")
            elif tagged == "not_in_111_unresolved":
                if key in unresolved:
                    raise ValueError("Jelly matching source incorrectly tagged as unmatched")
            else:
                raise ValueError("Unknown cross-analyzer classification")
            full = key + target
            if full in all_candidates:
                # Multiple intervals may identify one function; raw interval
                # evidence remains in the unmodified original candidate file.
                continue
            all_candidates.add(full)
            if tagged == "111_unresolved_exact_callsite":
                groups[key].add(target)
    reason_counts = Counter()
    grades = Counter()
    match_targets = 0
    by_module = Counter()
    unresolved_count = int(graph["summary"]["unresolvedCallExpressions"])
    if len(original_entries) != unresolved_count:
        raise ValueError("Original AST inventory is incomplete")
    duplicate_positions = sum(len(v) > 1 for v in unresolved.values())
    ambiguous_expressions = sum(len(v) for v in unresolved.values() if len(v) > 1)
    with (report / "Jelly未解析调用候选分级.tsv").open("w", encoding="utf8", newline="") as f, \
         (report / "Jelly唯一索引候选待验收.tsv").open("w", encoding="utf8", newline="") as focused:
        w = csv.writer(f, delimiter="\t", lineterminator="\n")
        fw = csv.writer(focused, delimiter="\t", lineterminator="\n")
        header = ("源文件","调用行","调用列","所属函数ID","AST未解析原因","调用语法",
                  "不同候选目标数","命中111函数索引目标数","证据等级",
                  "候选目标位置列表","运行时确权","原AST同位置调用表达式数")
        w.writerow(header)
        fw.writerow(header)
        for key in sorted(groups):
            targets = sorted(groups[key])
            match = [t for t in targets if t in indexed]
            match_targets += len(match)
            originals = unresolved[key]
            e = originals[0]
            grade = ("ambiguous_ast_callsite_location" if len(originals) != 1 else
                     "multiple_static_targets" if len(targets) > 1 else
                     "single_indexed_static_candidate" if match else
                     "single_unindexed_static_candidate")
            grades[grade] += 1
            for reason in {item["reason"] for item in originals}:
                reason_counts[(reason, grade)] += 1
            by_module[key[0]] += 1
            record = (key[0], key[1], key[2],
                      "|".join(sorted({item["owner"] for item in originals})),
                      "|".join(sorted({item["reason"] for item in originals})),
                      "|".join(sorted({item["callee"] for item in originals})),
                      len(targets), len(match), grade,
                      "|".join(f"{x[0]}:{x[1]}:{x[2]}" for x in targets),
                      "否",len(originals))
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
        "ambiguousASTLocationCallsites": grades["ambiguous_ast_callsite_location"],
        "originalASTPositionsWithMultipleExpressions": duplicate_positions,
        "originalASTExpressionsAtAmbiguousPositions": ambiguous_expressions,
        "indexedTargetAppearancesIncludingAmbiguous": match_targets,
        "functionIndexMainModulePathsNormalized": index_normalized,
        "notYetHavingJellyCandidates": unresolved_count - len(groups),
        "originalASTDistinctUnresolvedPositions": len(unresolved),
        "positionsWithoutJellyCandidates": len(unresolved) - len(groups),
        "existingASTUnresolvedCountUnchanged": unresolved_count,
        "runtimeCallsConfirmed": 0,
        "gradeCounts": dict(sorted(grades.items())),
        "limitations": "Same-start nested AST calls are ambiguous and excluded from unique candidates. File/line/column matches only, not source-map equivalence, exhaustive dispatch, or observed execution. Batches ignore other dependencies."
    }
    (report / "Jelly候选分级核验.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf8")
    return summary
