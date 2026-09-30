import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import vm from 'node:vm';

const webapp = new URL('../../browser-game/src/main/webapp/', import.meta.url);
const storageSource = readFileSync(new URL('vfs-storage.js', webapp), 'utf8');

const tick = () => new Promise(resolve => setTimeout(resolve, 0));
const bytes = (...values) => Uint8Array.from(values);

// A minimal IndexedDB: requests queue synchronously and a transaction commits
// (or aborts) as a unit on a later task, like the real API.
function fakeIndexedDb(initial = new Map()) {
  const data = new Map(initial);
  const database = {
    data,
    failNextWrite: false,
    transactions: 0,
    objectStoreNames: { contains: () => true },
    createObjectStore() {},
    transaction(name, mode) {
      if (mode === 'readwrite') database.transactions++;
      const operations = [];
      const transaction = {
        objectStore: () => ({
          put(value, key) { operations.push(() => data.set(key, value)); },
          delete(key) { operations.push(() => data.delete(key)); },
          openCursor() {
            const request = {};
            const entries = [...data.entries()];
            let index = 0;
            const step = () => {
              const entry = entries[index++];
              request.result = entry ? { key: entry[0], value: entry[1], continue: () => setTimeout(step, 0) } : null;
              request.onsuccess?.();
              if (!entry) setTimeout(() => transaction.oncomplete?.(), 0);
            };
            setTimeout(step, 0);
            return request;
          }
        })
      };
      if (mode === 'readwrite') {
        setTimeout(() => {
          if (database.failNextWrite) {
            database.failNextWrite = false;
            transaction.error = new Error('QuotaExceededError');
            transaction.onerror?.();
            transaction.onabort?.();
            return;
          }
          for (const operation of operations) operation();
          transaction.oncomplete?.();
        }, 0);
      }
      return transaction;
    }
  };
  return {
    database,
    open() {
      const request = { result: database };
      setTimeout(() => request.onsuccess?.(), 0);
      return request;
    }
  };
}

function fakeLocalStorage(entries = {}) {
  const map = new Map(Object.entries(entries));
  return {
    map,
    get length() { return map.size; },
    key: index => [...map.keys()][index] ?? null,
    getItem: key => map.get(key) ?? null,
    setItem: (key, value) => map.set(key, String(value)),
    removeItem: key => map.delete(key)
  };
}

function browser({ idb = fakeIndexedDb(), localStorage = fakeLocalStorage(), locks, search = '' } = {}) {
  const elements = new Map();
  const body = [];
  const element = id => {
    if (!elements.has(id)) elements.set(id, { id, hidden: false, textContent: '', dataset: {}, setAttribute() {} });
    return elements.get(id);
  };
  const document = {
    getElementById: id => elements.get(id) ?? (id === 'boot-status' ? element(id) : null),
    createElement: () => ({ hidden: false, textContent: '', dataset: {}, setAttribute() {} }),
    body: { append(node) { body.push(node); if (node.id) elements.set(node.id, node); } }
  };
  const context = vm.createContext({
    document, indexedDB: idb, localStorage, console: { warn() {}, log() {} },
    location: { search }, URLSearchParams,
    navigator: { locks, storage: { persist: async () => true } },
    atob, btoa, Uint8Array, ArrayBuffer, Promise, Map, Set, Date, Number, String, Object, Array
  });
  context.globalThis = context;
  vm.runInContext(storageSource, context);
  return { context, elements, idb };
}

test('hydrates current and legacy records with their modification times', async () => {
  const idb = fakeIndexedDb(new Map([
    ['data/settings.bin', { bytes: bytes(1, 2), mtime: 1700 }],
    ['saves/old.msav', bytes(9)]
  ]));
  const { context } = browser({ idb });
  await context.preloadMindustryStorage();
  assert.equal(context.__mindustryVfsBackend, 'indexeddb');
  const cache = context.__mindustryVfsCache;
  assert.deepEqual([...cache.get('data/settings.bin').bytes], [1, 2]);
  assert.equal(cache.get('data/settings.bin').mtime, 1700);
  assert.deepEqual([...cache.get('saves/old.msav').bytes], [9]);
  assert.equal(cache.get('saves/old.msav').mtime, 0);
});

