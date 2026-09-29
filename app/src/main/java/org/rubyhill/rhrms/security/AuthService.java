package org.rubyhill.rhrms.security;

import org.rubyhill.rhrms.config.Db;
import org.rubyhill.rhrms.config.Settings;
import org.rubyhill.rhrms.http.ApiException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * Everything to do with proving who somebody is: logging in, logging out, re-typing a password
 * to confirm a critical action, and changing a password.
 *
 * Four habits worth pointing out, because they are all easy to get wrong:
 *
 *  - A wrong username and a wrong password give the SAME answer, and both take about the same
 *    time (a throwaway BCrypt check runs when the username does not exist). Otherwise anybody
 *    could find out which usernames are real just by watching which reply came back faster.
 *
 *  - Nothing here ever logs, returns or stores a plaintext password, and auth_event has no
 *    column that could hold one.
 *
 *  - Every attempt is written to auth_event in its OWN transaction, which commits before the
 *    refusal is thrown. Writing the row inside the transaction that then fails would roll the
 *    row back with it, and a login trail that quietly loses every failed attempt is worse than
 *    none at all: it would look complete. So each of these methods decides, then records, then
 *    throws, in that order.
 *
 *  - A wrong password on a step-up confirmation does not count towards the lockout. Otherwise
 *    anybody who could reach the port could lock Diane out of the front desk all afternoon.
 */
public final class AuthService {
  /** A hash of a throwaway value, used to spend the same time on a username that does not exist. */
  private static final String DUMMY_HASH =
      "$2a$12$C1qjOwPxTOM.NbWF/DvUKORuF2bjtSw3dEjSA7hMPHkS/2iRZlLlq";

  private final Db db;
  private final Settings settings;
  private final Sessions sessions;

  public AuthService(Db db, Settings settings, Sessions sessions) {
    this.db = db;
    this.settings = settings;
    this.sessions = sessions;
  }

  public Sessions sessions() { return sessions; }

  /** One row of staff_user, as much of it as rhrms_auth is allowed to see. */
  private record Account(long id, String username, String displayName, Role role,
                         String passwordHash, boolean active, int version) {}

  /** What the read phase worked out, before anything is written or thrown. */
  private record Verdict(String event, String username, Long userId, String detail,
                         Account account, long minutesLeft, int failures) {}

  // --------------------------------------------------------------------- login

  public Session login(String username, String password, String client) throws SQLException {
    if (username == null || username.isBlank()) throw ApiException.badRequest("Enter your username.");
    if (password == null || password.isEmpty()) throw ApiException.badRequest("Enter your password.");
    String typed = username.trim();

    // 1. Decide. This transaction only reads, so there is nothing to lose if it rolls back.
    Verdict verdict = db.auth(c -> {
      Lockout lockout = lockoutFor(c, typed);
      if (lockout.blocked()) {
        return new Verdict("LOGIN_BLOCKED", typed, lockout.userId(),
            lockout.failures() + " wrong passwords in a row", null,
            lockout.minutesLeft(), lockout.failures());
      }
      Account account = findByUsername(c, typed);
      if (account == null) {
        Passwords.matches(password, DUMMY_HASH);      // spend the same time as a real check
        return new Verdict("LOGIN_FAILED", typed, null, "no such username", null, 0, 0);
      }
      if (!account.active()) {
        Passwords.matches(password, account.passwordHash());
        return new Verdict("LOGIN_INACTIVE", account.username(), account.id(),
            "account is not active", account, 0, 0);
      }
      if (!Passwords.matches(password, account.passwordHash())) {
        return new Verdict("LOGIN_FAILED", account.username(), account.id(), "wrong password",
            account, 0, 0);
      }
      return new Verdict("LOGIN_OK", account.username(), account.id(), null, account, 0, 0);
    });

    // 2. Record it, in a transaction of its own, so the trail keeps the failure as well as the
    //    success. Section 7: "Every login is logged."
    recordStandalone(verdict.event(), verdict.username(), verdict.userId(), null, client,
        verdict.detail());

    // 3. Act on it.
    return switch (verdict.event()) {
      case "LOGIN_OK" -> sessions.open(verdict.account().id(), verdict.account().username(),
          verdict.account().displayName(), verdict.account().role(), client);
      case "LOGIN_BLOCKED" -> throw new ApiException(429, "TOO_MANY_ATTEMPTS",
          "Too many wrong passwords for '" + typed + "'. Wait about " + verdict.minutesLeft()
              + " minute" + (verdict.minutesLeft() == 1 ? "" : "s")
              + " and try again, or ask the director to reset your password.");
      case "LOGIN_INACTIVE" -> throw ApiException.notAllowed("The account '" + typed
          + "' has been turned off. Ask the director to switch it back on.");
      default -> throw ApiException.notLoggedIn("Wrong username or password.");
    };
  }

