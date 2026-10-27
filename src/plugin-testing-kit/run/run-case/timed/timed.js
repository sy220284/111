import { DELAY_MAX_MS } from '../../../delay-max-ms/index.js';
import { installed } from '../../../installed/index.js';
import { overranError } from './overran-error/index.js';
import { rejectOverran } from './reject-overran/index.js';
export function timed(body, timeoutMs) {
    const { now } = installed.handover().host;
    return new Promise((resolve, reject) => {
        const started = now();
        const timer = setTimeout(rejectOverran, Math.min(timeoutMs, DELAY_MAX_MS), reject, timeoutMs);
        Promise.resolve()
            .then(body)
            .then(value => {
            clearTimeout(timer);
            const isLate = now() - started > timeoutMs;
            isLate ? reject(overranError(timeoutMs)) : resolve(value);
        }, (error) => {
            clearTimeout(timer);
            reject(error);
        });
    });
}

