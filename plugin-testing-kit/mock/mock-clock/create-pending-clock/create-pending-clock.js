import { dueOf } from './due-of/index.js';
import { pendingWaitOf } from './pending-wait-of/index.js';
export function createPendingClock(start) {
    const pending = new Set();
    let now = start;
    let order = 0;
    return {
        now: () => now,
        moveTo: ms => {
            now = ms;
        },
        wait: (ms, settling) => {
            order += 1;
            const wait = pendingWaitOf(now + ms, order, settling);
            pending.add(wait);
            return wait;
        },
        drop: wait => {
            pending.delete(wait);
        },
        due: until => dueOf(pending, until),
    };
}

