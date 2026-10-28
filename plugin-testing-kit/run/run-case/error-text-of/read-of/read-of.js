import { isObject } from '../../../../is-object/index.js';
export function readOf(error, key) {
    if (!isObject(error)) {
        return undefined;
    }
    try {
        return error[key];
    }
    catch {
        return undefined;
    }
}

