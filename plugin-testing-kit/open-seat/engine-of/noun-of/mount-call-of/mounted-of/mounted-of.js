import { isObject } from '../../../../../is-object/index.js';
import { queryOf } from '../query-of/index.js';
export function mountedOf(crossing, handle, surface) {
    const act = (value) => crossing.mounted(handle, JSON.stringify(value));
    const spread = (name, value) => act({ act: name, ...(isObject(value) ? value : {}) });
    const toClient = (name) => async (event) => {
        const { in: inClient, ...rest } = isObject(event) ? event : {};
        await act({ act: name, event: rest, in: inClient });
    };
    const built = Object.freeze({
        surface,
        drawn: (scope) => spread('drawn', scope),
        find: (query) => act({ act: 'find', query: queryOf(query, 'find') }),
        findAll: (query) => act({ act: 'findAll', query: queryOf(query, 'findAll') }),
        press: (target) => spread('press', target),
        input: (target) => spread('input', target),
        select: (target) => spread('select', target),
        key: toClient('key'),
        pointer: toClient('pointer'),
        post: async (data, scope) => {
            await act({ act: 'post', data, ...(isObject(scope) ? scope : {}) });
        },
        advance: async (ms) => {
            await act({ act: 'advance', ms });
        },
        resize: async (size) => {
            await spread('resize', size);
        },
        redraw: async (props) => {
            await act({ act: 'redraw', ...(props !== undefined && { props }) });
        },
        unmount: async () => {
            await act({ act: 'unmount' });
        },
    });
    return built;
}

