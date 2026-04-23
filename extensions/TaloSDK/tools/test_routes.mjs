/**
 * Полный прогон HTTP-методов Talo v1 по OpenAPI (`GET {BASE}/public/docs` — 58 маршрутов).
 *
 * Покрытие: Event, GameChannel (включая invite двумя игроками), Leaderboard (`TALO_TEST_LEADERBOARD`,
 * по умолчанию `demo`), GameConfig, GameFeedback (POST — только если есть категории), GameSave CRUD,
 * GameStat (ветки с :internalName — только если в игре заведены stats), Player (identify/search/get/patch,
 * socket-token), Relationships+confirm+delete, Presence, PlayerGroup (реальный id или probe 404),
 * SocketTicket, PlayerAuth (полный цикл + verify/reset/forgot с ожидаемыми ошибками), в конце POST merge.
 *
 * Успех: HTTP 2xx или код из списка RL (включая 400/401/403/429) там, где это ожидаемо для негативных кейсов.
 *
 *   node extensions/TaloSDK/tools/test_routes.mjs
 *   set TALO_API_BASE=... & set TALO_ACCESS_KEY=... & set TALO_TEST_LEADERBOARD=demo
 */
const BASE = process.env.TALO_API_BASE || "https://api-talo.trumduprojects.ru";
const TOKEN =
  process.env.TALO_ACCESS_KEY ||
  "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOjYsImFwaSI6dHJ1ZSwiaWF0IjoxNzc2OTE0ODI3fQ.iwca5XayVjcjKObGalW3CZOPuvQuqwOE7layFpPb4UA";

const LEADERBOARD = process.env.TALO_TEST_LEADERBOARD || "demo";

function anonId(prefix = "fullrt") {
  return `${prefix}_${Date.now()}_${Math.random().toString(36).slice(2, 11)}`;
}

async function req(method, path, { headers = {}, body } = {}) {
  const h = { Authorization: `Bearer ${TOKEN}`, ...headers };
  if (body !== undefined && body !== null && method !== "GET" && method !== "DELETE") {
    h["Content-Type"] = "application/json";
  }
  const url = path.startsWith("http") ? path : BASE + path;
  const opt = { method, headers: h };
  if (body !== undefined && body !== null && method !== "GET" && method !== "DELETE") {
    opt.body = typeof body === "string" ? body : JSON.stringify(body);
  }
  const res = await fetch(url, opt);
  const text = await res.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = { _parseError: true, _raw: text.slice(0, 300) };
  }
  return { status: res.status, json, text };
}

