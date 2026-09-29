package org.rubyhill.rhrms.ui;

import java.util.List;
import java.util.Map;

/**
 * User accounts, settings, the login trail, and the history of any record.
 *
 * Everything the director changes here needs his password again. Making somebody a DIRECTOR, or
 * turning an account off, is precisely what should not be possible for whoever wanders past an
 * unattended front desk.
 */
final class AdminScreens {
  private AdminScreens() {}

  /**
   * Everything the director does that is not about an animal, behind one command.
   *
   * There were seven commands here - users, newuser, user, settings, set, sessions, logins - and
   * they are all things a director does a handful of times a year. Seven names to remember for
   * that is a poor trade; one menu is not.
   */
  static void admin(Ui ui) {
    while (true) {
      Term.heading("Admin");
      Term.paragraph(Term.dim("Everything here is recorded against your name, and everything that "
          + "changes anything asks for your password again."));
      String choice = ui.prompt.choose("What would you like to do?", List.of(
          "USER_ACCOUNTS", "SETTINGS", "WHO_IS_LOGGED_IN", "THE_LOGIN_TRAIL", "NOTHING"), "NOTHING");
      switch (choice) {
        case "USER_ACCOUNTS" -> {
          users(ui);
          Term.out();
          String what = ui.prompt.choose("Anything to change?",
              List.of("ADD_SOMEBODY", "CHANGE_SOMEBODY", "NO"), "NO");
          if (what.equals("ADD_SOMEBODY")) newUser(ui);
          else if (what.equals("CHANGE_SOMEBODY")) editUser(ui, null);
        }
        case "SETTINGS" -> {
          settings(ui);
          if (ui.may("SETTINGS") && ui.prompt.yesNo("Change one?", false)) {
            setSetting(ui, List.of());
          }
        }
        case "WHO_IS_LOGGED_IN" -> sessions(ui);
        case "THE_LOGIN_TRAIL" -> logins(ui);
        default -> { return; }
      }
      Term.out();
      if (!ui.prompt.yesNo("Anything else in Admin?", false)) return;
    }
  }

  // ===================================================================== users

  static void users(Ui ui) {
    List<Map<String, Object>> users = ApiClient.list(ui.api.get("/api/users").get("users"));
    Term.heading("User accounts");
    Term.Table table = new Term.Table("id", "username", "name", "role", "active",
        "logged in now", "last login", "failed today");
    table.right(5, 7);
    for (Map<String, Object> u : users) {
      boolean active = ApiClient.flag(u.get("active"));
      int failed = ApiClient.intOf(u.get("failed_logins_today"), 0);
      table.row(u.get("id"), u.get("username"), u.get("display_name"), u.get("role"),
          active ? Term.green("yes") : Term.red("NO"),
          u.get("open_sessions"),
          u.get("last_login_at") == null ? Term.dim("never") : Term.when(u.get("last_login_at")),
          failed == 0 ? Term.dim("0") : Term.yellow(String.valueOf(failed)));
    }
    table.print();
    Term.out();
    Term.paragraph(Term.dim("Individual logins only; no shared accounts. Every change anybody "
        + "makes is recorded against their name, which is what makes the audit trail worth "
        + "anything. Nobody, including this program, can read a password back."));
    Term.out();
    Term.info(Term.dim("Adding and changing accounts is on the menu below."));
  }

