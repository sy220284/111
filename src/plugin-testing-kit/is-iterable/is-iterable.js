export const isIterable = (value) => value !== null &&
    value !== undefined &&
    typeof Reflect.get(Object(value), Symbol.iterator) === 'function';

