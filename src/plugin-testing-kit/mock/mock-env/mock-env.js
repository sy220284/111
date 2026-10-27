import { isObject } from '../../is-object/index.js';
export function mockEnv(on, variables) {
    if (!isObject(variables)) {
        throw new TypeError('mock.env(on, variables): an object of strings');
    }
    const held = { ...variables };
    on('env.get', ($, e) => {
        const value = held[e.name];
        return { value: typeof value === 'string' ? value : undefined };
    });
}