  static void newUser(Ui ui) {
    Term.heading("Adding a user account");
    Term.paragraph("Diane and the other part-timer are STAFF. Mike is DIRECTOR. Regular "
        + "volunteers are VOLUNTEER (section 7).");
    String username = ui.prompt.required("Username (letters, digits, dots or dashes): ");
    String displayName = ui.prompt.required("Their name, as it should appear on records: ");
    String role = ui.prompt.choose("Which role?", List.of("VOLUNTEER", "STAFF", "DIRECTOR"), "VOLUNTEER");
    describeRole(role);

    String password;
    while (true) {
      password = ui.prompt.password("A starting password (at least 8 characters): ");
      String again = ui.prompt.password("Type it again: ");
      if (password.equals(again)) break;
      Term.problem("Those two did not match. Try again.");
    }

    String confirm = ui.confirm("Creating a " + role + " account for " + displayName + ".");
    if (confirm == null) {
      Term.info("Nothing was created.");
      return;
    }
    ApiClient.Body body = ApiClient.body()
        .set("username", username).set("displayName", displayName)
        .set("role", role).set("password", password)
        .set("reason", "Account created by the director");
    ui.showResult(ui.api.post("/api/users", body.map(), confirm));
    Term.out();
    Term.info("Tell them the password in person, and tell them to change it with 'passwd'.");
  }

  private static void describeRole(String role) {
    Term.out();
    switch (role) {
      case "VOLUNTEER" -> Term.info(Term.dim("Can see everything except user accounts, and can "
          + "record intakes, applications, home visits, opening and receiving food, and flag food "
          + "to be ordered."));
      case "STAFF" -> Term.info(Term.dim("All of that, plus corrections, kennel moves, "
          + "restrictions, adoptions, returns, donations, vet visits and stock counts."));
      case "DIRECTOR" -> Term.warn("A director decides adoptions, overrides decisions, changes the "
          + "settings and manages accounts. Give it only to somebody who should be doing all of that.");
      default -> { }
    }
  }

  static void editUser(Ui ui, String reference) {
    long id = reference != null ? Long.parseLong(reference.trim())
                                : ui.prompt.requiredId("Which user id? ");
    List<Map<String, Object>> users = ApiClient.list(ui.api.get("/api/users").get("users"));
    Map<String, Object> user = null;
    for (Map<String, Object> u : users) {
      if (id == ApiClient.intOf(u.get("id"), -1)) user = u;
    }
    if (user == null) {
      Term.problem("There is no user account number " + id + ".");
      return;
    }
    Term.heading("Account " + id + ": " + user.get("display_name")
        + " (" + user.get("username") + ", " + user.get("role") + ")");

    String what = ui.prompt.choose("What would you like to do?",
        List.of("RESET_THEIR_PASSWORD", "CHANGE_NAME_OR_ROLE", "SWITCH_THE_ACCOUNT_OFF",
            "SWITCH_THE_ACCOUNT_ON", "NOTHING"), "NOTHING");

    switch (what) {
      case "RESET_THEIR_PASSWORD" -> {
        Term.out();
        Term.paragraph("You are setting a new password, not reading the old one. Nobody can read "
            + "a password out of this system. Every session they have open will end.");
        String password;
        while (true) {
          password = ui.prompt.password("New password for " + user.get("username") + ": ");
          String again = ui.prompt.password("Type it again: ");
          if (password.equals(again)) break;
          Term.problem("Those two did not match. Try again.");
        }
        String confirm = ui.confirm("Resetting " + user.get("username") + "'s password.");
        if (confirm == null) {
          Term.info("Nothing was changed.");
          return;
        }
        ui.showResult(ui.api.post("/api/users/" + id + "/password",
            ApiClient.body().set("newPassword", password)
                .set("reason", "Password reset by the director").map(), confirm));
      }
      case "CHANGE_NAME_OR_ROLE" -> {
        ApiClient.Body body = ApiClient.body();
        String name = ui.prompt.line("New name (Enter to keep \"" + user.get("display_name") + "\"): ");
        if (name != null) body.set("displayName", name);
        if (ui.prompt.yesNo("Change their role from " + user.get("role") + "?", false)) {
          String role = ui.prompt.choose("New role", List.of("VOLUNTEER", "STAFF", "DIRECTOR"),
              ApiClient.str(user.get("role")));
          describeRole(role);
          body.set("role", role);
          Term.out();
          Term.info(Term.dim("Their open sessions will end, so the new role takes effect at once."));
        }
        if (body.map().isEmpty()) {
          Term.info("Nothing was changed.");
          return;
        }
        body.set("reason", ui.prompt.required("Reason for the record: ", 3,
            "For example: \"promoted to staff, agreed with Mike\"."));
        String confirm = ui.confirm("Changing account " + id + ".");
        if (confirm == null) {
          Term.info("Nothing was changed.");
          return;
        }
        ui.showResult(ui.api.patch("/api/users/" + id, body.map(), confirm));
      }
      case "SWITCH_THE_ACCOUNT_OFF", "SWITCH_THE_ACCOUNT_ON" -> {
        boolean turnOn = what.endsWith("ON");
        Term.out();
        Term.paragraph(turnOn
            ? "They will be able to log in again."
            : "They will not be able to log in, and any session they have open will end now. "
              + "The account is never deleted: their name still appears on everything they did.");
        ApiClient.Body body = ApiClient.body().set("active", turnOn)
            .set("reason", ui.prompt.required("Reason for the record: ", 3,
                turnOn ? "For example: \"back from a break\"."
                       : "For example: \"left the rescue in September\"."));
        String confirm = ui.confirm((turnOn ? "Switching on" : "Switching off")
            + " the account for " + user.get("display_name") + ".");
        if (confirm == null) {
          Term.info("Nothing was changed.");
          return;
        }
        ui.showResult(ui.api.patch("/api/users/" + id, body.map(), confirm));
      }
      default -> Term.info("Nothing was changed.");
    }
  }

