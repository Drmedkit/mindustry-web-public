import { DurableObject } from "cloudflare:workers";
import { cleanListing, LISTING_TTL_MS } from "./listing.js";

// Public room list. Rooms that their host marks public post a short listing
// here; stale listings (no update for two minutes) disappear on their own.

const MAX_LISTINGS = 100;

export class Lobby extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.ctx = ctx;
  }

  async fetch(request) {
    const url = new URL(request.url);
    const now = Date.now();
    if (url.pathname === "/list") {
      const listings = await this.ctx.storage.list({ prefix: "room:" });
      const rooms = [];
      for (const [key, listing] of listings) {
        if (now - listing.updated > LISTING_TTL_MS) await this.ctx.storage.delete(key);
        else rooms.push({ code: key.slice(5), ...listing, updated: undefined });
      }
      rooms.sort((a, b) => b.players - a.players || a.name.localeCompare(b.name));
      return Response.json({ rooms });
    }
    const code = url.searchParams.get("code");
    if (!code) return new Response("Missing code", { status: 400 });
    if (url.pathname === "/remove") {
      await this.ctx.storage.delete(`room:${code}`);
      return new Response("ok");
    }
    if (url.pathname === "/update") {
      const listing = cleanListing(await request.json().catch(() => null));
      if (!listing) return new Response("Invalid listing", { status: 400 });
      const existing = await this.ctx.storage.get(`room:${code}`);
      if (!existing && (await this.ctx.storage.list({ prefix: "room:", limit: MAX_LISTINGS })).size >= MAX_LISTINGS) {
        return new Response("Lobby is full", { status: 503 });
      }
      await this.ctx.storage.put(`room:${code}`, { ...listing, updated: now });
      return new Response("ok");
    }
    return new Response("Not found", { status: 404 });
  }
}
