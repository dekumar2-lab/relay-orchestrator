#!/usr/bin/env node
/**
 * Copilot bridge — a daemon that Java spawns to talk to GitHub Copilot.
 *
 * Protocol: JSON Lines over stdin/stdout.
 *   Request  (stdin):  {"id":"...","op":"chat","githubToken":"...", ...}
 *   Response (stdout): {"id":"...","ok":true, ...}  or  {"id":"...","ok":false,"error":"..."}
 *
 * Token strategy: Java passes the token from config.yml in every request.
 * If empty, the bridge falls back to env var / VS Code config / gh CLI.
 *
 * Supports:
 *   - Fine-grained PAT (github_pat_) — direct Bearer with developer-cli integration ID
 *   - OAuth token (gho_)            — exchange for short-lived session token
 */

const https = require("https");
const fs = require("fs");
const os = require("os");
const path = require("path");
const crypto = require("crypto");
const readline = require("readline");

const DEFAULT_API_BASE = "https://api.githubcopilot.com";
const EDITOR_VERSION = "vscode/1.104.1";
const EDITOR_PLUGIN_VERSION = "copilot-chat/0.26.7";
const USER_AGENT = "GitHubCopilotChat/0.26.7";
const PAT_INTEGRATION_ID = "copilot-developer-cli";
const OAUTH_INTEGRATION_ID = "vscode-chat";

// --------------------------------------------------------------------------
// Fallback token resolution (only used when Java didn't supply one)
// --------------------------------------------------------------------------

function findVsCodeToken() {
  const candidates = [];
  if (process.platform === "win32") {
    candidates.push(
      path.join(process.env.APPDATA || "", "github-copilot", "apps.json"),
    );
    candidates.push(
      path.join(
        process.env.APPDATA || "",
        "Code",
        "User",
        "globalStorage",
        "github.copilot-chat",
        "apps.json",
      ),
    );
  } else if (process.platform === "darwin") {
    candidates.push(
      path.join(
        os.homedir(),
        "Library",
        "Application Support",
        "Code",
        "User",
        "globalStorage",
        "github.copilot-chat",
        "apps.json",
      ),
    );
    candidates.push(
      path.join(os.homedir(), ".config", "github-copilot", "apps.json"),
    );
  } else {
    candidates.push(
      path.join(
        os.homedir(),
        ".config",
        "Code",
        "User",
        "globalStorage",
        "github.copilot-chat",
        "apps.json",
      ),
    );
    candidates.push(
      path.join(os.homedir(), ".config", "github-copilot", "apps.json"),
    );
  }
  for (const p of candidates) {
    try {
      if (!fs.existsSync(p)) continue;
      const obj = JSON.parse(fs.readFileSync(p, "utf8"));
      const entry = obj["github.com"] || obj;
      const tok = entry.oauth_token || entry.token;
      if (tok) return tok;
    } catch (_) {}
  }
  return null;
}

function findGhToken() {
  try {
    const { execSync } = require("child_process");
    const out = execSync("gh auth token", {
      stdio: ["ignore", "pipe", "ignore"],
      timeout: 5000,
    })
      .toString()
      .trim();
    if (out) return out;
  } catch (_) {}
  return null;
}

function resolveFallbackToken() {
  const envTok = process.env.RELAY_COPILOT_TOKEN;
  if (envTok && envTok.trim()) return envTok.trim();
  return findVsCodeToken() || findGhToken();
}

// --------------------------------------------------------------------------
// HTTP helper
// --------------------------------------------------------------------------

function httpsRequest(method, url, headers, body) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const req = https.request(
      {
        method,
        hostname: u.hostname,
        port: u.port || 443,
        path: u.pathname + u.search,
        headers,
      },
      (res) => {
        const chunks = [];
        res.on("data", (c) => chunks.push(c));
        res.on("end", () =>
          resolve({
            status: res.statusCode,
            headers: res.headers,
            body: Buffer.concat(chunks).toString("utf8"),
          }),
        );
      },
    );
    req.on("error", reject);
    if (body) req.write(body);
    req.end();
  });
}

// --------------------------------------------------------------------------
// OAuth session token exchange (only for gho_ tokens)
// --------------------------------------------------------------------------

const sessionCache = new Map();

function hashKey(s) {
  return crypto.createHash("sha256").update(s).digest("hex");
}

async function getOAuthSession(githubToken) {
  const key = hashKey(githubToken);
  const cached = sessionCache.get(key);
  if (cached && Date.now() < cached.expiresAt - 60000) return cached;

  const res = await httpsRequest(
    "GET",
    "https://api.github.com/copilot_internal/v2/token",
    {
      Authorization: `token ${githubToken}`,
      Accept: "application/json",
      "Editor-Version": EDITOR_VERSION,
      "Editor-Plugin-Version": EDITOR_PLUGIN_VERSION,
      "User-Agent": USER_AGENT,
      "X-GitHub-Api-Version": "2025-04-01",
      "Copilot-Integration-Id": OAUTH_INTEGRATION_ID,
    },
    null,
  );

  if (res.status !== 200) {
    throw new Error(
      `Copilot token exchange failed (${res.status}): ${res.body.slice(0, 200)}`,
    );
  }

  const data = JSON.parse(res.body);
  if (!data.token) throw new Error('Exchange response missing "token"');

  let apiBase = DEFAULT_API_BASE;
  for (const part of data.token.split(";")) {
    const t = part.trim();
    if (t.startsWith("proxy-ep=")) {
      apiBase = t
        .substring("proxy-ep=".length())
        .replace("://proxy.", "://api.")
        .replace(/\/$/, "");
      break;
    }
  }

  const expiresAt = data.expires_at
    ? data.expires_at * 1000
    : Date.now() + 30 * 60 * 1000;
  const session = { token: data.token, apiBase, expiresAt };
  sessionCache.set(key, session);
  return session;
}

