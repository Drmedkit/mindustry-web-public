import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { readAtlas } from '../lib/aatls.mjs';
import { isHiddenSprite } from '../split-erekir-sprites.mjs';

const root = new URL('../../', import.meta.url);
const read = path => readFileSync(new URL(path, root));
const deferredPages = new Set(JSON.parse(read('browser-port/deferred-atlas-pages.json')).pages);
const manifest = JSON.parse(read('browser-port/assets-manifest.json'));
const snapshot = JSON.parse(read('browser-port/content-snapshot.json'));

const deferredSprites = new Set(read('browser-port/deferred-sprites.txt').toString().split('\n').filter(Boolean));

for (const atlas of ['sprites/sprites.aatls', 'sprites/fallback/sprites.aatls']) {
  test(`${atlas}: hidden-content sprites are alone on never-downloaded pages`, () => {
    const pages = readAtlas(read(`Mindustry/core/assets/${atlas}`));
    const directory = atlas.slice(0, atlas.lastIndexOf('/') + 1);
    let deferred = 0;
    for (const page of pages) {
      const pagePath = directory + page.image;
      const moved = page.regions.filter(region => deferredSprites.has(region.name));
      if (deferredPages.has(pagePath)) {
        deferred++;
        assert.equal(moved.length, page.regions.length, `${pagePath} holds sprites that must stay visible`);
        for (const region of page.regions) assert.ok(isHiddenSprite(region.name), `${region.name} is not hidden content`);
      } else {
        assert.deepEqual(moved.map(region => region.name), [], `${pagePath} holds deferred sprites`);
      }
    }
    assert.ok(deferred > 0, 'at least one page is deferred');
  });
}

test('terrain used on Serpulo maps keeps all of its sprites', () => {
  for (const name of ['red-stone-wall', 'carbon-wall', 'crystalline-stone', 'ore-crystal-thorium', 'redweed']) {
    assert.deepEqual([...deferredSprites].filter(sprite => sprite === name || sprite.includes(`${name}-`) || sprite.endsWith(`-${name}`)), [], name);
  }
});

test('the manifest marks exactly the deferred pages, so the browser never fetches them', () => {
  const marked = manifest.files.filter(file => file.deferred).map(file => file.path).sort();
  assert.deepEqual(marked, [...deferredPages].sort());
});

test('sprite ownership prefers the longest content name', () => {
  assert.equal(isHiddenSprite('reinforced-conduit-top'), true);
  assert.equal(isHiddenSprite('conduit-top'), false);
  assert.equal(isHiddenSprite('block-duct-full'), true);
  assert.equal(isHiddenSprite('unit-dagger-full'), false);
  assert.equal(isHiddenSprite('item-beryllium'), true);
  assert.equal(isHiddenSprite('item-copper'), false);
  assert.equal(isHiddenSprite('shape-3'), false);
});

test('content IDs are dense per type and Serpulo keeps vanilla IDs', () => {
  const byType = new Map();
  for (const content of snapshot) {
    if (!byType.has(content.type)) byType.set(content.type, []);
    byType.get(content.type).push(content.id);
  }
  for (const [type, ids] of byType) {
    assert.deepEqual(ids, ids.map((_, index) => index), `${type} IDs are not 0..n-1 in order`);
  }
  // Erekir stays registered: removing it would renumber Serpulo content and break vanilla multiplayer.
  const id = name => snapshot.find(content => content.name === name).id;
  assert.equal(id('copper'), 0);
  assert.equal(id('conveyor'), 257);
  assert.equal(id('duct'), 272);
  assert.equal(id('core-shard'), 339);
  assert.equal(id('serpulo'), 5);
});
