import { keyText } from './key-text/index.js';
export function partsOf(value, shown) {
    if (Array.isArray(value)) {
        return value.map(shown);
    }
    if (value instanceof Map) {
        return [...value].map(([key, part]) => `${shown(key)} => ${shown(part)}`);
    }
    const isSet = value instanceof Set;
    return isSet
        ? [...value].map(shown)
        : Object.keys(value).map(key => `${keyText(key)}: ${shown(value[key])}`);
}

