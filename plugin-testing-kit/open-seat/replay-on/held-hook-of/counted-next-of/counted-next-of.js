import { countingOf } from './counting-of/index.js';
export function countedNextOf(next, holds) {
    const isNext = typeof next === 'function';
    return isNext ? new Proxy(next, countingOf(holds)) : next;
}

