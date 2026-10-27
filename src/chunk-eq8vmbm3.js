// @bun @bytecode
// Claude Code is a Beta product per Anthropic's Commercial Terms of Service.
// By using Claude Code, you agree that all code acceptance or rejection decisions you make,
// and the associated conversations in context, constitute Feedback under Anthropic's Commercial Terms,
// and may be used to improve Anthropic's products, including training models.
// You are responsible for reviewing any code suggestions before use.

// (c) Anthropic PBC. All rights reserved. Use is subject to the Legal Agreements outlined here: https://code.claude.com/docs/en/legal-and-compliance.

// Version: 2.1.310
// Verified 2.1.310 external template lookup functions.
// The original module has an initially empty in-memory registry; do not
// synthesize template policy decisions from generic API documentation.
let externalTemplates;
let templateSource;

/** Initialize or return the cached external template registry. */
function externalTemplateRegistry() {
  if (externalTemplates !== undefined && templateSource === "external")
    return externalTemplates;
  templateSource = "external";
  externalTemplates = [];
  return externalTemplates;
}

/** List stable IDs without mutating the cached template registry. */
function listExternalTemplateIds() {
  return externalTemplateRegistry().map(template => template.id);
}

/** Resolve the first registered digest matching the supplied SHA256. */
function findExternalTemplateByDigest(sha256) {
  for (const template of externalTemplateRegistry()) {
    const digest = template.digests.find(item => item.sha256 === sha256);
    if (digest) return { template, label: digest.label };
  }
  return undefined;
}

/** Resolve one registered template by its exact ID. */
function findExternalTemplateById(id) {
  return externalTemplateRegistry().find(template => template.id === id);
}

/** True only for the exact PreToolUse permission-hook event. */
function isPreToolUseTemplateEvent(event) {
  return event.event === "PreToolUse";
}

/**
 * Original always-true predicate. Its caller-specific policy meaning remains
 * unverified, so retain the constant behavior instead of inventing checks.
 */
function externalTemplateConstantFlag() {
  return true;
}

export {
  listExternalTemplateIds as Vds,
  findExternalTemplateByDigest as qds,
  findExternalTemplateById as Oat,
  isPreToolUseTemplateEvent as Nxn,
  externalTemplateConstantFlag as $xn,
  listExternalTemplateIds,
  findExternalTemplateByDigest,
  findExternalTemplateById,
  isPreToolUseTemplateEvent,
  externalTemplateConstantFlag
};
