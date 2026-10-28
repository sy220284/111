import { asymmetry } from '../../equality/asymmetry/index.js';
import { equals } from '../../equality/equals/index.js';
import { format } from '../../equality/format/index.js';
import { isObject } from '../../is-object/index.js';
export const MATCHING = {
    any: expected => asymmetry.asymmetric(`Any<${expected.name}>`, value => value !== null &&
        value !== undefined &&
        Object(value) instanceof expected),
    anything: () => asymmetry.asymmetric('Anything', value => value !== null && value !== undefined),
    stringContaining: text => asymmetry.asymmetric(`StringContaining ${format(text)}`, value => typeof value === 'string' && value.includes(text)),
    stringMatching: pattern => asymmetry.asymmetric(`StringMatching ${format(pattern)}`, value => typeof value === 'string' && new RegExp(pattern).test(value)),
    objectContaining: shape => asymmetry.asymmetric(`ObjectContaining ${format(shape)}`, value => isObject(value) && equals(value, shape, 'subset')),
    arrayContaining: items => asymmetry.asymmetric(`ArrayContaining ${format(items)}`, value => Array.isArray(value) &&
        items.every(item => value.some(part => equals(part, item, 'equal')))),
};

