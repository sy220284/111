import { isObject } from '../../../../is-object/index.js';
import { RENDER_SURFACE_NAMES } from '../../../../render-surface-names/index.js';
export const inputCallOf = (crossing) => async (target) => {
    const isTarget = isObject(target) &&
        typeof target.plugin === 'string' &&
        typeof target.key === 'string' &&
        typeof target.text === 'string' &&
        (target.kind === undefined ||
            target.kind === 'change' ||
            target.kind === 'submit') &&
        (target.requestId === undefined ||
            typeof target.requestId === 'string') &&
        (target.surface === undefined ||
            (typeof target.surface === 'string' &&
                RENDER_SURFACE_NAMES.includes(target.surface)));
    if (!isTarget) {
        throw new TypeError('$.ui.input({ plugin, key, text, kind?, requestId?, surface? }): ' +
            "whose Input, its key, the field's text, `submit` (the default) " +
            'or `change`, and the instance and the surface (' +
            `${RENDER_SURFACE_NAMES.join(', ')}) when several hold it`);
    }
    await crossing.load();
    return crossing.input(JSON.stringify(target));
};