  public void logout(Session session, String client) throws SQLException {
    if (session == null) return;
    sessions.close(session.token());
    recordStandalone("LOGOUT", session.username(), session.userId(), null, client, null);
  }

  /**
   * Resolves a bearer token to a live session. Deliberately does not say WHY a token stopped
   * working: idle, a restarted server and a changed account look identical from here, and
   * picking one of them would be a small lie.
   */
  public Session resolve(String token, String client) throws SQLException {
    int idle = settings.idleMinutes();
    return sessions.lookup(token, idle).orElseThrow(() ->
        ApiException.notLoggedIn("This session is no longer valid, so nothing was done. "
            + "A session ends after " + idle + " minutes of no activity, when the server is "
            + "restarted, or when the director changes your account. Log in again."));
  }

  // ----------------------------------------------------- step-up confirmation

  /**
   * The UNIX-style check: before a critical action, the person at the keyboard re-types their own
   * password. It proves that whoever is acting right now is the person the session belongs to,
   * and not somebody who sat down at a terminal that was left logged in.
   *
   * Refusal is ACTION_DENIED and the action never starts, so nothing is half-done.
   */
  public void confirm(Session session, String password, String action, String client)
      throws SQLException {
    if (session == null) throw ApiException.notLoggedIn("Log in first.");

    if (password == null || password.isEmpty()) {
      recordStandalone("CONFIRM_FAILED", session.username(), session.userId(), action, client,
          "no password given");
      throw ApiException.actionDenied("This action needs your password. Nothing was changed.");
    }

    Verdict verdict = db.auth(c -> {
      Account account = findById(c, session.userId());
      if (account == null) {
        return new Verdict("CONFIRM_FAILED", session.username(), session.userId(),
            "account missing", null, 0, 0);
      }
      if (!account.active()) {
        return new Verdict("CONFIRM_FAILED", session.username(), session.userId(),
            "account is not active", account, 0, 0);
      }
      boolean ok = Passwords.matches(password, account.passwordHash());
      return new Verdict(ok ? "CONFIRM_OK" : "CONFIRM_FAILED", session.username(),
          session.userId(), ok ? null : "wrong password", account, 0, 0);
    });

    recordStandalone(verdict.event(), verdict.username(), verdict.userId(), action, client,
        verdict.detail());

    if (verdict.event().equals("CONFIRM_FAILED")) {
      if ("account is not active".equals(verdict.detail())) {
        throw ApiException.notAllowed("Your account is no longer active. Nothing was changed.");
      }
      throw ApiException.actionDenied("Action denied: that password did not match. "
          + "Nothing was changed.");
    }
  }

  // ------------------------------------------------------------------ passwords

  /** Somebody changing their own password. Needs the current one, exactly like passwd(1). */
  public void changeOwnPassword(Session session, String currentPassword, String newPassword,
                                String client) throws SQLException {
    Passwords.requireAcceptable(newPassword);
    if (newPassword.equals(currentPassword)) {
      throw ApiException.badRequest("The new password is the same as the old one.");
    }

    Account account = db.auth(c -> findById(c, session.userId()));
    if (account == null || !account.active()) {
      throw ApiException.notAllowed("Your account is no longer active.");
    }
    if (!Passwords.matches(currentPassword, account.passwordHash())) {
      recordStandalone("CONFIRM_FAILED", session.username(), session.userId(),
          "change your own password", client, "wrong current password");
      throw ApiException.actionDenied("Action denied: your current password did not match. "
          + "The password was not changed.");
    }

    String hash = Passwords.hash(newPassword);
    db.authTx(session.userId(), "Changed own password", c -> {
      writeHash(c, account.id(), account.version(), hash);
      // Same transaction as the change itself: if one is rolled back so is the other, which is
      // exactly right here, because the event and the change describe the same single fact.
      record(c, "PASSWORD_CHANGED", session.username(), session.userId(), null, client,
          "by the account holder");
      return null;
    });

    // Anyone else logged in as this account is signed out; the person who made the change stays.
    for (Session other : sessions.all()) {
      if (other.userId() == session.userId() && !other.token().equals(session.token())) {
        sessions.close(other.token());
      }
    }
  }

  /** The director resetting somebody else's password (section 7). */
  public void resetPassword(Session director, long targetUserId, String newPassword, String client)
      throws SQLException {
    Passwords.requireAcceptable(newPassword);

    Account target = db.auth(c -> findById(c, targetUserId));
    if (target == null) throw ApiException.notFound("There is no user account number " + targetUserId + ".");

    String hash = Passwords.hash(newPassword);
    db.authTx(director.userId(), "Password reset by " + director.displayName(), c -> {
      writeHash(c, target.id(), target.version(), hash);
      record(c, "PASSWORD_RESET", target.username(), target.id(), null, client,
          "reset by " + director.username());
      return null;
    });

    // Every session for that account ends: whoever was logged in must use the new password.
    sessions.closeAllFor(targetUserId);
  }

