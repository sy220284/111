import { callOf } from './call-of/index.js';
import { inputCallOf } from './input-call-of/index.js';
import { mountCallOf } from './mount-call-of/index.js';
import { pressCallOf } from './press-call-of/index.js';
import { selectCallOf } from './select-call-of/index.js';
import { streamOf } from './stream-of/index.js';
export function nounOf(crossing, noun, events) {
    const isUi = noun === 'ui';
    const beside = isUi
        ? [
            ['press', pressCallOf(crossing)],
            ['input', inputCallOf(crossing)],
            ['select', selectCallOf(crossing)],
            ['mount', mountCallOf(crossing)],
        ]
        : [];
    return Object.freeze(Object.fromEntries([
        ...events.map(([name, isStream]) => [
            name,
            isStream
                ? streamOf(crossing, `${noun}.${name}`)
                : callOf(crossing, `${noun}.${name}`),
        ]),
        ...beside,
    ]));
}

