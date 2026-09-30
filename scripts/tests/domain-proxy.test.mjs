import assert from 'node:assert/strict';
import { test } from 'node:test';
import { cacheControl } from '../../domain-proxy/src/cache.js';

const url = path => new URL(`https://mindustry.example.com${path}`);

test('only content-versioned URLs are immutable', () => {
  assert.match(cacheControl(url('/assets/sprites/sprites.png?v=0123456789abcdef'), 200), /immutable/);
  assert.match(cacheControl(url('/js/mindustry.js?v=0123456789abcdef'), 200), /immutable/);
  assert.equal(cacheControl(url('/assets/sprites/sprites.png'), 200), 'public, max-age=0, must-revalidate');
  assert.equal(cacheControl(url('/assets-manifest.json'), 200), 'public, max-age=0, must-revalidate');
  assert.equal(cacheControl(url('/'), 200), 'public, max-age=0, must-revalidate');
});

test('error responses are never cached, even for versioned URLs', () => {
  for (const status of [301, 404, 500, 503]) {
    assert.equal(cacheControl(url('/assets/maps/serpulo/groundZero.msav?v=abc'), status), 'no-store');
  }
});
