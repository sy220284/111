#!/usr/bin/env python3
"""Adapt Codex's Code Mode Host discovery and add a real Android V8 startup probe.

Only the CI checkout is mutated; the tracked upstream source snapshot stays intact.
Every upstream source anchor must match exactly, preventing silent patch drift.
"""
import argparse
from pathlib import Path

DISCOVERY_ANCHOR = """        if let Some(executable_dir) = executable_dir {
            let executable = executable_dir.join(CODE_MODE_HOST_EXECUTABLE_NAME);"""
ANDROID_DISCOVERY = """        #[cfg(target_os = "android")]
        if let Some(executable_dir) = executable_dir.as_ref() {
            // APK jniLibs extracts native ELF programs next to libcodex_server.so.
            return executable_dir.join("libcodex_code_mode_host.so").into_path_buf();
        }
"""
HOST_CLI_ANCHOR = """    otel_trace_exporter: Option<String>,
}"""
HOST_CLI_ADDITION = """    otel_trace_exporter: Option<String>,

    #[cfg(target_os = "android")]
    #[arg(long, hide = true, conflicts_with_all = ["listen", "otel_trace_listen", "otel_trace_exporter"])]
    android_v8_self_test: bool,
}"""
HOST_MAIN_ANCHOR = """    let cli = Cli::parse();
    if cli.codex_ui_plugin_process {"""
HOST_MAIN_ADDITION = """    let cli = Cli::parse();
    #[cfg(target_os = "android")]
    if cli.android_v8_self_test {
        codex_code_mode_runtime::initialize_v8(codex_code_mode_runtime::V8JitMode::Enabled)
            .map_err(anyhow::Error::msg)?;
        println!("codex-android-v8-initialized");
        return Ok(());
    }
    if cli.codex_ui_plugin_process {"""


def replace_exact(source: str, anchor: str, replacement: str, label: str) -> str:
    if replacement in source:
        return source
    if source.count(anchor) != 1:
        raise ValueError(f"Codex upstream changed {label}; review Android adaptation")
    return source.replace(anchor, replacement, 1)


def adapt_discovery(source: str) -> str:
    return replace_exact(source, DISCOVERY_ANCHOR, ANDROID_DISCOVERY + DISCOVERY_ANCHOR,
                         "Code Mode Host path resolution")


def adapt_host(source: str) -> str:
    source = replace_exact(source, HOST_CLI_ANCHOR, HOST_CLI_ADDITION,
                           "Code Mode Host CLI")
    return replace_exact(source, HOST_MAIN_ANCHOR, HOST_MAIN_ADDITION,
                         "Code Mode Host bootstrap")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="install-context/src/lib.rs")
    parser.add_argument("--host-source", type=Path, required=True,
                        help="code-mode-host/src/main.rs")
    parser.add_argument("--check", action="store_true", help="Validate without writing")
    args = parser.parse_args()
    for path, adapter in (
        (args.source, adapt_discovery),
        (args.host_source, adapt_host),
    ):
        original = path.read_text(encoding="utf-8")
        updated = adapter(original)
        if not args.check and updated != original:
            path.write_text(updated, encoding="utf-8")
    print("Code Mode Host: Android executable discovery and V8 self-test verified")


if __name__ == "__main__":
    main()
