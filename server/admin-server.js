const fs = require("fs");
const http = require("http");
const path = require("path");
const { URL } = require("url");
const { WebSocketServer, WebSocket } = require("ws");

const HOST = process.env.ADMIN_HOST || "0.0.0.0";
const PORT = Number(process.env.ADMIN_PORT || 8080);

const ROOT = __dirname;
const CONFIG_PATH = path.join(ROOT, "config.json");
const LICENSES_PATH = resolveRequiredPath("LICENSE_DATA_PATH");
const DASHBOARD_HTML_PATH = path.join(ROOT, "admin-dashboard.html");
const ADMIN_HTML_PATH = path.join(ROOT, "admin-licenses.html");

const DEFAULT_CONFIG = {
  relay: {
    mode: "websocket",
    url: "wss://intercom.ssrapps.my.id/relay",
    host: "intercom.ssrapps.my.id",
    port: 443,
    path: "/relay"
  },
  rooms: ["tiberias", "umum", "multimedia", "keamanan"],
  audio: { sampleRate: 8000, frameBytes: 320, micGain: 3, speakerVolume: 1, voxThreshold: 500 },
  license: { organization: "GPdI Tiberias Manokwari", maxUsers: 50, maxDevices: 50 }
};

const relayClients = new Set();
const relayRooms = new Map();
const MAX_RELAY_MESSAGE = 32 * 1024;

ensureJsonFile(CONFIG_PATH, DEFAULT_CONFIG);
validateRuntimeConfiguration();
ensureRequiredFile(LICENSES_PATH, "LICENSE_DATA_PATH");

const server = http.createServer(async (request, response) => {
  try {
    const requestUrl = new URL(request.url, `http://${request.headers.host || "localhost"}`);

    if (request.method === "GET" && requestUrl.pathname === "/") {
      return sendHtml(response, fs.readFileSync(DASHBOARD_HTML_PATH, "utf8"));
    }

    if (request.method === "GET" && requestUrl.pathname === "/licenses") {
      return sendHtml(response, fs.readFileSync(ADMIN_HTML_PATH, "utf8"));
    }

    if (request.method === "GET" && requestUrl.pathname === "/api/admin/summary") {
      return handleAdminSummary(requestUrl, response);
    }

    if (request.method === "POST" && requestUrl.pathname === "/api/admin/config/update") {
      return handleAdminConfigUpdate(await readJsonBody(request), response);
    }

    if (request.method === "GET" && (requestUrl.pathname === "/api/admin/licenses" || requestUrl.pathname === "/api/license/list")) {
      return handleAdminList(requestUrl, response);
    }

    if (request.method === "POST" && (requestUrl.pathname === "/api/admin/licenses/create" || requestUrl.pathname === "/api/license/add")) {
      return handleAdminCreate(await readJsonBody(request), response);
    }

    if (request.method === "POST" && (requestUrl.pathname === "/api/admin/licenses/update" || requestUrl.pathname === "/api/license/update")) {
      return handleAdminUpdate(await readJsonBody(request), response);
    }

    if (request.method === "POST" && (requestUrl.pathname === "/api/admin/licenses/reset-device" || requestUrl.pathname === "/api/license/reset-device")) {
      return handleAdminReset(await readJsonBody(request), response);
    }

    if (request.method === "POST" && requestUrl.pathname === "/api/license/delete") {
      return handleAdminDelete(await readJsonBody(request), response);
    }

    if (request.method === "POST" && requestUrl.pathname === "/api/admin/licenses/toggle") {
      return handleAdminToggle(await readJsonBody(request), response);
    }

    if (request.method === "POST" && requestUrl.pathname === "/api/license/activate") {
      return handleLicenseValidation(await readJsonBody(request), response, { bindIfEmpty: true, includeDeviceName: true });
    }

    if (request.method === "POST" && requestUrl.pathname === "/api/license/check") {
      return handleLicenseValidation(await readJsonBody(request), response, { bindIfEmpty: true, includeDeviceName: true });
    }

    if (request.method === "GET" && requestUrl.pathname === "/api/config") {
      return handleConfig(requestUrl, response);
    }

    return sendJson(response, 404, { success: false, error: "Endpoint tidak ditemukan" });
  } catch (error) {
    return sendJson(response, 500, { success: false, error: error.message || "Server error" });
  }
});

