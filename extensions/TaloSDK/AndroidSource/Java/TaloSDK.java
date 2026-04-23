package ${YYAndroidPackageName};

import ${YYAndroidPackageName}.RunnerActivity;
import com.yoyogames.runner.RunnerJNILib;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Talo HTTP client (Player API) for GameMaker Android.
 * Auth: {@code Authorization: Bearer <access key>}; player calls may use
 * {@code x-talo-player}, {@code x-talo-alias}, {@code x-talo-session} when set.
 */
public final class TaloSDK {

  private static final int EVENT_OTHER_SOCIAL = 70;
  private static final double ASYNC_ID = 9829;

  private static final String EXT_NAME = "TaloSDK";
  private static final String OPT_API_KEY = "api_key";
  private static final String OPT_API_ENDPOINT = "api_endpoint";
  private static final String OPT_ANONYMOUS_PLAYER = "anonymous_player";
  private static final String OPT_AUTO_INIT_PLAYER = "auto_init_player";
  /** After identify: socket + HTTP presence to stay "online" */
  private static final String OPT_MAINTAIN_ONLINE = "maintain_online_presence";

  private static final String PREFS_NAME = "talo_sdk_player";
  private static final String PREF_SERVICE = "player_service";
  private static final String PREF_IDENTIFIER = "player_identifier";
  private static final String SERVICE_ANONYMOUS = "anonymous";

  private static final ExecutorService executor = Executors.newCachedThreadPool();
  private static final SecureRandom secureRandom = new SecureRandom();

  private static volatile boolean sAutoStartupScheduled;

  private static volatile String sBaseUrl = "https://api.trytalo.com";
  private static volatile String sAccessKey = "";
  private static volatile boolean sSdkInitialized = false;
  private static volatile boolean sPlayerAuthorized = false;
  /** Optional: for endpoints that require player session headers */
  @Nullable
  private static volatile String sPlayerId;
  @Nullable
  private static volatile String sAliasId;
  @Nullable
  private static volatile String sSessionToken;

  /** Last successful identify: alias id (for x-talo-alias on merge/socket-token if session not set) */
  @Nullable
  private static volatile String sLastIdentifyAliasId;
  @Nullable
  private static volatile String sLastIdentifyPlayerId;

  private static final OkHttpClient sOkHttp = new OkHttpClient.Builder()
      .readTimeout(0, TimeUnit.MILLISECONDS)
      .build();
  @Nullable
  private static WebSocket sActiveWebSocket;
  @Nullable
  private static Handler sUiHandler;
  @Nullable
  private static Runnable sHeartbeatRunnable;
  @Nullable
  private static Runnable sPresenceRefreshRunnable;

  private static void log(String msg) {
    Log.i("yoyo", "TaloSDK: " + msg);
  }

  private static String extOpt(String key) {
    return RunnerJNILib.extOptGetString(EXT_NAME, key);
  }

  /** Boolean extension options (optType 0) are read as string (e.g. True/False). */
  private static boolean extOptBool(String key, boolean defaultValue) {
    try {
      String s = extOpt(key);
      if (s == null || s.trim().isEmpty())
        return defaultValue;
      s = s.trim().toLowerCase(Locale.US);
      if (s.equals("true") || s.equals("1") || s.equals("yes"))
        return true;
      if (s.equals("false") || s.equals("0") || s.equals("no"))
        return false;
    } catch (Throwable ignored) {
    }
    return defaultValue;
  }

  @Nullable
  private static Context appContext() {
    try {
      if (RunnerActivity.CurrentActivity != null)
        return RunnerActivity.CurrentActivity.getApplicationContext();
    } catch (Throwable ignored) {
    }
    return null;
  }

  private static SharedPreferences playerPrefs() {
    Context ctx = appContext();
    if (ctx == null)
      return null;
    return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
  }

  private static void saveCredentialsToStorage(String service, String identifier) {
    SharedPreferences p = playerPrefs();
    if (p == null)
      return;
    p.edit().putString(PREF_SERVICE, service).putString(PREF_IDENTIFIER, identifier).apply();
    log("saved credentials service=" + service);
  }

  /** Public: persist after custom login flow (same as successful identify). */
  public static double talo_save_credentials(String service, String identifier) {
    if (service != null && identifier != null && !service.isEmpty() && !identifier.isEmpty())
      saveCredentialsToStorage(service, identifier);
    return 1;
  }

  @Nullable
  private static String[] loadCredentialsFromStorage() {
    SharedPreferences p = playerPrefs();
    if (p == null)
      return null;
    String svc = p.getString(PREF_SERVICE, null);
    String id = p.getString(PREF_IDENTIFIER, null);
    if (svc == null || id == null || svc.isEmpty() || id.isEmpty())
      return null;
    return new String[] { svc, id };
  }

  /** RFC 9562 UUID version 7 (time-ordered). */
  private static String newUuidV7() {
    long ms = System.currentTimeMillis();
    byte[] b = new byte[16];
    secureRandom.nextBytes(b);
    b[0] = (byte) ((ms >> 40) & 0xFF);
    b[1] = (byte) ((ms >> 32) & 0xFF);
    b[2] = (byte) ((ms >> 24) & 0xFF);
    b[3] = (byte) ((ms >> 16) & 0xFF);
    b[4] = (byte) ((ms >> 8) & 0xFF);
    b[5] = (byte) (ms & 0xFF);
    b[6] = (byte) ((b[6] & 0x0F) | 0x70);
    b[8] = (byte) ((b[8] & 0x3F) | 0x80);
    return uuidBytesToString(b);
  }

  private static String uuidBytesToString(byte[] b) {
    StringBuilder sb = new StringBuilder(36);
    for (int i = 0; i < 16; i++) {
      if (i == 4 || i == 6 || i == 8 || i == 10)
        sb.append('-');
      sb.append(String.format(Locale.US, "%02x", b[i] & 0xFF));
    }
    return sb.toString();
  }

  private static String normalizeBaseUrl(String url) {
    if (url == null || url.trim().isEmpty())
      return "https://api.trytalo.com";
    String u = url.trim();
    while (u.endsWith("/"))
      u = u.substring(0, u.length() - 1);
    return u;
  }

  private static void loadConfigFromExtension() {
    sAccessKey = extOpt(OPT_API_KEY);
    if (sAccessKey == null)
      sAccessKey = "";
    sBaseUrl = normalizeBaseUrl(extOpt(OPT_API_ENDPOINT));
  }

  /** Initialize from extension options (Access keys + Api server URL). Schedules auto player once. */
  public static double talo_init() {
    loadConfigFromExtension();
    sSdkInitialized = true;
    sPlayerAuthorized = false;
    log("init baseUrl=" + sBaseUrl + " keyLen=" + sAccessKey.length());
    scheduleAutoStartupOnce();
    return 1;
  }

  /** Returns 1 when SDK init() was called successfully in this app session. */
  public static double talo_is_initialized() {
    return sSdkInitialized ? 1 : 0;
  }

