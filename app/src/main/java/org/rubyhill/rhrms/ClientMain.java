package org.rubyhill.rhrms;

import org.rubyhill.rhrms.config.AppConfig;
import org.rubyhill.rhrms.ui.Terminal;

/**
 * The RHRMS terminal.
 *
 *   scripts/rhrms.sh                      log in and work
 *   scripts/rhrms.sh --user diane         fill the username in
 *   scripts/rhrms.sh --url http://...     a different server
 *
 * Run it once per person. Each window logs in separately, so every change carries the name of
 * whoever actually made it - which is the whole point of the audit trail the director asked for.
 *
 * Everything this program can do, it does through the API in ServerMain. There is no database
 * password in here, no rule, and no permission check that matters. Replace this whole package
 * with a web page and nothing about how the rescue's data is protected changes.
 */
public final class ClientMain {
  public static void main(String[] args) {
    AppConfig config;
    try {
      config = AppConfig.load();
    } catch (RuntimeException e) {
      System.err.println("RHRMS cannot start: " + e.getMessage());
      System.exit(2);
      return;
    }

    String url = config.clientBaseUrl();
    String username = null;

    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--url" -> {
          if (++i >= args.length) { usage("--url needs an address, for example http://127.0.0.1:8642"); return; }
          url = args[i];
        }
        case "--user", "-u" -> {
          if (++i >= args.length) { usage("--user needs a username"); return; }
          username = args[i];
        }
        case "--help", "-h" -> { usage(null); return; }
        default -> { usage("I do not understand '" + args[i] + "'."); return; }
      }
    }

    // The outermost net. Nothing below here is allowed to reach the JVM's default handler,
    // which would print a stack trace at whoever is standing at the front desk.
    int exitCode;
    try {
      exitCode = new Terminal(url).run(username);
    } catch (Throwable e) {
      System.err.println();
      System.err.println("RHRMS could not start, or stopped unexpectedly.");
      System.err.println("  " + e.getClass().getSimpleName()
          + (e.getMessage() == null ? "" : ": " + e.getMessage()));
      System.err.println("Nothing half-finished was saved. Start the terminal again with "
          + "scripts/rhrms.sh; if it keeps happening, tell whoever looks after this computer.");
      exitCode = 1;
    }
    System.exit(exitCode);
  }

  private static void usage(String problem) {
    if (problem != null) System.err.println(problem);
    System.err.println("""
        RHRMS terminal

          scripts/rhrms.sh                    log in and work
          scripts/rhrms.sh --user diane       fill the username in for me
          scripts/rhrms.sh --url ADDRESS      talk to a server somewhere else

        The server has to be running first: scripts/start_server.sh""");
    System.exit(problem == null ? 0 : 2);
  }
}
