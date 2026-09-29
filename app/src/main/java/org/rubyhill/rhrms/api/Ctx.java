package org.rubyhill.rhrms.api;

import org.rubyhill.rhrms.config.AppConfig;
import org.rubyhill.rhrms.config.Db;
import org.rubyhill.rhrms.config.Settings;
import org.rubyhill.rhrms.http.ApiException;
import org.rubyhill.rhrms.http.Req;
import org.rubyhill.rhrms.http.Router;
import org.rubyhill.rhrms.json.Json;
import org.rubyhill.rhrms.security.AuthService;
import org.rubyhill.rhrms.security.Perm;
import org.rubyhill.rhrms.security.Permissions;
import org.rubyhill.rhrms.security.Role;
import org.rubyhill.rhrms.security.Session;
import org.rubyhill.rhrms.security.Sessions;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * The pieces every handler needs, in one object: the settings file, the database pools, the
 * live sessions, the setting table and the login service.
 *
 * It is also the Router's Guard, which is why the three security checks live here rather than
 * scattered through the handlers.
 */
public final class Ctx implements Router.Guard {
  private final AppConfig config;
  private final Db db;
  private final Settings settings;
  private final Sessions sessions;
  private final AuthService auth;

  public Ctx(AppConfig config, Db db) {
    this.config = config;
    this.db = db;
    this.settings = new Settings(db);
    this.sessions = new Sessions();
    this.auth = new AuthService(db, settings, sessions);
  }

  public AppConfig config()   { return config; }
  public Db db()              { return db; }
  public Settings settings()  { return settings; }
  public Sessions sessions()  { return sessions; }
  public AuthService auth()   { return auth; }

  // ------------------------------------------------------------- Router.Guard

  @Override public Session authenticate(String token, String clientLabel) throws SQLException {
    return auth.resolve(token, clientLabel);
  }

  @Override public void requirePermission(Session session, Perm perm) {
    if (perm == null) return;                     // a session is enough for this route
    Permissions.require(session.role(), perm);
  }

  @Override public void requireConfirmation(Session session, String password, String action,
                                            String clientLabel) throws SQLException {
    auth.confirm(session, password, action, clientLabel);
  }

  // ------------------------------------------------- small database helpers

  /** Read-only work on the connection pool that matches the logged-in person's role. */
  public <T> T read(Req req, Db.Work<T> body) throws SQLException {
    return db.read(req.role(), body);
  }

  /** Changing work, in one transaction, with the audit context set from the request. */
  public <T> T write(Req req, Db.Work<T> body) throws SQLException {
    return db.tx(req.role(), req.userId(), req.reason(), req.onBehalfOf(), body);
  }

  /** Changing work where the reason is not optional, so the record explains itself later. */
  public <T> T writeWithReason(Req req, Db.Work<T> body) throws SQLException {
    return db.tx(req.role(), req.userId(), req.requireReason(), req.onBehalfOf(), body);
  }

  /** Read-only work for a caller that has no session yet (health, first-run check). */
  public <T> T readAnonymous(Db.Work<T> body) throws SQLException {
    return db.read(Role.VOLUNTEER, body);
  }

  // --------------------------------------------------------- query shortcuts

  /** Runs a query with positional parameters and returns every row as a JSON-ready map. */
  public static List<Map<String, Object>> query(Connection c, String sql, Object... params)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      bind(ps, params);
      try (ResultSet rs = ps.executeQuery()) {
        return Json.rows(rs);
      }
    }
  }

  /** The first row, or null. */
  public static Map<String, Object> queryOne(Connection c, String sql, Object... params)
      throws SQLException {
    List<Map<String, Object>> rows = query(c, sql, params);
    return rows.isEmpty() ? null : rows.get(0);
  }

  /** The first row, or a 404 with the given message. */
  public static Map<String, Object> queryOneOr404(Connection c, String notFound, String sql,
                                                  Object... params) throws SQLException {
    Map<String, Object> row = queryOne(c, sql, params);
    if (row == null) throw ApiException.notFound(notFound);
    return row;
  }

  /** A single scalar value from the first row, or null. */
  public static Object scalar(Connection c, String sql, Object... params) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      bind(ps, params);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getObject(1) : null;
      }
    }
  }

  public static Long scalarLong(Connection c, String sql, Object... params) throws SQLException {
    Object v = scalar(c, sql, params);
    return v == null ? null : ((Number) v).longValue();
  }

  /**
   * Runs a statement for its effect, whether or not it hands anything back.
   *
   * This exists because update() below uses executeUpdate(), and JDBC throws "A result was
   * returned when none was expected" if that statement was a SELECT. The one place that
   * matters is SELECT set_config(...), the parameterised form of SET LOCAL - it is a command
   * in every sense that counts but a query as far as the driver is concerned. Calling
   * update() with it fails at run time, not compile time, and the first-run screen was
   * broken that way until somebody installed RHRMS on an empty database.
   */
  public static void run(Connection c, String sql, Object... params) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      bind(ps, params);
      ps.execute();
    }
  }

  /** An INSERT or UPDATE; returns how many rows it touched. Not for a SELECT - see run(). */
  public static int update(Connection c, String sql, Object... params) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      bind(ps, params);
      return ps.executeUpdate();
    }
  }

  /** An INSERT ... RETURNING id. */
  public static long insertReturningId(Connection c, String sql, Object... params) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      bind(ps, params);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) throw new SQLException("The insert returned no id: " + sql);
        return rs.getLong(1);
      }
    }
  }

  /**
   * Binds parameters. Every statement in this application is parameterised (spec section 13):
   * no value the user typed is ever pasted into SQL text.
   */
  private static void bind(PreparedStatement ps, Object... params) throws SQLException {
    for (int i = 0; i < params.length; i++) {
      Object p = params[i];
      int n = i + 1;
      if (p == null) {
        ps.setObject(n, null);
      } else if (p instanceof java.time.LocalDate d) {
        ps.setObject(n, d);
      } else if (p instanceof Enum<?> e) {
        ps.setString(n, e.name());
      } else {
        ps.setObject(n, p);
      }
    }
  }
}
