import { isObject } from '../../../../../is-object/index.js';
export const isViewport = (value) => value === undefined ||
    (isObject(value) &&
        Number.isInteger(value.columns) &&
        Number.isInteger(value.rows) &&
        (value.isFullscreen === undefined ||
            typeof value.isFullscreen === 'boolean'));

