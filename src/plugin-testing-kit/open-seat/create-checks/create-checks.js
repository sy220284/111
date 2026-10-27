import { ASSERTION_ERROR_NAME } from '../../expect/assertion-error-of/assertion-error-name/index.js';
import { errorTextOf } from '../../run/run-case/error-text-of/index.js';
import { readOf } from '../../run/run-case/error-text-of/read-of/index.js';
export function createChecks() {
    let first;
    return {
        note: (error, event) => {
            const isCheck = readOf(error, 'name') === ASSERTION_ERROR_NAME;
            first ??= isCheck ? { text: errorTextOf(error), event } : undefined;
        },
        first: () => first,
    };
}

