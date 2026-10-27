export function createInstalled() {
    let held;
    function handover() {
        if (!held) {
            throw new TypeError("'claude-code/testing' runs under claude plugin test; nothing here " +
                'installed it');
        }
        return held;
    }
    return {
        install: handover => {
            if (held) {
                throw new TypeError('the kit is installed once');
            }
            held = handover;
        },
        handover,
    };
}

