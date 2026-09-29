package org.rubyhill.rhrms.security;

import org.rubyhill.rhrms.http.ApiException;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The one place that decides whether a role may do something.
 *
 * This runs inside the API server, not in the terminal client, on purpose. A client can be
 * replaced, patched or written from scratch by anybody who can reach the port; hiding a menu
 * item is a courtesy to the user, not a security measure. Every request is checked here
 * before any SQL runs, and the PostgreSQL role behind the connection refuses the same thing
 * a second time. See docs/SECURITY.md.
 */
public final class Permissions {
  private Permissions() {}

  public static boolean allows(Role role, Perm perm) {
    return role != null && role.atLeast(perm.lowestRole());
  }

  public static Set<Perm> of(Role role) {
    EnumSet<Perm> set = EnumSet.noneOf(Perm.class);
    for (Perm p : Perm.values()) {
      if (allows(role, p)) set.add(p);
    }
    return set;
  }

  /**
   * Throws the refusal the client shows. The message names the role and the action, because
   * "permission denied" leaves a volunteer with nothing to do next.
   */
  public static void require(Role role, Perm perm) {
    if (!allows(role, perm)) {
      throw ApiException.notAllowed("Your account is " + role.name() + " and may not " + perm.description()
          + ". " + whoCan(perm));
    }
  }

  private static String whoCan(Perm perm) {
    return switch (perm.lowestRole()) {
      case DIRECTOR -> "Only the director can do this.";
      case STAFF -> "Ask a staff member or the director to do it.";
      case VOLUNTEER -> "Ask whoever set up this computer: your account may be inactive.";
    };
  }

  /**
   * Deciding an application is narrowed further by the database setting decision.allowed_roles
   * (default DIRECTOR). Interview 3 says the director makes the final call, and question Q4
   * asks whether anyone may stand in when he is away; the setting is the answer to that
   * without a new build.
   */
  public static void requireDecider(Role role, String allowedRolesSetting) {
    require(role, Perm.DECIDE);                 // the floor: never a volunteer
    List<String> allowed = decisionRoles(allowedRolesSetting);
    if (!allowed.contains(role.name())) {
      throw ApiException.notAllowed("Only these roles may decide an application: " + String.join(", ", allowed)
          + ". Your account is " + role.name() + ". (The director can change that in the settings, "
          + "under decision.allowed_roles.)");
    }
  }

  /** The setting's value, cleaned up, with DIRECTOR as the fallback. */
  public static List<String> decisionRoles(String allowedRolesSetting) {
    return Arrays.stream(
            (allowedRolesSetting == null || allowedRolesSetting.isBlank() ? "DIRECTOR" : allowedRolesSetting)
                .split(","))
        .map(s -> s.trim().toUpperCase(Locale.ROOT))
        .filter(s -> !s.isEmpty())
        .toList();
  }

  /** True when this role may decide right now, floor and setting together. */
  public static boolean mayDecide(Role role, String allowedRolesSetting) {
    return allows(role, Perm.DECIDE) && decisionRoles(allowedRolesSetting).contains(role.name());
  }

  /**
   * The permission list the terminal uses to decide which menu items to show.
   *
   * DECIDE is the one entry that is not decided by the role alone, so the current setting is
   * passed in. Otherwise a STAFF terminal would offer a "Decide" menu the server then refuses,
   * which is exactly the kind of lie level 2 is about.
   */
  public static Map<String, Object> describe(Role role, String decisionAllowedRoles) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Perm p : Perm.values()) {
      out.put(p.name(), p == Perm.DECIDE ? mayDecide(role, decisionAllowedRoles) : allows(role, p));
    }
    return out;
  }
}
