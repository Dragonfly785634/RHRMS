package org.rubyhill.rhrms.http;

import org.rubyhill.rhrms.json.Json;
import org.rubyhill.rhrms.security.Role;
import org.rubyhill.rhrms.security.Session;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One incoming request, already parsed: the path parts, the query string, the JSON body and
 * the session it belongs to.
 *
 * The typed readers (requireText, optLong, ...) exist so a handler never repeats the same
 * three lines of checking, and so a missing or misspelt field produces a sentence the front
 * desk can act on instead of a NullPointerException.
 */
public final class Req {
  private final String method;
  private final String path;
  private final Map<String, String> pathParams;
  private final Map<String, String> query;
  private final Map<String, Object> body;
  private final Map<String, String> headers;
  private final Session session;
  private final String clientLabel;

  public Req(String method, String path, Map<String, String> pathParams, Map<String, String> query,
             Map<String, Object> body, Map<String, String> headers, Session session, String clientLabel) {
    this.method = method;
    this.path = path;
    this.pathParams = pathParams;
    this.query = query;
    this.body = body;
    this.headers = headers;
    this.session = session;
    this.clientLabel = clientLabel;
  }

  public String method() { return method; }
  public String path() { return path; }
  public Map<String, Object> body() { return body; }
  public String clientLabel() { return clientLabel; }

  /** The logged-in person. Only null on the public endpoints (health, login, first run). */
  public Session session() { return session; }

  public Session requireSession() {
    if (session == null) throw ApiException.notLoggedIn("Log in first.");
    return session;
  }

  public long userId() { return requireSession().userId(); }
  public Role role() { return requireSession().role(); }

  public String header(String name) {
    return headers.get(name.toLowerCase(Locale.ROOT));
  }

  // ------------------------------------------------------------- path and query

  public String pathParam(String name) {
    String v = pathParams.get(name);
    if (v == null) throw ApiException.badRequest("The address is missing '" + name + "'.");
    return v;
  }

  public long pathId(String name) {
    String raw = pathParam(name);
    try {
      return Long.parseLong(raw);
    } catch (NumberFormatException e) {
      throw ApiException.badRequest("'" + raw + "' is not a record number.");
    }
  }

  public String queryParam(String name, String fallback) {
    String v = query.get(name);
    return (v == null || v.isBlank()) ? fallback : v.trim();
  }

  public Long queryId(String name) {
    String v = queryParam(name, null);
    if (v == null) return null;
    try {
      return Long.parseLong(v);
    } catch (NumberFormatException e) {
      throw ApiException.badRequest("'" + name + "' must be a record number, but is '" + v + "'.");
    }
  }

  public int queryInt(String name, int fallback, int max) {
    String v = queryParam(name, null);
    if (v == null) return fallback;
    try {
      return Math.min(max, Math.max(1, Integer.parseInt(v)));
    } catch (NumberFormatException e) {
      throw ApiException.badRequest("'" + name + "' must be a whole number, but is '" + v + "'.");
    }
  }

  public boolean queryFlag(String name) {
    String v = queryParam(name, null);
    return v != null && (v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("yes"));
  }

  // ------------------------------------------------------------------ body fields

  public boolean has(String field) {
    Object v = body.get(field);
    return v != null && !(v instanceof String s && s.isBlank());
  }

  public String optText(String field) {
    Object v = body.get(field);
    if (v == null) return null;
    String s = v instanceof String str ? str : String.valueOf(v);
    s = s.trim();
    return s.isEmpty() ? null : s;
  }

  public String requireText(String field, String humanName) {
    String v = optText(field);
    if (v == null) throw ApiException.badRequest(humanName + " is required.");
    return v;
  }

  /** For free-text that a rule insists on, such as a rationale of at least 15 characters. */
  public String requireText(String field, String humanName, int minLength) {
    String v = requireText(field, humanName);
    if (v.length() < minLength) {
      throw ApiException.badRequest(humanName + " must be at least " + minLength
          + " characters; you typed " + v.length() + ".");
    }
    return v;
  }

  public Long optLong(String field) {
    Object v = body.get(field);
    if (v == null || (v instanceof String s && s.isBlank())) return null;
    try {
      if (v instanceof Number n) return n.longValue();
      return Long.parseLong(String.valueOf(v).trim());
    } catch (NumberFormatException e) {
      throw ApiException.badRequest("'" + field + "' must be a whole number, but is '" + v + "'.");
    }
  }

  public long requireLong(String field, String humanName) {
    Long v = optLong(field);
    if (v == null) throw ApiException.badRequest(humanName + " is required.");
    return v;
  }

  public Integer optInt(String field) {
    Long v = optLong(field);
    if (v == null) return null;
    if (v > Integer.MAX_VALUE || v < Integer.MIN_VALUE) {
      throw ApiException.badRequest("'" + field + "' is too large.");
    }
    return v.intValue();
  }

  public int requireInt(String field, String humanName) {
    Integer v = optInt(field);
    if (v == null) throw ApiException.badRequest(humanName + " is required.");
    return v;
  }

