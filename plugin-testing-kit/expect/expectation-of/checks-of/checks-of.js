import { byMatcher } from '../../by-matcher/index.js';
import { check } from '../../check/index.js';
export const checksOf = (context) => byMatcher(name => (...args) => {
    check(context, name, args);
});

