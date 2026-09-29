package org.rubyhill.rhrms.api;

import org.rubyhill.rhrms.http.ApiException;
import org.rubyhill.rhrms.http.Res;
import org.rubyhill.rhrms.http.Router;
import org.rubyhill.rhrms.json.Json;
import org.rubyhill.rhrms.security.Passwords;
import org.rubyhill.rhrms.security.Perm;
import org.rubyhill.rhrms.security.Role;
import org.rubyhill.rhrms.security.Session;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * User accounts, settings, who is logged in, and the login trail. Director only, except for
 * reading the settings: the placement screen has to know the baseline fee, and the food screen
 * has to know the buffer days, so everybody may look.
 *
 * Every change in here re-asks for the director's password. Making somebody a DIRECTOR, or
 * turning an account off, is exactly the kind of thing that must not be possible for whoever
 * walks past an unattended terminal.
 */
final class AdminApi {
  private AdminApi() {}

  private static final String USER_COLUMNS =
      "u.id, u.username, u.display_name, u.role, u.active, u.created_at, u.version";

  static void register(Router r, Ctx ctx) {

    // ----------------------------------------------------------------- users
    r.route("GET", "/api/users", Perm.USER_ADMIN, "list the user accounts", req -> ctx.read(req, c -> {
      List<Map<String, Object>> users = Ctx.query(c, """
          SELECT %s,
                 (SELECT max(at) FROM rhrms.auth_event e
                   WHERE e.user_id = u.id AND e.event = 'LOGIN_OK')   AS last_login_at,
                 (SELECT count(*) FROM rhrms.auth_event e
                   WHERE e.user_id = u.id AND e.event = 'LOGIN_FAILED'
                     AND e.at > now() - INTERVAL '24 hours')          AS failed_logins_today
          FROM rhrms.staff_user u
          ORDER BY u.active DESC,
                   CASE u.role WHEN 'DIRECTOR' THEN 1 WHEN 'STAFF' THEN 2 ELSE 3 END,
                   lower(u.display_name)
          """.formatted(USER_COLUMNS));
      // Who is at a keyboard right now. Sessions live in memory, not in the database.
      for (Map<String, Object> u : users) {
        long id = ((Number) u.get("id")).longValue();
        u.put("open_sessions", ctx.sessions().all().stream().filter(s -> s.userId() == id).count());
      }
      return Res.list("users", users);
    }));

    r.critical("POST", "/api/users", Perm.USER_ADMIN, "create a user account",
        "add a new user account", req -> {
      String username = AuthApi.cleanUsername(req.requireText("username", "Username"));
      String displayName = req.requireText("displayName", "Display name");
      Role role = parseRole(req.requireText("role", "Role"));
      String password = req.requireText("password", "Password");
      Passwords.requireAcceptable(password);
      String hash = Passwords.hash(password);

      long id = ctx.write(req, c -> Ctx.insertReturningId(c,
          "INSERT INTO rhrms.staff_user(username, display_name, role, password_hash)"
              + " VALUES (?, ?, ?, ?) RETURNING id",
          username, displayName, role.name(), hash));

      ctx.auth().recordStandalone("ACCOUNT_CREATED", username, id, "add a new user account",
          req.clientLabel(), "created by " + req.requireSession().username());
      return Res.created(Json.obj().put("id", id).put("username", username).put("role", role.name())
          .put("message", displayName + " can now log in as " + username + "."));
    });

    r.critical("PATCH", "/api/users/{id}", Perm.USER_ADMIN, "change a user account",
        "rename a user, change their role, or switch the account on or off", req -> {
      long id = req.pathId("id");
      Session me = req.requireSession();
      String displayName = req.optText("displayName");
      String roleText = req.optText("role");
      Boolean active = req.optBool("active");
      if (displayName == null && roleText == null && active == null) {
        throw ApiException.badRequest("Nothing to change. Send a display name, a role, or active yes/no.");
      }
      Role newRole = roleText == null ? null : parseRole(roleText);

      // Two things the director must not be able to do by accident, because either would
      // leave the rescue with nobody who can decide an adoption (DE-1).
      if (id == me.userId() && Boolean.FALSE.equals(active)) {
        throw ApiException.badRequest("You cannot switch off your own account. "
            + "Ask another director to do it.");
      }
      if (id == me.userId() && newRole != null && newRole != Role.DIRECTOR) {
        throw ApiException.badRequest("You cannot take away your own director role. "
            + "Make somebody else a director first.");
      }

      Map<String, Object> result = ctx.writeWithReason(req, c -> {
        Map<String, Object> before = Ctx.queryOneOr404(c,
            "There is no user account number " + id + ".",
            "SELECT " + USER_COLUMNS + " FROM rhrms.staff_user u WHERE u.id = ?", id);

        if (wouldLeaveNoDirector(c, before, newRole, active)) {
          throw ApiException.badRequest("This is the only active director. "
              + "Somebody has to be able to decide adoptions, so make another director first.");
        }

        int version = ((Number) before.get("version")).intValue();
        int changed = Ctx.update(c, """
            UPDATE rhrms.staff_user
               SET display_name = coalesce(?::text, display_name),
                   role         = coalesce(?::text, role),
                   active       = coalesce(?::boolean, active),
                   version      = version + 1
             WHERE id = ? AND version = ?
            """, displayName, newRole == null ? null : newRole.name(), active, id, version);
        if (changed != 1) {
          throw ApiException.conflict("Somebody else changed this account a moment ago. "
              + "Nothing was changed. Open it again and re-apply your change.");
        }
        return Ctx.queryOne(c, "SELECT " + USER_COLUMNS + " FROM rhrms.staff_user u WHERE u.id = ?", id);
      });

      // Turning an account off, or changing its role, must take effect now, not at the end of
      // whatever that person is doing: their sessions end immediately.
      if (Boolean.FALSE.equals(active) || newRole != null) {
        int closed = ctx.sessions().closeAllFor(id);
        if (closed > 0) result.put("sessions_ended", closed);
      }
      ctx.auth().recordStandalone("ACCOUNT_CHANGED", String.valueOf(result.get("username")), id,
          "change a user account", req.clientLabel(), "changed by " + me.username());
      return Res.ok(Json.obj().put("user", result).put("message", "Account updated."));
    });

    r.critical("POST", "/api/users/{id}/password", Perm.USER_ADMIN, "reset somebody's password",
        "reset another user's password", req -> {
      long id = req.pathId("id");
      String password = req.requireText("newPassword", "New password");
      ctx.auth().resetPassword(req.requireSession(), id, password, req.clientLabel());
      return Res.done("Password reset. Tell them to log in with it and change it straight away.");
    });

    // -------------------------------------------------------------- settings
    // Reading is for everybody: the placement screen needs the baseline fee, the food screen
    // needs the buffer days. Writing is the director's (section 7).
    r.route("GET", "/api/settings", Perm.VIEW, "the values the rescue can change", req -> ctx.read(req,
        c -> Res.list("settings", Ctx.query(c,
            "SELECT key, value, updated_at, version FROM rhrms.setting ORDER BY key"))));

    r.critical("PUT", "/api/settings/{key}", Perm.SETTINGS, "change a setting",
        "change a system setting", req -> {
      String key = req.pathParam("key");
      String value = req.requireText("value", "Value");
      Map<String, Object> row = ctx.writeWithReason(req, c -> {
        Map<String, Object> before = Ctx.queryOneOr404(c,
            "There is no setting called '" + key + "'. Settings are created by the installer, not here.",
            "SELECT key, value, version FROM rhrms.setting WHERE key = ?", key);
        validateSetting(key, value);
        int version = ((Number) before.get("version")).intValue();
        int changed = Ctx.update(c,
            "UPDATE rhrms.setting SET value = ?, updated_at = now(), version = version + 1"
                + " WHERE key = ? AND version = ?", value, key, version);
        if (changed != 1) {
          throw ApiException.conflict("Somebody else changed '" + key + "' a moment ago. Nothing was saved.");
        }
        return Ctx.queryOne(c, "SELECT key, value, updated_at, version FROM rhrms.setting WHERE key = ?", key);
      });
      ctx.settings().invalidate();          // the other terminals see the new value at once
      return Res.ok(Json.obj().put("setting", row).put("message", key + " is now " + value + "."));
    });

    // ------------------------------------------------- sessions and login trail
    r.route("GET", "/api/sessions", Perm.USER_ADMIN, "who is logged in at this moment", req -> {
      List<Object> list = new ArrayList<>();
      for (Session s : ctx.sessions().all()) {
        list.add(Json.obj()
            .put("userId", s.userId()).put("username", s.username())
            .put("displayName", s.displayName()).put("role", s.role().name())
            .put("startedAt", s.startedAt().toString())
            .put("idleSeconds", s.idleSeconds())
            .put("client", s.client())
            .put("isYou", s.token().equals(req.requireSession().token())));
      }
      return Res.list("sessions", list,
          Map.of("locksAfterIdleMinutes", ctx.settings().idleMinutes()));
    });

    r.route("GET", "/api/auth/events", Perm.USER_ADMIN, "the login trail: who got in, and who did not",
        req -> {
          int limit = req.queryInt("limit", 100, 1000);
          String event = req.queryParam("event", null);
          String username = req.queryParam("username", null);
          return ctx.read(req, c -> Res.list("events", Ctx.query(c, """
              SELECT e.id, e.at, e.event, e.username, e.user_id, e.action, e.client, e.detail,
                     u.display_name
              FROM rhrms.auth_event e
              LEFT JOIN rhrms.staff_user u ON u.id = e.user_id
              WHERE (?::text IS NULL OR e.event = ?::text)
                AND (?::text IS NULL OR lower(e.username) = lower(?::text))
              ORDER BY e.at DESC, e.id DESC
              LIMIT ?::int
              """, event, event, username, username, limit)));
        });
  }