const relayWss = new WebSocketServer({ server, path: "/relay" });
relayWss.on("connection", (socket) => {
  const client = {
    socket,
    id: `guest-${Math.random().toString(16).slice(2)}`,
    name: "User",
    room: null,
    authenticated: false
  };
  relayClients.add(client);

  socket.on("message", (data, isBinary) => {
    if (isBinary) {
      return;
    }
    const text = Buffer.isBuffer(data) ? data.toString("utf8") : String(data || "");
    if (text.length > MAX_RELAY_MESSAGE) {
      socket.close(1009, "Payload terlalu besar");
      return;
    }
    try {
      handleRelayMessage(client, JSON.parse(text));
    } catch (error) {
      sendRelay(client, { type: "error", message: "JSON tidak valid" });
    }
  });

  socket.on("close", () => {
    leaveRelayRoom(client);
    relayClients.delete(client);
  });

  socket.on("error", () => {
    try {
      socket.close();
    } catch (ignored) {
    }
  });
});

server.listen(PORT, HOST, () => {
  console.log(`Web Admin/API listening on http://${HOST}:${PORT}`);
});

function handleAdminList(requestUrl, response) {
  if (!isApiTokenValid(requestUrl.searchParams.get("apiToken"))) {
    return sendJson(response, 403, { success: false, error: "API token salah" });
  }
  const licenses = loadLicenses().licenses.map(toPublicLicense);
  return sendJson(response, 200, { success: true, licenses });
}

function handleAdminSummary(requestUrl, response) {
  if (!isApiTokenValid(requestUrl.searchParams.get("apiToken"))) {
    return sendJson(response, 403, { success: false, error: "API token salah" });
  }
  const config = loadConfig();
  const licenses = loadLicenses().licenses;
  return sendJson(response, 200, {
    success: true,
    summary: {
      status: "ONLINE VPS + SECURED",
      relay: config.relay || { host: "", port: 47654 },
      rooms: Array.isArray(config.rooms) ? config.rooms : [],
      audio: config.audio || {},
      license: {
        organization: String((config.license && config.license.organization) || ""),
        maxUsers: Number((config.license && config.license.maxUsers) || 0),
        maxDevices: Number((config.license && config.license.maxDevices) || 0),
        totalLicenses: licenses.length
      },
      configSecure: true
    }
  });
}

function handleAdminConfigUpdate(body, response) {
  if (!isApiTokenValid(body.apiToken)) {
    return sendJson(response, 403, { success: false, error: "API token salah" });
  }

  const current = loadConfig();
  const relay = body.relay || {};
  const audio = body.audio || {};
  const license = body.license || {};
  const rooms = Array.isArray(body.rooms) ? body.rooms : current.rooms;

  const relayMode = String(relay.mode || current.relay.mode || "websocket").trim().toLowerCase() || "websocket";
  const relayHost = String(relay.host || current.relay.host || "intercom.ssrapps.my.id").trim();
  const relayPort = Number(relay.port || current.relay.port || (relayMode === "websocket" ? 443 : 47654));
  const relayPath = String(relay.path || current.relay.path || "/relay").trim() || "/relay";
  const relayUrl = String(relay.url || current.relay.url || buildRelayPublicUrl(relayMode, relayHost, relayPort, relayPath)).trim();
  current.relay = {
    mode: relayMode,
    url: relayUrl,
    host: relayHost,
    port: relayPort,
    path: relayPath
  };
  current.rooms = rooms
    .map((item) => String(item || "").trim().toLowerCase())
    .filter(Boolean);
  current.audio = {
    sampleRate: Number(audio.sampleRate || current.audio.sampleRate || 8000),
    frameBytes: Number(audio.frameBytes || current.audio.frameBytes || 320),
    micGain: Number(audio.micGain || current.audio.micGain || 3),
    speakerVolume: Number(audio.speakerVolume || current.audio.speakerVolume || 1),
    voxThreshold: Number(audio.voxThreshold || current.audio.voxThreshold || 500)
  };
  current.license = {
    organization: String(license.organization || current.license.organization || "").trim(),
    maxUsers: Number(license.maxUsers || current.license.maxUsers || 0),
    maxDevices: Number(license.maxDevices || current.license.maxDevices || 0)
  };

  fs.writeFileSync(CONFIG_PATH, `${JSON.stringify(current, null, 2)}\n`, "utf8");
  return sendJson(response, 200, { success: true });
}

