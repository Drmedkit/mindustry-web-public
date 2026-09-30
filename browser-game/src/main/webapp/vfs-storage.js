const mindustryVfsPrefix = 'mindustry-browser-vfs:';

function decodeLegacyVfs(value) {
  const raw = atob(value);
  const bytes = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
  return bytes;
}

function encodeLegacyVfs(bytes) {
  const parts = [];
  const step = 32768;
  for (let i = 0; i < bytes.length; i += step) {
    const end = Math.min(i + step, bytes.length);
    let part = '';
    for (let j = i; j < end; j++) part += String.fromCharCode(bytes[j] & 255);
    parts.push(part);
  }
  return btoa(parts.join(''));
}

// Records are {bytes, mtime}; builds before modification times stored a bare Uint8Array.
function readVfsRecord(value) {
  if (value instanceof Uint8Array || value instanceof ArrayBuffer) return {bytes: new Uint8Array(value), mtime: 0};
  return {bytes: new Uint8Array(value.bytes), mtime: Number(value.mtime) || 0};
}

function openMindustryVfsDatabase() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open('mindustry-browser-vfs', 1);
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains('files')) request.result.createObjectStore('files');
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error || new Error('IndexedDB could not be opened'));
    request.onblocked = () => reject(new Error('IndexedDB upgrade was blocked'));
  });
}

function loadMindustryVfsDatabase(database, cache) {
  return new Promise((resolve, reject) => {
    const transaction = database.transaction('files', 'readonly');
    const request = transaction.objectStore('files').openCursor();
    request.onsuccess = () => {
      const cursor = request.result;
      if (!cursor) return;
      cache.set(String(cursor.key), readVfsRecord(cursor.value));
      cursor.continue();
    };
    transaction.oncomplete = resolve;
    transaction.onerror = () => reject(transaction.error || new Error('IndexedDB hydration failed'));
    transaction.onabort = () => reject(transaction.error || new Error('IndexedDB hydration was aborted'));
  });
}

function legacyVfsEntries() {
  const entries = [];
  for (let i = 0; i < localStorage.length; i++) {
    const key = localStorage.key(i);
    if (key?.startsWith(mindustryVfsPrefix)) {
      const value = localStorage.getItem(key);
      if (value != null) entries.push([key, key.slice(mindustryVfsPrefix.length), decodeLegacyVfs(value)]);
    }
  }
  return entries;
}

// localStorage is only written while IndexedDB is unavailable, so its entries are
// newer than the IndexedDB copy and replace it.
function migrateLegacyVfs(database, cache, entries) {
  if (!entries.length) return Promise.resolve();
  return new Promise((resolve, reject) => {
    const transaction = database.transaction('files', 'readwrite');
    const store = transaction.objectStore('files');
    const now = Date.now();
    for (const [, path, bytes] of entries) {
      const record = {bytes, mtime: now};
      cache.set(path, record);
      store.put(record, path);
    }
    transaction.oncomplete = () => {
      for (const [key] of entries) localStorage.removeItem(key);
      resolve();
    };
    transaction.onerror = () => reject(transaction.error || new Error('Legacy save migration failed'));
    transaction.onabort = () => reject(transaction.error || new Error('Legacy save migration was aborted'));
  });
}

function showPersistenceWarning(visible) {
  let banner = document.getElementById('storage-warning');
  if (!visible) {
    if (banner) banner.hidden = true;
    return;
  }
  if (!banner) {
    banner = document.createElement('p');
    banner.id = 'storage-warning';
    banner.setAttribute('role', 'alert');
    banner.textContent = 'Progress is not being saved: browser storage is full or blocked. Free up space or allow site data, and keep this tab open.';
    document.body.append(banner);
  }
  banner.hidden = false;
}

// Java stages a flush with begin/write/remove, then commits it as one unit and
// polls its status: 0 while pending, 1 once durable, -1 if it failed.
function installVfsCommitApi(paths, apply) {
  const results = new Map();
  let staged = null;
  let nextId = 1;
  globalThis.__mindustryVfsBegin = () => { staged = []; };
  globalThis.__mindustryVfsStageWrite = (path, bytes, mtime) => { staged.push({path, bytes, mtime}); };
  globalThis.__mindustryVfsStageRemove = path => { staged.push({path, bytes: null, mtime: 0}); };
  globalThis.__mindustryVfsCommit = () => {
    const operations = staged || [];
    staged = null;
    const id = nextId++;
    results.set(id, 0);
    const settle = ok => {
      results.set(id, ok ? 1 : -1);
      showPersistenceWarning(!ok);
    };
    try {
      apply(operations, settle);
    } catch (error) {
      console.warn('Mindustry save data could not be written:', error);
      settle(false);
    }
    return id;
  };
  globalThis.__mindustryVfsCommitStatus = id => {
    const status = results.get(id) ?? -1;
    if (status !== 0) results.delete(id);
    return status;
  };
  // Directory deletes are recursive; the key set lets them find stored children.
  globalThis.__mindustryVfsMatching = path => {
    const child = `${path}/`;
    return [...paths].filter(key => key === path || key.startsWith(child));
  };
}

