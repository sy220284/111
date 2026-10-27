export const isPlainHook = (hook) => typeof hook === 'function' &&
    Object.prototype.toString.call(hook) !== '[object AsyncGeneratorFunction]';

