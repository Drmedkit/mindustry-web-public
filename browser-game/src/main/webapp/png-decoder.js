// Decodes PNGs to straight (non-premultiplied) RGBA with the browser's native
// zlib (DecompressionStream). Java's pure-Java PNG decoder spends seconds on
// the large atlas pages under TeaVM (64-bit checksum maths becomes BigInt).
// Supports 8-bit, non-interlaced grayscale, RGB, palette, gray+alpha and RGBA;
// returns null for anything else so Java decodes it as before.

async function decodePng(bytes) {
  if (typeof DecompressionStream !== 'function') return null;
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const signature = [137, 80, 78, 71, 13, 10, 26, 10];
  if (bytes.length < 8 || signature.some((value, index) => bytes[index] !== value)) return null;

  let width = 0, height = 0, colorType = -1, palette = null, transparency = null;
  const idat = [];
  let offset = 8;
  while (offset + 8 <= bytes.length) {
    const length = view.getUint32(offset);
    const type = String.fromCharCode(bytes[offset + 4], bytes[offset + 5], bytes[offset + 6], bytes[offset + 7]);
    const data = bytes.subarray(offset + 8, offset + 8 + length);
    if (type === 'IHDR') {
      width = view.getUint32(offset + 8);
      height = view.getUint32(offset + 12);
      const bitDepth = data[8];
      colorType = data[9];
      if (bitDepth !== 8 || data[12] !== 0) return null; // 8-bit, non-interlaced only
    } else if (type === 'PLTE') {
      palette = data;
    } else if (type === 'tRNS') {
      transparency = data;
    } else if (type === 'IDAT') {
      idat.push(data);
    } else if (type === 'IEND') {
      break;
    }
    offset += 12 + length;
  }
  const channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[colorType];
  if (!channels || !width || !height || (colorType === 3 && !palette)) return null;

  const inflated = new Uint8Array(await new Response(new Blob(idat).stream().pipeThrough(new DecompressionStream('deflate'))).arrayBuffer());
  const stride = width * channels;
  if (inflated.length < height * (stride + 1)) return null;

  // Undo the per-scanline filters, one specialised loop per filter type. A zero
  // row stands in for the row above the first one.
  const raw = new Uint8Array(height * stride);
  const zero = new Uint8Array(stride);
  for (let y = 0; y < height; y++) {
    const filter = inflated[y * (stride + 1)];
    const line = inflated.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1));
    const out = raw.subarray(y * stride, (y + 1) * stride);
    const up = y > 0 ? raw.subarray((y - 1) * stride, y * stride) : zero;
    const c = channels;
    switch (filter) {
      case 0:
        out.set(line);
        break;
      case 1:
        for (let x = 0; x < c; x++) out[x] = line[x];
        for (let x = c; x < stride; x++) out[x] = (line[x] + out[x - c]) & 255;
        break;
      case 2:
        for (let x = 0; x < stride; x++) out[x] = (line[x] + up[x]) & 255;
        break;
      case 3:
        for (let x = 0; x < c; x++) out[x] = (line[x] + (up[x] >> 1)) & 255;
        for (let x = c; x < stride; x++) out[x] = (line[x] + ((out[x - c] + up[x]) >> 1)) & 255;
        break;
      case 4:
        for (let x = 0; x < c; x++) out[x] = (line[x] + up[x]) & 255;
        for (let x = c; x < stride; x++) {
          const a = out[x - c], b = up[x], d = up[x - c];
          const p = a + b - d;
          const pa = p > a ? p - a : a - p, pb = p > b ? p - b : b - p, pd = p > d ? p - d : d - p;
          out[x] = (line[x] + (pa <= pb && pa <= pd ? a : pb <= pd ? b : d)) & 255;
        }
        break;
      default:
        return null;
    }
  }
  if (colorType === 6) return {width, height, rgba: raw};

  const rgba = new Uint8Array(width * height * 4);
  for (let i = 0, j = 0; i < raw.length; i += channels, j += 4) {
    if (colorType === 0) {
      rgba[j] = rgba[j + 1] = rgba[j + 2] = raw[i];
      rgba[j + 3] = transparency && transparency.length >= 2 && raw[i] === transparency[1] ? 0 : 255;
    } else if (colorType === 4) {
      rgba[j] = rgba[j + 1] = rgba[j + 2] = raw[i];
      rgba[j + 3] = raw[i + 1];
    } else if (colorType === 2) {
      rgba[j] = raw[i]; rgba[j + 1] = raw[i + 1]; rgba[j + 2] = raw[i + 2];
      const transparent = transparency && transparency.length >= 6
        && raw[i] === transparency[1] && raw[i + 1] === transparency[3] && raw[i + 2] === transparency[5];
      rgba[j + 3] = transparent ? 0 : 255;
    } else {
      const index = raw[i];
      rgba[j] = palette[index * 3]; rgba[j + 1] = palette[index * 3 + 1]; rgba[j + 2] = palette[index * 3 + 2];
      rgba[j + 3] = transparency && index < transparency.length ? transparency[index] : 255;
    }
  }
  return {width, height, rgba};
}
