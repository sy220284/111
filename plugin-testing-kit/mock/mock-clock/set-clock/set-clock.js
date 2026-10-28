import { advanceClock } from '../advance-clock/index.js';
export function setClock(clock, ms) {
    const isTime = typeof ms === 'number' && Number.isFinite(ms) && ms >= clock.now();
    if (!isTime) {
        throw new TypeError(`set(ms): a time in milliseconds at or past now (${clock.now()}); ` +
            'the clock does not run backwards');
    }
    return advanceClock(clock, ms - clock.now());
}

