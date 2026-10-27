import { isPlainHook } from '../is-plain-hook/index.js';
export function checkedHookOf(hook, event, checks) {
    const isPlain = isPlainHook(hook);
    return isPlain
        ? async function checked($, e, next) {
            try {
                return await hook($, e, next);
            }
            catch (error) {
                checks.note(error, event);
                throw error;
            }
        }
        : hook;
}

