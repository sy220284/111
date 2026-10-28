export const arraysEqual = (sameness, actual, expected) => actual.length === expected.length &&
    expected.every((value, at) => sameness.same(actual[at], value));

