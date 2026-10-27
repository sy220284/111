// Node implementation of terminal display width for Bun.stringWidth(text,
// { ambiguousIsNarrow: true }). Keep the native Bun implementation on Bun.
const segmenter = new Intl.Segmenter(undefined, { granularity: 'grapheme' });
const escapeCodes = /\u001B(?:\[[0-?]*[ -/]*[@-~]|\][^\u0007\u001B]*(?:\u0007|\u001B\\))/g;
const invisible = /[\p{Mark}\p{Control}\p{Format}]/u;
const emoji = /\p{Extended_Pictographic}|\p{Emoji_Presentation}/u;
const regional = /[\u{1F1E6}-\u{1F1FF}]/u;
function isWide(cp) {
  return cp >= 0x1100 && (
    cp <= 0x115f || cp === 0x2329 || cp === 0x232a ||
    (cp >= 0x2e80 && cp <= 0xa4cf && cp !== 0x303f) ||
    (cp >= 0xac00 && cp <= 0xd7a3) ||
    (cp >= 0xf900 && cp <= 0xfaff) ||
    (cp >= 0xfe10 && cp <= 0xfe19) ||
    (cp >= 0xfe30 && cp <= 0xfe6f) ||
    (cp >= 0xff00 && cp <= 0xff60) ||
    (cp >= 0xffe0 && cp <= 0xffe6) ||
    (cp >= 0x1b000 && cp <= 0x1b001) ||
    (cp >= 0x1f200 && cp <= 0x1f251) ||
    (cp >= 0x20000 && cp <= 0x3fffd)
  );
}
export function nodeTerminalStringWidth(value) {
  const text = String(value).replace(escapeCodes, '');
  let width = 0;
  for (const { segment } of segmenter.segment(text)) {
    if (regional.test(segment) || emoji.test(segment) ||
      segment.includes('\u20e3') || segment.includes('\ufe0f')) {
      width += 2;
      continue;
    }
    let cp;
    for (const character of segment) {
      if (!invisible.test(character)) { cp = character.codePointAt(0); break; }
    }
    if (cp !== undefined) width += isWide(cp) ? 2 : 1;
  }
  return width;
}