test('a flush commits in one transaction and reports success only once durable', async () => {
  const idb = fakeIndexedDb(new Map([
    ['saves/slot/a.msav', { bytes: bytes(1), mtime: 1 }],
    ['saves/slot/b.msav', { bytes: bytes(2), mtime: 1 }],
    ['saves/keep.msav', { bytes: bytes(3), mtime: 1 }]
  ]));
  const { context } = browser({ idb });
  await context.preloadMindustryStorage();
  const before = idb.database.transactions;

  context.__mindustryVfsBegin();
  context.__mindustryVfsStageWrite('data/settings.bin', bytes(7, 7), 42);
  context.__mindustryVfsStageRemove('saves/slot');
  const id = context.__mindustryVfsCommit();
  assert.equal(context.__mindustryVfsCommitStatus(id), 0, 'pending until the transaction completes');
  assert.equal(idb.database.data.has('data/settings.bin'), false);

  await tick();
  assert.equal(context.__mindustryVfsCommitStatus(id), 1);
  assert.equal(idb.database.transactions - before, 1, 'the whole flush is a single transaction');
  assert.deepEqual([...idb.database.data.get('data/settings.bin').bytes], [7, 7]);
  assert.equal(idb.database.data.get('data/settings.bin').mtime, 42);
  assert.equal(idb.database.data.has('saves/slot/a.msav'), false, 'directory deletes are recursive');
  assert.equal(idb.database.data.has('saves/slot/b.msav'), false);
  assert.equal(idb.database.data.has('saves/keep.msav'), true);
});

test('a failed transaction reports failure and shows a warning, then recovers', async () => {
  const idb = fakeIndexedDb();
  const { context, elements } = browser({ idb });
  await context.preloadMindustryStorage();

  idb.database.failNextWrite = true;
  context.__mindustryVfsBegin();
  context.__mindustryVfsStageWrite('data/settings.bin', bytes(1), 1);
  const failed = context.__mindustryVfsCommit();
  await tick();
  assert.equal(context.__mindustryVfsCommitStatus(failed), -1);
  assert.equal(idb.database.data.size, 0, 'an aborted flush writes nothing');
  assert.equal(elements.get('storage-warning').hidden, false);

  context.__mindustryVfsBegin();
  context.__mindustryVfsStageWrite('data/settings.bin', bytes(1), 2);
  const retried = context.__mindustryVfsCommit();
  await tick();
  assert.equal(context.__mindustryVfsCommitStatus(retried), 1);
  assert.equal(elements.get('storage-warning').hidden, true);
});

test('saves written to localStorage while IndexedDB was down replace the older IndexedDB copy', async () => {
  const idb = fakeIndexedDb(new Map([['data/settings.bin', { bytes: bytes(1), mtime: 5 }]]));
  const localStorage = fakeLocalStorage({ 'mindustry-browser-vfs:data/settings.bin': btoa('\u0002') });
  const { context } = browser({ idb, localStorage });
  await context.preloadMindustryStorage();
  await tick();
  assert.deepEqual([...context.__mindustryVfsCache.get('data/settings.bin').bytes], [2]);
  assert.deepEqual([...idb.database.data.get('data/settings.bin').bytes], [2]);
  assert.equal(localStorage.map.size, 0, 'migrated keys are removed');
});

test('a second tab waits for the first instead of loading a competing copy', async () => {
  const waiting = [];
  const locks = {
    request(name, options, callback) {
      if (typeof options === 'function') {
        waiting.push(options);
        return new Promise(() => {});
      }
      assert.equal(options.ifAvailable, true);
      return Promise.resolve(callback(null));
    }
  };
  const { context, elements } = browser({ locks });
  let started = false;
  context.preloadMindustryStorage().then(() => { started = true; });
  await tick();
  assert.equal(started, false);
  assert.match(elements.get('boot-status').textContent, /already open in another tab/);

  waiting[0]({ name: 'mindustry-campaign' });
  for (let i = 0; i < 5; i++) await tick();
  assert.equal(started, true, 'boot continues once the other tab closes');
});

test('joining a room while the game is open elsewhere plays from memory instead of waiting', async () => {
  const locks = { request: (name, options, callback) => Promise.resolve(callback(null)) };
  const guard = { open() { throw new Error('guest tab opened campaign storage'); } };
  const { context } = browser({ locks, idb: guard, search: '?room=K7Q2XM' });
  await context.preloadMindustryStorage();
  assert.equal(context.__mindustryVfsBackend, 'memory');
});
