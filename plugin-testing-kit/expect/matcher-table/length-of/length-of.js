export function lengthOf(received) {
    const isAbsent = received === null || received === undefined;
    return isAbsent ? received : Reflect.get(Object(received), 'length');
}

