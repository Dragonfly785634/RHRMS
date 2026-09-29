package org.rubyhill.rhrms.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.rubyhill.rhrms.security.Role;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.Map;

/**
 * Database access for the API server.
 *
 * There is one small connection pool per PostgreSQL login role, plus one for the login path.
 * A request from a VOLUNTEER session runs on a connection owned by rhrms_volunteer, which
 * PostgreSQL itself will not let write a decision row. So the permission rules are checked
 * twice, by two different pieces of software: the API (clear messages, section 7 table) and
 * the database (the last word). A bug in the API cannot turn into a volunteer approving an
 * adoption. See docs/DECISIONS.md (D-03).
 */
public final class Db implements AutoCloseable {
  private final AppConfig config;
  private final HikariDataSource authPool;
  private final Map<Role, HikariDataSource> rolePools = new EnumMap<>(Role.class);

  public Db(AppConfig config) {
    this.config = config;
    // The login path takes two short transactions per attempt (decide, then record), and BCrypt
    // makes each one slow on purpose, so give it the same room as the others.
    this.authPool = pool("rhrms-auth", config.authUser(), config.authPassword(),
        Math.max(2, config.poolSize()));
    try {
      for (Role role : Role.values()) {
        rolePools.put(role, pool("rhrms-" + role.name().toLowerCase(java.util.Locale.ROOT),
            config.roleUser(role), config.rolePassword(role), config.poolSize()));
      }
    } catch (RuntimeException e) {
      close();                            // do not leak the pools we already opened
      throw e;
    }
  }

  private HikariDataSource pool(String name, String user, String password, int size) {
    HikariConfig hc = new HikariConfig();
    hc.setPoolName(name);
    hc.setJdbcUrl(config.jdbcUrl());
    hc.setUsername(user);
    hc.setPassword(password);
    hc.setMaximumPoolSize(size);
    hc.setMinimumIdle(0);                 // a quiet front desk should not hold connections open
    hc.setAutoCommit(false);              // every unit of work is an explicit transaction
    hc.setConnectionTimeout(10_000);
    hc.setIdleTimeout(60_000);
    hc.setInitializationFailTimeout(-1);  // start even if PostgreSQL is not up yet; /api/health reports it
    hc.addDataSourceProperty("ApplicationName", "RHRMS " + name);
    return new HikariDataSource(hc);
  }

  /** What a unit of work does. It must not commit or roll back; tx() owns that. */
  public interface Work<T> {
    T run(Connection c) throws SQLException;
  }

  /**
   * Runs one unit of work as one database transaction, with the audit context set so the
   * triggers in 03_audit.sql can record who changed what and why.
   *
   * @param role        which PostgreSQL login role to borrow a connection from
   * @param userId      staff_user.id of the person acting; the audit trigger refuses a change without it
   * @param reason      free text stored on every audit row this transaction writes, or null
   * @param onBehalfOf  the name of a volunteer with no login, when someone types a visit in for them
   */
  public <T> T tx(Role role, Long userId, String reason, String onBehalfOf, Work<T> body) throws SQLException {
    SQLException last = null;
    for (int attempt = 1; attempt <= 3; attempt++) {
      try (Connection c = pooled(role)) {
        try {
          setAuditContext(c, userId, reason, onBehalfOf);
          T result = body.run(c);
          c.commit();
          return result;
        } catch (SQLException | RuntimeException e) {
          rollbackQuietly(c);
          if (e instanceof SQLException sql && isRetryable(sql) && attempt < 3) { last = sql; continue; }
          throw e;
        }
      }
    }
    throw last;                           // unreachable: the loop either returns or throws
  }

  /** A read-only unit of work. SELECTs fire no audit triggers, so no user id is needed. */
  public <T> T read(Role role, Work<T> body) throws SQLException {
    try (Connection c = pooled(role)) {
      try {
        T result = body.run(c);
        c.commit();                       // close the read transaction so no snapshot is held
        return result;
      } catch (SQLException | RuntimeException e) {
        rollbackQuietly(c);
        throw e;
      }
    }
  }

  /** A unit of work on the login pool: reading a password hash, writing an auth_event row. */
  public <T> T auth(Work<T> body) throws SQLException {
    return authInternal(null, null, body);
  }

  /**
   * A unit of work on the login pool that also changes staff_user, so the audit context has
   * to be set: changing your own password writes a staff_user row, and the audit trigger
   * refuses any change that does not say who made it.
   */
  public <T> T authTx(long userId, String reason, Work<T> body) throws SQLException {
    return authInternal(userId, reason, body);
  }

  private <T> T authInternal(Long userId, String reason, Work<T> body) throws SQLException {
    try (Connection c = authPool.getConnection()) {
      try {
        if (userId != null) setAuditContext(c, userId, reason, null);
        T result = body.run(c);
        c.commit();
        return result;
      } catch (SQLException | RuntimeException e) {
        rollbackQuietly(c);
        throw e;
      }
    }
  }

  private Connection pooled(Role role) throws SQLException {
    HikariDataSource ds = rolePools.get(role);
    if (ds == null) throw new IllegalStateException("No connection pool for role " + role);
    return ds.getConnection();
  }

  private static void setAuditContext(Connection c, Long userId, String reason, String onBehalfOf)
      throws SQLException {
    // set_config(..., is_local => true) is the parameterised form of SET LOCAL, so the values
    // are bound rather than pasted into SQL, and they last exactly one transaction.
    try (PreparedStatement ps = c.prepareStatement(
        "SELECT set_config('rhrms.user_id', ?, true), set_config('rhrms.reason', ?, true),"
            + " set_config('rhrms.on_behalf_of', ?, true)")) {
      ps.setString(1, userId == null ? "" : userId.toString());
      ps.setString(2, reason == null ? "" : reason);
      ps.setString(3, onBehalfOf == null ? "" : onBehalfOf);
      ps.execute();
    }
  }

  /** 40001 could not serialise, 40P01 deadlock: nobody's fault, worth one more try. */
  private static boolean isRetryable(SQLException e) {
    String state = e.getSQLState();
    return "40001".equals(state) || "40P01".equals(state);
  }

  private static void rollbackQuietly(Connection c) {
    try {
      if (!c.isClosed()) c.rollback();
    } catch (SQLException ignored) {
      // The connection is already broken; Hikari will drop it when it goes back to the pool.
    }
  }

  /** Cheap check for /api/health: is PostgreSQL answering? */
  public String serverVersion() throws SQLException {
    return read(Role.VOLUNTEER, c -> {
      try (var st = c.createStatement(); var rs = st.executeQuery("SELECT version()")) {
        return rs.next() ? rs.getString(1) : "unknown";
      }
    });
  }

  @Override public void close() {
    for (HikariDataSource ds : rolePools.values()) {
      if (ds != null) ds.close();
    }
    if (authPool != null) authPool.close();
  }
}
