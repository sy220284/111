import { format } from '../../../equality/format/index.js';
import { assertionErrorOf } from '../../assertion-error-of/index.js';
import { byMatcher } from '../../by-matcher/index.js';
import { check } from '../../check/index.js';
import { leadOf } from '../../lead-of/index.js';
import { outcomeOf } from './outcome-of/index.js';
export const settledChecksOf = (context, settlement) => byMatcher(name => async (...args) => {
    const outcome = await outcomeOf(context.received);
    const isOtherWay = outcome.isRejected !== (settlement === 'rejects');
    if (isOtherWay) {
        const way = outcome.isRejected ? 'rejected' : 'resolved';
        throw assertionErrorOf(`${leadOf(context.message)}expect(received).${settlement}.${name}()` +
            `\n\nReceived promise ${way} with ${format(outcome.value)}`);
    }
    check({
        ...context,
        received: outcome.value,
        mode: settlement === 'rejects' ? 'rejected' : 'value',
        via: `${settlement}.`,
    }, name, args);
});