// --------------------------------------------------------------------------
// Chat completion
// --------------------------------------------------------------------------

async function chatCompletion(req) {
  let token =
    (req.githubToken && req.githubToken.trim()) || resolveFallbackToken();
  if (!token) {
    throw new Error(
      "No Copilot token. Paste a PAT into Settings, set RELAY_COPILOT_TOKEN, " +
        "or sign in to Copilot in VS Code.",
    );
  }

  let bearerToken;
  let apiBase = DEFAULT_API_BASE;
  let integrationId;

  if (token.startsWith("github_pat_")) {
    bearerToken = token;
    integrationId = PAT_INTEGRATION_ID;
  } else if (token.startsWith("gho_")) {
    const session = await getOAuthSession(token);
    bearerToken = session.token;
    apiBase = session.apiBase;
    integrationId = OAUTH_INTEGRATION_ID;
  } else if (token.startsWith("ghp_")) {
    throw new Error(
      "Classic PATs (ghp_) are not accepted. Use github_pat_ or gho_.",
    );
  } else {
    throw new Error("Unrecognized token format. Expected github_pat_ or gho_.");
  }

  const body = {
    model: req.model || "gpt-4o",
    messages: req.messages || [],
  };
  if (req.maxTokens) body.max_tokens = req.maxTokens;
  if (req.temperature != null && req.temperature > 0)
    body.temperature = req.temperature;
  if (req.tools && req.tools.length) {
    body.tools = req.tools;
    if (req.toolChoice) body.tool_choice = req.toolChoice;
  }

  const res = await httpsRequest(
    "POST",
    `${apiBase}/chat/completions`,
    {
      Authorization: `Bearer ${bearerToken}`,
      "Content-Type": "application/json",
      "Copilot-Integration-Id": integrationId,
      "Editor-Version": EDITOR_VERSION,
      "Editor-Plugin-Version": EDITOR_PLUGIN_VERSION,
      "User-Agent": USER_AGENT,
    },
    JSON.stringify(body),
  );

  if (res.status !== 200) {
    throw new Error(
      `Copilot chat failed (${res.status}): ${res.body.slice(0, 300)}`,
    );
  }

  const parsed = JSON.parse(res.body);
  const choice = parsed.choices && parsed.choices[0];
  const msg = choice && choice.message;

  return {
    text: msg && msg.content ? String(msg.content) : null,
    toolCalls:
      msg && msg.tool_calls
        ? msg.tool_calls.map((tc) => ({
            id: tc.id,
            name: tc.function && tc.function.name,
            arguments: safeJson(tc.function && tc.function.arguments),
          }))
        : [],
    usage: {
      prompt_tokens: parsed.usage ? parsed.usage.prompt_tokens : 0,
      completion_tokens: parsed.usage ? parsed.usage.completion_tokens : 0,
      cached_tokens:
        parsed.usage && parsed.usage.prompt_tokens_details
          ? parsed.usage.prompt_tokens_details.cached_tokens || 0
          : 0,
    },
    model: parsed.model || req.model,
    finishReason: choice ? choice.finish_reason : null,
  };
}

function safeJson(s) {
  if (typeof s !== "string") return s || {};
  try {
    return JSON.parse(s);
  } catch (_) {
    return { _raw: s };
  }
}

// --------------------------------------------------------------------------
// Main loop
// --------------------------------------------------------------------------

async function handle(req) {
  switch (req.op) {
    case "chat":
      return await chatCompletion(req);
    case "ping":
      return {
        pong: true,
        authAvailable: !!(req.githubToken || resolveFallbackToken()),
      };
    default:
      throw new Error(`Unknown op: ${req.op}`);
  }
}

const rl = readline.createInterface({ input: process.stdin, terminal: false });
rl.on("line", async (line) => {
  let req;
  try {
    req = JSON.parse(line);
  } catch (e) {
    process.stdout.write(
      JSON.stringify({ id: null, ok: false, error: "Invalid JSON request" }) +
        "\n",
    );
    return;
  }
  try {
    const result = await handle(req);
    process.stdout.write(
      JSON.stringify({ id: req.id, ok: true, ...result }) + "\n",
    );
  } catch (err) {
    process.stdout.write(
      JSON.stringify({ id: req.id, ok: false, error: err.message }) + "\n",
    );
  }
});

process.stderr.write("copilot-bridge ready (node " + process.version + ")\n");
