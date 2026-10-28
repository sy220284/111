import { bracketsOf } from './brackets-of/index.js';
import { FORMAT_WIDTH } from './format-width/index.js';
import { partsOf } from './parts-of/index.js';
export function containerText(value, indent, shown) {
    const parts = partsOf(value, shown);
    const [open, close] = bracketsOf(value);
    if (parts.length === 0) {
        return `${open}${close}`;
    }
    const pad = open === '[' ? '' : ' ';
    const flat = `${open}${pad}${parts.join(', ')}${pad}${close}`;
    const isFlat = flat.length <= FORMAT_WIDTH && !flat.includes('\n');
    if (isFlat) {
        return flat;
    }
    const lines = parts.map(part => `${indent}  ${part}`).join(',\n');
    return `${open}\n${lines}\n${indent}${close}`;
}