  /** Returns 1 after successful identify; 0 otherwise. */
  public static double talo_is_player_authorized() {
    return sPlayerAuthorized ? 1 : 0;
  }

  private static void scheduleAutoStartupOnce() {
    if (sAutoStartupScheduled)
      return;
    sAutoStartupScheduled = true;
    RunnerActivity.ViewHandler.post(() -> {
      boolean anon = extOptBool(OPT_ANONYMOUS_PLAYER, true);
      boolean autoInit = extOptBool(OPT_AUTO_INIT_PLAYER, true);
      log("autoStartup anonymous_player=" + anon + " auto_init_player=" + autoInit);
      String[] saved = loadCredentialsFromStorage();
      if (saved != null) {
        if (autoInit) {
          queueIdentify(saved[0], saved[1], true, true);
          return;
        }
        if (anon && SERVICE_ANONYMOUS.equalsIgnoreCase(saved[0])) {
          queueIdentify(saved[0], saved[1], true, true);
          return;
        }
      }
      if (anon && saved == null) {
        String id = newUuidV7();
        log("anonymous new uuid7=" + id);
        queueIdentify(SERVICE_ANONYMOUS, id, true, true);
      }
    });
  }

  private static void ensureConfigForApi() {
    loadConfigFromExtension();
  }

  /**
   * Set player context for APIs that require {@code x-talo-player}, {@code x-talo-alias},
   * {@code x-talo-session}. Pass empty strings to clear optional fields.
   */
  public static double talo_set_player_context(String playerId, String aliasId, String sessionToken) {
    sPlayerId = emptyToNull(playerId);
    sAliasId = emptyToNull(aliasId);
    sSessionToken = emptyToNull(sessionToken);
    return 1;
  }

  @Nullable
  private static String emptyToNull(String s) {
    if (s == null || s.isEmpty())
      return null;
    return s;
  }

  public static String talo_get_last_player_id() {
    return sLastIdentifyPlayerId != null ? sLastIdentifyPlayerId : "";
  }

  public static String talo_get_last_alias_id() {
    return sLastIdentifyAliasId != null ? sLastIdentifyAliasId : "";
  }

