import { isObject } from '../../../../is-object/index.js';
import { RENDER_SURFACE_NAMES } from '../../../../render-surface-names/index.js';
export const pressCallOf = (crossing) => async (target) => {
    const isTarget = isObject(target) &&
        typeof target.plugin === 'string' &&
        typeof target.key === 'string' &&
        (target.requestId === undefined ||
            typeof target.requestId === 'string') &&
        (target.surface === undefined ||
            (typeof target.surface === 'string' &&
                RENDER_SURFACE_NAMES.includes(target.surface))) &&
        (target.link === undefined ||
            (isObject(target.link) && typeof target.link.href === 'string'));
    if (!isTarget) {
        throw new TypeError('$.ui.press({ plugin, key, requestId?, surface?, link? }): whose ' +
            'Button or Markdown, its key, the instance and the surface (' +
            `${RENDER_SURFACE_NAMES.join(', ')}) when several hold it, and ` +
            'for a Markdown the { href } of the link pressed');
    }
    await crossing.load();
    return crossing.press(JSON.stringify(target));
};

