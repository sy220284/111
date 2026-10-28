# 可执行高层终端屏幕单元兼容实现（非默认渲染器）

这一目录把先前本地 2.1.310 重建工程中的真实 TypeScript 逻辑转成 111 Node/Bun 都可以直接加载的 ECMAScript 模块。**不是** Bun 内置 `CellSegmenter` 的反编译原件，也不宣称所有终端二进制 ABI 或布局行为完全等价。

## 实现与来源

- `runtime/terminal/cell-segmenter.mjs`：Unicode 字素切分、SGR、OSC 8 链接、C1 控制序列、文字单元标记、`paint`/`setCell` 宽字符清理、字素与样式索引、双向文本重排。结合 2.1.310 恢复的运行时 ABI 和 2.1.88 `ink/screen.ts`、`ink/bidi.ts` 实际语义完成独立实现。
- `runtime/terminal/vendor/bidi-2188.cjs`：2.1.88 恢复源码引用的 bidi-js 实现，源项目是 Jason Johnston 的 MIT 许可 `lojjic/bidi-js`；保留 LICENSE.txt 作为第三方授权文件。后续可使用锁定依赖替换本地 vendor；未经重新核对不要声称它与其它版本二进制逐字节相同。
- 字符宽度直接调用 111 已有的 `runtime/string-width.mjs`（Node），在 Bun 存在时调用 `Bun.stringWidth`，不复制已有字符串宽度算法。

## 测试

`node --test tests/terminal/cell_segmenter*.test.mjs`（四个 Node 22 测试文件）。测试验证 Unicode、SGR、分段/绘制、RTL 视觉重排、独立与绘制时的宽字符残留清理。

## 集成边界

这是**显式导入**的独立可调用模块。PR 不修改 111 原有 `core/` 业务源码、不向 Bun 全局作用域注入替代组件、不覆盖默认 CLI。全量边界、真实光标布局与终端二进制结构的原版差异验证仍未完成；与原版私有组件不等价的特性须作为后续修复处理。