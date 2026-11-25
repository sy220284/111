#!/usr/bin/env python3
"""核验每份设计素材、原生资源、真实调用与设备测试声明；输出可追溯报告。"""
import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from check_component_system import without_comments_and_literals

ANDROID = "{http://schemas.android.com/apk/res/android}"
PACKAGE = "io/github/sy220284/codexandroid"
TEST_RE = re.compile(r"\bfun\s+(\w+)\s*\(")
PATH_TOKEN = re.compile(r"[A-Za-z]|[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?")

def tag(node):
    return node.tag.rsplit("}", 1)[-1]

def path_tokens(value):
    return [float(t) if t[0] in "+-.0123456789" else t for t in PATH_TOKEN.findall(value)]

def number(n):
    return format(n, "g")

def icon_paths(svg):
    result = []
    if svg.attrib.get("viewBox") != "0 0 24 24":
        raise ValueError("SVG 必须使用 24×24 画布")
    for name, expected in (("fill", "none"), ("stroke-width", "1.8"), ("stroke-linecap", "round"), ("stroke-linejoin", "round")):
        if svg.attrib.get(name) != expected:
            raise ValueError("图标规范错误：" + name)
    for child in svg:
        name, attr = tag(child), child.attrib
        if name == "path":
            result.append(attr["d"])
        elif name == "circle":
            x, y, z = (float(attr[k]) for k in ("cx", "cy", "r"))
            result.append("M%s %sa%s %s 0 1 0 %s 0a%s %s 0 1 0 -%s 0Z" %
                          tuple(map(number, (x-z, y, z, z, 2*z, z, z, 2*z))))
        elif name == "rect":
            x, y, w, h = (float(attr[k]) for k in ("x", "y", "width", "height"))
            k = float(attr["rx"])
            result.append("M%s %sh%sq%s 0 %s %sv%sq0 %s -%s %sh-%sq-%s 0 -%s -%sv-%sq0 -%s %s -%sZ" %
                          tuple(map(number, (x+k,y,w-2*k,k,k,k,h-2*k,k,k,k,w-2*k,k,k,k,h-2*k,k,k,k))))
        else:
            raise ValueError("不支持的图标几何：" + name)
    if not result:
        raise ValueError("空图标")
    return result

def verify_icon(svg, vector):
    for name, val in (("width", "24dp"), ("height", "24dp"), ("viewportWidth", "24"), ("viewportHeight", "24")):
        if vector.attrib.get(ANDROID+name) != val:
            raise ValueError("Android 图标尺寸错误：" + name)
    native = list(vector)
    original = icon_paths(svg)
    if len(native) != len(original):
        raise ValueError("SVG / VectorDrawable 路径数量不一致")
    for src, dst in zip(original, native):
        if tag(dst) != "path" or path_tokens(src) != path_tokens(dst.attrib[ANDROID+"pathData"]):
            raise ValueError("SVG / VectorDrawable 路径几何不一致")
        for key, val in (("strokeWidth", "1.8"), ("strokeLineCap", "round"), ("strokeLineJoin", "round"), ("fillColor", "#00000000")):
            if dst.attrib.get(ANDROID+key) != val:
                raise ValueError("原生图标规范错误：" + key)

def color(value):
    if value is None or value in ("none", "#00000000", "@android:color/transparent"):
        return "none"
    if len(value) == 9 and value[:3].upper() == "#FF":
        return "#" + value[3:].upper()
    return value.upper()

def verify_artwork(svg, vector):
    if svg.attrib.get("viewBox") != "0 0 152 116":
        raise ValueError("状态插画画布尺寸错误")
    if vector.attrib.get(ANDROID+"viewportWidth") != "152" or vector.attrib.get(ANDROID+"viewportHeight") != "116":
        raise ValueError("Android 插画画布尺寸错误")
    if len(svg) != len(vector) or not len(svg):
        raise ValueError("插画路径数量不一致或为空")
    for a,b in zip(svg,vector):
        if tag(a) != "path" or tag(b) != "path" or path_tokens(a.attrib["d"]) != path_tokens(b.attrib[ANDROID+"pathData"]):
            raise ValueError("插画路径几何不一致")
        for x,y in (("fill","fillColor"),("stroke","strokeColor")):
            if color(a.attrib.get(x)) != color(b.attrib.get(ANDROID+y)):
                raise ValueError("插画配色不一致：" + x)
        if a.attrib.get("stroke"):
            for x,y,default in (("stroke-width","strokeWidth","1"),("stroke-linecap","strokeLineCap","butt"),("stroke-linejoin","strokeLineJoin","miter")):
                if a.attrib.get(x,default) != b.attrib.get(ANDROID+y,default):
                    raise ValueError("插画描边不一致：" + x)

def invalid_glyph_namespace(source):
    """CodexGlyph is an application enum, never an Android/Compose vector type."""
    return bool(re.search(r"\bandroidx?\.[\w.]*\bCodexGlyph\b", source))

