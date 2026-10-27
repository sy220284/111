import { arraysEqual } from './arrays-equal/index.js';
import { mapsEqual } from './maps-equal/index.js';
import { propertiesEqual } from './properties-equal/index.js';
import { setsEqual } from './sets-equal/index.js';
export function structurallyEqual(sameness, actual, expected) {
    const isOtherPrototype = sameness.mode === 'strict' &&
        Object.getPrototypeOf(actual) !== Object.getPrototypeOf(expected);
    if (isOtherPrototype || Array.isArray(actual) !== Array.isArray(expected)) {
        return false;
    }
    if (actual instanceof Date || expected instanceof Date) {
        return (actual instanceof Date &&
            expected instanceof Date &&
            Object.is(actual.getTime(), expected.getTime()));
    }
    if (actual instanceof RegExp || expected instanceof RegExp) {
        return String(actual) === String(expected);
    }
    if (actual instanceof Map || expected instanceof Map) {
        return mapsEqual(sameness, actual, expected);
    }
    if (actual instanceof Set || expected instanceof Set) {
        return setsEqual(sameness, actual, expected);
    }
    const areArrays = Array.isArray(actual) && Array.isArray(expected);
    return areArrays
        ? arraysEqual(sameness, actual, expected)
        : propertiesEqual(sameness, actual, expected);
}

