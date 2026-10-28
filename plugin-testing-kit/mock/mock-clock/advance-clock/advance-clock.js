import { installed } from '../../../installed/index.js';
import { isDuration } from '../../../is-duration/index.js';
import { FIRES_PER_ADVANCE_MAX } from './fires-per-advance-max/index.js';
export async function advanceClock(clock, ms) {
    if (!isDuration(ms)) {
        throw new TypeError('advance(ms): a finite, non-negative number of milliseconds');
    }
    const { settle } = installed.handover().host;
    const until = clock.now() + ms;
    await settle();
    let fired = 0;
    for (let wait = clock.due(until); wait; wait = clock.due(until)) {
        if (fired >= FIRES_PER_ADVANCE_MAX) {
            throw new Error(`advance(ms): ${FIRES_PER_ADVANCE_MAX} waits resolved and more ` +
                'were due: an interval shorter than the advance?');
        }
        fired += 1;
        clock.drop(wait);
        clock.moveTo(wait.at);
        wait.resolve();
        await settle();
    }
    clock.moveTo(until);
    await settle();
}

