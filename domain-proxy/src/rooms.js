// Co-op room endpoints, served by the domain Worker next to the game.
//   GET /ws/host?v=<build>                 WebSocket: open a new room as its host
//   GET /ws/rehost/<code>?v=&token=<token> WebSocket: the host reconnects to its room
//   GET /ws/join/<code>?v=<build>          WebSocket: join a room as a player
//   GET /api/rooms/<code>                  room status (for "ping")

const CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ"; // no 0/O, 1/I/L, U
const CODE_LENGTH = 6;
const CODE_PATTERN = new RegExp(`^[${CODE_ALPHABET}]{${CODE_LENGTH}}$`);

export function newRoomCode(random = crypto.getRandomValues(new Uint8Array(CODE_LENGTH))) {
  return [...random].map(byte => CODE_ALPHABET[byte % CODE_ALPHABET.length]).join("");
}

export function normalizeCode(code) {
  const normalized = String(code ?? "").trim().toUpperCase();
  return CODE_PATTERN.test(normalized) ? normalized : null;
}

// Only the game's own pages may open rooms; other sites could otherwise use the relay.
// The game is served by this Worker, so its own origin (selfOrigin) is always allowed;
// ALLOWED_ORIGINS adds more, comma-separated.
export function allowedOrigin(origin, env, selfOrigin) {
  if (!origin) return false;
  const allowed = (env.ALLOWED_ORIGINS ?? "").split(",").map(value => value.trim()).filter(Boolean);
  const url = URL.canParse(origin) ? new URL(origin) : null;
  if (!url) return false;
  if (selfOrigin && url.origin === selfOrigin) return true;
  if (allowed.includes(url.origin)) return true;
  return env.ALLOW_LOCALHOST === "true" && (url.hostname === "localhost" || url.hostname === "127.0.0.1");
}

// All players must run the same bundle: content IDs and packet layouts come from it.
export function buildMatches(url, env) {
  const expected = env.BUILD_ID;
  return !expected || expected === "any" || url.searchParams.get("v") === expected;
}

function room(env, code) {
  return env.ROOMS.get(env.ROOMS.idFromName(code));
}

function forward(request, code, path) {
  const headers = new Headers(request.headers);
  headers.set("X-Room-Code", code);
  return new Request(`https://room${path}`, { headers, method: request.method });
}

export async function handleRooms(request, env) {
  const url = new URL(request.url);
  const path = url.pathname;
  if (!path.startsWith("/ws/") && !path.startsWith("/api/rooms/") && path !== "/api/lobby") return null;

  if (path === "/api/lobby") {
    if (!env.LOBBY) return Response.json({ rooms: [] });
    const response = await env.LOBBY.get(env.LOBBY.idFromName("public")).fetch("https://lobby/list");
    return new Response(response.body, {
      status: response.status,
      headers: { "Content-Type": "application/json", "Cache-Control": "no-store", "Access-Control-Allow-Origin": "*" }
    });
  }

  if (path.startsWith("/api/rooms/")) {
    const code = normalizeCode(path.slice("/api/rooms/".length));
    if (!code) return new Response("Invalid room code", { status: 400 });
    const response = await room(env, code).fetch(forward(request, code, "/status"));
    // Public, non-sensitive status; readable cross-origin so local builds can use a dev relay.
    return new Response(response.body, {
      status: response.status,
      headers: { "Content-Type": "application/json", "Cache-Control": "no-store", "Access-Control-Allow-Origin": "*" }
    });
  }

  if (request.headers.get("Upgrade") !== "websocket") return new Response("Expected a WebSocket", { status: 426 });
  if (!allowedOrigin(request.headers.get("Origin"), env, url.origin)) return new Response("Forbidden origin", { status: 403 });
  if (!buildMatches(url, env)) return new Response("Game version mismatch; reload the page", { status: 409 });
  // Per-address limits on opening rooms and on join attempts (guessing codes).
  const limiter = path === "/ws/host" ? env.ROOM_CREATION : env.ROOM_JOINS;
  if (limiter && !(await limiter.limit({ key: request.headers.get("CF-Connecting-IP") ?? "local" })).success) {
    return new Response("Too many attempts; try again in a minute", { status: 429 });
  }

  if (path === "/ws/host") {
    // Codes are random; retry on the rare collision with a live room.
    for (let attempt = 0; attempt < 5; attempt++) {
      const code = newRoomCode();
      const response = await room(env, code).fetch(forward(request, code, "/host"));
      if (response.status !== 409) return response;
    }
    return new Response("Could not open a room", { status: 503 });
  }
  if (path.startsWith("/ws/rehost/")) {
    const code = normalizeCode(path.slice("/ws/rehost/".length));
    if (!code) return new Response("Invalid room code", { status: 400 });
    const token = encodeURIComponent(url.searchParams.get("token") ?? "");
    return room(env, code).fetch(forward(request, code, `/host?token=${token}`));
  }
  if (path.startsWith("/ws/join/")) {
    const code = normalizeCode(path.slice("/ws/join/".length));
    if (!code) return new Response("Invalid room code", { status: 400 });
    return room(env, code).fetch(forward(request, code, "/join"));
  }
  return new Response("Not found", { status: 404 });
}
