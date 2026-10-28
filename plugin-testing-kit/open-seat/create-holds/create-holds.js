export function createHolds() {
    let held = 0;
    return {
        enter: () => {
            held += 1;
        },
        leave: () => {
            held -= 1;
        },
        holding: () => held,
    };
}

