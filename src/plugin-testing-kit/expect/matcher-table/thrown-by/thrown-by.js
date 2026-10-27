import { assertionErrorOf } from '../../assertion-error-of/index.js';
export function thrownBy(received, mode) {
    if (mode === 'rejected') {
        return { error: received };
    }
    if (typeof received !== 'function') {
        throw assertionErrorOf('expect(received).toThrow(): received is not a function');
    }
    try {
        received();
    }
    catch (error) {
        return { error };
    }
    return undefined;
}

