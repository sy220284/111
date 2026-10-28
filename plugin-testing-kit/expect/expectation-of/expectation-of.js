import { checksOf } from './checks-of/index.js';
import { negatableOf } from './negatable-of/index.js';
import { plainContextOf } from './plain-context-of/index.js';
import { settledChecksOf } from './settled-checks-of/index.js';
export function expectationOf(received, message) {
    const plain = plainContextOf(received, message);
    const negated = {
        ...plain,
        polarity: 'negated',
    };
    return {
        ...negatableOf(checksOf(plain), checksOf(negated)),
        resolves: negatableOf(settledChecksOf(plain, 'resolves'), settledChecksOf(negated, 'resolves')),
        rejects: negatableOf(settledChecksOf(plain, 'rejects'), settledChecksOf(negated, 'rejects')),
    };
}

