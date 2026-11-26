#!/usr/bin/env python3
"""Run all static rules against one shared source and protocol index."""
import argparse
import json
from pathlib import Path

from static_audit_inputs import AuditInputs, PRODUCTION, KOTLIN_PACKAGE, OFFICIAL
import check_protocol_schema_alignment as schema
import verify_protocol_methods as methods
import check_ui_protocol_matrix as matrix
import sync_ui_protocol_docs as sync
import audit_ui_feature_coverage as coverage
import check_full_feature_workflows as full
import check_interaction_coverage as interactions
import check_kotlin_file_budget as budget
import check_design_system_tokens as tokens
import check_ui_assets as assets
import check_component_system as components


def json_report(out, name, data):
    (out / name).write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def protocol(inputs, out):
    source, contracts = inputs.protocol, inputs.contracts
    errors, stats = schema.verify(
        source, inputs.root / OFFICIAL / "schema/json/v2",
        inputs.text(OFFICIAL / "src/protocol/v2/thread.rs"),
        inputs.text(KOTLIN_PACKAGE / "ProjectRequestContract.kt"),
        inputs.text(KOTLIN_PACKAGE / "CodexConnection.kt"),
        inputs.text(OFFICIAL / "src/protocol/v2/turn.rs"),
        inputs.text(KOTLIN_PACKAGE / "TurnRequestContract.kt"), contracts=contracts,
    )
    json_report(out, "protocol-contract-audit.json", {"statistics": stats, "errors": errors})
    errors = list(errors)
    found, _ = methods.verify_sources(
        source, {str(p.relative_to(inputs.root)): v for p, v in inputs.production.items()},
        contracts=contracts, notifications=inputs.notifications)
    errors.extend(found)
    errors.extend(matrix.verify(inputs.text(KOTLIN_PACKAGE / "UiProtocolMatrix.kt"),
                               inputs.policy_text, source, contracts=contracts, document=inputs.policy))
    errors.extend(matrix.verify_ui_entrypoints(
        inputs.text(KOTLIN_PACKAGE / "AppNavigation.kt"),
        inputs.text(KOTLIN_PACKAGE / "UiFeatureCatalog.kt"), inputs.policy_text,
        files=inputs.production, document=inputs.policy))
    clients = {name for name, c in contracts.items() if c.direction == "client_request"}
    hosts = {name for name, c in contracts.items() if c.direction == "host_request"}
    ledger = Path("docs/接口协议动作清单与落地验收台账-20261122.md")
    expected = {
        Path("docs/第五版接口与界面组件映射矩阵.md"): sync.render_document(inputs.policy),
        KOTLIN_PACKAGE / "UiProtocolMatrix.kt": sync.render_kotlin(inputs.policy),
        ledger: sync.render_ledger(inputs.text(ledger), inputs.policy, clients, hosts),
    }
    errors.extend("协议资产有漂移：" + str(p) for p in sync.stale_artifacts(expected, inputs.text))
    return errors


def functionality(inputs, out):
    report = coverage.audit_sources(inputs.names(inputs.production), inputs.contracts, inputs.policy)
    json_report(out, "ui-coverage.json", report)
    (out / "ui-coverage.md").write_text(coverage.report_markdown(report), encoding="utf-8")
    errors = [f"{field}: {issue}" for field in coverage.CHECK_FIELDS for issue in report[field]]
    report = full.audit(inputs.root, inputs=inputs)
    json_report(out, "full-feature-workflows.json", report)
    (out / "full-feature-workflows.md").write_text(full.markdown(report), encoding="utf-8")
    errors.extend(report["issues"])
    report = interactions.check(inputs.root, inputs=inputs)
    json_report(out, "interaction-coverage.json", report)
    errors.extend(report["errors"])
    return errors


def design(inputs, out):
    files = {str(p.relative_to(inputs.root / PRODUCTION)): v for p, v in inputs.production.items()}
    errors = [f"Kotlin 文件超过 760 行：{p} ({n})" for p, n in budget.oversized_sources(files, 760)]
    visual = inputs.text(KOTLIN_PACKAGE / "VisualSystem.kt")
    asset_json = inputs.text("docs/ui-assets/tokens.json")
    errors.extend(tokens.verify(visual, inputs.text("docs/设计系统.md")))
    errors.extend(tokens.verify_asset_tokens(visual, asset_json))
    errors.extend(tokens.verify_motion_tokens(inputs.text(KOTLIN_PACKAGE / "InteractionSystem.kt"), asset_json))
    findings, report = assets.audit(inputs.root, inputs=inputs)
    json_report(out, "ui-assets-audit.json", report)
    (out / "ui-assets-audit.md").write_text(assets.report_markdown(report), encoding="utf-8")
    errors.extend(findings)
    report = components.audit(inputs.names(inputs.production, stems=True))
    json_report(out, "component-system-audit.json", report)
    errors.extend(report["errors"])
    return errors


def run(root, out):
    inputs = AuditInputs(root)
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    summary = {}
    for name, check in (("protocol", protocol), ("functionality", functionality), ("design", design)):
        try:
            if not inputs.production:
                raise ValueError("缺少 Android 生产源码")
            errors = check(inputs, out)
        except (OSError, ValueError, KeyError, TypeError) as exc:
            errors = [f"{type(exc).__name__}: {exc}"]
        summary[name] = {"status": "failed" if errors else "passed", "errors": errors}
        print(f"{name}: {len(errors)} 个问题")
        for error in errors:
            print(error)
    json_report(out, "static-audit-summary.json", summary)
    return int(any(v["errors"] for v in summary.values()))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, default=Path("."))
    parser.add_argument("--output", type=Path, default=Path("."))
    args = parser.parse_args()
    return run(args.repository, args.output)


if __name__ == "__main__":
    raise SystemExit(main())
