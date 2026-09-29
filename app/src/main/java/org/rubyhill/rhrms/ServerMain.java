package org.rubyhill.rhrms;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.rubyhill.rhrms.api.Api;
import org.rubyhill.rhrms.api.Ctx;
import org.rubyhill.rhrms.api.Version;
import org.rubyhill.rhrms.config.AppConfig;
import org.rubyhill.rhrms.config.Db;
import org.rubyhill.rhrms.http.ApiException;
import org.rubyhill.rhrms.http.Req;
import org.rubyhill.rhrms.http.Res;
import org.rubyhill.rhrms.http.Router;
import org.rubyhill.rhrms.http.SqlErrors;
import org.rubyhill.rhrms.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The local RHRMS API server.
 *
 * WHY THERE IS A SERVER AT ALL, ON A SINGLE COMPUTER
 * The rescue has one front-desk PC, but the interviews are clear that more than one person
 * changes data at the same time. So instead of one program holding the database, there is one
 * small server on 127.0.0.1 and any number of terminal windows talking to it over HTTP. Each
 * window logs in separately, so each change is attributed to the person who made it, and two
 * people editing at once meet the locking and version rules in the database rather than each
 * other's half-finished work.
 *
 * It also means the terminal client is replaceable. Everything the client can do, it does
 * through this API; the rules, the permission checks and the password confirmations all live on
 * this side. A web page, a script, or a JavaFX window could take the terminal's place tomorrow
 * without a single rule moving.
 *
 * The listening address is the loopback interface, which nothing on the network can reach.
 * That is what makes plain HTTP acceptable here; see docs/SECURITY.md.
 */
public final class ServerMain {
  private static final Logger log = LoggerFactory.getLogger(ServerMain.class);

  /** A request body larger than this is a mistake or an attack, not an adoption application. */
  private static final int MAX_BODY_BYTES = 1024 * 1024;

