export class LazyImagePipeline {
    input;
    decode;
    processor;
    operations = [];
    applied = 0;
    constructor(input, decode) {
        this.input = input;
        this.decode = decode;
    }
    open() { return this.processor ??= this.decode(this.input); }
    async apply() { const p = await this.open(); for (; this.applied < this.operations.length; this.applied++)
        this.operations[this.applied](p); return p; }
    async metadata() { return (await this.open()).metadata(); }
    resize(w, h, options) { this.operations.push(p => { p.resize(w, h, options); }); return this; }
    jpeg(quality) { this.operations.push(p => { p.jpeg(quality); }); return this; }
    png(options) { this.operations.push(p => { p.png(options); }); return this; }
    webp(quality) { this.operations.push(p => { p.webp(quality); }); return this; }
    async toBuffer() { return (await this.apply()).toBuffer(); }
}