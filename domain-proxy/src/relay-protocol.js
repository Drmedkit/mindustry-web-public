// Binary envelope between the host's browser and its room (see room.js).
//   host -> room: [TO u8=0x10][id u16][batch]   [KICK u8=0x20][id u16]
//   room -> host: [FROM u8=0x01][id u16][batch] [JOIN u8=0x02][id u16][address utf8]
//                 [LEAVE u8=0x03][id u16]
// Players send and receive plain Mindustry packet batches with no envelope.

export const op = Object.freeze({ from: 0x01, join: 0x02, leave: 0x03, to: 0x10, kick: 0x20 });

export const MAX_PLAYERS = 8;
export const MAX_CLIENT_MESSAGE = 64 * 1024;

export function envelope(code, id, payload = new Uint8Array(0)) {
  const bytes = new Uint8Array(3 + payload.byteLength);
  bytes[0] = code;
  bytes[1] = id >> 8;
  bytes[2] = id & 255;
  bytes.set(payload, 3);
  return bytes;
}

export function parseEnvelope(bytes) {
  if (bytes.byteLength < 3) return null;
  return { op: bytes[0], id: (bytes[1] << 8) | bytes[2], payload: bytes.subarray(3) };
}
