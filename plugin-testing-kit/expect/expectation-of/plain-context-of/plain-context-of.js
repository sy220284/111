export const plainContextOf = (received, message) => ({
    received,
    polarity: 'plain',
    mode: 'value',
    message,
    via: '',
});