function installIndexedDbVfs(database, paths) {
  const pending = globalThis.__mindustryVfsPending = new Set();
  installVfsCommitApi(paths, (operations, settle) => {
    // One transaction per flush: either every write and delete lands, or none do.
    const transaction = database.transaction('files', 'readwrite');
    const store = transaction.objectStore('files');
    for (const {path, bytes, mtime} of operations) {
      if (bytes) {
        store.put({bytes, mtime}, path);
        paths.add(path);
      } else {
        for (const key of globalThis.__mindustryVfsMatching(path)) {
          store.delete(key);
          paths.delete(key);
        }
      }
    }
    const completion = new Promise(resolve => {
      transaction.oncomplete = () => { settle(true); resolve(); };
      transaction.onerror = () => { console.warn('Mindustry IndexedDB write failed:', transaction.error); };
      transaction.onabort = () => {
        console.warn('Mindustry IndexedDB write was aborted:', transaction.error);
        settle(false);
        resolve();
      };
    });
    pending.add(completion);
    completion.finally(() => pending.delete(completion));
  });
  globalThis.__mindustryVfsWaitForIdle = async () => {
    while (pending.size) await Promise.allSettled([...pending]);
  };
}

function installLocalStorageFallback(paths) {
  installVfsCommitApi(paths, (operations, settle) => {
    for (const {path, bytes} of operations) {
      if (bytes) {
        localStorage.setItem(mindustryVfsPrefix + path, encodeLegacyVfs(bytes));
        paths.add(path);
      } else {
        for (const key of globalThis.__mindustryVfsMatching(path)) {
          localStorage.removeItem(mindustryVfsPrefix + key);
          paths.delete(key);
        }
      }
    }
    settle(true);
  });
  globalThis.__mindustryVfsWaitForIdle = async () => {};
}

function installMemoryVfs(paths) {
  installVfsCommitApi(paths, (operations, settle) => settle(true));
  globalThis.__mindustryVfsWaitForIdle = async () => {};
}

// Two tabs would each load a copy of the campaign and overwrite each other's saves.
function acquireCampaignLock() {
  if (!navigator.locks?.request) return Promise.resolve(true);
  return new Promise(resolve => {
    navigator.locks.request('mindustry-campaign', {ifAvailable: true}, lock => {
      resolve(!!lock);
      // Hold the lock for the life of the page.
      return lock ? new Promise(() => {}) : undefined;
    }).catch(() => resolve(true));
  });
}

function waitForOtherTabToClose() {
  const status = document.getElementById('boot-status');
  status.hidden = false;
  status.textContent = 'Mindustry is already open in another tab. Close that tab and this one will start automatically.';
  // Asset progress must not overwrite this message (see asset-preloader.js).
  status.dataset.state = 'waiting';
  return new Promise(resolve => {
    navigator.locks.request('mindustry-campaign', () => {
      status.dataset.state = 'loading';
      status.textContent = 'Starting Mindustry…';
      resolve();
      return new Promise(() => {});
    });
  });
}

// Files hydrated before Java starts: path -> {bytes, mtime}. Java drops the
// bytes once it has copied them, keeping only the path set.
async function preloadMindustryStorage() {
  const cache = globalThis.__mindustryVfsCache = new Map();
  if (!await acquireCampaignLock()) {
    // Joining a friend's room needs no campaign: play from memory instead of waiting.
    if (new URLSearchParams(location.search).has('room')) {
      installMemoryVfs(new Set());
      globalThis.__mindustryVfsBackend = 'memory';
      return;
    }
    await waitForOtherTabToClose();
  }

  let legacy = [];
  try {
    legacy = legacyVfsEntries();
  } catch (error) {
    console.warn('Legacy Mindustry saves could not be read:', error);
  }

  try {
    const database = await openMindustryVfsDatabase();
    await loadMindustryVfsDatabase(database, cache);
    await migrateLegacyVfs(database, cache, legacy);
    installIndexedDbVfs(database, new Set(cache.keys()));
    globalThis.__mindustryVfsBackend = 'indexeddb';
    navigator.storage?.persist?.().catch(() => {});
  } catch (error) {
    console.warn('IndexedDB is unavailable; using limited localStorage persistence.', error);
    for (const [, path, bytes] of legacy) cache.set(path, {bytes, mtime: 0});
    installLocalStorageFallback(new Set(cache.keys()));
    globalThis.__mindustryVfsBackend = 'localstorage';
  }
}
