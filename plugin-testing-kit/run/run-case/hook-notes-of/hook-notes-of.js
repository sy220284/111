export const hookNotesOf = (event, told) => [`in the test's ${event} hook`, ...(told === '' ? [] : [told])].join('\n\n');

