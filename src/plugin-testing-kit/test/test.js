import { collector } from '../collector/index.js';
export const test = (name, ...rest) => collector.test(name, rest);