  // ================================================================== settings

  static void settings(Ui ui) {
    List<Map<String, Object>> settings = ApiClient.list(ui.api.get("/api/settings").get("settings"));
    Term.heading("Settings");
    Term.paragraph("These are the values the interviews left uncertain, or that the rescue may "
        + "change its mind about. They live in the database, not in the program, so nothing has to "
        + "be rebuilt to change one.");
    Term.out();
    Term.Table table = new Term.Table("key", "value", "what it means");
    for (Map<String, Object> s : settings) {
      table.row(s.get("key"), Term.bold(ApiClient.str(s.get("value"))),
          explain(ApiClient.str(s.get("key"))));
    }
    table.print();
    if (ui.may("SETTINGS")) {
      Term.out();
    } else {
      Term.out();
      Term.info(Term.dim("Only the director can change these."));
    }
  }

  private static String explain(String key) {
    return switch (key) {
      case "kennel.count" -> "how many kennels the rescue has";
      case "fee.baseline" -> "the usual adoption fee; any other amount needs a reason";
      case "decision.allowed_roles" -> "who may decide an application (never a volunteer)";
      case "food.buffer_days" -> "days of slack on top of the lead time before REORDER NOW";
      case "food.default_lead_days.regular" -> "days a regular food takes to arrive";
      case "food.default_lead_days.prescription" -> "days a prescription food takes to arrive";
      case "food.default_lbs_per_cup" -> "assumed weight of a cup of food; weigh it to be sure";
      case "visit.reuse_max_months" -> "how old a home visit may be and still be reused";
      case "placement.require_vetted" -> "block adopting out an animal that is not vetted";
      case "session.idle_minutes" -> "how long a terminal may sit idle before it locks";
      case "auth.max_failed_logins" -> "wrong passwords in a row before a short pause";
      case "auth.lockout_minutes" -> "how long that pause lasts";
      default -> "";
    };
  }

  static void setSetting(Ui ui, List<String> args) {
    String key = args.size() >= 1 ? args.get(0) : ui.prompt.required("Which setting? ");
    String value = args.size() >= 2 ? String.join(" ", args.subList(1, args.size()))
                                    : ui.prompt.required("New value: ");
    Term.out();
    Term.info("Setting " + Term.bold(key) + " to " + Term.bold(value) + ".");
    Term.info(Term.dim(explain(key)));
    ApiClient.Body body = ApiClient.body().set("value", value)
        .set("reason", ui.prompt.required("Reason for the record: ", 3,
            "The old value, the new value, who changed it and why are all kept."));
    String confirm = ui.confirm("Changing a setting affects every terminal at once.");
    if (confirm == null) {
      Term.info("Nothing was changed.");
      return;
    }
    ui.showResult(ui.api.put("/api/settings/" + AnimalScreens.url(key), body.map(), confirm));
  }

