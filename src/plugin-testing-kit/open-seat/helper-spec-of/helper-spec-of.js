import { isObject } from '../../is-object/index.js';
import { isPluginTier } from '../../is-plugin-tier/index.js';
import { PLUGIN_TIERS_TEXT } from '../../plugin-tiers/plugin-tiers-text/index.js';
export function helperSpecOf(plugin) {
    const fields = isObject(plugin)
        ? plugin
        : {};
    const { name, register, tier = 'user' } = fields;
    if (typeof name !== 'string' || typeof register !== 'function') {
        throw new TypeError('test(name, { plugins }): each is { name, tier?, register(on) { ... } }');
    }
    if (!isPluginTier(tier)) {
        throw new TypeError(`test(name, { plugins }): ${name}'s tier is ${PLUGIN_TIERS_TEXT}`);
    }
    return { name, tier, source: Function.prototype.toString.call(register) };
}

