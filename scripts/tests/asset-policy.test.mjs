import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import vm from 'node:vm';

const webapp = new URL('../../browser-game/src/main/webapp/', import.meta.url);
const source = readFileSync(new URL('asset-preloader.js', webapp), 'utf8');
const manifest = JSON.parse(readFileSync(new URL('../../browser-port/assets-manifest.json', import.meta.url), 'utf8'));

function policy({ languages = ['en-US', 'en'], maxTextureSize = 16384 } = {}) {
  const context = vm.createContext({ navigator: { languages } });
  vm.runInContext(source, context);
  return context.assetPolicy({ maxTextureSize, renderer: '', lowEnd: false });
}

const startupBytes = preload => manifest.files.filter(preload).reduce((sum, file) => sum + file.bytes, 0);

test('audio, unused atlases, other languages and desktop icons are not downloaded at startup', () => {
  const preload = policy();
  assert.equal(preload({ path: 'music/menu.ogg' }), false);
  assert.equal(preload({ path: 'sounds/ui/click.ogg' }), false);
  assert.equal(preload({ path: 'sprites/fallback/sprites.png' }), false);
  assert.equal(preload({ path: 'sprites/sprites.png' }), true);
  assert.equal(preload({ path: 'bundles/bundle.properties' }), true);
  assert.equal(preload({ path: 'bundles/bundle_de.properties' }), false);
  assert.equal(preload({ path: 'fonts/font_jp.woff' }), false);
  assert.equal(preload({ path: 'fonts/font.woff' }), true);
  assert.equal(preload({ path: 'icons/icon.icns' }), false);
  assert.equal(preload({ path: 'maps/serpulo/groundZero.msav' }), true);
  assert.ok(startupBytes(preload) < 25 * 1048576, `startup assets are ${startupBytes(preload)} bytes`);
});

test('the player\'s languages and a small-texture GPU get what they need', () => {
  const dutch = policy({ languages: ['nl-BE', 'nl'] });
  assert.equal(dutch({ path: 'bundles/bundle_nl.properties' }), true);
  assert.equal(dutch({ path: 'bundles/bundle_nl_BE.properties' }), true);
  assert.equal(dutch({ path: 'bundles/bundle_de.properties' }), false);

  assert.equal(policy({ languages: ['ja'] })({ path: 'fonts/font_jp.woff' }), true);

  const smallGpu = policy({ maxTextureSize: 2048 });
  assert.equal(smallGpu({ path: 'sprites/fallback/sprites.png' }), true);
  assert.equal(smallGpu({ path: 'sprites/fallback/sprites.aatls' }), true);
  assert.equal(smallGpu({ path: 'sprites/sprites.png' }), false);
});