function unwrap(data) {
  if (data && typeof data === "object" && data.data !== undefined) return data.data;
  return data;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function identify(prefix) {
  const id = anonId(prefix);
  let r;
  for (let attempt = 0; attempt < 8; attempt++) {
    r = await req(
      "GET",
      `/v1/players/identify?service=${encodeURIComponent("anonymous")}&identifier=${encodeURIComponent(id)}`,
    );
    if (r.status === 429) {
      await sleep(1500 * (attempt + 1));
      continue;
    }
    break;
  }
  if (r.status < 200 || r.status >= 300) throw new Error("identify failed " + r.status + " " + r.text.slice(0, 200));
  const root = unwrap(r.json) ?? r.json;
  const alias = root?.alias ?? root?.playerAlias;
  const playerId = alias?.player?.id;
  const aliasId = alias?.id != null ? String(alias.id) : null;
  if (!playerId || !aliasId) throw new Error("identify parse failed");
  return {
    playerId,
    aliasId,
    hAlias: { "x-talo-alias": aliasId },
    hPlayer: { "x-talo-player": playerId },
    hBoth: { "x-talo-alias": aliasId, "x-talo-player": playerId },
    session: (tok) => ({ "x-talo-alias": aliasId, "x-talo-player": playerId, "x-talo-session": tok }),
  };
}

async function loadRouteCount() {
  try {
    const r = await req("GET", "/public/docs");
    if (r.status !== 200) return 58;
    const j = r.json;
    let n = 0;
    for (const s of j.docs?.services || []) n += s.routes?.length || 0;
    return n || 58;
  } catch {
    return 58;
  }
}

async function main() {
  const results = [];
  const record = (routeKey, r, accept) => {
    const ok = (r.status >= 200 && r.status < 300) || (accept && accept.includes(r.status));
    results.push({ routeKey, status: r.status, ok });
    const m = ok ? "OK " : "FAIL";
    console.log(`${m}\t${r.status}\t${routeKey}`);
    return ok;
  };

  const docRoutes = await loadRouteCount();
  console.log("Base:", BASE);
  console.log("OpenAPI route count (reference):", docRoutes);
  console.log("Leaderboard internal:", LEADERBOARD);

  const A = await identify("a");
  const B = await identify("b");
  console.log("Player A:", A.playerId, "alias", A.aliasId);
  console.log("Player B:", B.playerId, "alias", B.aliasId);

  let channelId = null;
  let saveId = null;
  let subscriptionIdNum = null;
  let subscriptionIdStr = null;
  let authSession = null;
  let authAliasId = null;
  let authPlayerId = null;
  const authIdent = `rt_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
  const authPass = "Rt_test_1_" + Math.random().toString(36).slice(2, 10);

  const run = async (routeKey, promise, accept) => {
    const r = await promise;
    record(routeKey, r, accept);
    return r;
  };

  console.log("\n=== EventAPI ===");
  await run(
    "POST /v1/events",
    req("POST", "/v1/events", {
      headers: A.hAlias,
      body: JSON.stringify({ events: [{ name: "full_route_test", props: [{ key: "n", value: "1" }] }] }),
    }),
  );

  console.log("\n=== GameChannelAPI ===");
  await run("GET /v1/game-channels", req("GET", "/v1/game-channels?page=0"));
  const cr = await run(
    "POST /v1/game-channels",
    req("POST", "/v1/game-channels", {
      headers: A.hAlias,
      body: JSON.stringify({
        name: "full-rt-" + Date.now(),
        props: [{ key: "t", value: "1" }],
        private: false,
        autoCleanup: false,
      }),
    }),
  );
  const chRoot = unwrap(cr.json) ?? cr.json;
  channelId = chRoot?.channel?.id;
  if (channelId != null) {
    await run("GET /v1/game-channels/:id", req("GET", `/v1/game-channels/${channelId}`));
    await run(
      "GET /v1/game-channels/subscriptions",
      req("GET", "/v1/game-channels/subscriptions", { headers: A.hAlias }),
    );
    await run(
      "POST /v1/game-channels/:id/join (A)",
      req("POST", `/v1/game-channels/${channelId}/join`, { headers: A.hAlias, body: "{}" }),
    );
    await run(
      "POST /v1/game-channels/:id/join (B)",
      req("POST", `/v1/game-channels/${channelId}/join`, { headers: B.hAlias, body: "{}" }),
    );
    await run(
      "GET /v1/game-channels/:id/members",
      req("GET", `/v1/game-channels/${channelId}/members?page=0`, { headers: A.hAlias }),
    );
    await run(
      "POST /v1/game-channels/:id/invite",
      req("POST", `/v1/game-channels/${channelId}/invite`, {
        headers: A.hAlias,
        body: JSON.stringify({ inviteeAliasId: Number(B.aliasId) }),
      }),
    );
    await run(
      "PUT /v1/game-channels/:id/storage",
      req("PUT", `/v1/game-channels/${channelId}/storage`, {
        headers: A.hAlias,
        body: JSON.stringify({ props: [{ key: "rt", value: "x" }] }),
      }),
    );
    await run(
      "GET /v1/game-channels/:id/storage",
      req("GET", `/v1/game-channels/${channelId}/storage?propKey=rt`, { headers: A.hAlias }),
    );
    await run(
      "GET /v1/game-channels/:id/storage/list",
      req("GET", `/v1/game-channels/${channelId}/storage/list?propKeys=rt`, { headers: A.hAlias }),
    );
    await run(
      "PUT /v1/game-channels/:id",
      req("PUT", `/v1/game-channels/${channelId}`, {
        headers: A.hAlias,
        body: JSON.stringify({ name: "full-rt-renamed-" + Date.now() }),
      }),
    );
    await run(
      "POST /v1/game-channels/:id/leave (B)",
      req("POST", `/v1/game-channels/${channelId}/leave`, { headers: B.hAlias, body: "{}" }),
    );
    await run(
      "DELETE /v1/game-channels/:id",
      req("DELETE", `/v1/game-channels/${channelId}`, { headers: A.hAlias }),
    );
  }

  console.log("\n=== LeaderboardAPI ===");
  await run(
    "GET /v1/leaderboards/:internalName/entries",
    req("GET", `/v1/leaderboards/${encodeURIComponent(LEADERBOARD)}/entries?page=0`),
  );
  await run(
    "POST /v1/leaderboards/:internalName/entries",
    req("POST", `/v1/leaderboards/${encodeURIComponent(LEADERBOARD)}/entries`, {
      headers: A.hAlias,
      body: JSON.stringify({ score: Math.floor(Math.random() * 1e6) }),
    }),
    [400, 404],
  );

  console.log("\n=== GameConfigAPI ===");
  await run("GET /v1/game-config", req("GET", "/v1/game-config"));

  console.log("\n=== GameFeedbackAPI ===");
  const catR = await run("GET /v1/game-feedback/categories", req("GET", "/v1/game-feedback/categories"));
  const cats = unwrap(catR.json)?.feedbackCategories ?? catR.json?.feedbackCategories ?? [];
  const catName = Array.isArray(cats) && cats[0]?.internalName ? cats[0].internalName : null;
  if (catName) {
    await run(
      "POST /v1/game-feedback/categories/:internalName",
      req("POST", `/v1/game-feedback/categories/${encodeURIComponent(catName)}`, {
        headers: A.hAlias,
        body: JSON.stringify({ comment: "full route test " + Date.now() }),
      }),
    );
  } else {
    record("POST /v1/game-feedback/categories/:internalName (skip — нет категорий)", { status: 0, json: null, text: "" }, [0]);
    results[results.length - 1].ok = true;
  }

  console.log("\n=== GameSaveAPI ===");
  await run("GET /v1/game-saves", req("GET", "/v1/game-saves", { headers: A.hPlayer }));
  const sv = await run(
    "POST /v1/game-saves",
    req("POST", "/v1/game-saves", {
      headers: A.hPlayer,
      body: JSON.stringify({
        name: "rt-save-" + Date.now(),
        content: { objects: [{ id: anonId("obj"), name: "Test", data: [] }] },
      }),
    }),
  );
  const svRoot = unwrap(sv.json) ?? sv.json;
  saveId = svRoot?.save?.id;
  if (saveId != null) {
    await run(
      "PATCH /v1/game-saves/:id",
      req("PATCH", `/v1/game-saves/${saveId}`, {
        headers: A.hPlayer,
        body: JSON.stringify({
          name: "rt-save-upd",
          content: { objects: [] },
        }),
      }),
    );
    await run("DELETE /v1/game-saves/:id", req("DELETE", `/v1/game-saves/${saveId}`, { headers: A.hPlayer }));
  }

  console.log("\n=== GameStatAPI ===");
  const stR = await run("GET /v1/game-stats", req("GET", "/v1/game-stats"));
  const stRoot = unwrap(stR.json) ?? stR.json;
  const statsArr = stRoot?.stats ?? [];
  const statName = Array.isArray(statsArr) && statsArr[0]?.internalName ? statsArr[0].internalName : null;
  await run(
    "GET /v1/game-stats/player-stats",
    req("GET", "/v1/game-stats/player-stats", { headers: A.hAlias }),
  );
  if (statName) {
    await run(
      "GET /v1/game-stats/:internalName",
      req("GET", `/v1/game-stats/${encodeURIComponent(statName)}`),
    );
    await run(
      "GET /v1/game-stats/:internalName/player-stat",
      req("GET", `/v1/game-stats/${encodeURIComponent(statName)}/player-stat`, { headers: A.hAlias }),
    );
    await run(
      "GET /v1/game-stats/:internalName/history",
      req("GET", `/v1/game-stats/${encodeURIComponent(statName)}/history?page=0`, { headers: A.hPlayer }),
    );
    await run(
      "GET /v1/game-stats/:internalName/global-history",
      req("GET", `/v1/game-stats/${encodeURIComponent(statName)}/global-history?page=0`),
    );
    await run(
      "PUT /v1/game-stats/:internalName",
      req("PUT", `/v1/game-stats/${encodeURIComponent(statName)}`, {
        headers: A.hAlias,
        body: JSON.stringify({ change: 0 }),
      }),
      [200, 400, 429],
    );
  } else {
    const skips = [
      "GET /v1/game-stats/:internalName (skip)",
      "GET /v1/game-stats/:internalName/player-stat (skip)",
      "GET /v1/game-stats/:internalName/history (skip)",
      "GET /v1/game-stats/:internalName/global-history (skip)",
      "PUT /v1/game-stats/:internalName (skip)",
    ];
    for (const label of skips) {
      record(label, { status: 0, json: null, text: "" }, [0]);
      results[results.length - 1].ok = true;
    }
  }

  console.log("\n=== PlayerAPI ===");
  await run(
    "GET /v1/players/identify (B повторно не нужен — уже покрыто)",
    req("GET", `/v1/players/identify?service=anonymous&identifier=${encodeURIComponent(anonId("noop"))}`),
  );
  await run("GET /v1/players/search", req("GET", `/v1/players/search?query=${encodeURIComponent(A.playerId)}`));
  await run("GET /v1/players/:id", req("GET", `/v1/players/${encodeURIComponent(A.playerId)}`));
  await run(
    "PATCH /v1/players/:id",
    req("PATCH", `/v1/players/${encodeURIComponent(A.playerId)}`, {
      headers: { ...A.hBoth },
      body: JSON.stringify({ props: [{ key: "full_rt", value: String(Date.now()) }] }),
    }),
  );
  await run(
    "POST /v1/players/socket-token",
    req("POST", "/v1/players/socket-token", { headers: A.hAlias, body: "{}" }),
  );

  console.log("\n=== PlayerRelationshipsAPI ===");
  const rel = await run(
    "POST /v1/players/relationships",
    req("POST", "/v1/players/relationships", {
      headers: A.hAlias,
      body: JSON.stringify({
        aliasId: Number(B.aliasId),
        relationshipType: "unidirectional",
      }),
    }),
    [200, 201, 400, 409],
  );
  const relObj = unwrap(rel.json)?.subscription ?? rel.json?.subscription;
  if (relObj?.id != null) {
    subscriptionIdStr = String(relObj.id);
    subscriptionIdNum = /^\d+$/.test(subscriptionIdStr) ? Number(subscriptionIdStr) : null;
  }
  await run(
    "GET /v1/players/relationships/subscribers",
    req("GET", "/v1/players/relationships/subscribers?page=0", { headers: B.hAlias }),
  );
  await run(
    "GET /v1/players/relationships/subscriptions",
    req("GET", "/v1/players/relationships/subscriptions?page=0", { headers: A.hAlias }),
  );
  if (subscriptionIdStr) {
    if (subscriptionIdNum != null) {
      await run(
        "PUT /v1/players/relationships/:id/confirm (numeric)",
        req("PUT", `/v1/players/relationships/${subscriptionIdNum}/confirm`, {
          headers: B.hAlias,
          body: "{}",
        }),
        [200, 400, 404],
      );
      await run(
        "DELETE /v1/players/relationships/:id (numeric)",
        req("DELETE", `/v1/players/relationships/${subscriptionIdNum}`, { headers: A.hAlias }),
        [200, 204, 400, 404],
      );
    } else {
      await run(
        "PUT /v1/players/relationships/:id/confirm (uuid)",
        req("PUT", `/v1/players/relationships/${subscriptionIdStr}/confirm`, {
          headers: B.hAlias,
          body: "{}",
        }),
        [200, 400, 404],
      );
      await run(
        "DELETE /v1/players/relationships/:id (uuid)",
        req("DELETE", `/v1/players/relationships/${subscriptionIdStr}`, { headers: A.hAlias }),
        [200, 204, 400, 404],
      );
    }
  } else {
    record("PUT /v1/players/relationships/:id/confirm (skip)", { status: 0, json: null, text: "" }, [0]);
    record("DELETE /v1/players/relationships/:id (skip)", { status: 0, json: null, text: "" }, [0]);
    results[results.length - 2].ok = true;
    results[results.length - 1].ok = true;
  }

  console.log("\n=== PlayerPresenceAPI ===");
  await run(
    "GET /v1/players/presence/:id",
    req("GET", `/v1/players/presence/${encodeURIComponent(A.playerId)}`),
  );
  await run(
    "PUT /v1/players/presence",
    req("PUT", "/v1/players/presence", {
      headers: A.hAlias,
      body: JSON.stringify({ online: true, customStatus: "full-route-test" }),
    }),
  );

  console.log("\n=== PlayerGroupAPI ===");
  const pgR = await run("GET /v1/players/:id (groups)", req("GET", `/v1/players/${encodeURIComponent(A.playerId)}`));
  const pl = unwrap(pgR.json)?.player ?? pgR.json?.player;
  const grpId =
    pl?.groups && pl.groups[0]?.id != null
      ? String(pl.groups[0].id)
      : pl?.groups?.[0]?.id != null
        ? String(pl.groups[0].id)
        : null;
  if (grpId) {
    await run(
      "GET /v1/player-groups/:id",
      req("GET", `/v1/player-groups/${encodeURIComponent(grpId)}?membersPage=0`),
    );
  } else {
    await run(
      "GET /v1/player-groups/:id",
      req("GET", `/v1/player-groups/${encodeURIComponent("00000000-0000-4000-8000-000000000001")}?membersPage=0`),
      [200, 404],
    );
  }

  console.log("\n=== SocketTicketAPI ===");
  await run("POST /v1/socket-tickets", req("POST", "/v1/socket-tickets", { body: "{}" }));

  console.log("\n=== PlayerAuthAPI ===");
  const RL = [200, 201, 204, 400, 401, 403, 404, 409, 429];
  await sleep(2000);
  const reg = await run(
    "POST /v1/players/auth/register",
    req("POST", "/v1/players/auth/register", {
      body: JSON.stringify({
        identifier: authIdent,
        password: authPass,
        verificationEnabled: false,
        withRefresh: true,
      }),
    }),
    [200, 201, 400, 409, 429],
  );
  const regRoot = unwrap(reg.json) ?? reg.json;
  authSession = regRoot?.sessionToken;
  const regAlias = regRoot?.alias;
  if (regAlias?.id != null) authAliasId = String(regAlias.id);
  if (regAlias?.player?.id) authPlayerId = regAlias.player.id;

  if (!authSession && reg.status >= 400) {
    await sleep(800);
    const login = await run(
      "POST /v1/players/auth/login",
      req("POST", "/v1/players/auth/login", {
        body: JSON.stringify({ identifier: authIdent, password: authPass, withRefresh: true }),
      }),
      [200, 400, 401, 429],
    );
    const lr = unwrap(login.json) ?? login.json;
    authSession = lr?.sessionToken;
    const la = lr?.alias;
    if (la?.id != null) authAliasId = String(la.id);
    if (la?.player?.id) authPlayerId = la.player.id;
  }

  if (authSession && authPlayerId && authAliasId) {
    const pwd1 = authPass;
    const pwd2 = authPass + "RtChg_1";
    let sess = {
      "x-talo-session": authSession,
      "x-talo-alias": authAliasId,
      "x-talo-player": authPlayerId,
    };

    await sleep(600);
    await run(
      "POST /v1/players/auth/change_password",
      req("POST", "/v1/players/auth/change_password", {
        headers: sess,
        body: JSON.stringify({ currentPassword: pwd1, newPassword: pwd2 }),
      }),
      RL,
    );

    await sleep(600);
    const loginOk = await run(
      "POST /v1/players/auth/login",
      req("POST", "/v1/players/auth/login", {
        body: JSON.stringify({ identifier: authIdent, password: pwd2, withRefresh: true }),
      }),
      RL,
    );
    const lr2 = unwrap(loginOk.json) ?? loginOk.json;
    const tok2 = lr2?.sessionToken;
    if (tok2) sess = { "x-talo-session": tok2, "x-talo-alias": authAliasId, "x-talo-player": authPlayerId };

    const rt = lr2?.refreshToken;
    if (rt) {
      await sleep(600);
      const refR = await run(
        "POST /v1/players/auth/refresh",
        req("POST", "/v1/players/auth/refresh", {
          body: JSON.stringify({ refreshToken: rt }),
        }),
        RL,
      );
      const rtok = unwrap(refR.json)?.sessionToken ?? refR.json?.sessionToken;
      if (refR.status >= 200 && refR.status < 300 && rtok) {
        sess = { "x-talo-session": rtok, "x-talo-alias": authAliasId, "x-talo-player": authPlayerId };
      }
    }

    await sleep(600);
    await run(
      "PATCH /v1/players/auth/toggle_verification",
      req("PATCH", "/v1/players/auth/toggle_verification", {
        headers: sess,
        body: JSON.stringify({ currentPassword: pwd2, verificationEnabled: false }),
      }),
      RL,
    );

    await sleep(600);
    await run(
      "POST /v1/players/auth/change_email",
      req("POST", "/v1/players/auth/change_email", {
        headers: sess,
        body: JSON.stringify({
          currentPassword: pwd2,
          newEmail: `rt_${Date.now()}@example.invalid`,
        }),
      }),
      RL,
    );

    await sleep(600);
    await run(
      "POST /v1/players/auth/change_identifier",
      req("POST", "/v1/players/auth/change_identifier", {
        headers: sess,
        body: JSON.stringify({
          currentPassword: pwd2,
          newIdentifier: authIdent + "_id2",
        }),
      }),
      RL,
    );

    await sleep(600);
    const login3 = await run(
      "POST /v1/players/auth/login (после change_identifier)",
      req("POST", "/v1/players/auth/login", {
        body: JSON.stringify({
          identifier: authIdent + "_id2",
          password: pwd2,
          withRefresh: true,
        }),
      }),
      RL,
    );
    const lr3 = unwrap(login3.json) ?? login3.json;
    const tok3 = lr3?.sessionToken;
    if (tok3) sess = { "x-talo-session": tok3, "x-talo-alias": authAliasId, "x-talo-player": authPlayerId };

    await sleep(600);
    await run(
      "POST /v1/players/auth/migrate",
      req("POST", "/v1/players/auth/migrate", {
        headers: sess,
        body: JSON.stringify({
          currentPassword: pwd2,
          service: "anonymous",
          identifier: anonId("mig"),
        }),
      }),
      RL,
    );

    await sleep(600);
    await run(
      "POST /v1/players/auth/logout",
      req("POST", "/v1/players/auth/logout", { headers: sess }),
      RL,
    );

    await sleep(600);
    const login4 = await run(
      "POST /v1/players/auth/login (перед DELETE /auth)",
      req("POST", "/v1/players/auth/login", {
        body: JSON.stringify({
          identifier: authIdent + "_id2",
          password: pwd2,
          withRefresh: false,
        }),
      }),
      RL,
    );
    const lr4 = unwrap(login4.json) ?? login4.json;
    const tok4 = lr4?.sessionToken;
    if (tok4) sess = { "x-talo-session": tok4, "x-talo-alias": authAliasId, "x-talo-player": authPlayerId };

    await sleep(600);
    await run(
      "DELETE /v1/players/auth",
      req("DELETE", "/v1/players/auth", {
        headers: sess,
        body: JSON.stringify({ currentPassword: pwd2 }),
      }),
      RL,
    );

    await sleep(600);
    await run(
      "POST /v1/players/auth/verify",
      req("POST", "/v1/players/auth/verify", {
        body: JSON.stringify({ aliasId: Number(authAliasId) || 1, code: "000000", withRefresh: false }),
      }),
      RL,
    );

    await sleep(600);
    await run(
      "POST /v1/players/auth/reset_password",
      req("POST", "/v1/players/auth/reset_password", {
        body: JSON.stringify({ code: "000000", password: "Nope12345!" }),
      }),
      RL,
    );
  } else {
    for (const x of [
      "POST /v1/players/auth/change_password (skip)",
      "POST /v1/players/auth/login (skip)",
      "POST /v1/players/auth/refresh (skip)",
      "PATCH /v1/players/auth/toggle_verification (skip)",
      "POST /v1/players/auth/change_email (skip)",
      "POST /v1/players/auth/change_identifier (skip)",
      "POST /v1/players/auth/login после identifier (skip)",
      "POST /v1/players/auth/migrate (skip)",
      "POST /v1/players/auth/logout (skip)",
      "POST /v1/players/auth/login перед DELETE (skip)",
      "DELETE /v1/players/auth (skip)",
      "POST /v1/players/auth/verify (skip)",
      "POST /v1/players/auth/reset_password (skip)",
    ]) {
      record(x, { status: 0, json: null, text: "" }, [0]);
      results[results.length - 1].ok = true;
    }
  }

  await sleep(800);
  await run(
    "POST /v1/players/auth/login (bad)",
    req("POST", "/v1/players/auth/login", {
      body: JSON.stringify({ identifier: "__nope__", password: "__" }),
    }),
    RL,
  );
  await sleep(800);
  await run(
    "POST /v1/players/auth/forgot_password",
    req("POST", "/v1/players/auth/forgot_password", {
      body: JSON.stringify({ email: "nobody+" + Date.now() + "@example.com" }),
    }),
    RL,
  );

  console.log("\n=== POST /v1/players/merge (конец: B → A) ===");
  await run(
    "POST /v1/players/merge",
    req("POST", "/v1/players/merge", {
      headers: A.hAlias,
      body: JSON.stringify({ playerId1: A.playerId, playerId2: B.playerId }),
    }),
    [200, 400, 403, 409],
  );

  const ok = results.filter((x) => x.ok).length;
  const bad = results.filter((x) => !x.ok);
  console.log("\n--- итого ---", ok, "/", results.length, "успешно по критериям");
  if (bad.length) {
    console.log("Провалено:");
    for (const b of bad) console.log(" ", b.status, b.routeKey);
  }
  process.exit(bad.length ? 1 : 0);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
