// Public room listings are shown to everyone: keep them short and free of markup.
export const LISTING_TTL_MS = 2 * 60_000;

export function cleanListing(input) {
  const text = (value, max) => String(value ?? "")
    .replace(/\[[^\]]*\]/g, "") // Mindustry colour tags
    .replace(/[\u0000-\u001f\u007f<>]/g, "")
    .trim()
    .slice(0, max);
  const count = (value, max) => Math.max(0, Math.min(max, Math.floor(Number(value) || 0)));
  const name = text(input?.name, 32);
  if (!name) return null;
  return {
    name,
    map: text(input?.map, 40),
    mode: text(input?.mode, 16),
    players: count(input?.players, 64),
    max: count(input?.max, 64)
  };
}
