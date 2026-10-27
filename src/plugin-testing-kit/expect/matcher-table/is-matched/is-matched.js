export function isMatched(received, pattern) {
    const isPattern = pattern instanceof RegExp;
    return isPattern ? pattern.test(received) : received.includes(String(pattern));
}

