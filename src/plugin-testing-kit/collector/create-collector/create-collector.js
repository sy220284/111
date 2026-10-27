import { isPluginTier } from '../../is-plugin-tier/index.js';
import { PLUGIN_TIERS_TEXT } from '../../plugin-tiers/plugin-tiers-text/index.js';
import { testCaseOf } from './test-case-of/index.js';
import { titleOf } from './title-of/index.js';
export function createCollector() {
    const root = { name: '', parent: null };
    const cases = [];
    let current = root;
    let isCollecting = true;
    let fileTier;
    function collecting(what) {
        if (!isCollecting) {
            throw new TypeError(`${what} is called while the file loads, not inside a test`);
        }
    }
    return {
        describe: (name, body) => {
            collecting('describe');
            const isDescribe = typeof name === 'string' && typeof body === 'function';
            if (!isDescribe) {
                throw new TypeError('describe(name, body) takes a string and a function');
            }
            const outer = current;
            current = { name, parent: outer };
            try {
                body();
            }
            finally {
                current = outer;
            }
        },
        test: (name, ...rest) => {
            collecting('test');
            if (typeof name !== 'string') {
                throw new TypeError('test(name, ...): the name is a string');
            }
            cases.push(testCaseOf(titleOf(current, name), rest));
        },
        tier: tier => {
            collecting('tier(tier)');
            if (fileTier) {
                throw new TypeError('tier(tier) is called once, for the whole file');
            }
            if (!isPluginTier(tier)) {
                throw new TypeError(`tier(${JSON.stringify(tier)}): the tier is ${PLUGIN_TIERS_TEXT}`);
            }
            fileTier = tier;
        },
        close: () => {
            isCollecting = false;
        },
        cases: () => cases,
        fileTier: () => fileTier ?? 'user',
    };
}

