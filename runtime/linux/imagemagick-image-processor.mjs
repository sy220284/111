/**
 * Linux 图像处理后端：根据 2.1.88 image-processor-src 的调用协议，
 * 为恢复工程提供真正可执行的 resize / jpeg / png / webp 路径。
 *
 * 图像解码和编码由系统 ImageMagick 执行；操作排队、参数语义和进程管理为 TypeScript。
 * 这不是对原 Rust 图像编解码器的源码恢复，也不能替代对它的所有行为验证。
 */
import { runBoundedProcess } from './process-transport.mjs';
async function runMagick(binary, args, input, limits) {
    const result = await runBoundedProcess(binary, args, input, {
        timeoutMs: limits.timeoutMs ?? 60000,
        maxStdoutBytes: limits.maxStdoutBytes ?? 128 * 1024 * 1024,
        maxStderrBytes: limits.maxStderrBytes ?? 64 * 1024,
    });
    if (result.code !== 0)
        throw new Error(`ImageMagick failed (${result.code}): ${result.stderr.toString('utf8').slice(0, 2000)}`);
    return result.stdout;
}
export class ImageMagickProcessor {
    binary;
    limits;
    transformations = [];
    source;
    meta;
    constructor(bytes, binary = 'magick', limits = {}) {
        this.binary = binary;
        this.limits = limits;
        this.source = Buffer.from(bytes);
    }
    async inspect() {
        const text = (await runMagick(this.binary, ['identify', '-format', '%w %h %m', '-'], this.source, this.limits)).toString('utf8').trim();
        const match = /^(\d+) (\d+) (\S+)$/.exec(text);
        if (!match)
            throw new Error(`无法解析 ImageMagick 元数据：${text}`);
        return this.meta = { width: Number(match[1]), height: Number(match[2]), format: match[3].toLowerCase() };
    }
    metadata() { if (!this.meta)
        throw new Error('调用 metadata 前需要 await inspect()'); return { ...this.meta }; }
    resize(width, height, options) {
        if (!Number.isInteger(width) || width <= 0 || !Number.isInteger(height) || height <= 0)
            throw new RangeError('图像尺寸必须是正整数');
        this.transformations.push({ kind: 'resize', width, height, options });
        return this;
    }
    jpeg(quality) { this.transformations.push({ kind: 'jpeg', quality }); return this; }
    png(options) { this.transformations.push({ kind: 'png', options }); return this; }
    webp(quality) { this.transformations.push({ kind: 'webp', quality }); return this; }
    async toBuffer() {
        const args = ['-'];
        let format = 'png';
        const inputFormat = (await this.inspect()).format.toLowerCase();
        format = inputFormat === 'jpg' ? 'jpeg' : inputFormat;
        for (const op of this.transformations) {
            if (op.kind === 'resize') {
                const { width, height, options } = op;
                const size = `${width}x${height}`;
                const bound = options?.withoutEnlargement ? '>' : '';
                switch (options?.fit ?? 'cover') {
                    case 'fill':
                        args.push('-resize', `${size}!${bound}`);
                        break;
                    case 'inside':
                        args.push('-resize', `${size}${bound}`);
                        break;
                    case 'cover':
                        args.push('-resize', `${size}^${bound}`, '-gravity', 'center', '-extent', size);
                        break;
                    case 'contain':
                        args.push('-resize', `${size}${bound}`, '-background', 'none', '-gravity', 'center', '-extent', size);
                        break;
                    default: throw new Error(`尚未实现的图像适配模式：${options?.fit}`);
                }
            }
            else if (op.kind === 'jpeg' || op.kind === 'webp') {
                format = op.kind;
                if (op.quality !== undefined)
                    args.push('-quality', String(op.quality));
            }
            else if (op.kind === 'png') {
                format = 'png';
                if (op.options?.compressionLevel !== undefined)
                    args.push('-define', `png:compression-level=${op.options.compressionLevel}`);
                if (op.options?.colors !== undefined)
                    args.push('-colors', String(op.options.colors));
                if (op.options?.palette)
                    args.push('-type', 'Palette');
            }
        }
        return runMagick(this.binary, [...args, `${format}:-`], this.source, this.limits);
    }
}
export function imageMagickDecoder(binary = 'magick', limits = {}) {
    return async (bytes) => { const processor = new ImageMagickProcessor(bytes, binary, limits); await processor.inspect(); return processor; };
}