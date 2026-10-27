import { isPlainHook } from '../../is-plain-hook/index.js';
import { countedNextOf } from './counted-next-of/index.js';
export function heldHookOf(hook, holds) {
    const isPlain = isPlainHook(hook);
    return isPlain
        ? async function held($, e, next) {
            holds.enter();
            try {
                return await hook($, e, countedNextOf(next, holds));
            }
            finally {
                holds.leave();
            }
        }
        : hook;
}

