import { cacheControl } from "./cache.js";
import { handleRooms } from "./rooms.js";

export { Room } from "./room.js";
export { Lobby } from "./lobby.js";

// Deployment-specific values come from "vars" in wrangler.jsonc:
//   PAGES_ORIGIN      host name of the Cloudflare Pages deployment that holds the game files
//   SITE_URL          optional public URL of the game; enables the share card below
//   SHARE_TITLE, SHARE_DESCRIPTION, SHARE_IMAGE, SHARE_SITE_NAME  optional share-card text and image
function shareCard(env) {
  if (!env.SITE_URL) return null;
  return {
    url: env.SITE_URL,
    siteName: env.SHARE_SITE_NAME || "Mindustry",
    title: env.SHARE_TITLE || "Mindustry in your browser",
    description: env.SHARE_DESCRIPTION || "Play the complete solo Serpulo campaign in your browser. Build supply lines, defend your core and conquer the planet — no installation required, with progress saved locally.",
    image: env.SHARE_IMAGE || "",
    alt: "Mindustry — factory strategy in your browser",
  };
}

function escapeHTML(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll('"', "&quot;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

function shareMetadata(card) {
  const value = (name) => escapeHTML(card[name]);
  const image = card.image ? `
    <meta property="og:image" content="${value("image")}" />
    <meta property="og:image:alt" content="${value("alt")}" />
    <meta name="twitter:image" content="${value("image")}" />
    <meta name="twitter:image:alt" content="${value("alt")}" />` : "";
  return `
    <link rel="canonical" href="${value("url")}" />
    <meta property="og:type" content="website" />
    <meta property="og:site_name" content="${value("siteName")}" />
    <meta property="og:locale" content="en_US" />
    <meta property="og:url" content="${value("url")}" />
    <meta property="og:title" content="${value("title")}" />
    <meta property="og:description" content="${value("description")}" />
    <meta name="twitter:card" content="${card.image ? "summary_large_image" : "summary"}" />
    <meta name="twitter:title" content="${value("title")}" />
    <meta name="twitter:description" content="${value("description")}" />${image}`;
}

export default {
  async fetch(request, env) {
    const rooms = await handleRooms(request, env);
    if (rooms) return rooms;

    const requestUrl = new URL(request.url);
    if (requestUrl.pathname === "/robots.txt") {
      return new Response("User-agent: *\nAllow: /\n", {
        headers: {
          "Content-Type": "text/plain; charset=utf-8",
          "Cache-Control": "public, max-age=3600",
        },
      });
    }

    if (!env.PAGES_ORIGIN || env.PAGES_ORIGIN.includes("REPLACE")) {
      return new Response("Set PAGES_ORIGIN in domain-proxy/wrangler.jsonc to your Cloudflare Pages host name (see DEPLOY.md).", { status: 500 });
    }
    const upstreamUrl = new URL(request.url);
    upstreamUrl.protocol = "https:";
    upstreamUrl.hostname = env.PAGES_ORIGIN;
    upstreamUrl.port = "";

    const upstream = await fetch(new Request(upstreamUrl, request));
    const headers = new Headers(upstream.headers);
    headers.set("Cache-Control", cacheControl(upstreamUrl, upstream.status));
    headers.set("X-Mindustry-Origin", "Cloudflare-Pages");

    const response = new Response(upstream.body, {
      status: upstream.status,
      statusText: upstream.statusText,
      headers
    });

    const contentType = headers.get("Content-Type") || "";
    const card = shareCard(env);
    if (!card || request.method !== "GET" || !contentType.includes("text/html")) return response;

    return new HTMLRewriter()
      .on('link[rel="canonical"]', { element(element) { element.remove(); } })
      .on('meta[property^="og:"]', { element(element) { element.remove(); } })
      .on('meta[name^="twitter:"]', { element(element) { element.remove(); } })
      .on("head", { element(element) { element.append(shareMetadata(card), { html: true }); } })
      .transform(response);
  }
};
