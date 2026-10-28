import { advanceClock } from './advance-clock/index.js';
import { createPendingClock } from './create-pending-clock/index.js';
import { holdOf } from './hold-of/index.js';
import { setClock } from './set-clock/index.js';
import { sleepOn } from './sleep-on/index.js';
import { startOf } from './start-of/index.js';
export function mockClock(on, options = {}) {
    const clock = createPendingClock(startOf(options));
    const hold = holdOf(clock);
    on('clock.now', () => ({ value: clock.now() }));
    on('clock.sleep', hold);
    on('clock.after', hold);
    on('clock.every', hold);
    return Object.freeze({
        now: clock.now,
        advance: (ms) => advanceClock(clock, ms),
        set: (ms) => setClock(clock, ms),
        settle: () => advanceClock(clock, 0),
        sleep: (ms) => sleepOn(clock, ms),
    });
}