function handleAdminCreate(body, response) {
  if (!isApiTokenValid(body.apiToken)) {
    return sendJson(response, 403, { success: false, error: "API token salah" });
  }

  const licenseKey = normalizeLicenseKey(body.licenseKey);
  const customerName = String(body.customerName || "").trim();
  const activeUntil = normalizeDate(body.activeUntil);
  if (!licenseKey) {
    return sendJson(response, 400, { success: false, error: "Kode lisensi wajib diisi" });
  }
  if (!activeUntil) {
    return sendJson(response, 400, { success: false, error: "Tanggal aktif sampai wajib diisi" });
  }

  const data = loadLicenses();
  if (findLicense(data.licenses, licenseKey)) {
    return sendJson(response, 409, { success: false, error: "Kode lisensi sudah ada" });
  }

  data.licenses.push({
    licenseKey,
    customerName,
    active: true,
    activeUntil,
    deviceId: "",
    deviceName: "",
    activatedAt: "",
    lastCheckAt: ""
  });
  saveLicenses(data);
  return sendJson(response, 200, { success: true });
}

function handleAdminUpdate(body, response) {
  if (!isApiTokenValid(body.apiToken)) {
    return sendJson(response, 403, { success: false, error: "API token salah" });
  }
  const licenseKey = normalizeLicenseKey(body.licenseKey);
  const activeUntil = normalizeDate(body.activeUntil);
  const data = loadLicenses();
  const license = findLicense(data.licenses, licenseKey);
  if (!license) {
    return sendJson(response, 404, { success: false, error: "Kode lisensi tidak ditemukan" });
  }
  if (String(body.customerName || "").trim().length) {
    license.customerName = String(body.customerName || "").trim();
  }
  if (activeUntil) {
    license.activeUntil = activeUntil;
  }
  saveLicenses(data);
  return sendJson(response, 200, { success: true });
}

function handleAdminReset(body, response) {
  if (!isApiTokenValid(body.apiToken)) {
    return sendJson(response, 403, { success: false, error: "API token salah" });
  }
  const data = loadLicenses();
  const license = findLicense(data.licenses, normalizeLicenseKey(body.licenseKey));
  if (!license) {
    return sendJson(response, 404, { success: false, error: "Kode lisensi tidak ditemukan" });
  }
  license.deviceId = "";
  license.deviceName = "";
  license.activatedAt = "";
  license.lastCheckAt = "";
  saveLicenses(data);
  return sendJson(response, 200, { success: true });
}

function handleAdminToggle(body, response) {
  if (!isApiTokenValid(body.apiToken)) {
    return sendJson(response, 403, { success: false, error: "API token salah" });
  }
  const data = loadLicenses();
  const license = findLicense(data.licenses, normalizeLicenseKey(body.licenseKey));
  if (!license) {
    return sendJson(response, 404, { success: false, error: "Kode lisensi tidak ditemukan" });
  }
  license.active = !!body.active;
  saveLicenses(data);
  return sendJson(response, 200, { success: true });
}

function handleAdminDelete(body, response) {
  if (!isApiTokenValid(body.apiToken)) {
    return sendJson(response, 403, { success: false, error: "API token salah" });
  }

  const licenseKey = normalizeLicenseKey(body.licenseKey);
  if (!licenseKey) {
    return sendJson(response, 400, { success: false, error: "Kode lisensi wajib diisi" });
  }

  const data = loadLicenses();
  const licenseIndex = data.licenses.findIndex((item) => normalizeLicenseKey(item.licenseKey) === licenseKey);
  if (licenseIndex < 0) {
    return sendJson(response, 404, { success: false, error: "Lisensi tidak ditemukan" });
  }

  console.log("DELETE LICENSE:", licenseKey);
  data.licenses.splice(licenseIndex, 1);
  saveLicenses(data);
  return sendJson(response, 200, { success: true, message: "Lisensi berhasil dihapus" });
}

