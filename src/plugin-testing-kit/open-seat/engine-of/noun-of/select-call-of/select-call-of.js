import { isObject } from '../../../../is-object/index.js';
import { RENDER_SURFACE_NAMES } from '../../../../render-surface-names/index.js';
export const selectCallOf = (crossing) => async (target) => {
    const isTarget = isObject(target) &&
        typeof target.plugin === 'string' &&
        typeof target.key === 'string' &&
        typeof target.value === 'string' &&
        (target.requestId === undefined ||
            typeof target.requestId === 'string') &&
        (target.surface === undefined ||
            (typeof target.surface === 'string' &&
                RENDER_SURFACE_NAMES.includes(target.surface)));
    if (!isTarget) {
        throw new TypeError('$.ui.select({ plugin, key, value, requestId?, surface? }): whose ' +
            "Select, its key, the picked option's value, and the instance " +
            `and the surface (${RENDER_SURFACE_NAMES.join(', ')}) when ` +
            'several hold it');
    }
    await crossing.load();
    return crossing.select(JSON.stringify(target));
};

