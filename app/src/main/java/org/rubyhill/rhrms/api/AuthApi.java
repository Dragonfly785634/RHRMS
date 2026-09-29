package org.rubyhill.rhrms.api;

import org.rubyhill.rhrms.http.ApiException;
import org.rubyhill.rhrms.http.Req;
import org.rubyhill.rhrms.http.Res;
import org.rubyhill.rhrms.http.Router;
import org.rubyhill.rhrms.json.Json;
import org.rubyhill.rhrms.security.Passwords;
import org.rubyhill.rhrms.security.Perm;
import org.rubyhill.rhrms.security.Permissions;
import org.rubyhill.rhrms.security.Role;
import org.rubyhill.rhrms.security.Session;

import java.time.Instant;

/** Logging in and out, the first-run director account, and password changes. */
final class AuthApi {
  private AuthApi() {}

  static void register(Router r, Ctx ctx) {

    // ---------------------------------------------------------------- health
    r.publicRoute("GET", "/api/health", "is the server and the database up?", req -> {
      Json.Obj out = Json.obj().put("ok", true).put("service", "RHRMS API").put("version", Version.NUMBER);
      try {
        out.put("database", "up").put("databaseVersion", firstWords(ctx.db().serverVersion(), 2));
      } catch (Exception e) {
        out.put("ok", false).put("database", "down")
           .put("message", "PostgreSQL is not answering. Start it, then try again.");
      }
      out.put("time", Instant.now().toString()).put("openSessions", ctx.sessions().count());
      return Res.ok(out);
    });

    // ------------------------------------------------------------- first run
    // Nothing to protect yet: the whole point is that the database has no accounts at all.
    r.publicRoute("GET", "/api/bootstrap/status", "does this installation still need its first account?",
        req -> ctx.readAnonymous(c -> {
          long accounts = ((Number) Ctx.scalar(c, "SELECT count(*) FROM rhrms.staff_user")).longValue();
          return Res.ok(Json.obj()
              .put("needsFirstRun", accounts == 0)
              .put("accounts", accounts)
              .put("message", accounts == 0
                  ? "No accounts exist yet. Create the director's account to get started."
                  : "This installation is already set up. Log in."));
        }));

    r.publicRoute("POST", "/api/bootstrap/director", "create the very first DIRECTOR account", req -> {
      String username = cleanUsername(req.requireText("username", "Username"));
      String displayName = req.requireText("displayName", "Display name");
      String password = req.requireText("password", "Password");
      Passwords.requireAcceptable(password);
      String hash = Passwords.hash(password);

      long id = ctx.db().tx(Role.DIRECTOR, null, null, null, c -> {
        // Allowed exactly once, when staff_user is empty. After that the only way to make an
        // account is POST /api/users as a logged-in director, so this is not a way in later.
        long accounts = ((Number) Ctx.scalar(c, "SELECT count(*) FROM rhrms.staff_user")).longValue();
        if (accounts > 0) {
          throw ApiException.notAllowed("This installation already has accounts. "
              + "Ask the director to add yours from the Admin screen.");
        }
        // The audit trigger refuses any change that does not say who made it, and the first
        // account has no id yet. Claim the id from the sequence first, announce it as the
        // acting user, then insert it: the audit log correctly shows the director creating
        // their own account.
        long newId = Ctx.scalarLong(c, "SELECT nextval('rhrms.staff_user_id_seq')");
        Ctx.run(c, "SELECT set_config('rhrms.user_id', ?, true),"
            + " set_config('rhrms.reason', 'First run: created the director account', true)",
            String.valueOf(newId));
        Ctx.update(c, "INSERT INTO rhrms.staff_user(id, username, display_name, role, password_hash)"
            + " VALUES (?, ?, ?, 'DIRECTOR', ?)", newId, username, displayName, hash);
        return newId;
      });

      ctx.auth().recordStandalone("ACCOUNT_CREATED", username, id, "first run", req.clientLabel(),
          "created by the first-run screen");
      return Res.created(Json.obj()
          .put("id", id).put("username", username).put("role", "DIRECTOR")
          .put("message", "Director account created. Log in as " + username + "."));
    });

    // ----------------------------------------------------------------- login
    r.publicRoute("POST", "/api/auth/login", "log in and get a session token", req -> {
      Session s = ctx.auth().login(req.optText("username"), rawPassword(req), req.clientLabel());
      return Res.ok(sessionInfo(ctx, s).put("token", s.token()));
    });

    r.route("POST", "/api/auth/logout", Perm.VIEW, "end this session", req -> {
      ctx.auth().logout(req.requireSession(), req.clientLabel());
      return Res.done("Logged out.");
    });

    r.route("GET", "/api/auth/whoami", Perm.VIEW, "who this token belongs to and what they may do",
        req -> Res.ok(sessionInfo(ctx, req.requireSession())));

    /*
     * A dry run of the step-up check. The terminal calls this when somebody types their
     * password at an "Are you sure?" prompt, so a wrong password is reported before a long
     * form is thrown away. The real check still happens on the action itself: this endpoint
     * is a convenience, never the gate.
     */
    r.route("POST", "/api/auth/confirm", Perm.VIEW, "check a password without doing anything else",
        req -> {
          String action = req.optText("action");
          ctx.auth().confirm(req.requireSession(), rawPassword(req),
              action == null ? "confirm identity" : action, req.clientLabel());
          return Res.done("Password confirmed.");
        });

    r.route("POST", "/api/auth/password", Perm.VIEW, "change your own password", req -> {
      ctx.auth().changeOwnPassword(req.requireSession(),
          req.optText("currentPassword"), req.optText("newPassword"), req.clientLabel());
      return Res.done("Your password has been changed.");
    });

    // The route list, so a future frontend can discover the API instead of guessing.
    r.publicRoute("GET", "/api", "this list of endpoints", req -> Res.ok(Json.obj()
        .put("service", "RHRMS API").put("version", Version.NUMBER)
        .put("about", "Ruby Hill Rescue Management System. Every endpoint returns JSON. "
            + "Send 'Authorization: Bearer <token>' from POST /api/auth/login. Endpoints marked "
            + "needsPasswordConfirmation also require the header 'X-RHRMS-Confirm: <your password>'.")
        .put("endpoints", r.describe())));
  }

