import { ASSERTION_ERROR_NAME } from './assertion-error-name/index.js';
export function assertionErrorOf(text) {
    const error = new Error(text);
    error.name = ASSERTION_ERROR_NAME;
    return error;
}

