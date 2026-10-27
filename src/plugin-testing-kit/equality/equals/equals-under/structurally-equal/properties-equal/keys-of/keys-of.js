export const keysOf = (sameness, value) => Object.keys(value).filter(key => sameness.mode === 'strict' || value[key] !== undefined);

