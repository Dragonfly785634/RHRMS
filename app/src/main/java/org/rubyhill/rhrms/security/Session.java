package org.rubyhill.rhrms.security;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One logged-in person. Several of these exist at once: the interviews say more than one
 * person edits the data at the same time on the one front-desk computer, so every terminal
 * window logs in separately and gets its own session and its own audit trail.
 */
public final class Session {
  private final String token;
  private final long userId;
  private final String username;
  private final String displayName;
  private final Role role;
  private final Instant startedAt;
  private final String client;
  private final AtomicLong lastSeenMillis;

  public Session(String token, long userId, String username, String displayName, Role role, String client) {
    this.token = token;
    this.userId = userId;
    this.username = username;
    this.displayName = displayName;
    this.role = role;
    this.client = client;
    this.startedAt = Instant.now();
    this.lastSeenMillis = new AtomicLong(System.currentTimeMillis());
  }

  public String token() { return token; }
  public long userId() { return userId; }
  public String username() { return username; }
  public String displayName() { return displayName; }
  public Role role() { return role; }
  public Instant startedAt() { return startedAt; }
  public String client() { return client; }

  public Instant lastSeen() { return Instant.ofEpochMilli(lastSeenMillis.get()); }
  public void touch() { lastSeenMillis.set(System.currentTimeMillis()); }

  public boolean idleLongerThan(long minutes) {
    return System.currentTimeMillis() - lastSeenMillis.get() > minutes * 60_000L;
  }

  public long idleSeconds() {
    return (System.currentTimeMillis() - lastSeenMillis.get()) / 1000L;
  }
}
