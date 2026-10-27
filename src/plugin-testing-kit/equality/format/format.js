import { asymmetry } from '../asymmetry/index.js';
import { isObject } from '../../is-object/index.js';
import { containerText } from './container-text/index.js';
import { dateText } from './date-text/index.js';
export function format(value, indent = '', seen = []) {
    if (typeof value === 'string') {
        return JSON.stringify(value);
    }
    if (typeof value === 'bigint') {
        return `${value}n`;
    }
    if (typeof value === 'symbol') {
        return value.toString();
    }
    if (typeof value === 'function') {
        return value.name === '' ? '[Function]' : `[Function ${value.name}]`;
    }
    if (!isObject(value)) {
        return String(value);
    }
    if (asymmetry.isAsymmetric(value)) {
        return value.text;
    }
    if (seen.includes(value)) {
        return '[Circular]';
    }
    if (value instanceof Error) {
        return `${value.name}: ${value.message}`;
    }
    if (value instanceof RegExp) {
        return String(value);
    }
    const isDate = value instanceof Date;
    return isDate
        ? dateText(value)
        : containerText(value, indent, part => format(part, `${indent}  `, [...seen, value]));
}

