export const setsEqual = (sameness, actual, expected) => actual instanceof Set &&
    expected instanceof Set &&
    actual.size === expected.size &&
    [...expected].every(value => [...actual].some(candidate => sameness.same(candidate, value)));

