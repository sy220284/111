import { heldHookOf } from './held-hook-of/index.js';
export function replayOn(registrar, clauses, holds) {
    for (const clause of clauses) {
        const hook = heldHookOf(clause.rest.at(-1), holds);
        const registration = registrar(clause.pattern, ...clause.rest.slice(0, -1), hook);
        if (clause.caught !== undefined) {
            registration.catch(heldHookOf(clause.caught, holds));
        }
    }
}

