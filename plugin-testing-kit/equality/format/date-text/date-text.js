export function dateText(date) {
    const time = date.getTime();
    return Number.isNaN(time) ? 'Date(Invalid)' : `Date(${time})`;
}

