export const holdOf = (clock) => ($, e, next) => new Promise((resolve, reject) => {
    const wait = clock.wait(e.ms, {
        resolve: () => {
            resolve({ value: undefined });
        },
        reject,
    });
    function cancel() {
        clock.drop(wait);
        reject(next.signal.reason);
    }
    next.signal.aborted
        ? cancel()
        : next.signal.addEventListener('abort', cancel, { once: true });
});

