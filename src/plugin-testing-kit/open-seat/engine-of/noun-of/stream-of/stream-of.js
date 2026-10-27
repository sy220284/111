export const streamOf = (crossing, event) => async function* streamed(e) {
    await crossing.load();
    return yield* crossing.stream(event, e);
};

