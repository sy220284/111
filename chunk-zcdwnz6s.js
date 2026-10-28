// @bun @bytecode
// Claude Code is a Beta product per Anthropic's Commercial Terms of Service.
// By using Claude Code, you agree that all code acceptance or rejection decisions you make,
// and the associated conversations in context, constitute Feedback under Anthropic's Commercial Terms,
// and may be used to improve Anthropic's products, including training models.
// You are responsible for reviewing any code suggestions before use.
var __addDisposableResource = (this && this.__addDisposableResource) || function (env, value, async) {
    if (value !== null && value !== void 0) {
        if (typeof value !== "object" && typeof value !== "function") throw new TypeError("Object expected.");
        var dispose, inner;
        if (async) {
            if (!Symbol.asyncDispose) throw new TypeError("Symbol.asyncDispose is not defined.");
            dispose = value[Symbol.asyncDispose];
        }
        if (dispose === void 0) {
            if (!Symbol.dispose) throw new TypeError("Symbol.dispose is not defined.");
            dispose = value[Symbol.dispose];
            if (async) inner = dispose;
        }
        if (typeof dispose !== "function") throw new TypeError("Object not disposable.");
        if (inner) dispose = function() { try { inner.call(this); } catch (e) { return Promise.reject(e); } };
        env.stack.push({ value: value, dispose: dispose, async: async });
    }
    else if (async) {
        env.stack.push({ async: true });
    }
    return value;
};
var __disposeResources = (this && this.__disposeResources) || (function (SuppressedError) {
    return function (env) {
        function fail(e) {
            env.error = env.hasError ? new SuppressedError(e, env.error, "An error was suppressed during disposal.") : e;
            env.hasError = true;
        }
        var r, s = 0;
        function next() {
            while (r = env.stack.pop()) {
                try {
                    if (!r.async && s === 1) return s = 0, env.stack.push(r), Promise.resolve().then(next);
                    if (r.dispose) {
                        var result = r.dispose.call(r.value);
                        if (r.async) return s |= 2, Promise.resolve(result).then(next, function(e) { fail(e); return next(); });
                    }
                    else s |= 1;
                }
                catch (e) {
                    fail(e);
                }
            }
            if (s === 1) return env.hasError ? Promise.reject(env.error) : Promise.resolve();
            if (env.hasError) throw env.error;
        }
        return next();
    };
})(typeof SuppressedError === "function" ? SuppressedError : function (error, suppressed, message) {
    var e = new Error(message);
    return e.name = "SuppressedError", e.error = error, e.suppressed = suppressed, e;
});
// (c) Anthropic PBC. All rights reserved. Use is subject to the Legal Agreements outlined here: https://code.claude.com/docs/en/legal-and-compliance.
// Version: 2.1.310
import { d, E } from "./chunk-fs43t96t.js";
import { aee } from "./chunk-4kxznmzw.js";
import { No, ot } from "./chunk-8r2m7vfp.js";
import { ffi, kxe } from "./chunk-hc5j8sxy.js";
import { open as l, realpath as f, stat as g } from "fs/promises";
import { resolve as h } from "path";
var mgs = "Error: --agents takes a JSON object, or a file path only with --print (-p). Pass the agents as inline JSON, or run with -p to read them from a file.", s = 268435456;
function nMn(t) { let e = No(t).trimStart(); return e.startsWith("{") || ot(e, !1) !== null; }
async function ggs(t) { let e = h(t); try {
    const env_1 = { stack: [], error: void 0, hasError: false };
    try {
        const r = __addDisposableResource(env_1, await l(e, aee()), true);
        let a = await r.stat({ bigint: !0 });
        ffi(a, e);
        let o = await f(e).catch(() => e), n = await g(o, { bigint: !0 });
        if (n.dev !== a.dev || n.ino !== a.ino)
            return { ok: !1, error: `Error: --agents file changed while it was read: ${e}` };
        let { content: i } = await kxe(r, e, s);
        return { ok: !0, json: i, filePath: e, realPath: o };
    }
    catch (e_1) {
        env_1.error = e_1;
        env_1.hasError = true;
    }
    finally {
        const result_1 = __disposeResources(env_1);
        if (result_1)
            await result_1;
    }
}
catch (r) {
    switch (E(r)) {
        case "ENOENT": return { ok: !1, error: `Error: --agents file not found: ${e} (a value that is not a JSON object is read as a file path)` };
        case "ERR_FILE_TOO_LARGE": return { ok: !1, error: `Error: --agents file is larger than ${s} bytes: ${e}` };
        case "ERR_MULTIPLE_LINKS": return { ok: !1, error: `Error: --agents file has more than one hard link: ${e} (its other names are not write-protected; pass a file with one name)` };
        default: return { ok: !1, error: `Error reading --agents file ${e}: ${d(r)}` };
    }
} }
export { mgs, nMn, ggs };
