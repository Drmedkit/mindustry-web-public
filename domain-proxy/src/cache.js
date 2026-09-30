// Cache policy for responses proxied from Cloudflare Pages.
//
// Only URLs carrying a content version (?v=, added by the page from the asset
// manifest or by scripts/stamp-dist.mjs) are immutable. Everything else
// revalidates, so a deploy is visible on the next load. Error responses are
// never cached.
export function cacheControl(url, status) {
  if (status < 200 || status >= 300) return "no-store";
  if (url.searchParams.has("v")) return "public, max-age=31536000, immutable";
  return "public, max-age=0, must-revalidate";
}
