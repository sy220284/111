export function mockSession(on) {
    let appended = [];
    on('session.append', async ($, e, next) => {
        const stored = await next(e);
        const isStored = stored.deny === undefined;
        appended = isStored
            ? [...appended, { ...e, message: stored.message }]
            : appended;
        return stored;
    });
    return { appended: () => appended };
}

