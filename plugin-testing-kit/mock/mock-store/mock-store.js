import { isObject } from '../../is-object/index.js';
export function mockStore(on, entries = {}) {
    if (!isObject(entries)) {
        throw new TypeError('mock.store(on, entries): an object of values');
    }
    const held = new Map(Object.entries(entries));
    on('store.get', ($, e) => ({ value: held.get(e.key) }));
    on('store.set', ($, e) => {
        held.set(e.key, e.value);
        return { value: undefined };
    });
    on('store.delete', ($, e) => {
        held.delete(e.key);
        return { value: undefined };
    });
    on('store.keys', () => ({ value: [...held.keys()] }));
}

