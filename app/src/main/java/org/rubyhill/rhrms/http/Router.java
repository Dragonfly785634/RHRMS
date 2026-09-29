package org.rubyhill.rhrms.http;

import org.rubyhill.rhrms.security.Perm;
import org.rubyhill.rhrms.security.Session;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Matches a method and path to a handler, and enforces the three gates every request passes
 * before any SQL runs:
 *
 *   1. is there a live session?                         (unless the route is public)
 *   2. does this role hold the permission?              (section 7)
 *   3. for a critical action, was the password re-typed and correct?
 *
 * Doing this here rather than inside each handler means a new endpoint cannot forget a check:
 * the route declaration has to name a permission, and the compiler will not let it be left out.
 */
public final class Router {

  public interface Handler {
    Res handle(Req req) throws SQLException;
  }

  /** The checks the router needs from the rest of the application. */
  public interface Guard {
    /** Resolves the bearer token, or throws 401. */
    Session authenticate(String token, String clientLabel) throws SQLException;

    /** Throws 403 when the role does not hold the permission. */
    void requirePermission(Session session, Perm perm) throws SQLException;

    /**
     * The UNIX-style step-up check: re-verify the logged-in person's own password before a
     * critical action. Throws 403 ACTION_DENIED when it is missing or wrong, and records the
     * attempt either way.
     */
    void requireConfirmation(Session session, String password, String action, String clientLabel)
        throws SQLException;
  }

  public record Route(String method, String template, Pattern pattern, List<String> params, Perm perm,
                      boolean publicRoute, String confirmAction, String description, Handler handler) {}

  private final List<Route> routes = new ArrayList<>();
  private final Guard guard;

  public Router(Guard guard) { this.guard = guard; }

  // -------------------------------------------------------------- registration

  /** A route nobody has to be logged in for: health, login, first-run setup. */
  public Router publicRoute(String method, String path, String description, Handler handler) {
    return add(method, path, null, true, null, description, handler);
  }

  /** A route that needs a session and the given permission. */
  public Router route(String method, String path, Perm perm, String description, Handler handler) {
    return add(method, path, perm, false, null, description, handler);
  }

  /**
   * A route that needs a session, the permission, AND the password re-typed.
   * confirmAction is the sentence shown in the audit trail and in the refusal, for example
   * "record an adoption".
   */
  public Router critical(String method, String path, Perm perm, String confirmAction,
                         String description, Handler handler) {
    return add(method, path, perm, false, confirmAction, description, handler);
  }

  private Router add(String method, String path, Perm perm, boolean publicRoute,
                     String confirmAction, String description, Handler handler) {
    List<String> params = new ArrayList<>();
    StringBuilder regex = new StringBuilder("^");
    for (String segment : path.split("/", -1)) {
      if (segment.isEmpty()) continue;
      regex.append('/');
      if (segment.startsWith("{") && segment.endsWith("}")) {
        params.add(segment.substring(1, segment.length() - 1));
        regex.append("([^/]+)");
      } else {
        regex.append(Pattern.quote(segment));
      }
    }
    regex.append("/?$");
    routes.add(new Route(method.toUpperCase(Locale.ROOT), path, Pattern.compile(regex.toString()),
        List.copyOf(params), perm, publicRoute, confirmAction, description, handler));
    return this;
  }

  // ------------------------------------------------------------------ dispatch

  public record Match(Route route, Map<String, String> pathParams) {}

  /**
   * Finds the route for a request. Returns null when nothing matches the path at all, and
   * throws 405 when the path exists but not for this method, so a client mistake is obvious.
   */
  public Match match(String method, String path) {
    boolean pathExists = false;
    for (Route r : routes) {
      Matcher m = r.pattern().matcher(path);
      if (!m.matches()) continue;
      pathExists = true;
      if (!r.method().equalsIgnoreCase(method)) continue;
      Map<String, String> params = new LinkedHashMap<>();
      for (int i = 0; i < r.params().size(); i++) {
        params.put(r.params().get(i), java.net.URLDecoder.decode(m.group(i + 1),
            java.nio.charset.StandardCharsets.UTF_8));
      }
      return new Match(r, params);
    }
    if (pathExists) {
      throw new ApiException(405, "WRONG_METHOD",
          "This address does not accept " + method + " requests.");
    }
    return null;
  }

  /** Runs the three gates, then the handler. */
  public Res dispatch(Match match, String method, String path, Map<String, String> query,
                      Map<String, Object> body, Map<String, String> headers,
                      String bearerToken, String clientLabel) throws SQLException {
    Route route = match.route();
    Session session = null;
    if (!route.publicRoute()) {
      session = guard.authenticate(bearerToken, clientLabel);
      guard.requirePermission(session, route.perm());
    }

    Req req = new Req(method, path, match.pathParams(), query, body, headers, session, clientLabel);

    if (route.confirmAction() != null) {
      guard.requireConfirmation(session, req.confirmPassword(), route.confirmAction(), clientLabel);
    }
    return route.handler().handle(req);
  }

  /** The route list, so GET /api serves its own documentation. */
  public List<Map<String, Object>> describe() {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Route r : routes) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("method", r.method());
      m.put("path", r.template());
      m.put("permission", r.perm() == null ? "public" : r.perm().name());
      m.put("needsPasswordConfirmation", r.confirmAction() != null);
      m.put("what", r.description());
      out.add(m);
    }
    return out;
  }
}
