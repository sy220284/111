export const orderedOf = (value) => typeof value === 'bigint' || typeof value === 'number' ? value : Number(value);

