export function propertyAt(received, path) {
    const keys = Array.isArray(path)
        ? path
        : String(path).split('.');
    let at = received;
    for (const key of keys) {
        const isMissing = at === null || at === undefined || !(String(key) in Object(at));
        if (isMissing) {
            return { isFound: false, value: undefined };
        }
        at = Reflect.get(Object(at), String(key));
    }
    return { isFound: true, value: at };
}

