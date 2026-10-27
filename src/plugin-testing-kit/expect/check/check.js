import { format } from '../../equality/format/index.js';
import { assertionErrorOf } from '../assertion-error-of/index.js';
import { leadOf } from '../lead-of/index.js';
import { MATCHER_TABLE } from '../matcher-table/index.js';
export function check(context, name, args) {
    const { received, polarity, mode, message, via } = context;
    const verdict = MATCHER_TABLE[name](received, args, mode);
    const isNegated = polarity === 'negated';
    if (verdict.isPassing !== isNegated) {
        return;
    }
    throw assertionErrorOf(`${leadOf(message)}expect(received).${via}${isNegated ? 'not.' : ''}` +
        `${name}()\n\nExpected: ${isNegated ? 'not ' : ''}${verdict.expected}` +
        `\nReceived: ${verdict.shown ?? format(received)}`);
}

