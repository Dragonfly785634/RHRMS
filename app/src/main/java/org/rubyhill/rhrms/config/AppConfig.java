package org.rubyhill.rhrms.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * Every setting the two programs need to start up: where the database is, which PostgreSQL
 * login role to use for each application role, and which address the local API listens on.
 *
 * Anything that changes per installation lives here. Anything the rescue might change its
 * mind about (fees, lead times, thresholds) lives in the database's setting table instead,
 * because the spec says so and because the director has changed his answers before.
 *
 * Lookup order, first hit wins:
 *   1. a system property   -Drhrms.api.port=9000
 *   2. an environment variable, the key upper-cased with dots as underscores:
 *      api.port -> RHRMS_API_PORT
 *   3. rhrms.properties, found via -Drhrms.config=PATH, then ./rhrms.properties,
 *      then ../rhrms.properties, then the copy bundled in the jar
 */
public final class AppConfig {
  private final Properties props = new Properties();
  private final String source;

  private AppConfig(Properties fileProps, String source) {
    this.props.putAll(fileProps);
    this.source = source;
  }

  public static AppConfig load() {
    Properties p = new Properties();
    String from = "built-in defaults";

    Path explicit = systemPath("rhrms.config");
    Path[] candidates = explicit != null
        ? new Path[] { explicit }
        : new Path[] { Path.of("rhrms.properties"), Path.of("..", "rhrms.properties"),
                       Path.of("app", "rhrms.properties") };

    for (Path candidate : candidates) {
      if (candidate != null && Files.isReadable(candidate)) {
        try (var in = Files.newBufferedReader(candidate, StandardCharsets.UTF_8)) {
          p.load(in);
          from = candidate.toAbsolutePath().toString();
        } catch (IOException e) {
          throw new IllegalStateException("Could not read " + candidate.toAbsolutePath() + ": " + e.getMessage(), e);
        }
        break;
      }
    }

    if (from.equals("built-in defaults")) {
      try (InputStream in = AppConfig.class.getResourceAsStream("/rhrms.properties")) {
        if (in != null) { p.load(in); from = "rhrms.properties inside the jar"; }
      } catch (IOException e) {
        throw new IllegalStateException("Could not read the bundled rhrms.properties: " + e.getMessage(), e);
      }
    }
    if (explicit != null && !Files.isReadable(explicit)) {
      throw new IllegalStateException("-Drhrms.config points at " + explicit.toAbsolutePath() + ", which cannot be read.");
    }
    return new AppConfig(p, from);
  }

  private static Path systemPath(String key) {
    String v = System.getProperty(key);
    return (v == null || v.isBlank()) ? null : Path.of(v);
  }

  /** Where the settings came from, printed at start-up so nobody debugs the wrong file. */
  public String source() { return source; }

  public String get(String key, String fallback) {
    String sys = System.getProperty("rhrms." + key);
    if (sys != null && !sys.isBlank()) return sys.trim();

    String env = System.getenv("RHRMS_" + key.toUpperCase(Locale.ROOT).replace('.', '_'));
    if (env != null && !env.isBlank()) return env.trim();

    String fromFile = props.getProperty(key);
    if (fromFile != null && !fromFile.isBlank()) return fromFile.trim();

    return fallback;
  }

  public String require(String key) {
    String v = get(key, null);
    if (v == null) {
      throw new IllegalStateException("Setting '" + key + "' is missing. Add it to rhrms.properties ("
          + source + ") or set RHRMS_" + key.toUpperCase(Locale.ROOT).replace('.', '_') + ".");
    }
    return v;
  }

  public int getInt(String key, int fallback) {
    String v = get(key, null);
    if (v == null) return fallback;
    try {
      return Integer.parseInt(v);
    } catch (NumberFormatException e) {
      throw new IllegalStateException("Setting '" + key + "' should be a whole number but is '" + v + "'.");
    }
  }

  // ------------------------------------------------------------------ database

  public String dbHost() { return get("db.host", "localhost"); }
  public int    dbPort() { return getInt("db.port", 5432); }
  public String dbName() { return get("db.name", "rhrms"); }

  public String jdbcUrl() {
    return "jdbc:postgresql://" + dbHost() + ":" + dbPort() + "/" + dbName();
  }

  /** The login role used only to read password hashes and write login events. */
  public String authUser()     { return get("db.auth.user", "rhrms_auth"); }
  public String authPassword() { return require("db.auth.password"); }

  /** The login role for sessions with the given application role. */
  public String roleUser(org.rubyhill.rhrms.security.Role role) {
    return get("db." + role.name().toLowerCase(Locale.ROOT) + ".user", role.dbRole());
  }

  public String rolePassword(org.rubyhill.rhrms.security.Role role) {
    return require("db." + role.name().toLowerCase(Locale.ROOT) + ".password");
  }

  public int poolSize() { return getInt("db.pool.size", 4); }

  // ----------------------------------------------------------------- local API

  /**
   * Default 127.0.0.1: the API is for the people sitting at this one front-desk computer.
   * Binding to the loopback address means nothing on the network can reach it, which is the
   * whole reason a local server is acceptable without TLS. Changing this to 0.0.0.0 without
   * adding TLS would put passwords on the wire in the clear.
   */
  public String apiHost()    { return get("api.host", "127.0.0.1"); }
  public int    apiPort()    { return getInt("api.port", 8642); }
  public int    apiThreads() { return getInt("api.threads", 16); }

  public String apiBaseUrl() {
    return "http://" + apiHost() + ":" + apiPort();
  }

  /** The address the terminal client dials; defaults to the server's own address. */
  public String clientBaseUrl() { return get("client.base.url", apiBaseUrl()); }
}
