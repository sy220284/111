import { equals } from '../../equality/equals/index.js';
import { format } from '../../equality/format/index.js';
import { isIterable } from '../../is-iterable/index.js';
import { isObject } from '../../is-object/index.js';
import { isAffixed } from './is-affixed/index.js';
import { isMatched } from './is-matched/index.js';
import { isThrownAs } from './is-thrown-as/index.js';
import { lengthOf } from './length-of/index.js';
import { orderedOf } from './ordered-of/index.js';
import { propertyAt } from './property-at/index.js';
import { thrownBy } from './thrown-by/index.js';
export const MATCHER_TABLE = {
    toBe: (received, [expected]) => ({
        isPassing: Object.is(received, expected),
        expected: format(expected),
    }),
    toEqual: (received, [expected]) => ({
        isPassing: equals(received, expected, 'equal'),
        expected: format(expected),
    }),
    toStrictEqual: (received, [expected]) => ({
        isPassing: equals(received, expected, 'strict'),
        expected: format(expected),
    }),
    toMatchObject: (received, [expected]) => ({
        isPassing: isObject(received) && equals(received, expected, 'subset'),
        expected: format(expected),
    }),
    toContain: (received, [item]) => {
        const isText = typeof received === 'string';
        return {
            isPassing: isText
                ? typeof item === 'string' && received.includes(item)
                : isIterable(received) && [...received].includes(item),
            expected: `containing ${format(item)}`,
        };
    },
    toContainEqual: (received, [item]) => ({
        isPassing: isIterable(received) &&
            [...received].some(part => equals(part, item, 'equal')),
        expected: `containing ${format(item)}`,
    }),
    toHaveLength: (received, [length]) => ({
        isPassing: received !== null &&
            received !== undefined &&
            lengthOf(received) === length,
        expected: `length ${String(length)}`,
        shown: `length ${String(lengthOf(received))}`,
    }),
    toHaveProperty: (received, args) => {
        const [path, value] = args;
        const found = propertyAt(received, path);
        const isValued = args.length > 1;
        const wanted = isValued ? ` = ${format(value)}` : '';
        return {
            isPassing: found.isFound && (!isValued || equals(found.value, value, 'equal')),
            expected: `property ${format(path)}${wanted}`,
        };
    },
    toBeUndefined: received => ({
        isPassing: received === undefined,
        expected: 'undefined',
    }),
    toBeDefined: received => ({
        isPassing: received !== undefined,
        expected: 'defined',
    }),
    toBeNull: received => ({ isPassing: received === null, expected: 'null' }),
    toBeTruthy: received => ({
        isPassing: Boolean(received),
        expected: 'truthy',
    }),
    toBeFalsy: received => ({ isPassing: !received, expected: 'falsy' }),
    toBeNaN: received => ({
        isPassing: typeof received === 'number' && Number.isNaN(received),
        expected: 'NaN',
    }),
    toBeGreaterThan: (received, [bound]) => ({
        isPassing: orderedOf(received) > orderedOf(bound),
        expected: `> ${format(bound)}`,
    }),
    toBeGreaterThanOrEqual: (received, [bound]) => ({
        isPassing: orderedOf(received) >= orderedOf(bound),
        expected: `>= ${format(bound)}`,
    }),
    toBeLessThan: (received, [bound]) => ({
        isPassing: orderedOf(received) < orderedOf(bound),
        expected: `< ${format(bound)}`,
    }),
    toBeLessThanOrEqual: (received, [bound]) => ({
        isPassing: orderedOf(received) <= orderedOf(bound),
        expected: `<= ${format(bound)}`,
    }),
    toMatch: (received, [pattern]) => ({
        isPassing: typeof received === 'string' && isMatched(received, pattern),
        expected: format(pattern),
    }),
    toStartWith: (received, [prefix]) => ({
        isPassing: isAffixed(received, prefix, 'start'),
        expected: `starting with ${format(prefix)}`,
    }),
    toEndWith: (received, [suffix]) => ({
        isPassing: isAffixed(received, suffix, 'end'),
        expected: `ending with ${format(suffix)}`,
    }),
    toBeInstanceOf: (received, [expected]) => {
        const isClass = typeof expected === 'function';
        return {
            isPassing: isClass && received instanceof expected,
            expected: `an instance of ${isClass ? expected.name : format(expected)}`,
        };
    },
    toThrow: (received, [expected], mode) => {
        const thrown = thrownBy(received, mode);
        return {
            isPassing: isThrownAs(thrown, expected),
            expected: expected === undefined ? 'a throw' : format(expected),
            shown: thrown ? format(thrown.error) : 'nothing thrown',
        };
    },
};

