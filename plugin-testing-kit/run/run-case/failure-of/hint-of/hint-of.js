export function hintOf(message) {
    const met = /no implementation for ([\w.*-]+)/.exec(message);
    const isValueRefused = message.includes('neither { value } nor { deny }');
    const isNoResult = message.includes('something that is not a result object');
    const isNothingDrawn = met?.[1] === 'ui.render';
    return [
        ...(met
            ? [
                `nothing beneath the plugins answers ${met[1]}: a test answers it ` +
                    `with on('${met[1]}', ...): { value: ... } for a call on $, the ` +
                    "event's own result otherwise",
            ]
            : []),
        ...(isNothingDrawn
            ? [
                "nothing draws it beneath the plugins: the test's own " +
                    "on('ui.render', ...) stands for the engine's drawing",
            ]
            : []),
        ...(isValueRefused
            ? [
                'a stand-in for a call on $ answers { value: ... }, what it ' +
                    'resolves to',
            ]
            : []),
        ...(isNoResult
            ? [
                "a hook answers a result object: the event's own result, or " +
                    '{ value: ... } for a call on $',
            ]
            : []),
    ];
}

