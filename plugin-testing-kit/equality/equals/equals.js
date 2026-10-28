import { equalsUnder } from './equals-under/index.js';
export const equals = (actual, expected, mode) => equalsUnder({ mode, seen: [] }, actual, expected);