function handleLicenseValidation(body, response, options) {
  if (!isApiTokenValid(body.apiToken)) {
    return sendJson(response, 403, { success: false, error: "API token salah" });
  }

  const data = loadLicenses();
  const licenseKey = normalizeLicenseKey(body.licenseKey);
  const deviceId = String(body.deviceId || "").trim();
  const deviceName = options.includeDeviceName ? String(body.deviceName || "").trim() : "";
  const license = findLicense(data.licenses, licenseKey);
  const validation = validateLicense(license, deviceId, deviceName, options.bindIfEmpty);
  if (!validation.success) {
    return sendJson(response, validation.statusCode, { success: false, error: validation.error });
  }

  if (validation.changed) {
    saveLicenses(data);
  }

  return sendJson(response, 200, { success: true, license: toPublicLicense(license) });
}

function handleConfig(requestUrl, response) {
  if (!isApiTokenValid(requestUrl.searchParams.get("token"))) {
    return sendJson(response, 403, { success: false, error: "API token salah" });
  }

  const licenseKey = requestUrl.searchParams.get("licenseKey");
  const deviceId = requestUrl.searchParams.get("deviceId");
  const data = loadLicenses();
  const license = findLicense(data.licenses, normalizeLicenseKey(licenseKey));
  const validation = validateLicense(license, String(deviceId || "").trim(), "", false);
  if (!validation.success) {
    return sendJson(response, validation.statusCode, { success: false, error: validation.error });
  }

  saveLicenses(data);

  const config = loadConfig();
  const security = loadSecurity();
  return sendJson(response, 200, {
    relay: config.relay,
    rooms: Array.isArray(config.rooms) ? config.rooms : [],
    audio: config.audio || {},
    license: {
      organization: String((config.license && config.license.organization) || ""),
      licenseKey: license.licenseKey,
      active: !!license.active,
      activeUntil: String(license.activeUntil || ""),
      maxUsers: Number((config.license && config.license.maxUsers) || 0),
      maxDevices: Number((config.license && config.license.maxDevices) || 0)
    },
    security: {
      relayToken: String(security.relayToken || "")
    }
  });
}

function validateLicense(license, deviceId, deviceName, bindIfEmpty) {
  if (!license) {
    return { success: false, statusCode: 404, error: "Kode lisensi tidak ditemukan" };
  }
  if (!license.active) {
    return { success: false, statusCode: 403, error: "Lisensi tidak aktif" };
  }
  if (isExpired(license.activeUntil)) {
    return { success: false, statusCode: 403, error: "Lisensi sudah kedaluwarsa" };
  }
  if (!deviceId) {
    return { success: false, statusCode: 400, error: "Device ID wajib diisi" };
  }

  const nowIso = new Date().toISOString();
  if (!String(license.deviceId || "").trim()) {
    if (!bindIfEmpty) {
      return { success: false, statusCode: 403, error: "Lisensi belum diaktivasi di perangkat ini" };
    }
    license.deviceId = deviceId;
    license.deviceName = deviceName || String(license.deviceName || "");
    license.activatedAt = nowIso;
    license.lastCheckAt = nowIso;
    return { success: true, changed: true };
  }

  if (license.deviceId !== deviceId) {
    return { success: false, statusCode: 403, error: "Lisensi sudah digunakan di perangkat lain" };
  }

  if (deviceName && deviceName !== license.deviceName) {
    license.deviceName = deviceName;
  }
  license.lastCheckAt = nowIso;
  return { success: true, changed: true };
}

function isApiTokenValid(apiToken) {
  const security = loadSecurity();
  return String(apiToken || "").trim() === String(security.apiToken || "").trim();
}

function loadConfig() {
  return normalizeConfig(loadJson(CONFIG_PATH, DEFAULT_CONFIG));
}

function loadSecurity() {
  return {
    apiToken: process.env.ADMIN_API_TOKEN,
    relayToken: process.env.RELAY_TOKEN
  };
}

function loadLicenses() {
  const data = loadJson(LICENSES_PATH, null);
  if (!data || typeof data !== "object") {
    throw new Error("File operasional lisensi tidak dapat dibaca");
  }
  if (!Array.isArray(data.licenses)) {
    throw new Error("Struktur file operasional lisensi tidak valid");
  }
  return data;
}

function saveLicenses(data) {
  fs.writeFileSync(LICENSES_PATH, `${JSON.stringify(data, null, 2)}\n`, "utf8");
}

