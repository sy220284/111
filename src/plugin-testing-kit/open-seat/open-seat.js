import { installed } from '../installed/index.js';
import { checkedHookOf } from './checked-hook-of/index.js';
import { clauseOf } from './clause-of/index.js';
import { createChecks } from './create-checks/index.js';
import { createHolds } from './create-holds/index.js';
import { engineOf } from './engine-of/index.js';
import { helperSpecOf } from './helper-spec-of/index.js';
import { replayOn } from './replay-on/index.js';
export function openSeat(plugins, tier, options) {
    const host = installed.handover().host;
    const clauses = [];
    const holds = createHolds();
    const checks = createChecks();
    const patterns = new Set();
    const id = host.open(JSON.stringify({ tier, plugins: plugins.map(helperSpecOf), options }));
    let loading;
    function on(pattern, ...rest) {
        if (loading) {
            throw new TypeError(`on("${String(pattern)}") after the test first called $: the hooks ` +
                'beneath the plugins are registered before that, as a module ' +
                'registers its hooks in register()');
        }
        const clause = clauseOf(pattern, rest, checks);
        clauses.push(clause);
        patterns.add(clause.pattern);
        return Object.freeze({
            catch: (handler) => {
                clause.caught = checkedHookOf(handler, clause.pattern, checks);
            },
        });
    }
    function load() {
        loading ??= host.load(id, JSON.stringify([...patterns]), {
            register: (engineOn) => {
                replayOn(engineOn, clauses, holds);
            },
            holding: holds.holding,
        });
        return loading;
    }
    return {
        $: engineOf({
            load,
            call: (event, e) => host.call(id, event, e),
            stream: (event, e) => host.stream(id, event, e),
            press: targetText => host.press(id, targetText),
            input: targetText => host.input(id, targetText),
            select: targetText => host.select(id, targetText),
            mount: targetText => host.mount(id, targetText),
            mounted: (handle, actText) => host.mounted(id, handle, actText),
        }),
        on,
        close: async () => {
            const end = await host.close(id);
            const failed = checks.first() ?? end.failed;
            return failed === undefined ? end : { ...end, failed };
        },
    };
}

