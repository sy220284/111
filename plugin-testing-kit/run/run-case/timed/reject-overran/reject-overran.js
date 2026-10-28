import { overranError } from '../overran-error/index.js';
export const rejectOverran = (reject, timeoutMs) => reject(overranError(timeoutMs));

