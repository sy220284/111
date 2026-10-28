import { isObject } from '../../../is-object/index.js';
export function startOf(options) {
    const start = isObject(options) ? options.now : undefined;
    const isStart = isObject(options) &&
        (start === undefined ||
            (typeof start === 'number' && Number.isFinite(start)));
    if (!isStart) {
        throw new TypeError('mock.clock(on, { now }): the on of a test, and the time it starts at ' +
            'in milliseconds (0 when unsaid)');
    }
    return typeof start === 'number' ? start : 0;
}