function loadJson(filePath, fallback) {
  try {
    return JSON.parse(fs.readFileSync(filePath, "utf8"));
  } catch (error) {
    return JSON.parse(JSON.stringify(fallback));
  }
}

function requiredEnvironment(name) {
  const value = String(process.env[name] || "").trim();
  if (!value) {
    throw new Error(`Konfigurasi runtime wajib tidak tersedia: ${name}`);
  }
  return value;
}

function resolveRequiredPath(name) {
  const value = requiredEnvironment(name);
  return path.isAbsolute(value) ? value : path.resolve(ROOT, value);
}

function validateRuntimeConfiguration() {
  requiredEnvironment("ADMIN_API_TOKEN");
  requiredEnvironment("RELAY_TOKEN");
  requiredEnvironment("SESSION_SECRET");
  requiredEnvironment("ADMIN_ORIGIN");
}

function ensureRequiredFile(filePath, environmentName) {
  if (!fs.existsSync(filePath)) {
    throw new Error(`File operasional untuk ${environmentName} tidak ditemukan`);
  }
}

function ensureJsonFile(filePath, content) {
  if (!fs.existsSync(filePath)) {
    fs.writeFileSync(filePath, `${JSON.stringify(content, null, 2)}\n`, "utf8");
  }
}

function normalizeConfig(config) {
  const current = config && typeof config === "object" ? config : {};
  const relay = current.relay && typeof current.relay === "object" ? current.relay : {};
  const relayMode = String(relay.mode || "websocket").trim().toLowerCase() || "websocket";
  const relayHost = String(relay.host || "intercom.ssrapps.my.id").trim();
  const relayPort = Number(relay.port || (relayMode === "websocket" ? 443 : 47654));
  const relayPath = String(relay.path || "/relay").trim() || "/relay";
  const relayUrl = String(relay.url || buildRelayPublicUrl(relayMode, relayHost, relayPort, relayPath)).trim();

  return {
    relay: {
      mode: relayMode,
      url: relayUrl,
      host: relayHost,
      port: relayPort,
      path: relayPath
    },
    rooms: Array.isArray(current.rooms) ? current.rooms : DEFAULT_CONFIG.rooms,
    audio: current.audio || DEFAULT_CONFIG.audio,
    license: current.license || DEFAULT_CONFIG.license
  };
}

function buildRelayPublicUrl(mode, host, port, pathValue) {
  const relayMode = String(mode || "websocket").trim().toLowerCase();
  const relayHost = String(host || "intercom.ssrapps.my.id").trim();
  const relayPort = Number(port || 443);
  let relayPath = String(pathValue || "/relay").trim() || "/relay";
  if (!relayPath.startsWith("/")) {
    relayPath = `/${relayPath}`;
  }
  if (relayMode === "websocket") {
    if (relayPort === 443) {
      return `wss://${relayHost}${relayPath}`;
    }
    return `wss://${relayHost}:${relayPort}${relayPath}`;
  }
  return relayHost;
}

function sendRelay(client, payload) {
  if (client.socket.readyState === WebSocket.OPEN) {
    client.socket.send(JSON.stringify(payload));
  }
}

function relayRoomMembers(room) {
  return relayRooms.get(room) || new Set();
}

function joinRelayRoom(client, roomName) {
  leaveRelayRoom(client);
  const room = normalizeRelayRoom(roomName);
  client.room = room;
  if (!relayRooms.has(room)) {
    relayRooms.set(room, new Set());
  }
  relayRooms.get(room).add(client);
  sendRelay(client, { type: "joined", room });
  sendRelayPeers(room);
}

function leaveRelayRoom(client) {
  if (!client.room) {
    return;
  }
  const members = relayRooms.get(client.room);
  if (members) {
    members.delete(client);
    if (members.size === 0) {
      relayRooms.delete(client.room);
    }
  }
  const oldRoom = client.room;
  client.room = null;
  sendRelayPeers(oldRoom);
}

function sendRelayPeers(room) {
  if (!room) {
    return;
  }
  const members = relayRoomMembers(room);
  const authenticatedMembers = Array.from(members).filter((member) => member.authenticated);
  const peers = authenticatedMembers.map((member) => ({
    id: member.id,
    name: member.name
  }));
  for (const member of authenticatedMembers) {
    sendRelay(member, { type: "peers", room, peers });
  }
}

