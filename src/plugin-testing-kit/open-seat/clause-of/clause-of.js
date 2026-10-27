import { checkedHookOf } from '../checked-hook-of/index.js';
export function clauseOf(pattern, rest, checks) {
    const isClause = typeof pattern === 'string' && rest.length >= 1 && rest.length <= 2;
    if (!isClause) {
        throw new TypeError('on(event, hook) or on(event, matcher, hook)');
    }
    return {
        pattern,
        rest: rest.with(-1, checkedHookOf(rest.at(-1), pattern, checks)),
        caught: undefined,
    };
}

