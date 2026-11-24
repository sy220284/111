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
for tool in sh git rg python python3 node npm java javac clang gcc make cmake go rustc cargo ssh curl wget; do
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
