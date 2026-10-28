import { keysOf } from './keys-of/index.js';
export function propertiesEqual(sameness, actual, expected) {
    if (sameness.mode === 'subset') {
        return Object.keys(expected).every(key => key in actual && sameness.same(actual[key], expected[key]));
    }
    const expectedKeys = keysOf(sameness, expected);
    return (keysOf(sameness, actual).length === expectedKeys.length &&
        expectedKeys.every(key => Object.hasOwn(actual, key) && sameness.same(actual[key], expected[key])));
}

