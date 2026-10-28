export const dueOf = (pending, until) => [...pending]
    .filter(wait => wait.at <= until)
    .toSorted((one, other) => one.at - other.at || one.order - other.order)[0];

