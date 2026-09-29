package org.rubyhill.rhrms.config;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The setting table, cached for a few seconds.
 *
 * Build spec rule 4: "Every value marked (setting) must be read from the setting table, never
 * hard-coded. The client has changed his answers before and will again." That means these are
 * read constantly - on every login, every decision, every food forecast - so they are cached.
 *
 * The cache is deliberately short-lived. When the director changes the adoption fee the change
 * has to reach the other terminals without anyone restarting anything, and a few seconds of
 * staleness on a value like "buffer days" cannot hurt anybody. A write through
 * PUT /api/settings/{key} clears the cache immediately, so in practice the delay is zero.
 */
public final class Settings {
  private static final long CACHE_MILLIS = 5_000;

  private final Db db;
  private final AtomicLong loadedAt = new AtomicLong(0);
  private volatile Map<String, String> cache = Map.of();

  public Settings(Db db) { this.db = db; }

  private Map<String, String> current() {
    long now = System.currentTimeMillis();
    if (now - loadedAt.get() < CACHE_MILLIS && !cache.isEmpty()) return cache;
    try {
      Map<String, String> fresh = db.auth(c -> {
        Map<String, String> m = new LinkedHashMap<>();
        try (var st = c.createStatement();
             var rs = st.executeQuery("SELECT key, value FROM rhrms.setting ORDER BY key")) {
          while (rs.next()) m.put(rs.getString(1), rs.getString(2));
        }
        return m;
      });
      cache = Collections.unmodifiableMap(fresh);
      loadedAt.set(now);
    } catch (SQLException e) {
      // If the database is briefly unreachable, keep serving the last values rather than
      // failing every request. An empty cache means we genuinely have nothing, and the
      // callers below fall back to the documented default.
      loadedAt.set(now - CACHE_MILLIS + 500);      // try again shortly
    }
    return cache;
  }

  /** Forces the next read to go to the database. Called after a setting is written. */
  public void invalidate() { loadedAt.set(0); }

  public String get(String key, String fallback) {
    String v = current().get(key);
    return (v == null || v.isBlank()) ? fallback : v.trim();
  }

  public int getInt(String key, int fallback) {
    String v = get(key, null);
    if (v == null) return fallback;
    try {
      return Integer.parseInt(v);
    } catch (NumberFormatException e) {
      return fallback;      // a typo in the setting table must not take the system down
    }
  }

  public boolean getBool(String key, boolean fallback) {
    String v = get(key, null);
    if (v == null) return fallback;
    return switch (v.toLowerCase(Locale.ROOT)) {
      case "true", "yes", "1", "on" -> true;
      case "false", "no", "0", "off" -> false;
      default -> fallback;
    };
  }

  public BigDecimal getDecimal(String key, BigDecimal fallback) {
    String v = get(key, null);
    if (v == null) return fallback;
    try {
      return new BigDecimal(v);
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  public Map<String, String> all() { return current(); }

  // ---- the handful the server itself depends on, named so call sites read clearly ----

  /** Section 7: the session locks after this many idle minutes. */
  public int idleMinutes() { return Math.max(1, getInt("session.idle_minutes", 15)); }

  public int maxFailedLogins() { return Math.max(1, getInt("auth.max_failed_logins", 5)); }

  public int lockoutMinutes() { return Math.max(1, getInt("auth.lockout_minutes", 5)); }

  /** DE-1: which roles may record a decision. */
  public String decisionAllowedRoles() { return get("decision.allowed_roles", "DIRECTOR"); }

  /** PL-2: the baseline adoption fee. */
  public BigDecimal feeBaseline() { return getDecimal("fee.baseline", new BigDecimal("250.00")); }

  /** PL-3: may an animal that is not vetted be placed? */
  public boolean requireVetted() { return getBool("placement.require_vetted", true); }

  /** RC-2: how old an earlier home visit may be and still be reused. */
  public int visitReuseMaxMonths() { return Math.max(1, getInt("visit.reuse_max_months", 12)); }
}