function broadcastRelayToRoom(room, payload, exceptClient) {
  for (const member of relayRoomMembers(room)) {
    if (member !== exceptClient && member.authenticated) {
      sendRelay(member, payload);
    }
  }
}

function handleRelayMessage(client, message) {
  if (!message || typeof message.type !== "string") {
    return;
  }

  if (message.type === "hello") {
    const relayToken = String(loadSecurity().relayToken || "").trim();
    const requestToken = String(message.token || "").trim();
    if (relayToken && requestToken !== relayToken) {
      sendRelay(client, { type: "error", message: "Token relay tidak valid" });
      client.socket.close(1008, "Token relay tidak valid");
      return;
    }

    client.id = String(message.id || client.id).trim();
    client.name = normalizeRelayName(message.name);
    client.room = normalizeRelayRoom(message.room);
    client.authenticated = true;

    sendRelay(client, {
      type: "welcome",
      id: client.id,
      rooms: Array.from(relayRooms.keys()).sort()
    });
    joinRelayRoom(client, client.room);
    return;
  }

  if (!client.authenticated) {
    sendRelay(client, { type: "error", message: "Client belum login relay" });
    return;
  }

  if (message.type === "join") {
    joinRelayRoom(client, message.room);
    return;
  }

  if (message.type === "rooms") {
    sendRelay(client, { type: "rooms", rooms: Array.from(relayRooms.keys()).sort() });
    return;
  }

  if (message.type === "ping") {
    sendRelay(client, { type: "pong", at: Date.now() });
    return;
  }

  if (message.type === "audio" && client.room && typeof message.pcm === "string") {
    broadcastRelayToRoom(
      client.room,
      {
        type: "audio",
        room: client.room,
        from: client.id,
        name: client.name,
        seq: Number(message.seq || 0),
        pcm: message.pcm
      },
      client
    );
  }
}

function normalizeRelayRoom(value) {
  const room = String(value || "umum").trim().toLowerCase();
  return room.length ? room.slice(0, 40) : "umum";
}

function normalizeRelayName(value) {
  const name = String(value || "User").trim();
  return name.length ? name.slice(0, 32) : "User";
}

function readJsonBody(request) {
  return new Promise((resolve, reject) => {
    let body = "";
    request.on("data", (chunk) => {
      body += chunk;
      if (body.length > 1024 * 1024) {
        reject(new Error("Body terlalu besar"));
        request.destroy();
      }
    });
    request.on("end", () => {
      if (!body.trim()) {
        resolve({});
        return;
      }
      try {
        resolve(JSON.parse(body));
      } catch (error) {
        reject(new Error("JSON body tidak valid"));
      }
    });
    request.on("error", reject);
  });
}

function sendJson(response, statusCode, payload) {
  response.writeHead(statusCode, {
    "Content-Type": "application/json; charset=utf-8",
    "Access-Control-Allow-Origin": "*"
  });
  response.end(JSON.stringify(payload));
}

function sendHtml(response, html) {
  response.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
  response.end(html);
}

function normalizeLicenseKey(value) {
  return String(value || "").trim().toUpperCase();
}

function normalizeDate(value) {
  const text = String(value || "").trim();
  return /^\d{4}-\d{2}-\d{2}$/.test(text) ? text : "";
}

function isExpired(activeUntil) {
  const clean = normalizeDate(activeUntil);
  if (!clean) {
    return true;
  }
  const today = new Date().toISOString().slice(0, 10);
  return clean < today;
}

function findLicense(licenses, licenseKey) {
  return licenses.find((item) => normalizeLicenseKey(item.licenseKey) === normalizeLicenseKey(licenseKey));
}

function toPublicLicense(license) {
  return {
    licenseKey: normalizeLicenseKey(license.licenseKey),
    customerName: String(license.customerName || ""),
    active: !!license.active,
    activeUntil: String(license.activeUntil || ""),
    deviceId: String(license.deviceId || ""),
    deviceName: String(license.deviceName || ""),
    activatedAt: String(license.activatedAt || ""),
    lastCheckAt: String(license.lastCheckAt || "")
  };
}
