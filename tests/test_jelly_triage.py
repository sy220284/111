import csv
import json
import tempfile
import unittest
from pathlib import Path
from sys import path as python_path
python_path.insert(0, str(Path(__file__).resolve().parents[1] / "tools" / "analysis"))
from jelly_triage import classify_candidates

FIELDS=("调用模块","调用行","调用列","候选目标模块","候选目标行",
        "候选目标列","与原有111索引关系","静态来源","已运行确权")
CANDIDATES = [
 ("a.js","2","3","b.js","8","5","111_unresolved_exact_callsite","jelly_static_points_to","否"),
 ("a.js","3","3","b.js","8","5","111_unresolved_exact_callsite","jelly_static_points_to","否"),
 ("a.js","3","3","a.js","4","1","111_unresolved_exact_callsite","jelly_static_points_to","否"),
 ("a.js","4","3","b.js","9","5","111_unresolved_exact_callsite","jelly_static_points_to","否"),
 ("a.js","7","3","b.js","8","5","not_in_111_unresolved","jelly_static_points_to","否"),
]

class TriageTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.report = Path(self.tmp.name)
        self.graph = {
          "summary":{"unresolvedCallExpressions":4},
          "unresolvedCallsites":[
            {"file":"a.js","line":i,"column":3,"reason":"property_or_namespace_unresolved",
             "owner":"a.js#f","callee":"obj.call"}
            for i in (2,3,4,5)]
        }
        self.functions={"b.js":[{"name":"realFn","line":8,"column":5}]}
        with (self.report / "Jelly跨分析器调用候选.tsv").open("w",encoding="utf8",newline="") as f:
            w=csv.writer(f,delimiter="\t")
            w.writerow(FIELDS)
            w.writerows(CANDIDATES)
    def tearDown(self): self.tmp.cleanup()
    def test_indexed_unique_ambiguous_and_unindexed_are_separate(self):
        result=classify_candidates(self.report,self.graph,self.functions)
        self.assertEqual(result["distinctPreviouslyUnresolvedCallsitesWithCandidates"],3)
        self.assertEqual(result["singleIndexedStaticCandidateCallsites"],1)
        self.assertEqual(result["multipleStaticTargetsCallsites"],1)
        self.assertEqual(result["singleUnindexedStaticCandidateCallsites"],1)
        self.assertEqual(result["notYetHavingJellyCandidates"],1)
        self.assertEqual(result["runtimeCallsConfirmed"],0)
        self.assertEqual(result["existingASTUnresolvedCountUnchanged"],4)
        self.assertEqual(result["indexedTargetAppearancesIncludingAmbiguous"],2)
        rows=list(csv.DictReader((self.report/"Jelly唯一索引候选待验收.tsv").open(encoding="utf8"),delimiter="\t"))
        self.assertEqual(len(rows),1)
        self.assertEqual(rows[0]["运行时确权"],"否")
        self.assertEqual(rows[0]["调用行"],"2")
    def test_disallows_claiming_unverified_jelly_candidates_are_executed(self):
        s=(self.report/"Jelly跨分析器调用候选.tsv").read_text(encoding="utf8")
        (self.report/"Jelly跨分析器调用候选.tsv").write_text(s.replace("jelly_static_points_to\t否","jelly_static_points_to\t是"),encoding="utf8")
        with self.assertRaisesRegex(ValueError,"misclassified"):classify_candidates(self.report,self.graph,self.functions)
    def test_current_function_index_and_jelly_use_the_same_module_path(self):
        # The real recovered function-index file stores top-level source keys
        # as "chunk-...js", while Jelly locations include "".
        self.functions={"b.js":[{"name":"realFn","line":8,"column":5}]}
        result=classify_candidates(self.report,self.graph,self.functions)
        self.assertEqual(result["singleIndexedStaticCandidateCallsites"],1)
        self.assertEqual(result["functionIndexMainModulePathsNormalized"],0)
        self.assertEqual(result["runtimeCallsConfirmed"],0)

    def test_same_position_nested_ast_calls_are_ambiguous_even_with_one_jelly_target(self):
        # Nested calls f()() can share the same AST start location.
        self.graph["unresolvedCallsites"].append({
            "file":"a.js","line":2,"column":3,
            "reason":"local_binding_or_parameter",
            "owner":"a.js#nested","callee":"f()"})
        self.graph["summary"]["unresolvedCallExpressions"] += 1
        result=classify_candidates(self.report,self.graph,self.functions)
        self.assertEqual(result["distinctPreviouslyUnresolvedCallsitesWithCandidates"],3)
        self.assertEqual(result["ambiguousASTLocationCallsites"],1)
        self.assertEqual(result["originalASTPositionsWithMultipleExpressions"],1)
        self.assertEqual(result["originalASTExpressionsAtAmbiguousPositions"],2)
        self.assertEqual(result["singleIndexedStaticCandidateCallsites"],0)
        self.assertEqual(result["runtimeCallsConfirmed"],0)
        self.assertEqual(result["originalASTDistinctUnresolvedPositions"],4)
        rows=list(csv.DictReader(
            (self.report/"Jelly未解析调用候选分级.tsv").open(encoding="utf8"),
            delimiter="\t"))
        self.assertEqual(rows[0]["证据等级"],"ambiguous_ast_callsite_location")
        self.assertEqual(rows[0]["原AST同位置调用表达式数"],"2")
        focused=list(csv.DictReader(
            (self.report/"Jelly唯一索引候选待验收.tsv").open(encoding="utf8"),
            delimiter="\t"))
        self.assertEqual(focused,[])

    def test_mistagged_jelly_unresolved_position_is_rejected(self):
        contents=(self.report/"Jelly跨分析器调用候选.tsv").read_text(encoding="utf8")
        contents=contents.replace(
            "a.js\t2\t3\tb.js\t8\t5\t111_unresolved_exact_callsite",
            "a.js\t2\t3\tb.js\t8\t5\tnot_in_111_unresolved")
        (self.report/"Jelly跨分析器调用候选.tsv").write_text(contents,encoding="utf8")
        with self.assertRaisesRegex(ValueError,"incorrectly tagged"):
            classify_candidates(self.report,self.graph,self.functions)

    def test_unresolved_source_tag_must_be_present_in_primary_graph(self):
        s=(self.report/"Jelly跨分析器调用候选.tsv").read_text(encoding="utf8")
        (self.report/"Jelly跨分析器调用候选.tsv").write_text(s.replace("a.js\t7\t3\tb.js\t8\t5\tnot_in_111_unresolved","a.js\t7\t3\tb.js\t8\t5\t111_unresolved_exact_callsite"),encoding="utf8")
        with self.assertRaisesRegex(ValueError,"Invalid unresolved"):classify_candidates(self.report,self.graph,self.functions)

if __name__=="__main__":unittest.main()
