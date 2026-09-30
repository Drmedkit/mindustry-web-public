// Reader for Arc's binary texture atlas format (.aatls), matching
// TextureAtlas.TextureAtlasData: "AATLS", a version byte, then per page an
// EOF-check byte, page image, size, filters, wrap modes and its regions.

export function readAtlas(buffer) {
    let offset = 0;
    const u8 = () => buffer[offset++];
    const s16 = () => { const value = buffer.readInt16BE(offset); offset += 2; return value; };
    const i32 = () => { const value = buffer.readInt32BE(offset); offset += 4; return value; };
    const str = () => {
        const length = buffer.readUInt16BE(offset);
        offset += 2;
        const value = buffer.toString("utf8", offset, offset + length);
        offset += length;
        return value;
    };

    if (buffer.toString("latin1", 0, 5) !== "AATLS") throw new Error("Not an AATLS atlas");
    offset = 6;
    const pages = [];
    while (offset < buffer.length) {
        offset++; // Reads.checkEOF consumes one byte per page.
        if (offset >= buffer.length) break;
        const page = {image: str(), width: s16(), height: s16(), regions: []};
        offset += 4; // min/mag filter, wrap x/y
        const count = i32();
        for (let i = 0; i < count; i++) {
            const region = {name: str(), x: s16(), y: s16(), width: s16(), height: s16()};
            for (let block = 0; block < 3; block++) {
                if (u8()) offset += 8; // offsets, splits, pads
            }
            page.regions.push(region);
        }
        pages.push(page);
    }
    return pages;
}
