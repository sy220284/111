import { isDuration } from '../../../is-duration/index.js';
export function sleepOn(clock, ms) {
    if (!isDuration(ms)) {
        throw new TypeError('sleep(ms): a finite, non-negative number of milliseconds');
    }
    return new Promise(resolve => {
        clock.wait(ms, { resolve, reject: () => undefined });
    });
}

