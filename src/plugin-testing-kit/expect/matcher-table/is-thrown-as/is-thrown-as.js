import { isObject } from '../../../is-object/index.js';
export function isThrownAs(thrown, expected) {
    if (!thrown) {
        return false;
    }
    if (expected === undefined) {
        return true;
    }
    const { error } = thrown;
    const message = String(isObject(error) ? error.message : error);
    if (typeof expected === 'string') {
        return message.includes(expected);
    }
    if (expected instanceof RegExp) {
        return expected.test(message);
    }
    const isClass = typeof expected === 'function';
    return isClass
        ? error instanceof expected
        : isObject(expected) && message === expected.message;
}

