export function isAffixed(received, affix, end) {
    const areStrings = typeof received === 'string' && typeof affix === 'string';
    if (!areStrings) {
        return false;
    }
    return end === 'start' ? received.startsWith(affix) : received.endsWith(affix);
}