  /** The reply to a login and to whoami: who you are and, plainly, what you may do. */
  static Json.Obj sessionInfo(Ctx ctx, Session s) {
    return Json.obj()
        .put("user", Json.obj()
            .put("id", s.userId())
            .put("username", s.username())
            .put("displayName", s.displayName())
            .put("role", s.role().name())
            .put("databaseRole", s.role().dbRole()))
        .put("permissions", Permissions.describe(s.role(), ctx.settings().decisionAllowedRoles()))
        .put("sessionStartedAt", s.startedAt().toString())
        .put("idleMinutes", ctx.settings().idleMinutes())
        .put("serverTime", Instant.now().toString());
  }

  /**
   * Passwords are read raw: no trimming and no case folding, because a password that ends in
   * a space is still that person's password.
   */
  private static String rawPassword(Req req) {
    Object v = req.body().get("password");
    return v == null ? null : String.valueOf(v);
  }

  static String cleanUsername(String raw) {
    String u = raw.trim().toLowerCase(java.util.Locale.ROOT);
    if (!u.matches("[a-z0-9._-]{2,40}")) {
      throw ApiException.badRequest("A username is 2 to 40 characters, using letters, digits, "
          + "dots, dashes or underscores. '" + raw + "' does not fit.");
    }
    return u;
  }

  private static String firstWords(String s, int n) {
    if (s == null) return null;
    String[] parts = s.split("\\s+");
    return String.join(" ", java.util.Arrays.copyOfRange(parts, 0, Math.min(n, parts.length)));
  }
}
