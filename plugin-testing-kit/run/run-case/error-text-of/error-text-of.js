import { readOf } from './read-of/index.js';
export function errorTextOf(error) {
    const stack = readOf(error, 'stack');
    if (typeof stack === 'string' && stack !== '') {
        return stack;
    }
    const message = readOf(error, 'message');
    if (typeof message === 'string') {
        return message;
    }
    try {
        return String(error);
    }
    catch {
        return 'threw a value that does not print';
    }
}

