#!/usr/bin/env node
// End-to-end check of the co-op relay. Start it first:
//   cd domain-proxy && npx wrangler dev --port 8787 --var ALLOW_LOCALHOST:true --var BUILD_ID:any
// then run: node scripts/check-relay.mjs [ws://127.0.0.1:8787]
import WebSocket from "ws";
import { envelope, op } from "../domain-proxy/src/relay-protocol.js";

const base = process.argv[2] ?? "ws://127.0.0.1:8787";
const options = { headers: { Origin: "http://localhost:8080" } };
const fail = message => { console.error(`FAIL: ${message}`); process.exit(1); };
setTimeout(() => fail("timed out"), 10000);

const host = new WebSocket(`${base}/ws/host?v=any`, options);
host.on("message", (data, binary) => {
  if (!binary) {
    const room = JSON.parse(data.toString());
    if (room.type !== "room" || !/^[A-Z2-9]{6}$/.test(room.code)) fail(`unexpected room message ${data}`);
    const player = new WebSocket(`${base}/ws/join/${room.code}?v=any`, options);
    player.on("open", () => player.send(Uint8Array.from([1, 2, 3])));
    player.on("message", reply => {
      if ([...new Uint8Array(reply)].join() !== "9,9") fail(`player got ${[...new Uint8Array(reply)]}`);
      console.log(`PASS: room ${room.code} relayed host <-> player traffic.`);
      process.exit(0);
    });
    return;
  }
  const bytes = new Uint8Array(data);
  if (bytes[0] === op.from) host.send(envelope(op.to, (bytes[1] << 8) | bytes[2], Uint8Array.from([9, 9])));
});
host.on("unexpected-response", (request, response) => fail(`host rejected with HTTP ${response.statusCode}`));
