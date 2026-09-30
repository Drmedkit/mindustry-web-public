import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import vm from 'node:vm';
import zlib from 'node:zlib';

const source = readFileSync(new URL('../../browser-game/src/main/webapp/png-decoder.js', import.meta.url), 'utf8');
const context = vm.createContext({ DecompressionStream, Response, Blob, DataView, Uint8Array, Math, String });
vm.runInContext(source, context);

function crc32(bytes) {
  let crc = ~0;
  for (const byte of bytes) {
    crc ^= byte;
    for (let k = 0; k < 8; k++) crc = crc & 1 ? (crc >>> 1) ^ 0xedb88320 : crc >>> 1;
  }
  return ~crc >>> 0;
}

function chunk(type, data) {
  const out = Buffer.alloc(12 + data.length);
  out.writeUInt32BE(data.length, 0);
  out.write(type, 4, 'latin1');
  Buffer.from(data).copy(out, 8);
  out.writeUInt32BE(crc32(out.subarray(4, 8 + data.length)), 8 + data.length);
  return out;
}

// Encodes RGBA pixels, using a different filter on each row.
function encode(width, height, rgba, filters) {
  const stride = width * 4;
  const raw = Buffer.alloc(height * (stride + 1));
  for (let y = 0; y < height; y++) {
    const filter = filters[y % filters.length];
    raw[y * (stride + 1)] = filter;
    for (let x = 0; x < stride; x++) {
      const value = rgba[y * stride + x];
      const a = x >= 4 ? rgba[y * stride + x - 4] : 0;
      const b = y > 0 ? rgba[(y - 1) * stride + x] : 0;
      const c = x >= 4 && y > 0 ? rgba[(y - 1) * stride + x - 4] : 0;
      const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
      const predicted = [0, a, b, (a + b) >> 1, pa <= pb && pa <= pc ? a : pb <= pc ? b : c][filter];
      raw[y * (stride + 1) + 1 + x] = (value - predicted) & 255;
    }
  }
  const header = Buffer.alloc(13);
  header.writeUInt32BE(width, 0);
  header.writeUInt32BE(height, 4);
  header[8] = 8; header[9] = 6;
  return new Uint8Array(Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    chunk('IHDR', header), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))
  ]));
}

test('decodes every PNG filter type exactly', async () => {
  const width = 7, height = 10;
  const rgba = Uint8Array.from({ length: width * height * 4 }, (_, i) => (i * 37 + (i >> 3) * 11) & 255);
  const decoded = await context.decodePng(encode(width, height, rgba, [0, 1, 2, 3, 4]));
  assert.equal(decoded.width, width);
  assert.equal(decoded.height, height);
  assert.deepEqual([...decoded.rgba], [...rgba]);
});

test('declines formats Java should decode instead', async () => {
  const png = encode(1, 1, Uint8Array.from([1, 2, 3, 4]), [0]);
  const interlaced = png.slice();
  interlaced[28] = 1; // IHDR interlace byte
  assert.equal(await context.decodePng(interlaced), null);
  assert.equal(await context.decodePng(Uint8Array.from([1, 2, 3])), null);
});
