import { isObject } from '../../../../../is-object/index.js';
export function queryOf(query, act) {
    const text = isObject(query) ? query.text : undefined;
    const isRegExp = isObject(text) &&
        typeof text.source === 'string' &&
        typeof text.flags === 'string';
    const pattern = isRegExp
        ? { source: text.source, flags: String(text.flags).replace(/[gy]/g, '') }
        : undefined;
    const isQuery = isObject(query) &&
        (query.type === undefined || typeof query.type === 'string') &&
        (query.key === undefined || typeof query.key === 'string') &&
        (text === undefined || typeof text === 'string' || pattern !== undefined) &&
        (query.in === undefined || typeof query.in === 'string');
    if (!isQuery) {
        throw new TypeError(`${act}({ type?, key?, text?, in? }): an element's tag, its key, the ` +
            'text it shows (a string it includes or a RegExp it matches), and ' +
            '`in` naming a Client by key to search what its module drew');
    }
    return { ...query, ...(pattern !== undefined && { text: pattern }) };
}

