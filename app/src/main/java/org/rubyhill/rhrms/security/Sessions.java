package org.rubyhill.rhrms.security;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live sessions, held in memory by the one server process.
 *
 * Tokens are 256 random bits from SecureRandom, so a token cannot be guessed and none of the
 * user's details can be read out of it. They are deliberately NOT stored in the database:
 * stopping the server ends every session, which is the behaviour you want on a shared
 * front-desk computer at the end of the day. See docs/DECISIONS.md (D-05).
 *
 * Section 7 also asks for a lock after session.idle_minutes of inactivity. A session that has
 * been idle too long is dropped on its next use and the client is told to log in again.
 */
public final class Sessions {
  private static final SecureRandom RANDOM = new SecureRandom();

  private final Map<String, Session> byToken = new ConcurrentHashMap<>();

  public static String newToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public Session open(long userId, String username, String displayName, Role role, String client) {
    Session s = new Session(newToken(), userId, username, displayName, role, client);
    byToken.put(s.token(), s);
    return s;
  }

  /**
   * Returns the live session for a token, dropping it first if it has been idle too long.
   * Empty means "log in again", whether the token was never valid or has simply expired.
   */
  public Optional<Session> lookup(String token, long idleMinutes) {
    if (token == null || token.isBlank()) return Optional.empty();
    Session s = byToken.get(token);
    if (s == null) return Optional.empty();
    if (s.idleLongerThan(idleMinutes)) {
      byToken.remove(token, s);
      return Optional.empty();
    }
    return Optional.of(s);
  }

  public boolean close(String token) {
    return token != null && byToken.remove(token) != null;
  }

  /** Used when the director deactivates an account or resets someone's password. */
  public int closeAllFor(long userId) {
    int closed = 0;
    for (Map.Entry<String, Session> e : byToken.entrySet()) {
      if (e.getValue().userId() == userId && byToken.remove(e.getKey(), e.getValue())) closed++;
    }
    return closed;
  }

  /** Drops sessions that have gone idle, so the list the admin screen shows stays honest. */
  public int sweep(long idleMinutes) {
    int dropped = 0;
    for (Map.Entry<String, Session> e : byToken.entrySet()) {
      if (e.getValue().idleLongerThan(idleMinutes) && byToken.remove(e.getKey(), e.getValue())) dropped++;
    }
    return dropped;
  }

  public List<Session> all() { return new ArrayList<>(byToken.values()); }

  public int count() { return byToken.size(); }
}
