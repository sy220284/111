#!/usr/bin/env python3
"""Make a clean source release and full evidence-backed static graphs."""
import argparse, csv, hashlib, json, shutil
from pathlib import Path

def read(p): return json.loads(Path(p).read_text(encoding="utf-8"))
def table(path,head,records):
    with path.open("w",encoding="utf-8",newline="") as f:
        w=csv.writer(f,delimiter="\t",lineterminator="\n")
        w.writerow(head)
        w.writerows(records)
def esc(s): return str(s).replace("\\","\\\\").replace('"','\\"').replace("\n"," ")
def dot(path,edges):
    with path.open("w",encoding="utf-8") as f:
        f.write("digraph G {\n  rankdir=LR;\n")
        for a,b,kind in edges: f.write('  "'+esc(a)+'" -> "'+esc(b)+'" [label="'+esc(kind)+'"];\n')
        f.write("}\n")
def sha(path):
    h=hashlib.sha256()
    with path.open("rb") as f:
        for b in iter(lambda:f.read(1024*1024),b""):h.update(b)
    return h.hexdigest()

def main():
    p=argparse.ArgumentParser()
    for k in ("graph","module_index","callsites","closure","root","out"):
        p.add_argument("--"+k.replace("_","-"),required=True)
    a=p.parse_args()
    root=Path(a.root).resolve(); out=Path(a.out).resolve()
    report=out/"图谱与证据";report.mkdir(parents=True,exist_ok=True)
    graph=read(a.graph); module=read(a.module_index);calls=read(a.callsites)
    summary=read(root/"resources/function-summary.json")
    ledger=read(root/"resources/function-verification-ledger.json")["entries"]
    s=graph["summary"]
    assert graph["errors"]==[],graph["errors"][:3]
    assert s["modules"]==2553 and summary["modules"]==2551
    assert len(module)==len(calls)==2551 and set(module)==set(calls)
    assert sum(len(x) for x in calls.values())==summary["functions"]
    assert summary["parseFailed"]==summary["missingRelativeImports"]==0
    assert s["recordedSemantics"]==len(ledger)
    m_edges=[(m["file"],d,"静态依赖") for m in graph["modules"] for d in m["dependsOn"]]
    f_edges=[(e["from"],e["to"]["file"]+"#"+e["to"]["symbol"],e["kind"])
             for e in graph["callEdges"] if e["to"]]
    sem_edges=[(e["role"],e["source"]+"#"+e["symbol"],"职责定位") for e in ledger]
    sem_edges += [(e["source"]+"#"+e["symbol"],e["test"],"定向测试") for e in ledger]
    dot(report/"模块依赖全量.dot",m_edges)
    dot(report/"函数调用候选全量.dot",f_edges)
    dot(report/"功能与测试关系全量.dot",sem_edges)
    table(report/"模块索引.tsv",
          ["模块","sha256","字节数","函数数","表达式数","导入"],
          ((file,x["sha256"],x["bytes"],x["functionCount"],x["callCount"],"|".join(x["imports"]))
            for file,x in sorted(module.items())))
    table(report/"模块依赖边.tsv",["来源模块","目标模块","关系"],m_edges)
    table(report/"全部函数索引.tsv",["模块","函数名","行","列","参数数","表达式数"],
          ((file,f["name"],f["line"],f["column"],f["parameters"],len(f["calls"]))
           for file,fs in sorted(calls.items()) for f in fs))
    table(report/"全部调用表达式.tsv",["模块","所在函数","调用目标原始语法","行"],
          ((file,f["name"],c["name"],c["line"])
           for file,fs in sorted(calls.items()) for f in fs for c in f["calls"]))
    table(report/"函数调用候选边.tsv",["调用者","候选模块","候选函数","解析种类","次数","源码证据"],
          ((e["from"],e["to"]["file"],e["to"]["symbol"],e["kind"],e["count"],
            "|".join(x["file"]+":"+str(x["line"])+":"+str(x["column"]) for x in e["evidence"]))
           for e in graph["callEdges"] if e["to"]))
    table(report/"功能职责与测试.tsv",["模块","函数符号","职责","测试文件","状态"],
          ((e["source"],e["symbol"],e["role"],e["test"],e["status"]) for e in ledger))
    layout=read(root/"resources/asset-layout.json")["entries"]
    resources=sorted(x for x in (root/"resources/bunfs").rglob("*") if x.is_file())
    resolved_assets=[]
    for original_key,entry in sorted(layout.items()):
        target=root/entry["path"]
        assert target.is_file(),("missing embedded resource",original_key,entry["path"])
        resolved_assets.append((original_key,str(target.relative_to(root)),target.stat().st_size,sha(target)))
    assert len(resolved_assets)+len(resources)==250,(len(resolved_assets),len(resources))
    table(report/"嵌入资源清单.tsv",["原始BunFS键","真实资源路径","大小","sha256"],resolved_assets)
    table(report/"原始BunFS实体.tsv",["路径","大小","sha256"],
          ((str(f.relative_to(root)),f.stat().st_size,sha(f)) for f in resources))
    rolemap={}
    for e in ledger:rolemap.setdefault(e["role"].split("-")[0],set()).add(e["source"])
    mermaid=["flowchart LR"]
    for i,(domain,files) in enumerate(sorted(rolemap.items())):
        mermaid.append('  D'+str(i)+'["'+esc(domain)+' / '+str(len(files))+' modules"]')
        for j,f in enumerate(sorted(files)):
            if j<5:mermaid.append('  D'+str(i)+' --> F'+str(i)+'_'+str(j)+'["'+esc(f)+'"]')
    mermaid.append("%% 功能领域来源：已有定向测试台账职责前缀，非完整运行时语义。")
    (report/"功能领域概览.mmd").write_text("\n".join(mermaid)+"\n",encoding="utf-8")
    for origin,name in [(a.graph,"完整代码调用图.json"),
                        (a.module_index,"完整模块索引.json"),
                        (a.callsites,"完整函数及调用点.json"),
                        (a.closure,"静态闭环审计.json")]:
        shutil.copy2(origin,report/name)
    for folder in ("src","runtime","bin"):
        shutil.copytree(root/folder,out/folder,dirs_exist_ok=True)
    shutil.copytree(root/"resources",out/"resources",
        dirs_exist_ok=True,ignore=shutil.ignore_patterns("research"))
    for name in ("package.json","package-lock.json","LICENSE"):
        shutil.copy2(root/name,out/name)
    manifest={
        "version":"2.1.310","basis":"sy220284/111 recovered source checkout",
        "notOriginalTypeScript":True,"notELFByteEquivalenceVerified":True,
        "mainModules":summary["modules"],"allGraphModules":s["modules"],
        "functionSyntaxEntities":summary["functions"],"callExpressions":summary["callExpressions"],
        "moduleEdges":len(m_edges),"candidateFunctionEdges":len(f_edges),
        "unresolvedCallExpressions":s["unresolvedCallExpressions"],
        "targetedSemanticEvidence":len(ledger),"sourceFingerprint":summary["sourceFingerprint"],
        "embeddedResourceCount":len(resolved_assets)+len(resources), "mappedResourceCount":len(resolved_assets), "originalBunfsFileCount":len(resources),
        "note":"All syntax calls are indexed; candidate edges are not proven executed. Dynamic dispatch unresolved."
    }
    (report/"结果核验.json").write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    (out/"README.md").write_text(
        "# Claude Code 2.1.310 Linux x64 纯净恢复源码和全量静态图谱\n\n"
        "源码来自 111 仓库已恢复模块，未混入旧版候选替换、历史研究包或原 ELF。\n\n"
        "目录 src、runtime、resources、bin 为可阅读代码和必要运行资源；"
        "图谱与证据包含模块、函数、调用表达式、候选调用边、测试语义关系的完整 TSV、JSON 和 DOT，"
        "另有 Mermaid 功能概览。全量 DOT 可用 Graphviz 打开。\n\n"
        "静态函数候选调用不能证明动态实际调用；间接调用不会编造被调用者。"
        "本包不等于原始 TypeScript，也没有验收 ELF 二进制等价、完整 Bun/JSC 兼容或在线业务等价。\n\n"
        "请查阅 图谱与证据/结果核验.json 了解实际覆盖范围和源码指纹。\n",encoding="utf-8")
    assert len(list((out/"src").glob("*.js")))>=2550
    assert len(resolved_assets)+len(resources)==250,(len(resolved_assets),len(resources))
    assert len(m_edges)>0 and len(f_edges)>0
    print(json.dumps(manifest,ensure_ascii=False))
if __name__=="__main__":main()
