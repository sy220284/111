// @bun @bytecode
// Claude Code is a Beta product per Anthropic's Commercial Terms of Service.
// By using Claude Code, you agree that all code acceptance or rejection decisions you make,
// and the associated conversations in context, constitute Feedback under Anthropic's Commercial Terms,
// and may be used to improve Anthropic's products, including training models.
// You are responsible for reviewing any code suggestions before use.

// (c) Anthropic PBC. All rights reserved. Use is subject to the Legal Agreements outlined here: https://code.claude.com/docs/en/legal-and-compliance.

// Version: 2.1.310
// Verified 2.1.310 Hook origin and permission matcher functions.
// Keep all legacy exports as aliases to the actual maintainable implementations.
const hookSourcePattern = new RegExp("^(?:settings|(?:plugin|skill)(?::[A-Za-z0-9][A-Za-z0-9._-]{0,63})?)$");
const pluginIdentityPattern = new RegExp("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}@[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

/** @param {unknown} value @returns {string | undefined} */
function parseHookSourceDescriptor(value) {
  return typeof value === "string" && hookSourcePattern.test(value) ? value : undefined;
}

/** @param {unknown} value @returns {string | undefined} */
function parsePluginHookIdentity(value) {
  return typeof value === "string" && pluginIdentityPattern.test(value) ? value : undefined;
}

/**
 * Matches only the current PreToolUse target, or the separate tool.check event.
 * @param {unknown} registration
 * @param {string} toolName
 * @returns {"PreToolUse" | "tool.check" | undefined}
 */
function resolvePreToolUseRegistration(registration, toolName) {
  if (registration === "PreToolUse:" + toolName) return "PreToolUse";
  return registration === "tool.check" ? "tool.check" : undefined;
}

/** @param {unknown} event @returns {"PreToolUse" | "tool.check" | undefined} */
function parseToolPermissionEvent(event) {
  return event === "PreToolUse" || event === "tool.check" ? event : undefined;
}

export {
  parseHookSourceDescriptor as QMn,
  parsePluginHookIdentity as ZMn,
  resolvePreToolUseRegistration as jhs,
  parseToolPermissionEvent as Whs,
  parseHookSourceDescriptor,
  parsePluginHookIdentity,
  resolvePreToolUseRegistration,
  parseToolPermissionEvent
};
