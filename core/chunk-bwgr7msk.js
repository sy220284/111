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
import "./chunk-acd8w9vv.js";
import { E } from "./chunk-fs43t96t.js";
import "./chunk-2qx8pe6s.js";
import "./chunk-6bbdndfe.js";
import "./chunk-wrtp8qz8.js";
import "./chunk-qq3ygwcj.js";
import "./chunk-4hww8411.js";
import "./chunk-h5gbmqm1.js";
import "./chunk-jgvfzp2r.js";
import { aee } from "./chunk-4kxznmzw.js";
import "./chunk-w2jwj725.js";
import "./chunk-jazcqqqr.js";
import "./chunk-45h8cn90.js";
import "./chunk-a16y5hcz.js";
import "./chunk-3b9j76mf.js";
import "./chunk-cj37hmns.js";
import "./chunk-xc1j8sdj.js";
import "./chunk-ewvf3tep.js";
import "./chunk-19xvg81r.js";
import "./chunk-xe386mry.js";
import "./chunk-4kxy1036.js";
import "./chunk-afxq0vrk.js";
import { ut, sP } from "./chunk-jtb5bzvb.js";
import "./chunk-t0wrcf3t.js";
import { hHe } from "./chunk-6a9zzk1k.js";
import { Ca } from "./chunk-87f056s4.js";
import { JC, wU, cv } from "./chunk-d5zj5299.js";
import { Bse, bB, kxe } from "./chunk-hc5j8sxy.js";
import "./chunk-ct112gdr.js";
import "./chunk-njpzwa1b.js";
import { ve } from "./chunk-vk5p9xv3.js";
import { open as s } from "fs/promises";
var m = 10485760, u = 4;
async function F(r, o) { try {
    let t = ut(o);
    if (hHe(t) || sP(t))
        return "not_recordable";
    return await f.readAndRecord(r, t);
}
catch (t) {
    let n = E(t);
    return n === "ENOENT" || n === "EISDIR" || Bse(t) || bB(t) ? "not_recordable" : "read_failed";
} }
function c() { return Ca(u, p); }
var f = ve({ readAndRecord: c() }, (r) => { r.readAndRecord = c(); });
async function p(r, o) { const env_1 = { stack: [], error: void 0, hasError: false }; try {
    const t = __addDisposableResource(env_1, await s(o, aee()), true);
    let n = Math.floor((await t.stat()).mtimeMs), e = r.get(o);
    if (e !== void 0 && !(e.contentFromTranscript && e.limit === void 0 && JC(e) && e.timestamp < n))
        return "kept_existing";
    let i = await kxe(t, o, m);
    if (i.content.slice(0, 4096).includes("\x00"))
        return "not_recordable";
    let a = cv(i.content);
    if (e !== void 0 && !wU(e, a.trim()))
        return "kept_existing";
    let d = r.get(o);
    if (d !== void 0 && d !== e)
        return "kept_existing";
    return r.set(o, { content: a, timestamp: n, offset: void 0, limit: void 0, contentNotInModelContext: !0 }), "recorded";
}
catch (e_1) {
    env_1.error = e_1;
    env_1.hasError = true;
}
finally {
    const result_1 = __disposeResources(env_1);
    if (result_1)
        await result_1;
} }
export { F as recordStagedOutputAsRead };
