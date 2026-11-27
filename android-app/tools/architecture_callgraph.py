"""Conservative Kotlin source relationship candidates; compiler remains authoritative."""
import collections
import re

DECL = re.compile(r"\b(?:class|interface|object|typealias)\s+([A-Za-z_]\w*)|\bfun\s+(?:<[^>\n]+>\s*)?(?:[\w?.<>]+\.)?([A-Za-z_]\w*)\s*\(")
PKG = re.compile(r"(?m)^\s*package\s+([\w.]+)")
IMPORT = re.compile(r"(?m)^\s*import\s+([\w.]+)(?:\s+as\s+(\w+))?")
CALL = re.compile(r"\b([A-Za-z_]\w*)\s*[(\{]|::\s*([A-Za-z_]\w*)")
TRIVIA = re.compile(r'"""[\s\S]*?"""|/\*[\s\S]*?\*/|//[^\n]*|"(?:\\.|[^"\\])*"')

def scan(inputs):
    records = {}
    symbols = collections.defaultdict(set)
    for file, raw in sorted(inputs.production.items()):
        path = str(file.relative_to(inputs.root))
        source = TRIVIA.sub(" ", raw)
        pkg = PKG.search(source)
        package = pkg.group(1) if pkg else ""
        declarations = sorted({a or b for a,b in DECL.findall(source)})
        records[path] = {"package":package, "declarations":declarations, "imports":IMPORT.findall(source),
                         "tokens":sorted({a or b for a,b in CALL.findall(source)}), "call_candidates":[], "import_edges":[]}
        for name in declarations:
            symbols[package+"."+name].add(path)
    for path,row in records.items():
        aliases = {alias or name.rsplit(".",1)[-1]:name for name,alias in row["imports"]}
        row["import_edges"] = sorted({target for name in aliases.values() for target in symbols.get(name,()) if target != path})
        candidates=set()
        for token in row.pop("tokens"):
            if token in row["declarations"]:
                continue
            targets=symbols.get(aliases.get(token,row["package"]+"."+token),set())-{path}
            if len(targets)==1:
                candidates.update(targets)
        row["call_candidates"]=sorted(candidates)
    graph={p:set(r["import_edges"])|set(r["call_candidates"]) for p,r in records.items()}
    visited=set()
    order=[]
    for root in graph:
        if root in visited:
            continue
        stack=[(root,False)]
        while stack:
            node,done=stack.pop()
            if done:
                order.append(node)
            elif node not in visited:
                visited.add(node)
                stack.append((node,True))
                stack.extend((child,False) for child in sorted(graph[node],reverse=True) if child not in visited)
    reverse={p:set() for p in graph}
    for src,targets in graph.items():
        for target in targets:
            reverse[target].add(src)
    visited.clear()
    cycles=[]
    for root in reversed(order):
        if root in visited:
            continue
        group=set()
        stack=[root]
        while stack:
            node=stack.pop()
            if node in visited:
                continue
            visited.add(node)
            group.add(node)
            stack.extend(reverse[node]-visited)
        if len(group)>1:
            cycles.append(sorted(group))
    return {"scope":"Android production Kotlin sources","kind":"static_candidates_only",
            "source_files":len(records),"edge_count":sum(map(len,graph.values())),
            "cycle_candidates":sorted(cycles),
            "unreferenced_candidates":sorted(p for p,targets in reverse.items() if not targets),
            "files":records}
