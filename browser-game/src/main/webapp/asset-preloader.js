// Probes WebGL once: the texture limit decides which sprite atlas the game loads
// (ClientLauncher uses the fallback atlas below 4096), and the renderer name and
// device hints pick the first-run graphics preset (see MindustryBrowserLauncher).
function probeDevice() {
  const device = {maxTextureSize: 4096, renderer: '', lowEnd: false};
  try {
    const canvas = document.createElement('canvas');
    const gl = canvas.getContext('webgl2') || canvas.getContext('webgl');
    if (gl) {
      device.maxTextureSize = gl.getParameter(gl.MAX_TEXTURE_SIZE);
      const info = gl.getExtension('WEBGL_debug_renderer_info');
      device.renderer = String(info ? gl.getParameter(info.UNMASKED_RENDERER_WEBGL) : gl.getParameter(gl.RENDERER));
      gl.getExtension('WEBGL_lose_context')?.loseContext();
    }
  } catch (error) {
    // Keep the defaults; the game reports a WebGL failure itself.
  }
  const softwareGpu = /swiftshader|llvmpipe|softpipe|software|microsoft basic render/i.test(device.renderer);
  const memory = navigator.deviceMemory;
  const cores = navigator.hardwareConcurrency;
  device.lowEnd = softwareGpu || (memory != null && memory <= 4) || (cores != null && cores <= 4);
  return device;
}

// A valid 1x1 transparent RGBA PNG that Arc's PNG decoder accepts.
function placeholderImage() {
  const data = atob('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR4nGNgAAIAAAUAAXpeqz8AAAAASUVORK5CYII=');
  return Uint8Array.from(data, character => character.charCodeAt(0));
}

// Files the game reads at startup are fetched before Java starts. Everything else
// is registered with its size and fetched synchronously on first read (see
// TeaAssetBridge), so it costs nothing unless it is actually used.
function assetPolicy(device) {
  const fallbackAtlas = device.maxTextureSize < 4096;
  const languages = new Set((navigator.languages?.length ? navigator.languages : [navigator.language || 'en'])
    .map(language => language.toLowerCase().split('-')[0]));
  const bundleWanted = name => {
    const match = /^bundle(?:_([a-z]+)(?:_[A-Za-z]+)?)?\.properties$/.exec(name);
    return !match || !match[1] || languages.has(match[1]);
  };
  return file => {
    const path = file.path;
    // Audio is silent in this build: sounds and music are never read.
    if (path.startsWith('music/') || path.startsWith('sounds/')) return false;
    if (path.startsWith('sprites/fallback/')) return fallbackAtlas;
    if (/^sprites\/sprites\d*\.(png|aatls)$/.test(path)) return !fallbackAtlas;
    if (path.startsWith('bundles/')) return bundleWanted(path.slice('bundles/'.length));
    if (path === 'fonts/font_jp.woff') return languages.has('ja');
    if (path === 'icons/icon.icns' || path === 'icons/icon.ico') return false;
    return true;
  };
}

async function preloadMindustryAssets() {
  const status = document.getElementById('boot-status');
  const response = await fetch('assets-manifest.json', {cache: 'no-cache'});
  if (!response.ok) throw new Error(`Asset manifest returned HTTP ${response.status}`);
  const manifest = await response.json();
  const vfxFiles = [
    'bias.frag', 'blur.vert', 'chromatic-aberration.frag', 'combine.frag', 'convolve-1d.frag', 'copy.frag',
    'crt.frag', 'default.vert', 'film-grain.frag', 'fisheye.frag', 'fxaa.frag', 'hdr.frag', 'lensflare.frag',
    'levels.frag', 'mix.frag', 'motionblur-max.frag', 'motionblur-mix.frag', 'nfaa.frag', 'noise.frag',
    'old-tv.frag', 'radial-blur.frag', 'radial-blur.vert', 'radial-distortion.frag', 'ripple.frag',
    'screenspace.vert', 'threshold.frag', 'vignetting.frag', 'zoom.frag', 'zoom.vert'
  ].map(name => ({path: `vfxshaders/${name}`, url: `vfxshaders/${name}?v=${globalThis.mindustryBuild}`}));

  const assetUrl = file => {
    // The content hash in the URL lets the domain proxy cache assets as immutable.
    const version = file.sha256 ? `?v=${file.sha256.slice(0, 16)}` : '';
    return file.url || `assets/${file.path.split('/').map(encodeURIComponent).join('/')}${version}`;
  };
  const device = globalThis.mindustryDevice = probeDevice();
  const preload = assetPolicy(device);
  const files = [];
  const lazy = globalThis.__mindustryLazyAssets = Object.create(null);
  const assets = globalThis.__mindustryAssets = Object.create(null);
  // Large PNGs (atlas pages, backgrounds) are decoded natively while other files
  // download; Java's decoder is far slower under TeaVM. See TeaAssetBridge.
  const decoded = globalThis.__mindustryDecoded = Object.create(null);
  const decodes = [];
  for (const file of manifest.files.concat(vfxFiles)) {
    // Atlas pages of hidden (Erekir) content are never downloaded: the atlas
    // still lists their sprites, which render as a transparent pixel.
    if (file.deferred) assets[file.path] = placeholderImage();
    else if (preload(file)) files.push(file);
    else lazy[file.path] = {url: assetUrl(file), bytes: file.bytes};
  }

  const totalBytes = files.reduce((sum, file) => sum + (file.bytes || 0), 0);
  let next = 0;
  let completed = 0;
  let loadedBytes = 0;

  async function worker() {
    while (true) {
      const index = next++;
      if (index >= files.length) return;
      const file = files[index];
      const assetResponse = await fetch(assetUrl(file));
      if (!assetResponse.ok) throw new Error(`${file.path} returned HTTP ${assetResponse.status}`);
      const bytes = new Uint8Array(await assetResponse.arrayBuffer());
      if (file.bytes != null && bytes.byteLength !== file.bytes) throw new Error(`${file.path} has an unexpected size`);
      assets[file.path] = bytes;
      if (file.path.endsWith('.png') && bytes.byteLength > 256 * 1024 && typeof decodePng === 'function') {
        decodes.push(decodePng(bytes).then(result => { if (result) decoded[file.path] = result; }, () => {}));
      }
      completed++;
      loadedBytes += bytes.byteLength;
      if (status.dataset.state !== 'waiting') {
        status.textContent = `Loading ${(loadedBytes / 1048576).toFixed(1)} of ${(totalBytes / 1048576).toFixed(1)} MB`;
      }
    }
  }

  await Promise.all(Array.from({length: Math.min(12, files.length)}, worker));
  await Promise.all(decodes);
  if (status.dataset.state !== 'waiting') status.textContent = `Starting Mindustry · ${completed} assets ready`;
}
