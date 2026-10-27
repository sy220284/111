import { isObject } from '../../../is-object/index.js';
export function createAsymmetry() {
    const probes = new WeakSet();
    return {
        asymmetric: (text, test) => {
            const probe = Object.freeze({ text, test });
            probes.add(probe);
            return probe;
        },
        isAsymmetric: (value) => isObject(value) && probes.has(value),
    };
}

