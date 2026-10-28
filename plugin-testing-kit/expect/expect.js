import { expectationOf } from './expectation-of/index.js';
import { MATCHING } from './matching/index.js';
export const expect = Object.assign((received, message) => expectationOf(received, message), MATCHING);

