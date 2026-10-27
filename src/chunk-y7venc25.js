// @bun @bytecode
// Claude Code is a Beta product per Anthropic's Commercial Terms of Service.
// By using Claude Code, you agree that all code acceptance or rejection decisions you make,
// and the associated conversations in context, constitute Feedback under Anthropic's Commercial Terms,
// and may be used to improve Anthropic's products, including training models.
// You are responsible for reviewing any code suggestions before use.

// (c) Anthropic PBC. All rights reserved. Use is subject to the Legal Agreements outlined here: https://code.claude.com/docs/en/legal-and-compliance.

// Version: 2.1.310
// Readable reconstruction of the exact 2.1.310 command-routing predicates.
// Preserve the original non-REPL subcommand set and all public export names.
const NON_REPL_SUBCOMMANDS = new Set(["update","upgrade","doctor","forward-home-settings"]);

/** The 2.1.310 build has no project-verb invocation route. */
function isProjectVerbInvocation(argv) {
  return false;
}

/** Match the first "mcp" token and require its immediate "serve" follower. */
function isMcpServeInvocation(argv) {
  const index = argv.indexOf("mcp");
  return index !== -1 && argv[index + 1] === "serve";
}

/** Recognize the agents command with a later --json flag. */
function isAgentsJsonInvocation(argv) {
  const index = argv.indexOf("agents");
  return index !== -1 && argv.includes("--json", index + 1);
}

/** Recognize "plugin eval" and its "plugins eval" plural spelling. */
function isPluginEvalInvocation(argv) {
  return argv.some((part, index) =>
    (part === "plugin" || part === "plugins") && argv[index + 1] === "eval");
}

/** Remote-control aliases are recognized anywhere in the invocation. */
function isRemoteControlInvocation(argv) {
  return argv.some(part => part === "remote-control" || part === "rc");
}

export {
  NON_REPL_SUBCOMMANDS,
  isAgentsJsonInvocation,
  isMcpServeInvocation,
  isPluginEvalInvocation,
  isProjectVerbInvocation,
  isRemoteControlInvocation
};
