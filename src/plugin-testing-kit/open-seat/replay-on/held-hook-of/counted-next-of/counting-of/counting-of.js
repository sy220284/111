export const countingOf = (holds) => ({
    apply(target, self, args) {
        holds.leave();
        try {
            return Promise.resolve(Reflect.apply(target, self, args)).finally(holds.enter);
        }
        catch (error) {
            holds.enter();
            throw error;
        }
    },
});

