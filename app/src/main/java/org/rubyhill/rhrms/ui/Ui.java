package org.rubyhill.rhrms.ui;

import java.util.List;
import java.util.Map;

/**
 * What every screen needs: the API client, the prompt, and who is logged in.
 *
 * The one thing to be clear about is may(). It hides a menu item somebody cannot use, which is a
 * kindness - nobody should be offered a door they cannot open. It is NOT a security measure, and
 * nothing in this package treats it as one. Every one of those actions is refused again by the
 * API, and again by PostgreSQL, whatever this terminal chooses to draw. See docs/SECURITY.md.
 */
final class Ui {
  final ApiClient api;
  final Prompt prompt;
  private LoginScreen.Session session;

  Ui(ApiClient api, Prompt prompt) {
    this.api = api;
    this.prompt = prompt;
  }

  LoginScreen.Session session() { return session; }
  void setSession(LoginScreen.Session session) { this.session = session; }

  /** Does the server say this person holds the permission? Used only to decide what to show. */
  boolean may(String permission) {
    return session != null && session.may(permission);
  }

  /**
   * The step-up prompt. Returns the typed password, or null when the answer was no - in which
   * case the caller sends nothing at all, so there is no request to refuse.
   *
   * The password is asked for every time, not cached the way sudo does. A front desk where
   * anybody might sit down is not a personal laptop, and the whole point of the re-check is that
   * it happens at the moment of the action.
   */
  String confirm(String whatWillHappen) {
    return prompt.confirmAction(whatWillHappen);
  }

  /** Prints a refusal the way level 2 asks for: the server's own words, and what to do next. */
  void showFailure(ApiClient.Failure e) {
    if (e.wasReported()) return;              // a screen has already put this on the screen
    e.markReported();
    Term.out();
    Term.problem(e.actionDenied() ? "Action denied." : "That did not happen.");
    Term.paragraph(e.getMessage());
    if (e.rule() != null) {
      Term.out();
      Term.info(Term.dim("(database rule " + e.rule().replace("RULE ", "") + ")"));
    }
  }

  /** A confirmation, and any extra note the server sent with it. */
  void showResult(Map<String, Object> reply) {
    String message = ApiClient.str(reply.get("message"));
    Term.out();
    if (message != null) {
      Term.out("  " + Term.green("Done."));
      Term.paragraph(message);
    } else {
      Term.good("Done.");
    }
    List<String> warnings = ApiClient.strings(reply.get("warnings"));
    for (String warning : warnings) {
      Term.out();
      Term.warn(warning);
    }
  }

  /** A label / value line, wrapped, for the record screens. */
  static void field(String label, Object value) {
    Term.paragraph(Term.dim(Term.pad(label, 22)), Term.text(value));
  }

  static void fieldIf(String label, Object value) {
    if (value != null && !String.valueOf(value).isBlank()) field(label, value);
  }
}
