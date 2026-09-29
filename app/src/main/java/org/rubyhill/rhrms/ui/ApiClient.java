package org.rubyhill.rhrms.ui;

import org.rubyhill.rhrms.json.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The terminal's only way to reach the data: HTTP and JSON against the local API.
 *
 * This class knows nothing about the rescue. No rule, no permission check and no password
 * comparison happens on this side of the wire - that is the whole point of the API sitting in
 * between. Replace everything in the ui package with a web page or a JavaFX window and the rules
 * do not move.
 *
 * Two small habits make the terminal honest about failure:
 *   - a refusal from the server is raised as Failure carrying the server's own sentence, which
 *     the screens print as-is rather than inventing their own wording;
 *   - a server that is not answering says so in words, instead of a stack trace.
 */
public final class ApiClient {
  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .followRedirects(HttpClient.Redirect.NEVER)
      .build();

  private final String baseUrl;
  private final String clientName;
  private String token;

  public ApiClient(String baseUrl, String clientName) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.clientName = clientName;
  }

  public String baseUrl() { return baseUrl; }
  public void setToken(String token) { this.token = token; }
  public void clearToken() { this.token = null; }
  public boolean hasToken() { return token != null; }

  /** A refusal or failure the terminal should show to the person, in the server's own words. */
  public static final class Failure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int status;
    private final String code;
    private final String rule;
    private transient Map<String, Object> detail = Map.of();

    Failure(int status, String code, String message, String rule) {
      super(message);
      this.status = status;
      this.code = code;
      this.rule = rule;
    }

    public int status() { return status; }
    public String code() { return code; }
    /** The rule id from the database trigger, such as "RULE AP-1", when there is one. */
    public String rule() { return rule; }

    /**
     * The rest of the refused reply. The intake screen uses it: a 409 POSSIBLE_DUPLICATE also
     * carries the list of animals it thinks this might be, and that list is the whole point.
     */
    public Map<String, Object> detail() { return detail; }

    /**
     * A refusal the terminal worked out for itself, before any request was sent: a name that
     * matches no animal, for instance. Status 0 because no server was involved.
     */
    static Failure local(String code, String message) {
      return new Failure(0, code, message, null);
    }

    /**
     * Set once a screen has printed this failure for itself. A screen sometimes needs to report
     * a refusal in its own context - showing the half-filled form alongside it - and then hand
     * the exception on so the shell can act on it. Without this the person reads the same
     * paragraph twice.
     */
    private transient boolean reported;

    public void markReported() { this.reported = true; }
    public boolean wasReported() { return reported; }

    /** True when the session has gone and the person has to log in again. */
    public boolean needsLogin() { return status == 401; }

    /** True when a critical action was refused for want of a correct password. */
    public boolean actionDenied() { return "ACTION_DENIED".equals(code); }
  }

  // ------------------------------------------------------------------ requests

  public Map<String, Object> get(String path) {
    return send("GET", path, null, null);
  }

  public Map<String, Object> post(String path, Map<String, Object> body) {
    return send("POST", path, body, null);
  }

  /** A POST for a critical action: the password travels in a header, not in the body. */
  public Map<String, Object> post(String path, Map<String, Object> body, String confirmPassword) {
    return send("POST", path, body, confirmPassword);
  }

  public Map<String, Object> patch(String path, Map<String, Object> body) {
    return send("PATCH", path, body, null);
  }

  public Map<String, Object> patch(String path, Map<String, Object> body, String confirmPassword) {
    return send("PATCH", path, body, confirmPassword);
  }

  public Map<String, Object> put(String path, Map<String, Object> body, String confirmPassword) {
    return send("PUT", path, body, confirmPassword);
  }

  private Map<String, Object> send(String method, String path, Map<String, Object> body,
                                   String confirmPassword) {
    String json = body == null ? "" : Json.write(body);
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create(baseUrl + path))
        .timeout(Duration.ofSeconds(30))
        .header("Accept", "application/json")
        .header("X-RHRMS-Client", clientName);
    if (token != null) builder.header("Authorization", "Bearer " + token);
    if (confirmPassword != null) builder.header("X-RHRMS-Confirm", confirmPassword);
    if (body == null) {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      builder.header("Content-Type", "application/json")
             .method(method, HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
    }

    HttpResponse<String> response;
    try {
      response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (java.net.ConnectException e) {
      throw new Failure(0, "NO_SERVER", "The RHRMS server is not answering on " + baseUrl
          + ". No request was sent. Ask whoever set up this computer to start it "
          + "(scripts/start_server.sh).", null);
    } catch (java.io.IOException e) {
      throw new Failure(0, "NETWORK", "The connection to the server broke: " + e.getMessage()
          + ". The result could not be confirmed; check the record before retrying.", null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Failure(0, "INTERRUPTED", "That was interrupted. The result could not be confirmed; "
          + "check the record before retrying.", null);
    }

    Map<String, Object> parsed;
    try {
      parsed = Json.parseObject(response.body());
    } catch (RuntimeException e) {
      throw new Failure(response.statusCode(), "BAD_REPLY",
          "The server sent something this terminal could not read. The result could not be confirmed; "
              + "check the record before retrying.", null);
    }

    if (response.statusCode() >= 400) {
      Object error = parsed.get("error");
      String code = "ERROR";
      String message = "The server refused that (" + response.statusCode() + ").";
      String rule = null;
      if (error instanceof Map<?, ?> m) {
        if (m.get("code") != null) code = String.valueOf(m.get("code"));
        if (m.get("message") != null) message = String.valueOf(m.get("message"));
        if (m.get("rule") != null) rule = String.valueOf(m.get("rule"));
      }
      Failure failure = new Failure(response.statusCode(), code, message, rule);
      failure.detail = parsed;          // keep the rest of the reply; see Failure.detail()
      throw failure;
    }
    return parsed;
  }

  // --------------------------------------------------- reading what came back
  // The API returns plain JSON objects. These readers keep the screens free of casts.

  @SuppressWarnings("unchecked")
  public static Map<String, Object> object(Object value) {
    return value instanceof Map ? (Map<String, Object>) value : Map.of();
  }

  @SuppressWarnings("unchecked")
  public static List<Map<String, Object>> list(Object value) {
    if (!(value instanceof List<?> raw)) return List.of();
    List<Map<String, Object>> out = new ArrayList<>(raw.size());
    for (Object item : raw) {
      if (item instanceof Map) out.add((Map<String, Object>) item);
    }
    return out;
  }

  public static List<String> strings(Object value) {
    if (!(value instanceof List<?> raw)) return List.of();
    List<String> out = new ArrayList<>(raw.size());
    for (Object item : raw) out.add(String.valueOf(item));
    return out;
  }

  public static String str(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  public static Long id(Object value) {
    if (value instanceof Number n) return n.longValue();
    if (value == null) return null;
    try {
      return Long.parseLong(String.valueOf(value).trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  public static int intOf(Object value, int fallback) {
    Long v = id(value);
    return v == null ? fallback : v.intValue();
  }

  public static boolean flag(Object value) {
    return Boolean.TRUE.equals(value);
  }

  /** A body builder that leaves out anything not set, so the server sees only real answers. */
  public static final class Body {
    private final Map<String, Object> map = new LinkedHashMap<>();

    public Body set(String key, Object value) {
      if (value != null && !(value instanceof String s && s.isBlank())) map.put(key, value);
      return this;
    }

    /** Sets the key even when the value is null, for "clear this field". */
    public Body setEvenIfNull(String key, Object value) {
      map.put(key, value);
      return this;
    }

    public Map<String, Object> map() { return map; }
  }

  public static Body body() { return new Body(); }
}
