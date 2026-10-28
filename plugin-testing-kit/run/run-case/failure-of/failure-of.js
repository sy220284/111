import { hintOf } from './hint-of/index.js';
export function failureOf(text, told) {
    const lines = text.split('\n');
    const stackAt = lines.findIndex(line => /^\s+at /.test(line));
    const message = stackAt === -1 ? lines : lines.slice(0, stackAt);
    const frames = stackAt === -1 ? [] : lines.slice(stackAt);
    const notes = [
        ...hintOf([...message, told].join('\n')),
        ...(told === '' ? [] : [told]),
    ];
    return [
        message.join('\n'),
        ...notes.map(note => `\n${note}`),
        ...(frames.length === 0 ? [] : [`\n${frames.join('\n')}`]),
    ].join('\n');
}