  // =================================================== sessions and login trail

  static void sessions(Ui ui) {
    Map<String, Object> reply = ui.api.get("/api/sessions");
    Term.heading("Who is logged in right now");
    Term.Table table = new Term.Table("user", "role", "terminal", "since", "idle", "");
    for (Map<String, Object> s : ApiClient.list(reply.get("sessions"))) {
      long idle = ApiClient.intOf(s.get("idleSeconds"), 0);
      table.row(s.get("displayName"), s.get("role"), s.get("client"),
          Term.when(s.get("startedAt")),
          idle < 60 ? idle + "s" : (idle / 60) + "m",
          ApiClient.flag(s.get("isYou")) ? Term.green("this is you") : "");
    }
    table.print();
    Term.out();
    Term.paragraph("A session ends after " + Term.text(reply.get("locksAfterIdleMinutes"))
        + " idle minutes, or when the server is stopped. Several people can be logged in at once, "
        + "which is the point: each one's changes are recorded against their own name.");
  }

  static void logins(Ui ui) {
    List<Map<String, Object>> events = ApiClient.list(ui.api.get("/api/auth/events?limit=60").get("events"));
    Term.heading("The login trail");
    Term.paragraph("Every attempt, successful or not, and every time somebody was asked to "
        + "re-type their password for a critical action.");
    Term.out();
    Term.Table table = new Term.Table("when", "what", "username", "action", "terminal", "detail");
    for (Map<String, Object> e : events) {
      String event = ApiClient.str(e.get("event"));
      String shown = switch (event) {
        case "LOGIN_OK" -> Term.green("logged in");
        case "LOGIN_FAILED" -> Term.red("WRONG PASSWORD");
        case "LOGIN_BLOCKED" -> Term.red("BLOCKED");
        case "LOGIN_INACTIVE" -> Term.red("account off");
        case "CONFIRM_OK" -> Term.green("confirmed");
        case "CONFIRM_FAILED" -> Term.red("CONFIRM FAILED");
        case "PASSWORD_CHANGED", "PASSWORD_RESET" -> Term.yellow(Term.words(event));
        default -> Term.words(event);
      };
      table.row(Term.when(e.get("at")), shown, e.get("username"), e.get("action"),
          e.get("client"), e.get("detail"));
    }
    table.print();
    Term.out();
    Term.paragraph(Term.dim("This trail holds no password material of any kind, not even a hash. "
        + "It cannot be edited or deleted, by anybody."));
  }

  // ================================================================== passwords

  static void changePassword(Ui ui) {
    Term.heading("Changing your own password");
    Term.paragraph("At least 8 characters. Nobody can read your password back, so if you forget "
        + "it the director resets it rather than telling you what it was.");
    String current = ui.prompt.password("Your current password: ");
    String next;
    while (true) {
      next = ui.prompt.password("New password: ");
      String again = ui.prompt.password("Type it again: ");
      if (next.equals(again)) break;
      Term.problem("Those two did not match. Try again.");
    }
    ui.showResult(ui.api.post("/api/auth/password", ApiClient.body()
        .set("currentPassword", current).set("newPassword", next).map()));
    Term.info(Term.dim("Anyone else logged in as you has been signed out. You stay logged in here."));
  }


  private static String shorten(Object value) {
    if (value == null) return Term.dim("(blank)");
    String s = String.valueOf(value);
    if (s.isEmpty()) return Term.dim("(blank)");
    return s.length() <= 40 ? s : s.substring(0, 39) + "...";
  }
}
