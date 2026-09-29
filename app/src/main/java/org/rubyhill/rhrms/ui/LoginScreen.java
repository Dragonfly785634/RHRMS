package org.rubyhill.rhrms.ui;

import java.util.List;
import java.util.Map;

/**
 * The login screen: a title and a terminal, and nothing else.
 *
 * Deliberately plain. The client values simplicity above everything (build spec rule 8), and the
 * first thing anybody sees every morning should have exactly two questions on it.
 *
 * Notice what this screen does NOT do:
 *   - it does not know any password. It sends the two words to POST /api/auth/login and shows
 *     whatever comes back.
 *   - a wrong username and a wrong password get the same answer, because that is what the
 *     server sends; this screen could not tell them apart if it wanted to.
 *   - on a brand new installation it offers to create the director's account, because until
 *     somebody does, nobody can log in at all.
 */
final class LoginScreen {
  private final ApiClient api;
  private final Prompt prompt;

  LoginScreen(ApiClient api, Prompt prompt) {
    this.api = api;
    this.prompt = prompt;
  }

  /** The signed-in session, or null when the person gave up. */
  Session show(String suggestedUsername) {
    Term.clear();
    title();

    Map<String, Object> health;
    try {
      health = api.get("/api/health");
    } catch (ApiClient.Failure e) {
      Term.out();
      Term.problem(e.getMessage());
      return null;
    }

    Term.out();
    boolean databaseUp = "up".equals(ApiClient.str(health.get("database")));
    Term.info(Term.dim("server    ") + api.baseUrl()
        + "   " + (databaseUp ? Term.green("online") : Term.red("database not answering")));
    if (!databaseUp) {
      Term.problem(ApiClient.str(health.getOrDefault("message",
          "PostgreSQL is not answering. Nothing can be saved until it is running.")));
      Term.info("Ask whoever set up this computer to start PostgreSQL, then try again.");
      return null;
    }
    Term.info(Term.dim("database  ") + ApiClient.str(health.get("databaseVersion")));

    if (firstRunNeeded()) {
      if (!firstRun()) return null;
    }

    Term.out();
    for (int attempt = 1; attempt <= 3; attempt++) {
      String username = suggestedUsername != null && attempt == 1
          ? suggestedUsername
          : prompt.required("username: ");
      if (suggestedUsername != null && attempt == 1) {
        Term.info(Term.dim("username: ") + username);
      }
      String password = prompt.password("password: ");

      try {
        Map<String, Object> reply = api.post("/api/auth/login", ApiClient.body()
            .set("username", username).set("password", password).map());
        api.setToken(ApiClient.str(reply.get("token")));
        return Session.from(reply);
      } catch (ApiClient.Failure e) {
        Term.out();
        Term.problem(e.getMessage());
        Term.out();
        if (e.status() == 429 || e.status() == 503) return null;   // locked out, or no database
        if (attempt == 3) {
          Term.info("Three tries is enough. Start the terminal again when you are ready.");
          return null;
        }
      }
    }
    return null;
  }

  private void title() {
    Term.out();
    Term.titleBox(List.of(
        Term.bold("R H R M S"),
        "Ruby Hill Rescue Management System",
        Term.dim("front desk terminal")));
  }

  private boolean firstRunNeeded() {
    try {
      return ApiClient.flag(api.get("/api/bootstrap/status").get("needsFirstRun"));
    } catch (ApiClient.Failure e) {
      return false;                 // if we cannot tell, fall through to the normal login
    }
  }

  /**
   * A brand new installation has no accounts, so there is nobody who could create one. This is
   * the one and only way in, and the server allows it only while staff_user is empty.
   */
  private boolean firstRun() {
    Term.heading("First time on this computer");
    Term.paragraph("There are no accounts yet. The first one is the director's, because only a "
        + "director can add the others.");
    Term.out();
    try {
      String username = prompt.withDefault("Username for the director:", "mike");
      String displayName = prompt.required("Their name, as it should appear on records: ");
      while (true) {
        String password = prompt.password("Choose a password (at least 8 characters): ");
        String again = prompt.password("Type it again: ");
        if (!password.equals(again)) {
          Term.problem("Those two did not match. Try again.");
          continue;
        }
        try {
          Map<String, Object> reply = api.post("/api/bootstrap/director", ApiClient.body()
              .set("username", username)
              .set("displayName", displayName)
              .set("password", password).map());
          Term.out();
          Term.good(ApiClient.str(reply.get("message")));
          Term.paragraph("Write that password down somewhere safe. Nobody, including this "
              + "program, can read it back: only a matching guess is recognised.");
          return true;
        } catch (ApiClient.Failure e) {
          Term.problem(e.getMessage());
          if (e.status() != 400) return false;
        }
      }
    } catch (Prompt.Cancelled | Prompt.EndOfInput e) {
      Term.out();
      Term.info("Nothing was created.");
      return false;
    }
  }

  /** Who is logged in, and what the server says they may do. */
  record Session(long userId, String username, String displayName, String role,
                 String databaseRole, Map<String, Object> permissions, int idleMinutes) {

    static Session from(Map<String, Object> reply) {
      Map<String, Object> user = ApiClient.object(reply.get("user"));
      return new Session(
          ApiClient.id(user.get("id")),
          ApiClient.str(user.get("username")),
          ApiClient.str(user.get("displayName")),
          ApiClient.str(user.get("role")),
          ApiClient.str(user.get("databaseRole")),
          ApiClient.object(reply.get("permissions")),
          ApiClient.intOf(reply.get("idleMinutes"), 15));
    }

    boolean may(String permission) {
      return Boolean.TRUE.equals(permissions.get(permission));
    }
  }
}