  public static void main(String[] args) {
    AppConfig config;
    try {
      config = AppConfig.load();
    } catch (RuntimeException e) {
      System.err.println("RHRMS server cannot start: " + e.getMessage());
      System.exit(2);
      return;
    }

    Db db;
    try {
      db = new Db(config);
    } catch (RuntimeException e) {
      System.err.println("RHRMS server cannot reach the database: " + e.getMessage());
      System.err.println("Check db.* in rhrms.properties, and that PostgreSQL is running.");
      System.exit(3);
      return;
    }

    Ctx ctx = new Ctx(config, db);
    Router router = Api.build(ctx);

    HttpServer http;
    try {
      http = HttpServer.create(new InetSocketAddress(config.apiHost(), config.apiPort()), 64);
    } catch (BindException e) {
      System.err.println("Port " + config.apiPort() + " on " + config.apiHost() + " is already in use.");
      System.err.println("The RHRMS server is probably already running. "
          + "Use scripts/stop_server.sh, or set RHRMS_API_PORT to a different port.");
      db.close();
      System.exit(4);
      return;
    } catch (IOException e) {
      System.err.println("RHRMS server cannot listen on " + config.apiBaseUrl() + ": " + e.getMessage());
      db.close();
      System.exit(4);
      return;
    }

    ThreadPoolExecutor pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(config.apiThreads());
    http.setExecutor(pool);
    http.createContext("/", exchange -> handle(exchange, router, ctx));

    // Drop sessions that have sat idle, so "who is logged in" stays truthful even when nobody
    // is making requests.
    var reaper = Executors.newSingleThreadScheduledExecutor(runnable -> {
      Thread t = new Thread(runnable, "rhrms-session-reaper");
      t.setDaemon(true);
      return t;
    });
    reaper.scheduleWithFixedDelay(() -> {
      try {
        ctx.sessions().sweep(ctx.settings().idleMinutes());
      } catch (RuntimeException e) {
        log.warn("Session sweep failed: {}", e.getMessage());
      }
    }, 1, 1, TimeUnit.MINUTES);

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      log.info("Shutting down.");
      http.stop(1);
      reaper.shutdownNow();
      pool.shutdown();
      db.close();
    }, "rhrms-shutdown"));

    http.start();
    System.out.println("RHRMS API " + Version.NUMBER + " listening on " + config.apiBaseUrl());
    System.out.println("  settings from : " + config.source());
    System.out.println("  database      : " + config.jdbcUrl());
    System.out.println("  roles         : " + config.roleUser(org.rubyhill.rhrms.security.Role.VOLUNTEER)
        + " / " + config.roleUser(org.rubyhill.rhrms.security.Role.STAFF)
        + " / " + config.roleUser(org.rubyhill.rhrms.security.Role.DIRECTOR)
        + " (+ " + config.authUser() + " for logins)");
    try {
      System.out.println("  PostgreSQL    : up");
      db.serverVersion();
    } catch (SQLException e) {
      System.out.println("  PostgreSQL    : NOT ANSWERING yet - " + e.getMessage());
    }
    System.out.println("Start a terminal with: scripts/rhrms.sh        (Ctrl-C here stops the server)");
  }

  // ------------------------------------------------------------------ request

  private static void handle(HttpExchange exchange, Router router, Ctx ctx) {
    long startedAt = System.nanoTime();
    String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
    String path = exchange.getRequestURI().getPath();
    String rawQuery = exchange.getRequestURI().getRawQuery();
    int status = 500;
    String who = "-";

    try {
      Map<String, String> headers = lowerCasedHeaders(exchange.getRequestHeaders());
      String clientLabel = clientLabel(exchange, headers);

      if (path.equals("/") || path.isEmpty()) {
        status = write(exchange, 200, Json.obj()
            .put("service", "RHRMS API")
            .put("version", Version.NUMBER)
            .put("about", "Ruby Hill Rescue Management System, local API. See GET /api for the endpoint list.")
            .put("terminal", "scripts/rhrms.sh"));
        return;
      }

      Router.Match match = router.match(method, path);
      if (match == null) {
        status = writeError(exchange, new ApiException(404, "NO_SUCH_ADDRESS",
            "There is no '" + path + "' in this API. GET /api lists everything it can do."));
        return;
      }

      String body = readBody(exchange);
      requireJsonContentType(headers, body);
      Map<String, Object> parsed = Req.parseBody(body);
      Map<String, String> query = Req.parseQuery(rawQuery);
      String token = bearerToken(headers);

      Res res = router.dispatch(match, method, path, query, parsed, headers, token, clientLabel);
      var session = null == token ? null : ctx.sessions().lookup(token, ctx.settings().idleMinutes()).orElse(null);
      if (session != null) who = session.username() + "/" + session.role();
      status = write(exchange, res.status(), res.body());

    } catch (ApiException e) {
      status = writeError(exchange, e);
    } catch (SQLException e) {
      ApiException translated = SqlErrors.translate(e);
      if (translated.status() >= 500) {
        // Worth a log line with detail, because nobody can act on "the database refused this"
        // without knowing what it actually said.
        log.warn("Database error on {} {}: SQLSTATE={} {}", method, path, e.getSQLState(), e.getMessage());
      }
      status = writeError(exchange, translated);
    } catch (Json.JsonException e) {
      status = writeError(exchange, ApiException.badRequest("The request body is not valid JSON: " + e.getMessage()));
    } catch (IllegalArgumentException e) {
      status = writeError(exchange, ApiException.badRequest(e.getMessage()));
    } catch (Exception e) {
      // The last resort. The person gets a sentence; the details go to the log, never to them.
      log.error("Unhandled failure on {} {}", method, path, e);
      status = writeError(exchange, new ApiException(500, "SERVER_ERROR",
          "Something went wrong inside the server and nothing was saved. "
              + "Tell whoever looks after this computer; the details are in the server's log."));
    } finally {
      long micros = (System.nanoTime() - startedAt) / 1000;
      log.info("{} {} -> {} [{}] {}ms", method, path, status, who, micros / 1000);
      exchange.close();
    }
  }

  private static Map<String, String> lowerCasedHeaders(Headers headers) {
    Map<String, String> out = new LinkedHashMap<>();
    for (Map.Entry<String, List<String>> e : headers.entrySet()) {
      if (e.getValue() != null && !e.getValue().isEmpty()) {
        out.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().get(0));
      }
    }
    return out;
  }

  private static String bearerToken(Map<String, String> headers) {
    String auth = headers.get("authorization");
    if (auth == null) return null;
    String trimmed = auth.trim();
    if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) return trimmed.substring(7).trim();
    return trimmed.isEmpty() ? null : trimmed;      // accept a bare token too, for curl by hand
  }

  /** Body-bearing API requests must declare JSON so alternate clients fail predictably. */
  private static void requireJsonContentType(Map<String, String> headers, String body) {
    if (body.isBlank()) return;
    String contentType = headers.get("content-type");
    if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
      throw ApiException.badRequest("Requests with a body must use Content-Type: application/json.");
    }
  }

  /** Which terminal this was, for the login trail: "rhrms-term/127.0.0.1". */
  private static String clientLabel(HttpExchange exchange, Map<String, String> headers) {
    String name = headers.getOrDefault("x-rhrms-client", "unknown");
    String address = exchange.getRemoteAddress() == null ? "?"
        : exchange.getRemoteAddress().getAddress().getHostAddress();
    String label = name.replaceAll("[^A-Za-z0-9._/-]", "") + "/" + address;
    return label.length() <= 80 ? label : label.substring(0, 80);
  }

  private static String readBody(HttpExchange exchange) throws IOException {
    try (InputStream in = exchange.getRequestBody()) {
      byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
      if (bytes.length > MAX_BODY_BYTES) {
        throw ApiException.badRequest("That request is too big for this API (limit 1 MB).");
      }
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }

  // ----------------------------------------------------------------- response

  private static int write(HttpExchange exchange, int status, Object body) {
    byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
    try {
      Headers out = exchange.getResponseHeaders();
      out.set("Content-Type", "application/json; charset=utf-8");
      out.set("Cache-Control", "no-store");
      // No CORS headers on purpose: a page in a browser must not be able to drive the front
      // desk's database. A future web frontend is served from this same origin instead.
      out.set("X-Content-Type-Options", "nosniff");
      exchange.sendResponseHeaders(status, bytes.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(bytes);
      }
    } catch (IOException e) {
      // The client hung up mid-reply (closed the terminal). Nothing to do and nothing lost:
      // the transaction has already committed or rolled back.
      log.debug("Client went away before the reply was sent: {}", e.getMessage());
    }
    return status;
  }

  private static int writeError(HttpExchange exchange, ApiException e) {
    Json.Obj error = Json.obj()
        .put("code", e.code())
        .put("message", e.getMessage() == null ? "Something went wrong." : e.getMessage());
    error.putIf("rule", e.hint());
    return write(exchange, e.status(), Json.obj().put("ok", false).put("error", error));
  }
}
