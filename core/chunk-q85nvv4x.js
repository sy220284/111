// @bun @bytecode
// Claude Code is a Beta product per Anthropic's Commercial Terms of Service.
// By using Claude Code, you agree that all code acceptance or rejection decisions you make,
// and the associated conversations in context, constitute Feedback under Anthropic's Commercial Terms,
// and may be used to improve Anthropic's products, including training models.
// You are responsible for reviewing any code suggestions before use.

// (c) Anthropic PBC. All rights reserved. Use is subject to the Legal Agreements outlined here: https://code.claude.com/docs/en/legal-and-compliance.

// Version: 2.1.310
import{Ie}from"./chunk-g2mvjkdk.js";import{DS}from"./chunk-y609bddh.js";import{GNe}from"./chunk-5rw2zfa0.js";/**
 * Managed policy modules are withheld from general-purpose hook dispatch
 * for permission-sensitive events. The predicates below retain the 2.1.310 rules.
 * @param {{name: string}} candidate
 */
function isReservedPolicyHookModule(candidate) {
  return candidate.name === GNe && DS(candidate);
}

/**
 * Finds modules eligible to receive a Hook event. Classic hooks and tool
 * describe/call/check omit the privileged managed-policy module.
 * @param {string} eventName
 */
function listModulesForHookDispatch(eventName) {
  const excludesManagedPolicy = eventName.startsWith("classic.") ||
    eventName === "tool.describe" ||
    eventName === "tool.call" ||
    eventName === "tool.check";
  return Ie().loadedModules.filter(candidate =>
    !(excludesManagedPolicy && isReservedPolicyHookModule(candidate)));
}

export {
  isReservedPolicyHookModule as uun,
  listModulesForHookDispatch as HE,
  isReservedPolicyHookModule,
  listModulesForHookDispatch
};
