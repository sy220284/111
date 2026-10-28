export async function outcomeOf(received) {
    try {
        return { value: await received, isRejected: false };
    }
    catch (error) {
        return { value: error, isRejected: true };
    }
}

