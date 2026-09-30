import { DurableObject } from "cloudflare:workers";

import { envelope, MAX_CLIENT_MESSAGE, MAX_PLAYERS, op, parseEnvelope } from "./relay-protocol.js";

// One Durable Object per co-op room. The host's browser runs the Mindustry
// server; this object only relays binary WebSocket messages between the host
// and its players, using the WebSocket Hibernation API. The first text message
// to the host is {"type":"room","code","token"}.

const HOST_GRACE_MS = 60_000;

export class Room extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.ctx = ctx;
    this.env = env;
  }

  async fetch(request) {
    const url = new URL(request.url);
    const code = request.headers.get("X-Room-Code");
    if (url.pathname === "/status") {
      return Response.json({ code, open: this.host() != null, players: this.players().length, max: MAX_PLAYERS });
    }
    if (request.headers.get("Upgrade") !== "websocket") return new Response("Expected a WebSocket", { status: 426 });

    const [client, server] = Object.values(new WebSocketPair());
    if (url.pathname === "/host") {
      const token = url.searchParams.get("token");
      const stored = await this.ctx.storage.get("token");
      if (this.host()) return new Response("Room already has a host", { status: 409 });
      // A new room, or the same host coming back within the grace period.
      if (stored && token !== stored) return new Response("Room is taken", { status: 409 });
      const hostToken = stored ?? crypto.randomUUID();
      await this.ctx.storage.put("token", hostToken);
      await this.ctx.storage.put("code", code);
      await this.ctx.storage.deleteAlarm();
      this.ctx.acceptWebSocket(server, ["host"]);
      server.send(JSON.stringify({ type: "room", code, token: hostToken }));
      // Players who stayed connected while the host was away join again.
      for (const player of this.players()) {
        const { id, address } = player.deserializeAttachment();
        server.send(this.joinMessage(id, address));
      }
    } else {
      const host = this.host();
      if (!host) return new Response("No such room", { status: 404 });
      if (this.players().length >= MAX_PLAYERS) return new Response("Room is full", { status: 409 });
      const id = await this.nextId();
      const address = await this.address(request);
      this.ctx.acceptWebSocket(server, [`p:${id}`]);
      server.serializeAttachment({ id, address });
      host.send(this.joinMessage(id, address));
      this.ctx.waitUntil(this.refreshListing());
    }
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(socket, message) {
    if (typeof message === "string") {
      // The host may list the room publicly: {"type":"listing","public":true,"name",...}.
      if (this.ctx.getTags(socket).includes("host")) await this.updateListing(message);
      return;
    }
    const bytes = new Uint8Array(message);
    if (this.ctx.getTags(socket).includes("host")) {
      this.route(bytes);
      return;
    }
    if (bytes.byteLength > MAX_CLIENT_MESSAGE) {
      socket.close(1009, "Message too large");
      return;
    }
    const { id } = socket.deserializeAttachment();
    this.host()?.send(envelope(op.from, id, bytes));
  }

  webSocketClose(socket, code) {
    if (this.ctx.getTags(socket).includes("host")) {
      // Give the host a minute to reconnect before closing the room.
      for (const player of this.players()) player.send(JSON.stringify({ type: "host-away" }));
      this.ctx.storage.setAlarm(Date.now() + HOST_GRACE_MS);
      return;
    }
    const { id } = socket.deserializeAttachment();
    this.host()?.send(envelope(op.leave, id));
    this.ctx.waitUntil(this.refreshListing());
  }

  webSocketError(socket) {
    this.webSocketClose(socket, 1011);
  }

  async alarm() {
    if (this.host()) return;
    for (const player of this.players()) player.close(4000, "The host left");
    await this.lobby(`/remove?code=${await this.code()}`);
    await this.ctx.storage.deleteAll();
  }

  async code() {
    return (await this.ctx.storage.get("code")) ?? null;
  }

  async updateListing(text) {
    let message;
    try { message = JSON.parse(text); } catch { return; }
    if (message?.type !== "listing") return;
    if (message.public) {
      await this.ctx.storage.put("listing", { name: message.name, map: message.map, mode: message.mode, max: MAX_PLAYERS });
      await this.refreshListing();
    } else {
      await this.ctx.storage.delete("listing");
      await this.lobby(`/remove?code=${await this.code()}`);
    }
  }

  async refreshListing() {
    const listing = await this.ctx.storage.get("listing");
    if (!listing || !this.host()) return;
    await this.lobby(`/update?code=${await this.code()}`, { ...listing, players: this.players().length + 1 });
  }

  async lobby(path, body) {
    if (!this.env.LOBBY) return;
    const lobby = this.env.LOBBY.get(this.env.LOBBY.idFromName("public"));
    await lobby.fetch(`https://lobby${path}`, body ? { method: "POST", body: JSON.stringify(body) } : { method: "POST" });
  }

  route(bytes) {
    const message = parseEnvelope(bytes);
    if (!message) return;
    const player = this.ctx.getWebSockets(`p:${message.id}`)[0];
    if (!player) return;
    if (message.op === op.to) player.send(message.payload);
    else if (message.op === op.kick) player.close(4001, "Kicked by the host");
  }

  host() {
    return this.ctx.getWebSockets("host")[0] ?? null;
  }

  players() {
    return this.ctx.getWebSockets().filter(socket => !this.ctx.getTags(socket).includes("host"));
  }

  joinMessage(id, address) {
    return envelope(op.join, id, new TextEncoder().encode(address));
  }

  async nextId() {
    const id = ((await this.ctx.storage.get("nextId")) ?? 0) + 1;
    await this.ctx.storage.put("nextId", id > 65535 ? 1 : id);
    return id;
  }

  // The host sees a salted hash, never the player's IP, so bans work without exposing addresses.
  async address(request) {
    let salt = await this.ctx.storage.get("salt");
    if (!salt) {
      salt = crypto.randomUUID();
      await this.ctx.storage.put("salt", salt);
    }
    const ip = request.headers.get("CF-Connecting-IP") ?? "local";
    const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(`${salt}:${ip}`));
    return [...new Uint8Array(digest).slice(0, 6)].map(byte => byte.toString(16).padStart(2, "0")).join("");
  }
}
