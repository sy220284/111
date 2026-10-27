export const mapsEqual = (sameness, actual, expected) => actual instanceof Map &&
    expected instanceof Map &&
    actual.size === expected.size &&
    [...expected].every(([key, value]) => actual.has(key) && sameness.same(actual.get(key), value));

