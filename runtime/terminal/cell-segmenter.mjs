/**
 * Independent high-level implementation of the terminal cell API required by
 * Claude Code 2.1.310. Implements Unicode grapheme segmentation, ANSI SGR and
 * hyperlink run tracking, terminal width, packed cell painting and setCell.
 * The original @anthropic-ai/bun-internal source is NOT available.
 */
import bidiFactory from './vendor/bidi-2188.cjs';
import { nodeTerminalStringWidth } from '../string-width.mjs';
export class CellSegmenter {
    graphemes = ['', ' '];
    sgrKeys = [''];
    sgrCloseKeys = [''];
    uris = [''];
    graphemeIds = new Map([['', 0], [' ', 1]]);
    styleIds = new Map([['', 0]]);
    uriIds = new Map([['', 0]]);
    textSegmenter = new Intl.Segmenter(undefined, { granularity: 'grapheme' });
    bidiEngine;
    screen;
    styles = new Map();
    uri = '';
    ambiguousIsNarrow;
    substitute;
    constructor(config = {}) {
        this.screen = config.screen ?? {};
        this.ambiguousIsNarrow = config.ambiguousIsNarrow ?? true;
        this.substitute = (config.substitute ?? []);
    }
    intern(value, values, indexes) {
        let index = indexes.get(value);
        if (index !== undefined)
            return index;
        index = values.length;
        indexes.set(value, index);
        values.push(value);
        return index;
    }
    style() {
        const codes = [...this.styles.values()];
        const key = codes.join('\x00');
        const existing = this.styleIds.get(key);
        if (existing !== undefined)
            return existing;
        const closeByGroup = {
            bold: '\x1b[22m', dim: '\x1b[22m', italic: '\x1b[23m',
            underline: '\x1b[24m', blink: '\x1b[25m', inverse: '\x1b[27m',
            conceal: '\x1b[28m', strike: '\x1b[29m', fg: '\x1b[39m',
            bg: '\x1b[49m', overline: '\x1b[55m', underlineColor: '\x1b[59m',
        };
        const closes = [...this.styles.keys()].map(group => closeByGroup[group]);
        const id = this.sgrKeys.length;
        this.sgrKeys.push(key);
        this.sgrCloseKeys.push(closes.join('\x00'));
        this.styleIds.set(key, id);
        return id;
    }
    /**
     * SGR parser derived from the verified 2.1.88 termio rules and the 2.1.310
     * caller ABI (the latter accepts only canonical semicolon-form CSI m codes).
     * Colon-form extended colors are normalized before interning.
     */
    sgr(code) {
        const raw = code.startsWith('\x1b[') ? code.slice(2, -1) : code.slice(1, -1);
        const tokens = raw.split(';');
        for (let i = 0; i < tokens.length; i++) {
            const token = tokens[i];
            const colon = token.includes(':');
            const fields = token.split(':');
            const n = fields[0] === '' ? 0 : Number(fields[0]);
            if (!Number.isInteger(n))
                continue;
            if (n === 0) {
                this.styles.clear();
                continue;
            }
            let group = '';
            let normalized = String(n);
            if (n === 1)
                group = 'bold';
            else if (n === 2)
                group = 'dim';
            else if (n === 3)
                group = 'italic';
            else if (n === 4) {
                // 2.1.310's style decoder supports 4 and 21, not newer Kitty
                // underline variants. Keep their visible underline semantics.
                const variant = colon ? Number(fields[1] ?? 1) : 1;
                if (variant === 0) {
                    this.styles.delete('underline');
                    continue;
                }
                normalized = variant === 2 ? '21' : '4';
                group = 'underline';
            }
            else if (n === 21) {
                group = 'underline';
                normalized = '21';
            }
            else if (n === 5 || n === 6)
                group = 'blink';
            else if (n === 7)
                group = 'inverse';
            else if (n === 8)
                group = 'conceal';
            else if (n === 9)
                group = 'strike';
            else if (n === 53)
                group = 'overline';
            else if (n === 22) {
                this.styles.delete('bold');
                this.styles.delete('dim');
                continue;
            }
            else if (n === 23) {
                this.styles.delete('italic');
                continue;
            }
            else if (n === 24) {
                this.styles.delete('underline');
                continue;
            }
            else if (n === 25) {
                this.styles.delete('blink');
                continue;
            }
            else if (n === 27) {
                this.styles.delete('inverse');
                continue;
            }
            else if (n === 28) {
                this.styles.delete('conceal');
                continue;
            }
            else if (n === 29) {
                this.styles.delete('strike');
                continue;
            }
            else if (n === 39) {
                this.styles.delete('fg');
                continue;
            }
            else if (n === 49) {
                this.styles.delete('bg');
                continue;
            }
            else if (n === 55) {
                this.styles.delete('overline');
                continue;
            }
            else if (n === 59) {
                this.styles.delete('underlineColor');
                continue;
            }
            else if ((n >= 30 && n <= 37) || (n >= 90 && n <= 97))
                group = 'fg';
            else if ((n >= 40 && n <= 47) || (n >= 100 && n <= 107))
                group = 'bg';
            else if (n === 38 || n === 48 || n === 58) {
                group = n === 38 ? 'fg' : n === 48 ? 'bg' : 'underlineColor';
                let mode, numbers;
                if (colon) {
                    mode = Number(fields[1]);
                    numbers = fields.slice(2).filter(x => x !== '').map(Number);
                    // 38:2:<colorspace>:r:g:b; the colorspace is optional.
                    // A numeric colorspace field (normally 0) is ignored.
                    if (mode === 2 && numbers.length === 4)
                        numbers = numbers.slice(1);
                }
                else {
                    mode = Number(tokens[i + 1]);
                    const size = mode === 5 ? 1 : mode === 2 ? 3 : 0;
                    numbers = tokens.slice(i + 2, i + 2 + size).map(Number);
                    if (size === 0 || numbers.length !== size)
                        continue;
                    i += 1 + size;
                }
                const size = mode === 5 ? 1 : mode === 2 ? 3 : 0;
                if (size === 0 || numbers.length !== size || !numbers.every(v => Number.isInteger(v) && v >= 0 && v <= 255))
                    continue;
                normalized = [n, mode, ...numbers].join(';');
            }
            else
                continue;
            this.styles.set(group, `\x1b[${normalized}m`);
        }
    }
    /**
     * 2.1.310 explicitly passes bidi-format codepoint ranges as `substitute`.
     * 2.1.88 treated bidi as a layout concern; here these invisible controls
     * must not reach the rendered terminal as invisible direction overrides.
     * U+FFFD is an explicit, single-cell visible replacement. The exact
     * replacement glyph of the private original still needs binary comparison.
     */
    visibleText(value) {
        if (this.substitute.length === 0)
            return value;
        let out = '';
        for (const cp of value) {
            const n = cp.codePointAt(0);
            out += this.substitute.some(([start, end]) => n >= start && n <= end) ? '\ufffd' : cp;
        }
        return out;
    }
    width(grapheme) {
        if (grapheme === '\t')
            return 1;
        if (grapheme === '\n' || grapheme === '\r')
            return 0;
        return typeof Bun !== "undefined" && typeof Bun.stringWidth === "function"
            ? Bun.stringWidth(grapheme, { ambiguousIsNarrow: this.ambiguousIsNarrow })
            : nodeTerminalStringWidth(grapheme);
    }
    segment(text, cellBuffer, runBuffer, reorder = false) {
        // Retain SGR/hyperlink state within one segment call, not across unrelated lines.
        this.styles.clear();
        this.uri = '';
        const segments = [];
        for (let i = 0; i < text.length;) {
            // CSI: both 7-bit ESC [ and the standard C1 single-byte form.
            const csi = text.startsWith('\x1b[', i) ? 2 : text[i] === '\x9b' ? 1 : 0;
            if (csi) {
                const match = /^[0-9;:]*m/.exec(text.slice(i + csi));
                if (match) {
                    this.sgr(text.slice(i, i + csi) + match[0]);
                    i += csi + match[0].length;
                    continue;
                }
                // The 2.1.88 tokenizer recognizes all CSI final bytes, not only m.
                // Screen control operations are not printable glyphs.
                const control = /^[\x30-\x3f]*[\x20-\x2f]*[\x40-\x7e]/.exec(text.slice(i + csi));
                if (control) {
                    i += csi + control[0].length;
                    continue;
                }
            }
            // OSC 8: ESC ] 8 ; params ; URI BEL/ST.  Parameters may contain
            // an `id=` field; it does not change the URI interned for the renderer.
            const osc = text.startsWith('\x1b]', i) ? 2 : text[i] === '\x9d' ? 1 : 0;
            if (osc) {
                const commandStart = i + osc;
                const bell = text.indexOf('\x07', commandStart);
                const st = text.indexOf('\x1b\\', commandStart);
                const c1st = text.indexOf('\x9c', commandStart);
                const ends = [{ at: bell, size: 1 }, { at: st, size: 2 }, { at: c1st, size: 1 }]
                    .filter(e => e.at >= 0).sort((a, b) => a.at - b.at);
                if (ends.length) {
                    const end = ends[0];
                    const payload = text.slice(commandStart, end.at);
                    if (payload.startsWith('8;')) {
                        const separator = payload.indexOf(';', 2);
                        if (separator >= 0)
                            this.uri = payload.slice(separator + 1);
                    }
                    i = end.at + end.size;
                    continue;
                }
                // An unterminated OSC is non-printable, too.
                i = text.length;
                continue;
            }
            // DCS/APC/PM/SOS are terminated by ST and must never leak their
            // payload into the text buffer.  These are identified in the 2.1.88
            // termio streaming parser but were missing in our replacement.
            const stringControl = text.startsWith('\x1bP', i) || text.startsWith('\x1b_', i) ||
                text.startsWith('\x1b^', i) || text.startsWith('\x1bX', i) ||
                ['\x90', '\x9f', '\x9e', '\x98'].includes(text[i]);
            if (stringControl) {
                const start = i + (text[i] === '\x1b' ? 2 : 1);
                const st = text.indexOf('\x1b\\', start), c1 = text.indexOf('\x9c', start);
                const ends = [{ at: st, size: 2 }, { at: c1, size: 1 }].filter(e => e.at >= 0).sort((a, b) => a.at - b.at);
                i = ends.length ? ends[0].at + ends[0].size : text.length;
                continue;
            }
            // Preserve unrecognized escapes as characters rather than consume
            // arbitrary trailing text; only recognized controls affect state.
            const next = [text.indexOf('\x1b', i + 1), text.indexOf('\x9b', i + 1), text.indexOf('\x9d', i + 1)]
                .filter(n => n >= 0).reduce((a, b) => Math.min(a, b), text.length);
            const part = text.slice(i, next);
            for (const { segment } of this.textSegmenter.segment(this.visibleText(part))) {
                segments.push({ value: segment, width: this.width(segment), style: this.style(), uri: this.intern(this.uri, this.uris, this.uriIds) });
            }
            i = next;
        }
        const required = segments.length;
        if (cellBuffer.length < required * 2 || runBuffer.length < required * 2)
            return -required;
        // The 2.1.310 adapter indexes runs by their *logical* position, even when
        // screen cells are reordered.  Keep runBuffer in that order; the upper
        // bits of the packed cell word carry its original run index.
        for (let i = 0; i < required; i++) {
            runBuffer[2 * i] = segments[i].style;
            runBuffer[2 * i + 1] = segments[i].uri;
        }
        const order = Array.from({ length: required }, (_, i) => i);
        if (reorder && required > 0) {
            const plain = segments.map(s => s.value).join('');
            // Same RTL fast path and level-run reversal as 2.1.88 ink/bidi.ts.
            if (/[\u0590-\u05ff\ufb1d-\ufb4f\u0600-\u06ff\u0750-\u077f\u08a0-\u08ff\ufb50-\ufdff\ufe70-\ufeff\u0780-\u07bf\u0700-\u074f]/u.test(plain)) {
                this.bidiEngine ??= bidiFactory();
                const { levels } = this.bidiEngine.getEmbeddingLevels(plain, 'auto');
                const charLevels = [];
                let offset = 0;
                for (const item of segments) {
                    charLevels.push(levels[offset] ?? 0);
                    offset += item.value.length;
                }
                for (let level = Math.max(...charLevels); level >= 1; level--) {
                    for (let i = 0; i < required;) {
                        if (charLevels[i] >= level) {
                            let j = i + 1;
                            while (j < required && charLevels[j] >= level)
                                j++;
                            for (let a = i, b = j - 1; a < b; a++, b--) {
                                [order[a], order[b]] = [order[b], order[a]];
                                [charLevels[a], charLevels[b]] = [charLevels[b], charLevels[a]];
                            }
                            i = j;
                        }
                        else
                            i++;
                    }
                }
            }
        }
        for (let i = 0; i < required; i++) {
            const logical = order[i];
            const s = segments[logical];
            cellBuffer[2 * i] = this.intern(s.value, this.graphemes, this.graphemeIds);
            cellBuffer[2 * i + 1] = (logical << 10) | (s.value === '\t' ? 256 : Math.min(2, s.width));
        }
        return required;
    }
    packed(begin, end, finish) {
        return end * 2 ** 36 + begin * 2 ** 20 + finish;
    }
    /**
     * Replicates the wide-cell orphan cleanup from 2.1.88 ink/screen.ts
     * setCellAt, but operates directly on 2.1.310's packed [char,word] ABI.
     */
    setCell(cells, width, x, y, char, word) {
        const height = cells.length / (width * 2);
        if (x < 0 || x >= width || y < 0 || y >= height)
            return this.packed(x, x, x);
        const at = (y * width + x) * 2;
        const mask = this.screen.widthMask ?? 3;
        const wide = this.screen.wide ?? 1;
        const tail = this.screen.spacerTail ?? 2;
        const narrow = this.screen.narrow ?? 0;
        const empty = this.screen.emptyCharIndex ?? 0;
        const emptyWord = this.screen.emptyWord ?? 0;
        const clear = (offset) => {
            cells[offset] = empty;
            cells[offset + 1] = (emptyWord & ~mask) | narrow;
        };
        const previous = cells[at + 1] & mask;
        const current = word & mask;
        let left = x, right = x + 1;
        // Replacing a wide head must also clear its old tail.
        if (previous === wide && current !== wide && x + 1 < width) {
            const next = at + 2;
            if ((cells[next + 1] & mask) === tail) {
                clear(next);
                right = x + 2;
            }
        }
        // Replacing a tail must erase the old 2-column glyph's head.
        if (previous === tail && current !== tail && x > 0) {
            const head = at - 2;
            if ((cells[head + 1] & mask) === wide) {
                clear(head);
                left = x - 1;
            }
        }
        cells[at] = char;
        cells[at + 1] = word;
        if (current === wide && x + 1 < width) {
            const next = at + 2;
            // The new spacer overwrites another wide head: clear its stale tail.
            if ((cells[next + 1] & mask) === wide && x + 2 < width) {
                const orphan = at + 4;
                if ((cells[orphan + 1] & mask) === tail) {
                    clear(orphan);
                    right = x + 3;
                }
            }
            cells[next] = this.screen.spacerCharIndex ?? 1;
            cells[next + 1] = (emptyWord & ~mask) | tail;
            right = Math.max(right, x + 2);
        }
        return this.packed(left, right, x + (current === wide && x + 1 < width ? 2 : 1));
    }
    paint(cells, width, x, y, segments, count, _unused, charIds, words) {
        let right = x;
        let dirtyLeft = x, dirtyRight = x;
        const height = Math.floor(cells.length / (Math.max(1, width) * 2));
        if (y < 0 || y >= height || width <= 0)
            return this.packed(x, x, x);
        const update = (packed) => {
            dirtyLeft = Math.min(dirtyLeft, Math.floor(packed / 2 ** 20) % 65536);
            dirtyRight = Math.max(dirtyRight, Math.floor(packed / 2 ** 36));
        };
        for (let i = 0; i < count; i++) {
            const graphemeId = segments[2 * i];
            const packed = segments[2 * i + 1];
            const run = packed >>> 10;
            const tab = (packed & 256) !== 0;
            const advance = tab ? (this.screen.tabWidth ?? 8) - right % (this.screen.tabWidth ?? 8) : packed & 255;
            const word = words[run] ?? 0;
            if (advance === 0)
                continue;
            for (let col = 0; col < advance; col++) {
                if (right >= width)
                    return this.packed(dirtyLeft, dirtyRight, right);
                const first = col === 0;
                const isWide = advance === 2 && !tab;
                const value = first ? charIds[graphemeId] ?? (this.screen.emptyCharIndex ?? 0) : (this.screen.spacerCharIndex ?? 1);
                const cellWord = (word & ~3) | (isWide ? (first ? (this.screen.wide ?? 1) : (this.screen.spacerTail ?? 2)) : (this.screen.narrow ?? 0));
                // The 2.1.88 screen.ts orphan-cleanup rules apply to text paint as
                // well as isolated setCell writes. Preserve 2.1.310's final word
                // encoding, including the styled SpacerTail on second-wide-column.
                update(this.setCell(cells, width, right, y, value, cellWord));
                right++;
            }
        }
        return this.packed(dirtyLeft, dirtyRight, right);
    }
}