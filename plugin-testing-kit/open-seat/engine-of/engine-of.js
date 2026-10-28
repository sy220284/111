import { installed } from '../../installed/index.js';
import { nounOf } from './noun-of/index.js';
export function engineOf(crossing) {
    const built = Object.freeze(Object.fromEntries(installed
        .handover()
        .events.map(([noun, events]) => [noun, nounOf(crossing, noun, events)])));
    return built;
}