  private void writeHash(Connection c, long userId, int version, String hash) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "UPDATE rhrms.staff_user SET password_hash = ?, version = version + 1"
            + " WHERE id = ? AND version = ?")) {
      ps.setString(1, hash);
      ps.setLong(2, userId);
      ps.setInt(3, version);
      if (ps.executeUpdate() != 1) {
        // Level 3: somebody changed this account between our read and our write.
        throw ApiException.conflict("Somebody else changed this account a moment ago. "
            + "Nothing was changed. Please try again.");
      }
    }
  }

  // --------------------------------------------------------------- the trail

  /** Writes one row of the login trail. Never called with a password in any argument. */
  public void record(Connection c, String event, String username, Long userId, String action,
                     String client, String detail) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "INSERT INTO rhrms.auth_event(event, username, user_id, action, client, detail)"
            + " VALUES (?, ?, ?, ?, ?, ?)")) {
      ps.setString(1, event);
      ps.setString(2, trim(username, 60));
      if (userId == null) ps.setNull(3, java.sql.Types.BIGINT); else ps.setLong(3, userId);
      ps.setString(4, trim(action, 60));
      ps.setString(5, trim(client, 80));
      ps.setString(6, detail);
      ps.executeUpdate();
    }
  }

  /**
   * Writes one row of the trail in a transaction of its own, which commits immediately. This is
   * the form used for anything that is about to be refused.
   */
  public void recordStandalone(String event, String username, Long userId, String action,
                               String client, String detail) throws SQLException {
    db.auth(c -> {
      record(c, event, username, userId, action, client, detail);
      return null;
    });
  }

  private static String trim(String s, int max) {
    if (s == null) return null;
    return s.length() <= max ? s : s.substring(0, max);
  }

  // ------------------------------------------------------------- lookups

  private static final String ACCOUNT_COLUMNS =
      "id, username, display_name, role, password_hash, active, version";

  private Account findByUsername(Connection c, String username) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "SELECT " + ACCOUNT_COLUMNS + " FROM rhrms.staff_user WHERE lower(username) = lower(?)")) {
      ps.setString(1, username);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? read(rs) : null;
      }
    }
  }

  private Account findById(Connection c, long id) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "SELECT " + ACCOUNT_COLUMNS + " FROM rhrms.staff_user WHERE id = ?")) {
      ps.setLong(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? read(rs) : null;
      }
    }
  }

  private static Account read(ResultSet rs) throws SQLException {
    return new Account(rs.getLong("id"), rs.getString("username"), rs.getString("display_name"),
        Role.of(rs.getString("role")), rs.getString("password_hash"), rs.getBoolean("active"),
        rs.getInt("version"));
  }

  // ------------------------------------------------------------- lockout

  private record Lockout(boolean blocked, int failures, long minutesLeft, Long userId) {}

  /**
   * Counts wrong passwords for this username since the later of (a) the start of the lockout
   * window and (b) that account's last successful login or password reset. A successful login
   * therefore wipes the slate, and one bad afternoon does not follow anybody around.
   */
  private Lockout lockoutFor(Connection c, String username) throws SQLException {
    int max = settings.maxFailedLogins();
    int minutes = settings.lockoutMinutes();
    String sql = """
        SELECT count(*) AS failures, max(at) AS last_failure, max(user_id) AS user_id
        FROM rhrms.auth_event
        WHERE lower(username) = lower(?)
          AND event = 'LOGIN_FAILED'
          AND at > now() - make_interval(mins => ?)
          AND at > coalesce((SELECT max(at) FROM rhrms.auth_event
                              WHERE lower(username) = lower(?)
                                AND event IN ('LOGIN_OK', 'PASSWORD_RESET')),
                            '-infinity'::timestamptz)
        """;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, username);
      ps.setInt(2, minutes);
      ps.setString(3, username);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) return new Lockout(false, 0, 0, null);
        int failures = rs.getInt("failures");
        Timestamp last = rs.getTimestamp("last_failure");
        long uid = rs.getLong("user_id");
        Long userId = rs.wasNull() ? null : uid;
        if (failures < max || last == null) return new Lockout(false, failures, 0, userId);
        Instant until = last.toInstant().plusSeconds(minutes * 60L);
        long secondsLeft = until.getEpochSecond() - Instant.now().getEpochSecond();
        if (secondsLeft <= 0) return new Lockout(false, failures, 0, userId);
        return new Lockout(true, failures, Math.max(1, (secondsLeft + 59) / 60), userId);
      }
    }
  }
}
