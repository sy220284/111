export function bracketsOf(value) {
    if (Array.isArray(value)) {
        return ['[', ']'];
    }
    const name = value instanceof Map ? 'Map ' : value instanceof Set ? 'Set ' : '';
    return [`${name}{`, '}'];
}

