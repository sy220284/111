import { isObject } from '../../../../is-object/index.js';
import { RENDER_SURFACE_NAMES } from '../../../../render-surface-names/index.js';
import { isViewport } from './is-viewport/index.js';
import { mountedOf } from './mounted-of/index.js';
export const mountCallOf = (crossing) => async (target) => {
    const surface = isObject(target) ? target.surface : undefined;
    const isSurface = typeof surface === 'string' && RENDER_SURFACE_NAMES.includes(surface);
    const isTarget = isObject(target) &&
        typeof target.plugin === 'string' &&
        isSurface &&
        typeof target.component === 'string' &&
        isObject(target.props) &&
        (target.requestId === undefined ||
            typeof target.requestId === 'string') &&
        isViewport(target.viewport);
    const isSurfaceWrong = isObject(target) && !isSurface;
    if (!isTarget) {
        throw new TypeError('$.ui.mount({ plugin, surface, component, props, requestId?, ' +
            'viewport? }): whose elements, the surface that draws (one of ' +
            `${RENDER_SURFACE_NAMES.join(', ')}; there is no default), the ` +
            'component and its props as a ui.render hook sees them, the ' +
            'instance, and what the surface measured ({ columns, rows, ' +
            'isFullscreen? })' +
            (isSurfaceWrong
                ? `; got surface ${JSON.stringify(surface) ?? 'undefined'}`
                : ''));
    }
    await crossing.load();
    return mountedOf(crossing, await crossing.mount(JSON.stringify(target)), surface);
};

