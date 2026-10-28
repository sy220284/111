export const keyText = (key) => /^[A-Za-z_$][\w$]*$/.test(key) ? key : JSON.stringify(key);

