import assert from 'node:assert/strict';
import { test } from 'node:test';
import { envelope, op, parseEnvelope } from '../../domain-proxy/src/relay-protocol.js';
import { allowedOrigin, buildMatches, newRoomCode, normalizeCode } from '../../domain-proxy/src/rooms.js';

test('room codes are six unambiguous characters', () => {
  for (let i = 0; i < 200; i++) {
    const code = newRoomCode();
    assert.match(code, /^[23456789ABCDEFGHJKMNPQRSTVWXYZ]{6}$/);
  }
  assert.equal(normalizeCode(' k7q2xm '), 'K7Q2XM');
  assert.equal(normalizeCode('K7Q2X'), null);
  assert.equal(normalizeCode('K7Q2X0'), null, '0 is not in the alphabet');
  assert.equal(normalizeCode('../../x'), null);
});

test('only the game pages may open or join rooms', () => {
  const env = { ALLOWED_ORIGINS: 'https://play.example.com', ALLOW_LOCALHOST: 'false' };
  assert.equal(allowedOrigin('https://play.example.com', env), true);
  assert.equal(allowedOrigin('https://game.example.net', env, 'https://game.example.net'), true, 'the Worker origin itself');
  assert.equal(allowedOrigin('https://evil.example', env, 'https://game.example.net'), false);
  assert.equal(allowedOrigin('https://evil.example', {}), false, 'no default origins');
  assert.equal(allowedOrigin('https://evil.example', env), false);
  assert.equal(allowedOrigin(null, env), false);
  assert.equal(allowedOrigin('http://localhost:8080', env), false);
  assert.equal(allowedOrigin('http://localhost:8080', { ...env, ALLOW_LOCALHOST: 'true' }), true);
});

test('players must run the deployed bundle', () => {
  const url = build => new URL(`https://x/ws/host${build ? `?v=${build}` : ''}`);
  assert.equal(buildMatches(url('abc'), { BUILD_ID: 'abc' }), true);
  assert.equal(buildMatches(url('old'), { BUILD_ID: 'abc' }), false);
  assert.equal(buildMatches(url(null), { BUILD_ID: 'abc' }), false);
  assert.equal(buildMatches(url('anything'), { BUILD_ID: 'any' }), true);
});

test('envelopes carry a 16-bit player id and the batch', () => {
  const bytes = envelope(op.to, 513, Uint8Array.from([9, 8, 7]));
  assert.deepEqual([...bytes], [0x10, 2, 1, 9, 8, 7]);
  const parsed = parseEnvelope(bytes);
  assert.equal(parsed.op, op.to);
  assert.equal(parsed.id, 513);
  assert.deepEqual([...parsed.payload], [9, 8, 7]);
  assert.equal(parseEnvelope(Uint8Array.from([1, 2])), null);
});

import { cleanListing } from '../../domain-proxy/src/listing.js';

test('public listings are short and carry no markup', () => {
  const listing = cleanListing({ name: '[red]Tobias[] <b>game</b>', map: 'x'.repeat(100), mode: 'co-op', players: 99, max: '8' });
  assert.equal(listing.name, 'Tobias bgame/b');
  assert.equal(listing.map.length, 40);
  assert.equal(listing.players, 64);
  assert.equal(listing.max, 8);
  assert.equal(cleanListing({ name: '[accent]' }), null, 'a name made only of tags is rejected');
  assert.equal(cleanListing(null), null);
});