  public BigDecimal optMoney(String field) {
    Object v = body.get(field);
    if (v == null || (v instanceof String s && s.isBlank())) return null;
    try {
      // Money is BigDecimal everywhere, never double (spec section 13).
      BigDecimal d = v instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(v).trim().replace("$", ""));
      return d.setScale(2, java.math.RoundingMode.HALF_UP);
    } catch (NumberFormatException e) {
      throw ApiException.badRequest("'" + field + "' must be an amount of money, but is '" + v + "'.");
    }
  }

  public BigDecimal requireMoney(String field, String humanName) {
    BigDecimal v = optMoney(field);
    if (v == null) throw ApiException.badRequest(humanName + " is required.");
    return v;
  }

  public BigDecimal optDecimal(String field) {
    Object v = body.get(field);
    if (v == null || (v instanceof String s && s.isBlank())) return null;
    try {
      return v instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(v).trim());
    } catch (NumberFormatException e) {
      throw ApiException.badRequest("'" + field + "' must be a number, but is '" + v + "'.");
    }
  }

  public Boolean optBool(String field) {
    Object v = body.get(field);
    if (v == null || (v instanceof String s && s.isBlank())) return null;
    if (v instanceof Boolean b) return b;
    String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
    return switch (s) {
      case "true", "yes", "y", "1" -> Boolean.TRUE;
      case "false", "no", "n", "0" -> Boolean.FALSE;
      default -> throw ApiException.badRequest("'" + field + "' must be yes or no, but is '" + v + "'.");
    };
  }

  public boolean requireBool(String field, String humanName) {
    Boolean v = optBool(field);
    if (v == null) throw ApiException.badRequest(humanName + " must be answered yes or no.");
    return v;
  }

  public boolean flag(String field) {
    return Boolean.TRUE.equals(optBool(field));
  }

  public LocalDate optDate(String field) {
    String v = optText(field);
    if (v == null) return null;
    try {
      return LocalDate.parse(v);
    } catch (DateTimeParseException e) {
      throw ApiException.badRequest("'" + field + "' must be a date written as YYYY-MM-DD, but is '" + v + "'.");
    }
  }

  public LocalDate requireDate(String field, String humanName) {
    LocalDate v = optDate(field);
    if (v == null) throw ApiException.badRequest(humanName + " is required, written as YYYY-MM-DD.");
    return v;
  }

  /** One of a fixed list, upper-cased, with the accepted values listed in the refusal. */
  public String requireOneOf(String field, String humanName, String... allowed) {
    String v = requireText(field, humanName).toUpperCase(Locale.ROOT).replace(' ', '_');
    for (String a : allowed) {
      if (a.equals(v)) return v;
    }
    throw ApiException.badRequest(humanName + " must be one of: " + String.join(", ", allowed) + ".");
  }

  public String optOneOf(String field, String humanName, String... allowed) {
    if (!has(field)) return null;
    return requireOneOf(field, humanName, allowed);
  }

  @SuppressWarnings("unchecked")
  public List<Object> optList(String field) {
    Object v = body.get(field);
    if (v == null) return List.of();
    if (v instanceof List<?> list) return (List<Object>) list;
    throw ApiException.badRequest("'" + field + "' should be a list.");
  }

  // --------------------------------------------------------- audit and step-up

  /**
   * The "why" stored on every audit row this request writes. Sent as the body field "reason"
   * or the header X-RHRMS-Reason.
   */
  public String reason() {
    String v = optText("reason");
    if (v != null) return v;
    String h = header("X-RHRMS-Reason");
    return (h == null || h.isBlank()) ? null : h.trim();
  }

  public String requireReason() {
    String v = reason();
    if (v == null || v.length() < 3) {
      throw ApiException.badRequest("A reason is required for this change, so the record explains itself later.");
    }
    return v;
  }

  /** Section 6.3 HV-2: the volunteer who actually made the visit, when someone else types it in. */
  public String onBehalfOf() {
    String v = optText("onBehalfOf");
    if (v != null) return v;
    String h = header("X-RHRMS-On-Behalf-Of");
    return (h == null || h.isBlank()) ? null : h.trim();
  }

  /**
   * The password re-typed to confirm a critical action. Read from the header first so it does
   * not have to appear in the JSON body at all; the body field is there so a future web
   * frontend can post a form without setting custom headers.
   */
  public String confirmPassword() {
    String h = header("X-RHRMS-Confirm");
    if (h != null && !h.isBlank()) return h;
    Object v = body.get("confirmPassword");
    return v == null ? null : String.valueOf(v);
  }

  // ------------------------------------------------------------------- parsing

  public static Map<String, String> parseQuery(String rawQuery) {
    Map<String, String> out = new LinkedHashMap<>();
    if (rawQuery == null || rawQuery.isBlank()) return out;
    for (String pair : rawQuery.split("&")) {
      if (pair.isEmpty()) continue;
      int eq = pair.indexOf('=');
      String key = eq < 0 ? pair : pair.substring(0, eq);
      String value = eq < 0 ? "" : pair.substring(eq + 1);
      out.put(decode(key), decode(value));
    }
    return out;
  }

  private static String decode(String s) {
    try {
      return URLDecoder.decode(s, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return s;                            // a stray % is not worth refusing the whole request
    }
  }

  public static Map<String, Object> parseBody(String raw) {
    try {
      return Json.parseObject(raw);
    } catch (Json.JsonException e) {
      throw ApiException.badRequest("The request body is not valid JSON: " + e.getMessage());
    }
  }
}
