import { asymmetry } from '../../asymmetry/index.js';
import { isObject } from '../../../is-object/index.js';
import { structurallyEqual } from './structurally-equal/index.js';
export function equalsUnder(comparison, actual, expected) {
    if (asymmetry.isAsymmetric(expected)) {
        return expected.test(actual);
    }
    if (Object.is(actual, expected)) {
        return true;
    }
    if (!isObject(actual) || !isObject(expected)) {
        return false;
    }
    const isEntered = comparison.seen.some(([one, other]) => one === actual && other === expected);
    if (isEntered) {
        return true;
    }
    const inner = {
        mode: comparison.mode,
        seen: [...comparison.seen, [actual, expected]],
    };
    return structurallyEqual({
        mode: comparison.mode,
        same: (one, other) => equalsUnder(inner, one, other),
    }, actual, expected);
}

