---
name: 111-runtime-inspection
description: Inspect the actual Android Codex runtime, command availability, PATH and working directory when asked to diagnose the execution environment or missing development tools. Never install or modify anything merely to inspect.
---
<!-- 111-managed: runtime-inspection -->

# 本机运行环境自检

先确认当前工作目录、PATH、实际 shell 和文件访问能力。逐项探测命令，不把可选工具缺失当作自检任务失败。不要执行安装、联网或改变权限，除非用户另有明确要求。

使用 POSIX shell，分别报告未安装、可执行、已找到但探测失败；保留真实错误和退出码。不同工具使用其实际支持的版本或最小执行参数，禁止用管道末端状态冒充命令状态。例如：

```sh
printf 'cwd: '; pwd
printf 'PATH: %s\n' "$PATH"
for tool in sh git rg python python3 pip pip3 node npm java javac clang gcc make cmake go rustc cargo ssh curl wget; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        printf '%s: unavailable\n' "$tool"
        continue
    fi
    case "$tool" in
        sh) output=$(sh -c 'printf "shell executable"' 2>&1); rc=$? ;;
        java|javac) output=$("$tool" -version 2>&1); rc=$? ;;
        ssh) output=$(ssh -V 2>&1); rc=$? ;;
        go) output=$(go version 2>&1); rc=$? ;;
        *) output=$("$tool" --version 2>&1); rc=$? ;;
    esac
    printf '%s: probe exit=%s\n%s\n' "$tool" "$rc" "$output"
done
printf 'Inspection finished; assess each probe above.\n'
```

工具不存在时属于能力缺失，而非应用或任务崩溃。找到工具但版本探测失败时，必要时使用文档支持的无副作用探测确认；不得伪造成功。若用户指定必须可用的能力不可用，明确告知该能力验证失败及其影响。应用随包的 git、ripgrep 与系统 PATH、可选开发运行时是不同层次，不能把缺少 Python、Node、Java 等推断为核心故障。对话正文给出能力摘要、具体缺项和影响，原始输出留在可展开的执行记录；不要输出令牌或其他秘密。

缺少 curl/wget 只说明命令行网络客户端未找到；不得据此判定应用网络或安装网络受限。Git 本地提交、Git HTTPS 传输与 SSH 分别验证，`git --version` 不能证明可克隆/拉取/推送。Code Mode 内置 V8 与 Node.js 命令是独立能力；应通过真实 feature/list 和实际 Code Mode 执行回执确认当前启用及运行情况。自检未实际执行联网或安装时，明确标注网络和安装能力未验证。

优先使用签名 APK 内的 Android 预编译工具。Python 包管理使用 `python3 -m pip`，默认只安装 wheel；Node.js 用于 Node API 和 npm，简单 JavaScript 计算优先 Code Mode。curl 与 Git HTTPS 分别验证。只在真实退出码确认后登记可用；本机缺少编译工具链时按授权选择远程执行。

## 项目依赖与远端任务

明确获得安装授权后，先读取项目已有的 requirements、pyproject 或 package-lock 等声明和锁定版本，优先使用项目依赖而非反复进行全局安装。对于 Python 优先采用项目目标目录并通过解释器或明确的 PYTHONPATH 调用，Node 优先在项目目录按锁文件使用 npm ci。保留安装退出码，并用实际 import 或 require 检查功能。Android 共享目录的 POSIX 权限、符号链接与可写目录直接执行受限，使用 python3 或 node 解释脚本；优先兼容的纯 Python wheel、纯 JavaScript 包，需要原生编译器的任务明确提示并选择用户已授权的远端执行环境。不要把远端路径当作手机路径。私有已安装依赖不随便携备份迁移，项目声明保留以便恢复后重建。仅检查环境时仍不得安装或联网。
