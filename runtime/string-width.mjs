// Node implementation of terminal display width for Bun.stringWidth(text,
// { ambiguousIsNarrow: true }). Keep the native Bun implementation on Bun.
const segmenter = new Intl.Segmenter(undefined, { granularity: 'grapheme' });
// Width is measured on visible glyphs, not the payloads of terminal control
// strings. The old CSI/OSC regex ignored DCS/APC and 8-bit C1 forms, making
// arbitrary terminal metadata count as visible columns.
function stripTerminalControls(text) {
  let visible = '';
  const len = text.length;
  let i = 0;
  const isStringControl = code => code === 0x5d || code === 0x50 ||
    code === 0x58 || code === 0x5e || code === 0x5f;
  while (i < len) {
    const code = text.charCodeAt(i);
    let next;
    if (code === 0x1b) {
      next = text.charCodeAt(i + 1);
      if (next === 0x5b) { // ESC [ : CSI
        i += 2;
        while (i < len) {
          const cp = text.charCodeAt(i++);
          if (cp >= 0x40 && cp <= 0x7e) break;
        }
        continue;
      }
      if (isStringControl(next)) { // ESC ] / P / X / ^ / _ : OSC/DCS/SOS/PM/APC
        i += 2;
      } else if (Number.isFinite(next)) {
        // ESC intermediates select character sets, not printable glyphs.
        i += next >= 0x20 && next <= 0x2f && i + 2 < len ? 3 : 2;
        continue;
      } else { i++; continue; }
    } else if (code === 0x9b) { // 8-bit CSI
      i++;
      while (i < len) {
        const cp = text.charCodeAt(i++);
        if (cp >= 0x40 && cp <= 0x7e) break;
      }
      continue;
    } else if (code >= 0x98 && code <= 0x9f && code !== 0x9c) {
      // C1 SOS, DCS, OSC, PM and APC, terminated by ST.
      i++;
    } else {
      visible += text[i++];
      continue;
    }
    // Consume a terminal control string through BEL or ST (8-bit or ESC \\).
    while (i < len) {
      const cp = text.charCodeAt(i++);
      if (cp === 0x07 || cp === 0x9c) break;
      if (cp === 0x1b && text.charCodeAt(i) === 0x5c) { i++; break; }
    }
  }
  return visible;
}
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
  const text = stripTerminalControls(String(value));
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
