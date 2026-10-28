import { strict as assert } from 'node:assert';
import { CellSegmenter } from '../../runtime/terminal/cell-segmenter.mjs';
import { test } from 'node:test';
test('真实终端单元与宽字符/BiDi 行为：_wide_cleanup.test', () => {
const s = new CellSegmenter({ screen: { widthMask: 3, narrow: 0, wide: 1, spacerTail: 2, spacerHead: 3, emptyCharIndex: 0, spacerCharIndex: 1, emptyWord: 0 } });
const width = 6;
const blank = () => new Int32Array(width * 2);
const type = (cells, x) => cells[x * 2 + 1] & 3;
const char = (cells, x) => cells[x * 2];
const damage = (packed) => ({ left: Math.floor(packed / 1048576) % 65536, right: Math.floor(packed / 68719476736), finish: packed % 1048576 });
// 2.1.88 screen.ts: a narrow overwrite of a wide head clears SpacerTail.
let c = blank();
s.setCell(c, width, 1, 0, 99, 1);
assert.equal(type(c, 2), 2);
let p = s.setCell(c, width, 1, 0, 65, 0);
assert.equal(char(c, 1), 65);
assert.equal(type(c, 1), 0);
assert.equal(char(c, 2), 0);
assert.equal(type(c, 2), 0);
assert.deepEqual(damage(p), { left: 1, right: 3, finish: 2 });
// Overwriting SpacerTail must clear its matching wide head.
c = blank();
s.setCell(c, width, 1, 0, 99, 1);
p = s.setCell(c, width, 2, 0, 67, 0);
assert.equal(char(c, 1), 0);
assert.equal(type(c, 1), 0);
assert.equal(char(c, 2), 67);
assert.deepEqual(damage(p), { left: 1, right: 3, finish: 3 });
// Writing Wide on an existing tail clears the old wide head.
c = blank();
s.setCell(c, width, 1, 0, 99, 1);
p = s.setCell(c, width, 2, 0, 100, 1);
assert.equal(char(c, 1), 0);
assert.equal(char(c, 2), 100);
assert.equal(type(c, 2), 1);
assert.equal(type(c, 3), 2);
assert.deepEqual(damage(p), { left: 1, right: 4, finish: 4 });
// Overwriting an adjacent wide head creates no orphan tail at the next cell.
c = blank();
s.setCell(c, width, 2, 0, 99, 1);
p = s.setCell(c, width, 1, 0, 100, 1);
assert.equal(char(c, 1), 100);
assert.equal(type(c, 2), 2);
assert.equal(char(c, 3), 0);
assert.equal(type(c, 3), 0);
assert.deepEqual(damage(p), { left: 1, right: 4, finish: 3 });
// Boundaries: no stray writes across row or at rightmost screen column.
c = blank();
p = s.setCell(c, width, 5, 0, 88, 1);
assert.equal(char(c, 5), 88);
assert.equal(c.length, width * 2);
assert.deepEqual(damage(p), { left: 5, right: 6, finish: 6 });
console.log(JSON.stringify({ status: 'pass', scenarios: 5, source: '2.1.88 ink/screen.ts setCellAt' }));

});