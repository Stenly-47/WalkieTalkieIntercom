const net = require("net");
const fs = require("fs");
const path = require("path");

const PORT = Number(process.env.PORT || 47654);
const MAX_LINE = 32 * 1024;
const SECURITY_PATH = path.join(__dirname, "security.json");

const clients = new Set();
const rooms = new Map();

function normalizeRoom(value) {
  const room = String(value || "umum").trim().toLowerCase();
  return room.length ? room.slice(0, 40) : "umum";
}

function normalizeName(value) {
  const name = String(value || "User").trim();
  return name.length ? name.slice(0, 32) : "User";
}

function send(client, payload) {
  if (!client.socket.destroyed) {
    client.socket.write(`${JSON.stringify(payload)}\n`);
  }
}

function loadSecurity() {
  try {
    return JSON.parse(fs.readFileSync(SECURITY_PATH, "utf8"));
  } catch (error) {
    return { relayToken: "" };
  }
}

function roomMembers(room) {
  return rooms.get(room) || new Set();
}

function joinRoom(client, roomName) {
  leaveRoom(client);
  const room = normalizeRoom(roomName);
  client.room = room;
  if (!rooms.has(room)) {
    rooms.set(room, new Set());
  }
  rooms.get(room).add(client);
  send(client, { type: "joined", room });
  console.log("JOIN:", client.name, client.id, client.room);
  sendPeers(room);
}

function leaveRoom(client) {
  if (!client.room) {
    return;
  }
  const members = rooms.get(client.room);
  if (members) {
    members.delete(client);
    if (members.size === 0) {
      rooms.delete(client.room);
    }
  }
  const oldRoom = client.room;
  client.room = null;
  sendPeers(oldRoom);
}

function sendPeers(room) {
  const members = roomMembers(room);
  const authenticatedMembers = Array.from(members).filter((member) => member.authenticated);
  const peers = authenticatedMembers.map((member) => ({
    id: member.id,
    name: member.name,
  }));
  console.log("PEERS:", room, peers);
  for (const member of authenticatedMembers) {
    send(member, { type: "peers", room, peers });
  }
}

function broadcastToRoom(room, payload, exceptClient) {
  for (const member of roomMembers(room)) {
    if (member !== exceptClient) {
      send(member, payload);
    }
  }
}

function handleMessage(client, message) {
  if (!message || typeof message.type !== "string") {
    return;
  }

  if (message.type === "hello") {
    const relayToken = String(loadSecurity().relayToken || "").trim();
    const requestToken = String(message.token || "").trim();
    if (relayToken && requestToken !== relayToken) {
      send(client, { type: "error", message: "Token relay tidak valid" });
      client.socket.destroy();
      return;
    }
    client.id = String(message.id || client.id).trim();
    client.name = normalizeName(message.name);
    client.room = normalizeRoom(message.room);
    client.authenticated = true;
    send(client, {
      type: "welcome",
      id: client.id,
      rooms: Array.from(rooms.keys()).sort(),
    });
    joinRoom(client, client.room);
    return;
  }

  if (message.type === "join") {
    joinRoom(client, message.room);
    return;
  }

  if (message.type === "rooms") {
    send(client, { type: "rooms", rooms: Array.from(rooms.keys()).sort() });
    return;
  }

  if (message.type === "ping") {
    send(client, { type: "pong", at: Date.now() });
    return;
  }

  if (message.type === "audio" && client.room && typeof message.pcm === "string") {
    broadcastToRoom(
      client.room,
      {
        type: "audio",
        room: client.room,
        from: client.id,
        name: client.name,
        seq: Number(message.seq || 0),
        pcm: message.pcm,
      },
      client
    );
  }
}

const server = net.createServer((socket) => {
  const client = {
    socket,
    id: `guest-${Math.random().toString(16).slice(2)}`,
    name: "User",
    room: null,
    authenticated: false,
    buffer: "",
  };
  clients.add(client);
  socket.setEncoding("utf8");
  socket.setKeepAlive(true, 15000);

  socket.on("data", (chunk) => {
    client.buffer += chunk;
    if (client.buffer.length > MAX_LINE) {
      socket.destroy();
      return;
    }
    let index;
    while ((index = client.buffer.indexOf("\n")) >= 0) {
      const line = client.buffer.slice(0, index).trim();
      client.buffer = client.buffer.slice(index + 1);
      if (!line) {
        continue;
      }
      try {
        handleMessage(client, JSON.parse(line));
      } catch (error) {
        send(client, { type: "error", message: "JSON tidak valid" });
      }
    }
  });

  socket.on("close", () => {
    leaveRoom(client);
    clients.delete(client);
  });

  socket.on("error", () => {
    socket.destroy();
  });
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`Walkie relay server listening on 0.0.0.0:${PORT}`);
});
