export const callOf = (crossing, event) => async (e) => {
    await crossing.load();
    return crossing.call(event, e);
};