  private static String urlEncode(String value) {
    try {
      return URLEncoder.encode(value, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException(e);
    }
  }

  private static void applyDefaultHeaders(HttpURLConnection c, boolean jsonBody) throws Exception {
    if (sAccessKey == null || sAccessKey.isEmpty())
      throw new IllegalStateException("Missing access key (extension option api_key)");
    c.setRequestProperty("Authorization", "Bearer " + sAccessKey);
    if (jsonBody)
      c.setRequestProperty("Content-Type", "application/json");
    String player = sPlayerId != null ? sPlayerId : sLastIdentifyPlayerId;
    if (player != null)
      c.setRequestProperty("x-talo-player", player);
    String alias = sAliasId != null ? sAliasId : sLastIdentifyAliasId;
    if (alias != null)
      c.setRequestProperty("x-talo-alias", alias);
    if (sSessionToken != null)
      c.setRequestProperty("x-talo-session", sSessionToken);
  }

  private static String readStream(InputStream is) throws Exception {
    BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
    StringBuilder sb = new StringBuilder();
    String line;
    while ((line = br.readLine()) != null)
      sb.append(line);
    return sb.toString();
  }

  private static final class HttpResult {
    final int code;
    final String body;

    HttpResult(int code, String body) {
      this.code = code;
      this.body = body != null ? body : "";
    }
  }

  private static HttpResult httpRequest(
      String method,
      String pathAndQuery,
      @Nullable String jsonBody,
      boolean jsonContentType,
      @Nullable Map<String, String> headerOverrides) {
    try {
      URL url = new URL(sBaseUrl + pathAndQuery);
      HttpURLConnection c = (HttpURLConnection) url.openConnection();
      c.setRequestMethod(method);
      c.setConnectTimeout(30000);
      c.setReadTimeout(30000);
      boolean hasBody =
          jsonBody != null
              && !jsonBody.isEmpty()
              && ("POST".equals(method)
                  || "PATCH".equals(method)
                  || "PUT".equals(method)
                  || "DELETE".equals(method));
      applyDefaultHeaders(c, jsonContentType && hasBody);
      if (headerOverrides != null) {
        for (Map.Entry<String, String> e : headerOverrides.entrySet()) {
          if (e.getValue() != null)
            c.setRequestProperty(e.getKey(), e.getValue());
        }
      }
      if (hasBody) {
        c.setDoOutput(true);
        byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        c.setRequestProperty("Content-Length", String.valueOf(bytes.length));
        try (OutputStream os = c.getOutputStream()) {
          os.write(bytes);
        }
      }
      int code = c.getResponseCode();
      InputStream stream = code >= 400 ? c.getErrorStream() : c.getInputStream();
      String body = stream != null ? readStream(stream) : "";
      if (code >= 400)
        throw new HttpError(code, body);
      return new HttpResult(code, body);
    } catch (HttpError e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static final class HttpError extends RuntimeException {
    final int code;
    final String body;

    HttpError(int code, String body) {
      super("HTTP " + code);
      this.code = code;
      this.body = body != null ? body : "";
    }
  }

  private static void sendAsync(String op, boolean success, int httpCode, @Nullable String body, @Nullable String err,
      boolean fromAutoStartup) {
    RunnerActivity.ViewHandler.post(() -> {
      int map = RunnerJNILib.jCreateDsMap(null, null, null);
      RunnerJNILib.DsMapAddDouble(map, "id", ASYNC_ID);
      RunnerJNILib.DsMapAddString(map, "talo_op", op);
      RunnerJNILib.DsMapAddDouble(map, "success", success ? 1 : 0);
      RunnerJNILib.DsMapAddDouble(map, "http_code", httpCode);
      RunnerJNILib.DsMapAddString(map, "body", body != null ? body : "");
      RunnerJNILib.DsMapAddString(map, "error", err != null ? err : "");
      RunnerJNILib.DsMapAddDouble(map, "talo_auto", fromAutoStartup ? 1 : 0);
      RunnerJNILib.CreateAsynEventWithDSMap(map, EVENT_OTHER_SOCIAL);
    });
  }

  private static void runPlayerApi(String op, Runnable task) {
    executor.execute(() -> {
      try {
        task.run();
      } catch (HttpError e) {
        log(op + " HTTP " + e.code + " " + e.body);
        sendAsync(op, false, e.code, e.body, e.getMessage(), false);
      } catch (RuntimeException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        log(op + " " + cause.getMessage());
        sendAsync(op, false, 0, "", cause.getMessage() != null ? cause.getMessage() : "error", false);
      }
    });
  }

  private static void runPlayerApi(String op, Runnable task, boolean fromAuto) {
    executor.execute(() -> {
      try {
        task.run();
      } catch (HttpError e) {
        log(op + " HTTP " + e.code + " " + e.body);
        sendAsync(op, false, e.code, e.body, e.getMessage(), fromAuto);
      } catch (RuntimeException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        log(op + " " + cause.getMessage());
        sendAsync(op, false, 0, "", cause.getMessage() != null ? cause.getMessage() : "error", fromAuto);
      }
    });
  }

  private static void ensureUiHandler() {
    if (sUiHandler == null)
      sUiHandler = new Handler(Looper.getMainLooper());
  }

  private static void sendAsyncSocketMessage(String text) {
    RunnerActivity.ViewHandler.post(() -> {
      int map = RunnerJNILib.jCreateDsMap(null, null, null);
      RunnerJNILib.DsMapAddDouble(map, "id", ASYNC_ID);
      RunnerJNILib.DsMapAddString(map, "talo_op", "socket");
      RunnerJNILib.DsMapAddDouble(map, "success", 1);
      RunnerJNILib.DsMapAddDouble(map, "http_code", 0);
      RunnerJNILib.DsMapAddString(map, "body", text != null ? text : "");
      RunnerJNILib.DsMapAddString(map, "error", "");
      RunnerJNILib.DsMapAddDouble(map, "talo_auto", 0);
      RunnerJNILib.CreateAsynEventWithDSMap(map, EVENT_OTHER_SOCIAL);
    });
  }

  private static String wsBaseUrl() {
    String b = sBaseUrl;
    if (b.startsWith("https://"))
      return "wss://" + b.substring("https://".length());
    if (b.startsWith("http://"))
      return "ws://" + b.substring("http://".length());
    return b;
  }

  private static void cancelOnlineLoops() {
    ensureUiHandler();
    if (sHeartbeatRunnable != null) {
      sUiHandler.removeCallbacks(sHeartbeatRunnable);
      sHeartbeatRunnable = null;
    }
    if (sPresenceRefreshRunnable != null) {
      sUiHandler.removeCallbacks(sPresenceRefreshRunnable);
      sPresenceRefreshRunnable = null;
    }
  }

  private static void closeSocketInternal() {
    cancelOnlineLoops();
    if (sActiveWebSocket != null) {
      try {
        sActiveWebSocket.close(1000, "close");
      } catch (Throwable ignored) {
      }
      sActiveWebSocket = null;
    }
  }

  /** Stop WebSocket and presence refresh (HTTP online may still need explicit PUT offline). */
  public static double talo_socket_disconnect() {
    executor.execute(TaloSDK::closeSocketInternal);
    return 1;
  }

  /**
   * Full pipeline: REST socket-token + socket-ticket, WebSocket connect, {@code v1.players.identify},
   * {@code v1.heartbeat} every 25s, PUT presence {@code online} + periodic refresh.
   */
  private static void runOnlinePresencePipeline() {
    if (sAccessKey == null || sAccessKey.isEmpty())
      return;
    String alias = resolveAliasForHeader();
    if (alias == null)
      return;
    try {
      Map<String, String> hAlias = new HashMap<>();
      hAlias.put("x-talo-alias", alias);
      HttpResult tokRes = httpRequest("POST", "/v1/players/socket-token", "{}", true, hAlias);
      JSONObject tokRoot = new JSONObject(tokRes.body);
      String socketToken = tokRoot.getString("socketToken");

      HttpResult ticketRes = httpRequest("POST", "/v1/socket-tickets", "{}", true, null);
      String ticket = new JSONObject(ticketRes.body).getString("ticket");

      String wsUrl = wsBaseUrl() + "/socket?ticket=" + urlEncode(ticket);
      closeSocketInternal();

      Request req = new Request.Builder().url(wsUrl).build();
      sOkHttp.newWebSocket(req, new WebSocketListener() {
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
          sActiveWebSocket = webSocket;
          try {
            JSONObject data = new JSONObject();
            data.put("playerAliasId", Long.parseLong(alias));
            data.put("socketToken", socketToken);
            if (sSessionToken != null)
              data.put("sessionToken", sSessionToken);
            JSONObject msg = new JSONObject();
            msg.put("req", "v1.players.identify");
            msg.put("data", data);
            webSocket.send(msg.toString());
          } catch (JSONException e) {
            log("socket identify json: " + e.getMessage());
          }
          ensureUiHandler();
          cancelOnlineLoops();
          sHeartbeatRunnable = new Runnable() {
            @Override
            public void run() {
              if (sActiveWebSocket != null) {
                try {
                  sActiveWebSocket.send("v1.heartbeat");
                } catch (Throwable ignored) {
                }
                sUiHandler.postDelayed(this, 25000L);
              }
            }
          };
          sUiHandler.postDelayed(sHeartbeatRunnable, 25000L);
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
          if ("v1.heartbeat".equals(text))
            return;
          sendAsyncSocketMessage(text);
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
          onMessage(webSocket, bytes.utf8());
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, @Nullable Response response) {
          log("socket failure: " + (t != null ? t.getMessage() : "?"));
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
          log("socket closed " + code + " " + reason);
        }
      });

      // HTTP presence: online
      try {
        JSONObject body = new JSONObject();
        body.put("online", true);
        httpRequest("PUT", "/v1/players/presence", body.toString(), true, hAlias);
      } catch (JSONException e) {
        throw new RuntimeException(e);
      }

      ensureUiHandler();
      if (sPresenceRefreshRunnable != null) {
        sUiHandler.removeCallbacks(sPresenceRefreshRunnable);
        sPresenceRefreshRunnable = null;
      }
      sPresenceRefreshRunnable = new Runnable() {
        @Override
        public void run() {
          if (sLastIdentifyAliasId == null)
            return;
          try {
            Map<String, String> h = new HashMap<>();
            String al = resolveAliasForHeader();
            if (al == null)
              return;
            h.put("x-talo-alias", al);
            JSONObject body = new JSONObject();
            body.put("online", true);
            executor.execute(() -> {
              try {
                httpRequest("PUT", "/v1/players/presence", body.toString(), true, h);
              } catch (Throwable ignored) {
              }
            });
          } catch (JSONException ignored) {
          }
          sUiHandler.postDelayed(this, 55000L);
        }
      };
      sUiHandler.postDelayed(sPresenceRefreshRunnable, 55000L);
    } catch (Throwable t) {
      log("runOnlinePresencePipeline: " + t.getMessage());
    }
  }

  private static void scheduleOnlineAfterIdentify() {
    if (!extOptBool(OPT_MAINTAIN_ONLINE, true))
      return;
    if (sLastIdentifyAliasId == null)
      return;
    executor.execute(TaloSDK::runOnlinePresencePipeline);
  }

  private static Map<String, String> requireAliasHeaderMap() {
    String a = resolveAliasForHeader();
    if (a == null)
      throw new IllegalStateException("x-talo-alias required (identify or set_player_context)");
    Map<String, String> h = new HashMap<>();
    h.put("x-talo-alias", a);
    return h;
  }

  private static Map<String, String> requirePlayerHeaderMap() {
    String p = sPlayerId != null ? sPlayerId : sLastIdentifyPlayerId;
    if (p == null)
      throw new IllegalStateException("x-talo-player required (set_player_context or identify)");
    Map<String, String> h = new HashMap<>();
    h.put("x-talo-player", p);
    return h;
  }

  /** Low-level async HTTP; {@code pathAndQuery} starts with /v1, optional ?query included. */
  public static double talo_http_async(String method, String pathAndQuery, String bodyJson, String taloOp) {
    ensureConfigForApi();
    final String m = method.toUpperCase(Locale.US);
    final String body = (bodyJson == null || bodyJson.isEmpty()) ? null : bodyJson;
    runPlayerApi(taloOp, () -> {
      HttpResult res = httpRequest(m, pathAndQuery, body, body != null, null);
      sendAsync(taloOp, true, res.code, res.body, null, false);
    });
    return 1;
  }

  // --- Player API (https://api.trytalo.com/public/docs → PlayerAPI) ---

  private static void queueIdentify(String service, String identifier, boolean persistOnSuccess, boolean fromAutoStartup) {
    if (sAccessKey == null || sAccessKey.isEmpty()) {
      log("queueIdentify skipped: no api_key");
      return;
    }
    sPlayerAuthorized = false;
    runPlayerApi("identify", () -> {
      String q = "?service=" + urlEncode(service) + "&identifier=" + urlEncode(identifier);
      HttpResult res = httpRequest("GET", "/v1/players/identify" + q, null, false, null);
      try {
        JSONObject root = new JSONObject(res.body);
        if (root.has("alias")) {
          JSONObject alias = root.getJSONObject("alias");
          if (!alias.isNull("id"))
            sLastIdentifyAliasId = String.valueOf(alias.getLong("id"));
          JSONObject player = alias.getJSONObject("player");
          sLastIdentifyPlayerId = player.getString("id");
        }
      } catch (Exception ignored) {
      }
      if (persistOnSuccess)
        saveCredentialsToStorage(service, identifier);
      sPlayerAuthorized = true;
      sendAsync("identify", true, res.code, res.body, null, fromAutoStartup);
      scheduleOnlineAfterIdentify();
    }, fromAutoStartup);
  }

  /** GET /v1/players/identify?service=&identifier= */
  public static double talo_players_identify(String service, String identifier) {
    ensureConfigForApi();
    queueIdentify(service, identifier, true, false);
    return 1;
  }

  /** GET /v1/players/search?query= */
  public static double talo_players_search(String query) {
    ensureConfigForApi();
    runPlayerApi("search", () -> {
      String q = "?query=" + urlEncode(query);
      HttpResult res = httpRequest("GET", "/v1/players/search" + q, null, false, null);
        sendAsync("search", true, res.code, res.body, null, false);
    });
    return 1;
  }

  /** POST /v1/players/merge — requires x-talo-alias (uses context or last identify alias). */
  public static double talo_players_merge(String playerId1, String playerId2) {
    ensureConfigForApi();
    runPlayerApi("merge", () -> {
      String alias = resolveAliasForHeader();
      if (alias == null)
        throw new IllegalStateException("Set player context (alias) or call players_identify first");
      JSONObject json = new JSONObject();
      try {
        json.put("playerId1", playerId1);
        json.put("playerId2", playerId2);
      } catch (JSONException e) {
        throw new RuntimeException(e);
      }
      Map<String, String> h = new HashMap<>();
      h.put("x-talo-alias", alias);
      HttpResult res = httpRequest("POST", "/v1/players/merge", json.toString(), true, h);
        sendAsync("merge", true, res.code, res.body, null, false);
    });
    return 1;
  }

  @Nullable
  private static String resolveAliasForHeader() {
    if (sAliasId != null)
      return sAliasId;
    return sLastIdentifyAliasId;
  }

  /** POST /v1/players/socket-token */
  public static double talo_players_create_socket_token() {
    ensureConfigForApi();
    runPlayerApi("socket_token", () -> {
      String alias = resolveAliasForHeader();
      if (alias == null)
        throw new IllegalStateException("Set player context (alias) or call players_identify first");
      Map<String, String> h = new HashMap<>();
      h.put("x-talo-alias", alias);
      HttpResult res = httpRequest("POST", "/v1/players/socket-token", "{}", true, h);
        sendAsync("socket_token", true, res.code, res.body, null, false);
    });
    return 1;
  }

  /** GET /v1/players/:id */
  public static double talo_players_get(String playerId) {
    ensureConfigForApi();
    runPlayerApi("get_player", () -> {
      String path = "/v1/players/" + playerId.replace("/", "%2F");
      HttpResult res = httpRequest("GET", path, null, false, null);
        sendAsync("get_player", true, res.code, res.body, null, false);
    });
    return 1;
  }

  /** PATCH /v1/players/:id — body: { "props": [ ... ] } */
  public static double talo_players_patch_props(String playerId, String propsJsonArray) {
    ensureConfigForApi();
    runPlayerApi("patch_player", () -> {
      JSONArray props;
      try {
        props = new JSONArray(propsJsonArray);
      } catch (JSONException e) {
        throw new RuntimeException(e);
      }
      JSONObject json = new JSONObject();
      try {
        json.put("props", props);
      } catch (JSONException e) {
        throw new RuntimeException(e);
      }
      String path = "/v1/players/" + playerId.replace("/", "%2F");
      HttpResult res = httpRequest("PATCH", path, json.toString(), true, null);
        sendAsync("patch_player", true, res.code, res.body, null, false);
    });
    return 1;
  }

  // --- EventAPI ---
  /** OpenAPI body field is {@code events}; accepts JSON array (wrapped) or full object. */
  public static double talo_events_track(String eventsJsonArray) {
    ensureConfigForApi();
    runPlayerApi("events_track", () -> {
      String raw = eventsJsonArray == null ? "[]" : eventsJsonArray.trim();
      String body;
      if (raw.startsWith("{")) {
        body = raw;
      } else if (raw.startsWith("[")) {
        body = "{\"events\":" + raw + "}";
      } else {
        throw new IllegalArgumentException("eventsJsonArray must be a JSON array or object");
      }
      HttpResult res = httpRequest("POST", "/v1/events", body, true, requireAliasHeaderMap());
      sendAsync("events_track", true, res.code, res.body, null, false);
    });
    return 1;
  }

  // --- GameChannelAPI ---
  public static double talo_game_channels_list(String query) {
    ensureConfigForApi();
    String q = query == null || query.isEmpty() ? "" : ("?" + query);
    runPlayerApi("game_channels_list", () -> {
      HttpResult res = httpRequest("GET", "/v1/game-channels" + q, null, false, null);
      sendAsync("game_channels_list", true, res.code, res.body, null, false);
    });
    return 1;
  }

  public static double talo_game_channels_create(String bodyJson) {
    ensureConfigForApi();
    runPlayerApi("game_channels_create", () -> {
      HttpResult res = httpRequest("POST", "/v1/game-channels", bodyJson, true, requireAliasHeaderMap());
      sendAsync("game_channels_create", true, res.code, res.body, null, false);
    });
    return 1;
  }

  public static double talo_game_channels_subscriptions(String query) {
    ensureConfigForApi();
    String q = query == null || query.isEmpty() ? "" : ("?" + query);
    runPlayerApi("game_channels_subscriptions", () -> {
      HttpResult res = httpRequest("GET", "/v1/game-channels/subscriptions" + q, null, false, requireAliasHeaderMap());
      sendAsync("game_channels_subscriptions", true, res.code, res.body, null, false);
    });
    return 1;
  }

  public static double talo_game_channels_join(double channelId) {
    ensureConfigForApi();
    runPlayerApi("game_channels_join", () -> {
      HttpResult res = httpRequest(
          "POST", "/v1/game-channels/" + (long) channelId + "/join", "{}", true, requireAliasHeaderMap());
      sendAsync("game_channels_join", true, res.code, res.body, null, false);
    });
    return 1;
  }

  public static double talo_game_channels_leave(double channelId) {
    ensureConfigForApi();
    runPlayerApi("game_channels_leave", () -> {
      HttpResult res = httpRequest(
          "POST", "/v1/game-channels/" + (long) channelId + "/leave", "{}", true, requireAliasHeaderMap());
      sendAsync("game_channels_leave", true, res.code, res.body, null, false);
    });
    return 1;
  }

  public static double talo_game_channels_invite(double channelId, String bodyJson) {
    ensureConfigForApi();
    runPlayerApi("game_channels_invite", () -> {
      HttpResult res = httpRequest(
          "POST", "/v1/game-channels/" + (long) channelId + "/invite", bodyJson, true, requireAliasHeaderMap());
      sendAsync("game_channels_invite", true, res.code, res.body, null, false);
    });
    return 1;
  }

  public static double talo_game_channels_members(double channelId, String query) {
    ensureConfigForApi();
    String q = query == null || query.isEmpty() ? "" : ("?" + query);
    runPlayerApi("game_channels_members", () -> {
      HttpResult res = httpRequest(
          "GET", "/v1/game-channels/" + (long) channelId + "/members" + q, null, false, requireAliasHeaderMap());
      sendAsync("game_channels_members", true, res.code, res.body, null, false);
    });
    return 1;
  }

  public static double talo_game_channels_storage_list(double channelId, String query) {
    ensureConfigForApi();
    String q = query == null || query.isEmpty() ? "" : ("?" + query);
    runPlayerApi("game_channels_storage_list", () -> {
      HttpResult res = httpRequest(
          "GET",
          "/v1/game-channels/" + (long) channelId + "/storage/list" + q,
          null,
          false,
          requireAliasHeaderMap());
      sendAsync("game_channels_storage_list", true, res.code, res.body, null, false);
    });
    return 1;
  }

  public static double talo_game_channels_storage_get(double channelId, String query) {
    ensureConfigForApi();
    String q = query == null || query.isEmpty() ? "" : ("?" + query);
    runPlayerApi("game_channels_storage_get", () -> {
      HttpResult res = httpRequest(
          "GET", "/v1/game-channels/" + (long) channelId + "/storage" + q, null, false, requireAliasHeaderMap());
      sendAsync("game_channels_storage_get", true, res.code, res.body, null, false);
    });
    return 1;
  }

  public static double talo_game_channels_storage_put(double channelId, String bodyJson) {
    ensureConfigForApi();
    runPlayerApi("game_channels_storage_put", () -> {
      HttpResult res = httpRequest(
          "PUT", "/v1/game-channels/" + (long) channelId + "/storage", bodyJson, true, requireAliasHeaderMap());
      sendAsync("game_channels_storage_put", true, res.code, res.body, null, false);
    });
    return 1;
  }

  public static double talo_game_channels_get(double channelId) {
    ensureConfigForApi();
    runPlayerApi("game_channels_get", () -> {
      HttpResult res = httpRequest("GET", "/v1/game-channels/" + (long) channelId, null, false, null);
      sendAsync("game_channels_get", true, res.code, res.body, null, false);
    });
    return 1;
  }

  public static double talo_game_channels_update(double channelId, String bodyJson) {
    ensureConfigForApi();
    runPlayerApi("game_channels_update", () -> {
      HttpResult res = httpRequest(
          "PUT", "/v1/game-channels/" + (long) channelId, bodyJson, true, requireAliasHeaderMap());
      sendAsync("game_channels_update", true, res.code, res.body, null, false);
    });
    return 1;
  }

  public static double talo_game_channels_delete(double channelId) {
    ensureConfigForApi();
    runPlayerApi("game_channels_delete", () -> {
      HttpResult res = httpRequest(
          "DELETE", "/v1/game-channels/" + (long) channelId, null, false, requireAliasHeaderMap());
      sendAsync("game_channels_delete", true, res.code, res.body, null, false);
    });
    return 1;
  }

  // --- LeaderboardAPI ---
  public static double talo_leaderboard_entries_get(String internalName, String query) {
    ensureConfigForApi();
    String q = query == null || query.isEmpty() ? "" : ("?" + query);
    runPlayerApi("leaderboard_entries_get", () -> {
      HttpResult res = httpRequest(
          "GET", "/v1/leaderboards/" + urlEncodePathSegment(internalName) + "/entries" + q, null, false, null);
      sendAsync("leaderboard_entries_get", true, res.code, res.body, null, false);
      //String bodyWithTests = appendTestLeaderboardEntries(res.body, 50);
      //sendAsync("leaderboard_entries_get", true, res.code, bodyWithTests, null, false);
    });
    return 1;
  }

  /**
   * Appends random test leaderboard entries into GET /entries response body.
   * Used only for UI/debug validation on client side.
   */
  private static String appendTestLeaderboardEntries(String body, int amount) {
    if (body == null || body.isEmpty() || amount <= 0)
      return body;
    try {
      JSONObject root = new JSONObject(body);
      JSONObject payload = unwrapJsonDataObject(root);
      JSONArray entries = payload.optJSONArray("entries");
      if (entries == null) {
        entries = new JSONArray();
        payload.put("entries", entries);
      }
      int maxPosition = -1;
      for (int i = 0; i < entries.length(); i++) {
        JSONObject e = entries.optJSONObject(i);
        if (e == null || !e.has("position") || e.isNull("position"))
          continue;
        int p = e.optInt("position", -1);
        if (p > maxPosition)
          maxPosition = p;
      }
      for (int i = 0; i < amount; i++) {
        int pos = maxPosition + 1 + i;
        entries.put(buildTestLeaderboardEntry(pos, i));
      }
      int oldCount = payload.optInt("count", 0);
      payload.put("count", Math.max(oldCount, entries.length()));
      if (root.has("data") && root.opt("data") instanceof JSONObject)
        root.put("data", payload);
      return root.toString();
    } catch (Exception ignored) {
      return body;
    }
  }

  private static JSONObject buildTestLeaderboardEntry(int position, int index) throws JSONException {
    ThreadLocalRandom rnd = ThreadLocalRandom.current();
    JSONObject entry = new JSONObject();
    entry.put("position", position);
    entry.put("score", rnd.nextInt(1, 5000001));

    JSONObject player = new JSONObject();
    player.put("id", "test-player-" + (index + 1));
    JSONArray props = new JSONArray();
    JSONObject loginProp = new JSONObject();
    loginProp.put("key", "Login");
    loginProp.put("value", "TestUser_" + (index + 1) + "_" + rnd.nextInt(1000, 9999));
    props.put(loginProp);
    player.put("props", props);

    JSONObject playerAlias = new JSONObject();
    playerAlias.put("id", 900000 + index);
    playerAlias.put("player", player);
    entry.put("playerAlias", playerAlias);

    return entry;
  }

  public static double talo_leaderboard_entries_post(String internalName, String bodyJson) {
    ensureConfigForApi();
    runPlayerApi("leaderboard_entries_post", () -> {
      HttpResult res = httpRequest(
          "POST",
          "/v1/leaderboards/" + urlEncodePathSegment(internalName) + "/entries",
          bodyJson,
          true,
          requireAliasHeaderMap());
      sendAsync("leaderboard_entries_post", true, res.code, res.body, null, false);
    });
    return 1;
  }

  /**
   * Reads current player's score from GET /entries?player_id=..., adds {@code addPoints}, POSTs new score.
   * Async result: {@code talo_op} = {@code leaderboard_entries_add_score}, {@code body} = JSON with
   * {@code previousScore}, {@code added}, {@code newScore}, {@code postResponse} (object or string on parse failure).
   */
  public static double talo_leaderboard_entries_add_score(String internalName, double addPoints) {
    ensureConfigForApi();
    runPlayerApi("leaderboard_entries_add_score", () -> {
      String playerId = sPlayerId != null ? sPlayerId : sLastIdentifyPlayerId;
      if (playerId == null || playerId.isEmpty())
        throw new IllegalStateException("Player id required — TaloSDK_players_identify or TaloSDK_set_player_context");
      Map<String, String> aliasHeaders = requireAliasHeaderMap();
      String q = "?player_id=" + urlEncode(playerId);
      HttpResult getRes =
          httpRequest(
              "GET",
              "/v1/leaderboards/" + urlEncodePathSegment(internalName) + "/entries" + q,
              null,
              false,
              null);
      double previous = parseLeaderboardPlayerScore(getRes.body, playerId);
      double newScore = previous + addPoints;
      JSONObject postJson = new JSONObject();
      try {
        postJson.put("score", newScore);
      } catch (JSONException e) {
        throw new RuntimeException(e);
      }
      HttpResult postRes =
          httpRequest(
              "POST",
              "/v1/leaderboards/" + urlEncodePathSegment(internalName) + "/entries",
              postJson.toString(),
              true,
              aliasHeaders);
      JSONObject out = new JSONObject();
      try {
        out.put("previousScore", previous);
        out.put("added", addPoints);
        out.put("newScore", newScore);
        try {
          out.put("postResponse", new JSONObject(postRes.body));
        } catch (JSONException je) {
          out.put("postResponse", postRes.body);
        }
      } catch (JSONException e) {
        throw new RuntimeException(e);
      }
      sendAsync("leaderboard_entries_add_score", true, postRes.code, out.toString(), null, false);
    });
    return 1;
  }

  private static JSONObject unwrapJsonDataObject(JSONObject root) throws JSONException {
    if (root.has("data") && !root.isNull("data") && root.get("data") instanceof JSONObject)
      return root.getJSONObject("data");
    return root;
  }

  /** Finds current player's score in {@code entries}; 0 if missing or unparseable. */
  private static double parseLeaderboardPlayerScore(String body, String playerId) {
    if (body == null || body.isEmpty())
      return 0;
    try {
      JSONObject root = unwrapJsonDataObject(new JSONObject(body));
      if (!root.has("entries"))
        return 0;
      JSONArray arr = root.getJSONArray("entries");
      for (int i = 0; i < arr.length(); i++) {
        JSONObject e = arr.optJSONObject(i);
        if (e == null)
          continue;
        String entryPlayerId = leaderboardEntryPlayerId(e);
        if (entryPlayerId == null || !entryPlayerId.equals(playerId))
          continue;
        if (!e.has("score") || e.isNull("score"))
          return 0;
        return e.getDouble("score");
      }
      // Fallback: if server already filtered by player_id, may still return one entry without nested player info.
      if (arr.length() == 1) {
        JSONObject only = arr.optJSONObject(0);
        if (only != null && only.has("score") && !only.isNull("score"))
          return only.getDouble("score");
      }
      return 0;
    } catch (Exception ignored) {
      return 0;
    }
  }

  @Nullable
  private static String leaderboardEntryPlayerId(JSONObject entry) {
    if (entry == null)
      return null;
    if (entry.has("playerId") && !entry.isNull("playerId"))
      return entry.optString("playerId", null);
    JSONObject pa = entry.optJSONObject("playerAlias");
    if (pa == null)
      return null;
    JSONObject p = pa.optJSONObject("player");
    if (p == null)
      return null;
    return p.optString("id", null);
  }

  private static String urlEncodePathSegment(String s) {
    if (s == null || s.isEmpty())
      return "";
    try {
      return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException(e);
    }
  }

  // --- GameConfigAPI ---
  public static double talo_game_config_get() {
    ensureConfigForApi();
    runPlayerApi("game_config_get", () -> {
      HttpResult res = httpRequest("GET", "/v1/game-config", null, false, null);
      sendAsync("game_config_get", true, res.code, res.body, null, false);
    });
    return 1;
  }

  // --- GameFeedbackAPI ---
  public static double talo_game_feedback_categories_get() {
    ensureConfigForApi();
    runPlayerApi("game_feedback_categories_get", () -> {
      HttpResult res = httpRequest("GET", "/v1/game-feedback/categories", null, false, null);
      sendAsync("game_feedback_categories_get", true, res.code, res.body, null, false);
    });
    return 1;
  }

  public static double talo_game_feedback_create(String internalName, String bodyJson) {
    ensureConfigForApi();
    runPlayerApi("game_feedback_create", () -> {
      HttpResult res = httpRequest(
          "POST",
          "/v1/game-feedback/categories/" + urlEncodePathSegment(internalName),
          bodyJson,
          true,
          requireAliasHeaderMap());
      sendAsync("game_feedback_create", true, res.code, res.body, null, false);
    });
    return 1;
  }

  // --- GameSaveAPI ---
  public static double talo_game_saves_list() {
    ensureConfigForApi();
    runPlayerApi("game_saves_list", () -> {
      HttpResult res = httpRequest("GET", "/v1/game-saves", null, false, requirePlayerHeaderMap());
      sendAsync("game_saves_list", true, res.code, res.body, null, false);
    });
    return 1;
  }

  public static double talo_game_saves_create(String bodyJson) {
    ensureConfigForApi();
    runPlayerApi("game_saves_create", () -> {
      HttpResult res = httpRequest("POST", "/v1/game-saves", bodyJson, true, requirePlayerHeaderMap());
      sendAsync("game_saves_create", true, res.code, res.body, null, false);
    });
    return 1;
  }

  public static double talo_game_saves_update(double saveId, String bodyJson) {
    ensureConfigForApi();
    runPlayerApi("game_saves_update", () -> {
      HttpResult res = httpRequest(
          "PATCH", "/v1/game-saves/" + (long) saveId, bodyJson, true, requirePlayerHeaderMap());
      sendAsync("game_saves_update", true, res.code, res.body, null, false);
    });
    return 1;
  }

  public static double talo_game_saves_delete(double saveId) {
    ensureConfigForApi();
    runPlayerApi("game_saves_delete", () -> {
      HttpResult res = httpRequest("DELETE", "/v1/game-saves/" + (long) saveId, null, false, requirePlayerHeaderMap());
      sendAsync("game_saves_delete", true, res.code, res.body, null, false);
    });
    return 1;
  }

  // --- GameStatAPI ---
  public static double talo_game_stats_list() {
    ensureConfigForApi();
    runPlayerApi("game_stats_list", () -> {
      HttpResult res = httpRequest("GET", "/v1/game-stats", null, false, null);
      sendAsync("game_stats_list", true, res.code, res.body, null, false);
    });
    return 1;
  }

  public static double talo_game_stats_player_stats() {
    ensureConfigForApi();
    runPlayerApi("game_stats_player_stats", () -> {
      HttpResult res = httpRequest("GET", "/v1/game-stats/player-stats", null, false, requireAliasHeaderMap());
      sendAsync("game_stats_player_stats", true, res.code, res.body, null, false);
    });
    return 1;
  }

  public static double talo_game_stats_history(String internalName, String query) {
    ensureConfigForApi();
    String q = query == null || query.isEmpty() ? "" : ("?" + query);
    runPlayerApi("game_stats_history", () -> {
      HttpResult res = httpRequest(
          "GET",
          "/v1/game-stats/" + urlEncodePathSegment(internalName) + "/history" + q,
          null,
          false,
          requirePlayerHeaderMap());
      sendAsync("game_stats_history", true, res.code, res.body, null, false);
    });
    return 1;
  }

  public static double talo_game_stats_global_history(String internalName, String query) {
    ensureConfigForApi();
    String q = query == null || query.isEmpty() ? "" : ("?" + query);
    runPlayerApi("game_stats_global_history", () -> {
      HttpResult res = httpRequest(
          "GET", "/v1/game-stats/" + urlEncodePathSegment(internalName) + "/global-history" + q, null, false, null);
      sendAsync("game_stats_global_history", true, res.code, res.body, null, false);
    });
    return 1;
  }

  public static double talo_game_stats_player_stat_get(String internalName) {
    ensureConfigForApi();
    runPlayerApi("game_stats_player_stat_get", () -> {
      HttpResult res = httpRequest(
          "GET",
          "/v1/game-stats/" + urlEncodePathSegment(internalName) + "/player-stat",
          null,
          false,
          requireAliasHeaderMap());
      sendAsync("game_stats_player_stat_get", true, res.code, res.body, null, false);
    });
    return 1;
  }

  public static double talo_game_stats_get(String internalName) {
    ensureConfigForApi();
    runPlayerApi("game_stats_get", () -> {
      HttpResult res = httpRequest("GET", "/v1/game-stats/" + urlEncodePathSegment(internalName), null, false, null);
      sendAsync("game_stats_get", true, res.code, res.body, null, false);
    });
    return 1;
  }

  public static double talo_game_stats_put(String internalName, String bodyJson) {
    ensureConfigForApi();
    runPlayerApi("game_stats_put", () -> {
      HttpResult res = httpRequest(
          "PUT", "/v1/game-stats/" + urlEncodePathSegment(internalName), bodyJson, true, requireAliasHeaderMap());
      sendAsync("game_stats_put", true, res.code, res.body, null, false);
    });
    return 1;
  }

  // --- PlayerAuthAPI ---
  public static double talo_players_auth_register(String bodyJson) {
    ensureConfigForApi();
    runPlayerApi("players_auth_register", () -> {
      HttpResult res = httpRequest("POST", "/v1/players/auth/register", bodyJson, true, null);
      sendAsync("players_auth_register", true, res.code, res.body, null, false);
    });
    return 1;
  }

  public static double talo_players_auth_login(String bodyJson) {
    ensureConfigForApi();
    runPlayerApi("players_auth_login", () -> {
      HttpResult res = httpRequest("POST", "/v1/players/auth/login", bodyJson, true, null);
      sendAsync("players_auth_login", true, res.code, res.body, null, false);
    });
    return 1;
  }

  public static double talo_players_auth_refresh(String bodyJson) {
    ensureConfigForApi();
    runPlayerApi("players_auth_refresh", () -> {
      HttpResult res = httpRequest("POST", "/v1/players/auth/refresh", bodyJson, true, null);
      sendAsync("players_auth_refresh", true, res.code, res.body, null, false);
    });
    return 1;
  }

  public static double talo_players_auth_verify(String bodyJson) {
    ensureConfigForApi();
    runPlayerApi("players_auth_verify", () -> {
      HttpResult res = httpRequest("POST", "/v1/players/auth/verify", bodyJson, true, null);
      sendAsync("players_auth_verify", true, res.code, res.body, null, false);
    });
    return 1;
  }

  public static double talo_players_auth_logout() {
    ensureConfigForApi();
    runPlayerApi("players_auth_logout", () -> {
      HttpResult res = httpRequest("POST", "/v1/players/auth/logout", null, false, null);
      sendAsync("players_auth_logout", true, res.code, res.body, null, false);
    });
    return 1;
  }

  public static double talo_players_auth_change_password(String bodyJson) {
    ensureConfigForApi();
    runPlayerApi("players_auth_change_password", () -> {
      HttpResult res = httpRequest("POST", "/v1/players/auth/change_password", bodyJson, true, null);
      sendAsync("players_auth_change_password", true, res.code, res.body, null, false);
    });
    return 1;
  }

  public static double talo_players_auth_change_email(String bodyJson) {
    ensureConfigForApi();
    runPlayerApi("players_auth_change_email", () -> {
      HttpResult res = httpRequest("POST", "/v1/players/auth/change_email", bodyJson, true, null);
      sendAsync("players_auth_change_email", true, res.code, res.body, null, false);
    });
    return 1;
  }

  public static double talo_players_auth_change_identifier(String bodyJson) {
    ensureConfigForApi();
    runPlayerApi("players_auth_change_identifier", () -> {
      HttpResult res = httpRequest("POST", "/v1/players/auth/change_identifier", bodyJson, true, null);
      sendAsync("players_auth_change_identifier", true, res.code, res.body, null, false);
    });
    return 1;
  }

  public static double talo_players_auth_forgot_password(String bodyJson) {
    ensureConfigForApi();
    runPlayerApi("players_auth_forgot_password", () -> {
      HttpResult res = httpRequest("POST", "/v1/players/auth/forgot_password", bodyJson, true, null);
      sendAsync("players_auth_forgot_password", true, res.code, res.body, null, false);
    });
    return 1;
  }

  public static double talo_players_auth_reset_password(String bodyJson) {
    ensureConfigForApi();
    runPlayerApi("players_auth_reset_password", () -> {
      HttpResult res = httpRequest("POST", "/v1/players/auth/reset_password", bodyJson, true, null);
      sendAsync("players_auth_reset_password", true, res.code, res.body, null, false);
    });
    return 1;
  }

  public static double talo_players_auth_toggle_verification(String bodyJson) {
    ensureConfigForApi();
    runPlayerApi("players_auth_toggle_verification", () -> {
      HttpResult res = httpRequest("PATCH", "/v1/players/auth/toggle_verification", bodyJson, true, null);
      sendAsync("players_auth_toggle_verification", true, res.code, res.body, null, false);
    });
    return 1;
  }

  public static double talo_players_auth_delete(String bodyJson) {
    ensureConfigForApi();
    runPlayerApi("players_auth_delete", () -> {
      HttpResult res = httpRequest("DELETE", "/v1/players/auth", bodyJson, true, null);
      sendAsync("players_auth_delete", true, res.code, res.body, null, false);
    });
    return 1;
  }

  public static double talo_players_auth_migrate(String bodyJson) {
    ensureConfigForApi();
    runPlayerApi("players_auth_migrate", () -> {
      HttpResult res = httpRequest("POST", "/v1/players/auth/migrate", bodyJson, true, null);
      sendAsync("players_auth_migrate", true, res.code, res.body, null, false);
    });
    return 1;
  }

  // --- PlayerGroupAPI ---
  public static double talo_player_groups_get(String groupId, String query) {
    ensureConfigForApi();
    String q = query == null || query.isEmpty() ? "" : ("?" + query);
    runPlayerApi("player_groups_get", () -> {
      HttpResult res = httpRequest(
          "GET", "/v1/player-groups/" + urlEncodePathSegment(groupId) + q, null, false, null);
      sendAsync("player_groups_get", true, res.code, res.body, null, false);
    });
    return 1;
  }

  // --- PlayerPresenceAPI ---
  public static double talo_players_presence_get(String playerId) {
    ensureConfigForApi();
    runPlayerApi("players_presence_get", () -> {
      HttpResult res = httpRequest(
          "GET", "/v1/players/presence/" + urlEncodePathSegment(playerId), null, false, null);
      sendAsync("players_presence_get", true, res.code, res.body, null, false);
    });
    return 1;
  }

  public static double talo_players_presence_put(String bodyJson) {
    ensureConfigForApi();
    runPlayerApi("players_presence_put", () -> {
      HttpResult res = httpRequest("PUT", "/v1/players/presence", bodyJson, true, requireAliasHeaderMap());
      sendAsync("players_presence_put", true, res.code, res.body, null, false);
    });
    return 1;
  }

  // --- PlayerRelationshipsAPI ---
  public static double talo_players_relationships_create(String bodyJson) {
    ensureConfigForApi();
    runPlayerApi("players_relationships_create", () -> {
      HttpResult res = httpRequest("POST", "/v1/players/relationships", bodyJson, true, requireAliasHeaderMap());
      sendAsync("players_relationships_create", true, res.code, res.body, null, false);
    });
    return 1;
  }

  private static String relationshipsSubscriptionPathSegment(String subscriptionId) {
    if (subscriptionId == null || subscriptionId.isEmpty())
      throw new IllegalArgumentException("subscription id required");
    String s = subscriptionId.trim();
    if (s.indexOf('/') >= 0 || s.indexOf('?') >= 0 || s.indexOf('#') >= 0)
      throw new IllegalArgumentException("invalid subscription id");
    return s;
  }

  /** {@code PUT /v1/players/relationships/:id/confirm} — {@code id} numeric or UUID string. */
  public static double talo_players_relationships_confirm_str(String subscriptionId) {
    ensureConfigForApi();
    final String seg = relationshipsSubscriptionPathSegment(subscriptionId);
    runPlayerApi("players_relationships_confirm", () -> {
      HttpResult res = httpRequest(
          "PUT", "/v1/players/relationships/" + seg + "/confirm", "{}", true, requireAliasHeaderMap());
      sendAsync("players_relationships_confirm", true, res.code, res.body, null, false);
    });
    return 1;
  }

  public static double talo_players_relationships_confirm(double subscriptionId) {
    return talo_players_relationships_confirm_str(String.valueOf((long) subscriptionId));
  }

  public static double talo_players_relationships_subscribers(String query) {
    ensureConfigForApi();
    String q = query == null || query.isEmpty() ? "" : ("?" + query);
    runPlayerApi("players_relationships_subscribers", () -> {
      HttpResult res = httpRequest(
          "GET", "/v1/players/relationships/subscribers" + q, null, false, requireAliasHeaderMap());
      sendAsync("players_relationships_subscribers", true, res.code, res.body, null, false);
    });
    return 1;
  }

  public static double talo_players_relationships_subscriptions(String query) {
    ensureConfigForApi();
    String q = query == null || query.isEmpty() ? "" : ("?" + query);
    runPlayerApi("players_relationships_subscriptions", () -> {
      HttpResult res = httpRequest(
          "GET", "/v1/players/relationships/subscriptions" + q, null, false, requireAliasHeaderMap());
      sendAsync("players_relationships_subscriptions", true, res.code, res.body, null, false);
    });
    return 1;
  }

  /** {@code DELETE /v1/players/relationships/:id} — {@code id} numeric or UUID string. */
  public static double talo_players_relationships_delete_str(String subscriptionId) {
    ensureConfigForApi();
    final String seg = relationshipsSubscriptionPathSegment(subscriptionId);
    runPlayerApi("players_relationships_delete", () -> {
      HttpResult res =
          httpRequest("DELETE", "/v1/players/relationships/" + seg, null, false, requireAliasHeaderMap());
      sendAsync("players_relationships_delete", true, res.code, res.body, null, false);
    });
    return 1;
  }

  public static double talo_players_relationships_delete(double subscriptionId) {
    return talo_players_relationships_delete_str(String.valueOf((long) subscriptionId));
  }

  // --- SocketTicketAPI ---
  public static double talo_socket_tickets_create() {
    ensureConfigForApi();
    runPlayerApi("socket_tickets_create", () -> {
      HttpResult res = httpRequest("POST", "/v1/socket-tickets", "{}", true, null);
      sendAsync("socket_tickets_create", true, res.code, res.body, null, false);
    });
    return 1;
  }
}
