package org.rubyhill.rhrms.ui;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The last thing between a Java failure and the person at the front desk.
 *
 * A volunteer should never see a stack trace. It tells them nothing they can act on, it looks
 * like the system has broken in some unrecoverable way, and it buries the one fact that actually
 * matters: whether anything was half-saved. (It never is - every change is one transaction on the
 * server, so it either happened or it did not.)
 *
 * The detail is not thrown away. It goes to a log file, and the message says where, so whoever
 * looks after the computer has something to work with.
 *
 * Note this handles Throwable, not Exception. The failure that prompted this class was a
 * NoClassDefFoundError - an Error, not an Exception - raised because the program was rebuilt
 * while a terminal was still running. Catching only Exception let it straight through to the JVM,
 * which printed the trace.
 */
final class Crash {
  private Crash() {}

  /**
   * Loads this class while the program files are still intact.
   *
   * The failure it exists to report - the jar being replaced underneath a running terminal -
   * is precisely the failure that would stop this class from loading at the moment it is
   * wanted. Touching it at start-up means it is already in memory by then.
   */
  static void warmUp() {
    // Referencing the constant is enough to force the class to load and initialise.
    assert GUIDANCE_LOADED;
  }

  private static final boolean GUIDANCE_LOADED = true;

  /**
   * Reports a failure in words, writes the detail to a log, and says whether carrying on is
   * sensible.
   *
   * @return true when this terminal should close rather than keep going
   */
  static boolean report(Throwable failure, String whileDoing) {
    Term.out();
    Term.problem("Something went wrong inside this terminal.");
    Term.out();

    boolean fatal = true;
    if (isProgramChanged(failure)) {
      // By far the most likely cause, and entirely harmless: somebody rebuilt or updated RHRMS
      // while this window was open, so a part of the program it had not loaded yet disappeared.
      Term.paragraph("The program files changed while this terminal was running. That normally "
          + "means RHRMS was rebuilt or updated by somebody else on this computer.");
      Term.out();
      Term.paragraph("Close this window and start it again with scripts/rhrms.sh. "
          + "Everything already saved is fine.");
    } else if (failure instanceof OutOfMemoryError) {
      Term.paragraph("This terminal ran out of memory. Close it and start it again. If it keeps "
          + "happening, tell whoever looks after this computer: something is asking for more "
          + "records than a front desk should need at once.");
    } else if (failure instanceof StackOverflowError) {
      Term.paragraph("This terminal got stuck in a loop and stopped itself. Close it and start "
          + "it again, and tell whoever looks after this computer what you were doing.");
    } else {
      Term.paragraph("It happened while " + whileDoing + ".");
      Term.out();
      Term.paragraph(describe(failure));
      Term.out();
      Term.paragraph("Tell whoever looks after this computer.");
      fatal = failure instanceof Error;      // an ordinary bug need not close the terminal
    }

    Term.out();
    Term.paragraph(Term.bold("Nothing half-finished was saved.") + " Every change is one step on "
        + "the server: it either happened completely, or not at all.");

    Path log = writeLog(failure, whileDoing);
    if (log != null) {
      Term.out();
      Term.info(Term.dim("The technical detail is in " + log));
    }
    return fatal;
  }

  /**
   * A missing or mismatched class, which in practice means the jar was replaced underneath a
   * running terminal rather than anything being wrong with the code.
   */
  private static boolean isProgramChanged(Throwable failure) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (t instanceof LinkageError || t instanceof ClassNotFoundException) return true;
      if (t == t.getCause()) break;                       // a cycle; stop walking
    }
    return false;
  }

  /** One readable line: the kind of failure and whatever it had to say for itself. */
  private static String describe(Throwable failure) {
    String kind = failure.getClass().getSimpleName()
        .replaceAll("([a-z])([A-Z])", "$1 $2")
        .toLowerCase(java.util.Locale.ROOT);
    String message = failure.getMessage();
    return message == null || message.isBlank()
        ? "The failure was: " + kind + ", with no further detail."
        : "The failure was: " + kind + " - " + message;
  }

  /**
   * Appends the full trace to rhrms-terminal.log beside the program, falling back to the current
   * directory. Returns where it went, or null when it could not be written anywhere - in which
   * case the message above is all anyone gets, which is still better than a trace on screen.
   */
  private static Path writeLog(Throwable failure, String whileDoing) {
    StringWriter trace = new StringWriter();
    try (PrintWriter out = new PrintWriter(trace)) {
      out.println("---- " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
          + "  while " + whileDoing);
      failure.printStackTrace(out);
    }
    for (Path candidate : new Path[] {beside(), Path.of("rhrms-terminal.log")}) {
      if (candidate == null) continue;
      try {
        Files.writeString(candidate, trace.toString(), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return candidate.toAbsolutePath();
      } catch (Exception ignored) {
        // Try the next place. A terminal that cannot write its log still has to report clearly.
      }
    }
    return null;
  }

  /** The directory the jar is in, which is where the server's log lives too. */
  private static Path beside() {
    try {
      Path jar = Path.of(Crash.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      Path directory = Files.isDirectory(jar) ? jar : jar.getParent();
      return directory == null ? null : directory.resolve("rhrms-terminal.log");
    } catch (Exception | NoClassDefFoundError e) {
      return null;                            // no code source, or the classpath is already broken
    }
  }
}
