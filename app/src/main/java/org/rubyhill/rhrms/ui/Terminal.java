package org.rubyhill.rhrms.ui;

/**
 * Ties the login screen to the command loop, and decides what happens when a session ends.
 *
 * Logging out returns to the login screen rather than closing the window, because the front desk
 * has one computer and several people: Diane finishes, Sam sits down, and Sam's changes have to be
 * recorded as Sam's. Making that the easy path is the only thing stopping a shared account.
 */
public final class Terminal {
  private final ApiClient api;
  private final Prompt prompt = new Prompt();

  public Terminal(String baseUrl) {
    this.api = new ApiClient(baseUrl, "rhrms-term");
  }

  /** Returns the process exit code: 0 normally, 2 when the server could not be reached. */
  public int run(String suggestedUsername) {
    Crash.warmUp();            // load the error handler while the program files are still whole
    Ui ui = new Ui(api, prompt);
    LoginScreen login = new LoginScreen(api, prompt);
    String nextUsername = suggestedUsername;

    while (true) {
      LoginScreen.Session session;
      try {
        session = login.show(nextUsername);
      } catch (Prompt.Cancelled | Prompt.EndOfInput e) {
        Term.out();
        Term.info("Goodbye.");
        return 0;
      }
      if (session == null) return 2;

      nextUsername = null;                      // only fill it in the first time
      ui.setSession(session);                   // the login screen already set the token

      Shell.Outcome outcome;
      try {
        outcome = new Shell(ui).run();
      } catch (Throwable e) {
        // Throwable, not RuntimeException. The failure this guards against is a
        // NoClassDefFoundError raised when the program is rebuilt underneath a running terminal,
        // and an Error would walk straight past a RuntimeException catch and out to the JVM,
        // which prints a stack trace at somebody who cannot use one.
        try {
          Crash.report(e, "working in the terminal");
        } catch (Throwable worse) {
          // The classpath is broken badly enough that even the reporter cannot run. Say the one
          // thing that matters, using nothing but what is already loaded.
          System.err.println();
          System.err.println("RHRMS stopped: the program files changed while it was running.");
          System.err.println("Close this window and start it again with scripts/rhrms.sh.");
          System.err.println("Nothing half-finished was saved.");
        }
        return 1;
      }

      switch (outcome) {
        case QUIT -> { return 0; }
        case LOGGED_OUT -> {
          Term.out();
          Term.info("Back to the login screen. The next person should log in as themselves.");
        }
        case SESSION_ENDED -> api.clearToken();   // the shell has already explained why
      }
    }
  }
}
