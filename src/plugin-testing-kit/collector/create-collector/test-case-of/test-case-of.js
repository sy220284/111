import { isObject } from '../../../is-object/index.js';
import { TIMEOUT_MS } from './timeout-ms/index.js';
export function testCaseOf(title, rest) {
    const [options, body] = rest.length === 1 ? [{}, rest[0]] : rest;
    if (typeof body !== 'function') {
        throw new TypeError('test(name, body) or test(name, options, body): a string, then a ' +
            'function ($, on)');
    }
    if (!isObject(options)) {
        throw new TypeError('test(name, options, body): options is an object');
    }
    const { plugins = [], timeoutMs = TIMEOUT_MS, options: values } = options;
    if (!Array.isArray(plugins)) {
        throw new TypeError('test(name, { plugins }): an array of plugins');
    }
    const isPositive = typeof timeoutMs === 'number' && timeoutMs > 0;
    if (!isPositive) {
        throw new TypeError(`test(${JSON.stringify(title)}, { timeoutMs }): a positive number`);
    }
    const isValues = values === undefined ||
        (isObject(values) &&
            Object.prototype.toString.call(values) === '[object Object]');
    if (!isValues) {
        throw new TypeError(`test(${JSON.stringify(title)}, { options }): an object of the ` +
            "plugin's userConfig values");
    }
    return {
        title,
        body: body,
        plugins,
        timeoutMs,
        options: values,
    };
}