def source_marker_lines(source, marker):
    """Exact source evidence, excluding comments and text literals."""
    code = without_comments_and_literals(source)
    pattern = r"(?<!\w)" + re.escape(marker) + r"(?!\w)" if re.fullmatch(r"\w+", marker) else re.escape(marker)
    return [code.count("\n", 0, hit.start()) + 1 for hit in re.finditer(pattern, code)]


def audit(root):
    errors, assets = [], []
    design = root/"docs/ui-assets"
    src = root/"android-app/app/src/main/java"
    drawable = root/"android-app/app/src/main/res/drawable"
    test_root = root/"android-app/app/src/androidTest/java"
    manifest = json.loads((design/"素材接入清单.json").read_text(encoding="utf-8"))
    sources = {x.stem:x.read_text(encoding="utf-8") for x in src.rglob("*.kt")}
    tests = {x.stem:set(TEST_RE.findall(x.read_text(encoding="utf-8"))) for x in test_root.rglob("*.kt")}
    def test_ref(ref):
        parts = ref.split(".")
        if len(parts) != 2 or parts[1] not in tests.get(parts[0], set()):
            errors.append("不存在的测试声明："+ref)
    def source_ref(file, marker):
        lines = source_marker_lines(sources.get(file, ""), marker)
        if not lines:
            candidates = [name for name, code in sources.items() if name != file and source_marker_lines(code, marker)]
            hint = "；实际候选："+", ".join(candidates[:8]) if candidates else "；生产源码中已不存在"
            errors.append("缺少源码证据："+file+"."+marker+hint)
        return lines
    def matching(pattern, exclude=()):
        return {name:[i for i,l in enumerate(text.splitlines(),1) if re.search(pattern,l)]
                for name,text in sources.items() if name not in exclude and re.search(pattern,text)}
    def checked_xml(path, action):
        try:
            action(ET.parse(path).getroot())
        except (ValueError, KeyError, OSError, ET.ParseError) as e:
            errors.append(str(path.relative_to(root))+": "+str(e))

    routes = manifest["routes"]
    route_ids = set(re.findall(r'^\s*Page\("([^"]+)"', sources.get("AppNavigation",""), re.M))
    listed = [x["route"] for x in routes]
    if set(listed) != route_ids or len(set(listed)) != len(listed):
        duplicates = sorted({route for route in listed if listed.count(route) > 1})
        errors.append("路由接入清单必须覆盖真实 AppNavigation 路由且无重复；新增未登记："+str(sorted(route_ids-set(listed)))+"；已删除仍登记："+str(sorted(set(listed)-route_ids))+"；重复："+str(duplicates))
    for entry in routes:
        for file in entry["sources"]:
            if file not in sources:
                errors.append("缺少路由源文件："+entry["route"]+":"+file)
        if not entry["business"] or not entry["tests"]:
            errors.append("缺少业务或验收声明："+entry["route"])
        for test in entry["tests"]:
            test_ref(test)
    def business_for(files):
        result = [x["title"]+"："+x["business"] for x in routes if set(x["sources"]) & set(files)]
        if "AppNavigation" in files:
            result.append("统一导航：页面、侧栏和工作区入口")
        if not result:
            result.append("共享组件：页面及上下文容器使用")
        return result

    for name,text in sources.items():
        if invalid_glyph_namespace(text):
            errors.append("原生图标类型引用了错误的 Android/Compose 命名空间："+name)
        if re.search(r"\bIcons\.\w+\.|import androidx\.compose\.material\.icons\.|\bImageVector\b",text):
            errors.append("系统图标直接引用："+name)
        if name != "CodexIcon" and re.search(r"(?<!\w)Icon\s*\(",text):
            errors.append("绕开统一图标入口："+name)
        if re.search(r"\b(?:ModalBottomSheet|BottomSheetScaffold|CodexSheetFrame|rememberModalBottomSheetState)\s*\(",text):
            errors.append("生产界面存在已废弃底部弹窗："+name)
        if name != "CodexSurfaceFrames" and re.search(r"\b(?:AlertDialog|DropdownMenu)\s*\(",text):
            errors.append("绕开共享弹层或菜单："+name)
    registry = dict(re.findall(r"^\s+(\w+)\(R\.drawable\.(ic_ui_\w+)\),?\s*$",sources.get("CodexIcon",""),re.M))
    used = set(re.findall(r"\bCodexGlyph\.(\w+)", "\n".join(sources.values())))
    for glyph in sorted(used-set(registry)):
        errors.append("未注册图标："+glyph)
    icon_files = {x.stem.replace("-","_"):x for x in (design/"icons").glob("*.svg")}
    if set(registry.values()) != {"ic_ui_"+x for x in icon_files}:
        errors.append("图标注册表与 SVG 源文件不是一一对应")
    if set(registry.values()) != {x.stem for x in drawable.glob("ic_ui_*.xml")}:
        errors.append("图标注册表与 Android 矢量资源不是一一对应")
    test_ref(manifest["iconTest"])
    for glyph, res in sorted(registry.items()):
        callsites = matching(r"\bCodexGlyph\."+re.escape(glyph)+r"\b", ("CodexIcon",))
        if not callsites:
            errors.append("未接入生产页面的图标："+glyph)
        original = icon_files.get(res.removeprefix("ic_ui_"))
        if original:
            checked_xml(original, lambda svg: verify_icon(svg,ET.parse(drawable/(res+".xml")).getroot()))
        assets.append({"asset":str(original.relative_to(root)) if original else res,"resource":res,"component":"CodexIcon","callsites":callsites,"business":business_for(callsites),"tests":[manifest["iconTest"]]})
    art_paths = set()
    resources = set()
    for entry in manifest["artwork"]:
        res, name = entry["resource"],entry["asset"]
        test_ref(entry["test"])
        callsites = matching(r"\bR\.drawable\."+re.escape(res)+r"\b", ("CodexStateArtwork",))
        if not callsites:
            errors.append("未接入生产页面的插画："+name)
        source_ref("CodexStateArtwork","R.drawable."+res+" -> R.drawable."+res+"_dark")
        for theme,suffix in (("", ""),("dark/","_dark")):
            original=design/"illustrations"/theme/(name+".svg")
            art_paths.add(original)
            resources.add(res+suffix)
            checked_xml(original, lambda svg,suffix=suffix:verify_artwork(svg,ET.parse(drawable/(res+suffix+".xml")).getroot()))
            assets.append({"asset":str(original.relative_to(root)),"resource":res+suffix,"component":"CodexStateArtwork","callsites":callsites,"business":[entry["business"]]+business_for(callsites),"tests":[entry["test"]]})
    if art_paths != set((design/"illustrations").rglob("*.svg")):
        errors.append("插画源文件有遗漏或未登记")
    if resources != {x.stem for x in drawable.glob("ic_empty_*.xml")}:
        errors.append("原生状态插画有遗漏或未登记")
    atlas_files = set()
    for entry in manifest["atlases"]:
        original=design/entry["asset"]
        atlas_files.add(original)
        checked_xml(original, lambda svg: None if tag(svg)=="svg" and len(svg) else (_ for _ in ()).throw(ValueError("空图谱")))
        bindings = {}
        for file,marker in entry["components"]:
            identity = file+"."+marker
            if identity in bindings:
                errors.append("图谱组件重复登记："+entry["asset"]+":"+identity)
            bindings[identity] = source_ref(file,marker)
        for test in entry["tests"]:
            test_ref(test)
        assets.append({"asset":str(original.relative_to(root)),"component":"设计参考图谱（不作运行时贴图）","callsites":bindings,"business":[entry["business"]],"tests":entry["tests"]})
    actual_atlases = set(design.glob("*.svg"))
    if atlas_files != actual_atlases:
        errors.append("设计图谱有遗漏或未登记；新增未登记："+str(sorted(x.name for x in actual_atlases-atlas_files))+"；缺失仍登记："+str(sorted(x.name for x in atlas_files-actual_atlases)))
    return errors, {"status":"failed" if errors else "source_verified","figma":manifest["figma"],"route_count":len(routes),"icon_count":len(registry),"artwork_count":len(manifest["artwork"]),"asset_count":len(assets),"routes":routes,"assets":assets,"errors":errors,"evidence":"静态核查与设备测试声明；真实设备结果以当前 CI 测试报告及截图为准"}

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository",type=Path,default=Path("."))
    parser.add_argument("--report",type=Path,default=Path("ui-assets-audit.json"))
    parser.add_argument("--markdown",type=Path,default=Path("ui-assets-audit.md"))
    args=parser.parse_args()
    errors,report=audit(args.repository.resolve())
    args.report.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    lines=["# UI 素材接入验收","",report["evidence"],"",f"路由 {report['route_count']}；图标 {report['icon_count']}；插画 {report['artwork_count']} 类。","","| 素材 | 共享组件 | 实际调用位置 | 业务 | 测试声明 |","| --- | --- | --- | --- | --- |"]
    for row in report["assets"]:
        lines.append("| "+" | ".join([row["asset"],row["component"],", ".join(row["callsites"]),"; ".join(row["business"]),"; ".join(row["tests"])])+" |")
    lines.extend(["","## 问题"]+[("- "+x) for x in errors] if errors else ["","静态检查通过。"])
    args.markdown.write_text("\n".join(lines)+"\n",encoding="utf-8")
    print(f"素材检查：{report['icon_count']} 图标 / {report['artwork_count']} 类插画 / {report['route_count']} 路由；{len(errors)} 个问题")
    for error in errors:
        print(error,file=sys.stderr)
    return bool(errors)

if __name__=="__main__":
    sys.exit(main())
