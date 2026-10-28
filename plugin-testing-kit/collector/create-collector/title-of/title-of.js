export function titleOf(group, name) {
    const names = [name];
    for (let at = group; at; at = at.parent) {
        if (at.name !== '') {
            names.unshift(at.name);
        }
    }
    return names.join(' > ');
}