  private static Role parseRole(String text) {
    try {
      return Role.of(text);
    } catch (IllegalArgumentException e) {
      throw ApiException.badRequest("Role must be DIRECTOR, STAFF or VOLUNTEER, not '" + text + "'.");
    }
  }

  /** True when this change would leave the rescue with no active director. */
  private static boolean wouldLeaveNoDirector(java.sql.Connection c, Map<String, Object> before,
                                              Role newRole, Boolean active) throws java.sql.SQLException {
    boolean wasDirector = "DIRECTOR".equals(before.get("role"));
    boolean wasActive = Boolean.TRUE.equals(before.get("active"));
    if (!wasDirector || !wasActive) return false;
    boolean stillDirector = (newRole == null || newRole == Role.DIRECTOR) && !Boolean.FALSE.equals(active);
    if (stillDirector) return false;
    long otherDirectors = ((Number) Ctx.scalar(c,
        "SELECT count(*) FROM rhrms.staff_user WHERE role = 'DIRECTOR' AND active AND id <> ?",
        before.get("id"))).longValue();
    return otherDirectors == 0;
  }

  /**
   * Catches the settings whose shape matters, so a typo cannot quietly break the food forecast
   * or lock everybody out of deciding applications. Anything not listed is free text on purpose.
   */
  private static void validateSetting(String key, String value) {
    switch (key) {
      case "kennel.count", "food.buffer_days", "food.default_lead_days.regular",
           "food.default_lead_days.prescription", "visit.reuse_max_months",
           "session.idle_minutes", "auth.max_failed_logins", "auth.lockout_minutes" -> {
        int n = requireInt(key, value);
        if (n < 1) throw ApiException.badRequest(key + " has to be 1 or more.");
      }
      case "fee.baseline", "food.default_lbs_per_cup" -> {
        try {
          if (new java.math.BigDecimal(value).signum() < 0) {
            throw ApiException.badRequest(key + " cannot be negative.");
          }
        } catch (NumberFormatException e) {
          throw ApiException.badRequest(key + " has to be a number, not '" + value + "'.");
        }
      }
      case "placement.require_vetted" -> {
        if (!value.equals("true") && !value.equals("false")) {
          throw ApiException.badRequest(key + " has to be true or false.");
        }
      }
      case "decision.allowed_roles" -> {
        List<String> roles = new ArrayList<>();
        for (String part : value.split(",")) {
          String p = part.trim().toUpperCase(java.util.Locale.ROOT);
          if (p.isEmpty()) continue;
          try {
            roles.add(Role.of(p).name());
          } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("'" + p + "' is not a role. Use DIRECTOR, STAFF or VOLUNTEER.");
          }
        }
        if (roles.isEmpty()) {
          throw ApiException.badRequest("decision.allowed_roles cannot be empty: "
              + "somebody has to be able to decide an adoption.");
        }
        if (!roles.contains("DIRECTOR")) {
          // Interview 3 is unambiguous that the director makes the final call. Letting him
          // remove himself from the list is almost certainly a mistake, not a policy change.
          throw ApiException.badRequest("DIRECTOR has to stay in decision.allowed_roles. "
              + "Add other roles alongside it if somebody needs to stand in.");
        }
        if (roles.contains("VOLUNTEER")) {
          // A volunteer's input is a recommendation. That separation is the single clearest
          // requirement change between Interview 1 and Interview 3, so it is not a setting.
          // PostgreSQL refuses it as well: rhrms_volunteer has no INSERT on the decision table.
          throw ApiException.badRequest("VOLUNTEER cannot be added to decision.allowed_roles. "
              + "Volunteers record a recommendation on the home visit; the decision is the "
              + "director's, or a staff member standing in for him.");
        }
      }
      default -> { /* free text: backup paths, notes, anything added later */ }
    }
  }

  private static int requireInt(String key, String value) {
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      throw ApiException.badRequest(key + " has to be a whole number, not '" + value + "'.");
    }
  }
}
