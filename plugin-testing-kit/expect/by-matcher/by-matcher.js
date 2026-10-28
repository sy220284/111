import { MATCHER_NAMES } from '../matcher-names/index.js';
export function byMatcher(make) {
    const built = Object.fromEntries(MATCHER_NAMES.map(name => [name, make(name)]));
    return built;
}

