import { strict as assert } from 'node:assert';
import { CellSegmenter } from '../../runtime/terminal/cell-segmenter.mjs';
// The Unicode Bidi Algorithm is taken from the 2.1.88 source map's bidi-js,
// while 2.1.310's packed screen/run ABI comes from its actual JS call sites.
function decode(text, reorder) {
    const segmenter = new CellSegmenter({ ambiguousIsNarrow: true });
    const cells = new Int32Array(256), runs = new Int32Array(256);
    const count = segmenter.segment(text, cells, runs, reorder);
    assert.ok(count >= 0);
    const records = Array.from({ length: count }, (_, i) => {
        const logical = cells[2 * i + 1] >>> 10;
        return {
            char: segmenter.graphemes[cells[2 * i]], logical,
            style: segmenter.sgrKeys[runs[2 * logical]],
            url: segmenter.uris[runs[2 * logical + 1]],
        };
    });
    return { segmenter, cells, runs, count, records };
}
const original = decode('A\x1b[31mאב\x1b[0mB', false);
const reversed = decode('A\x1b[31mאב\x1b[0mB', true);
assert.equal(original.records.map(x => x.char).join(''), 'AאבB');
assert.equal(reversed.records.map(x => x.char).join(''), 'AבאB');
assert.deepEqual(reversed.records.map(x => x.logical), [0, 2, 1, 3]);
assert.deepEqual(reversed.records.map(x => x.style), ['', '\x1b[31m', '\x1b[31m', '']);
const hyperlink = decode('L\x1b]8;;https://example.test\x07אָב\x1b]8;;\x07R', true);
assert.deepEqual(hyperlink.records.map(x => x.char), ['L', 'ב', 'אָ', 'R']);
assert.deepEqual(hyperlink.records.map(x => x.url), ['', 'https://example.test', 'https://example.test', '']);
// Pure LTR and non-reordered text must preserve original cells/run mapping.
const ltr = decode('ASCII 中文 👩‍💻', true);
assert.deepEqual(ltr.records.map(x => x.logical), Array.from({ length: ltr.count }, (_, i) => i));
const notReordered = decode('Hello שלום', false);
assert.deepEqual(notReordered.records.map(x => x.logical), Array.from({ length: notReordered.count }, (_, i) => i));
console.log(JSON.stringify({ status: 'pass', scenarios: 4, layout: 'logical runs preserved / visual cells reordered', implementation: '2.1.88 bidi-js + 2.1.310 packed ABI' }));