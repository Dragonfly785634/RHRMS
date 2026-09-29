package org.rubyhill.rhrms.ui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Asking the person at the keyboard for something.
 *
 * Two things here are worth knowing about:
 *
 *  - Passwords are read without echo through System.console().readPassword(), and the char[] is
 *    blanked as soon as the String is built, so the password is not left lying around in memory
 *    any longer than it has to be. When there is no real terminal (input is piped from a test
 *    script) there is nothing to hide the typing from, so it falls back to reading a line and
 *    says so once.
 *
 *  - Every prompt understands a bare Enter as "no answer", and a leading DOT as a system
 *    command rather than an answer: .q .quit .e .exit all leave the form having saved nothing,
 *    and .help lists them. The dot is what makes this safe - somebody halfway through an intake
 *    form has to be able to get out without inventing values, and "exit" is a perfectly ordinary
 *    thing to write in a notes box.
 */
public final class Prompt {
  /** Thrown when the person types .q (or .quit, .e, .exit). Nothing is sent to the server. */
  public static final class Cancelled extends RuntimeException {
    private static final long serialVersionUID = 1L;
    Cancelled() { super("Left without saving."); }
  }

  /** Thrown when input runs out: end of a piped script, or Ctrl-D. */
  public static final class EndOfInput extends RuntimeException {
    private static final long serialVersionUID = 1L;
    EndOfInput() { super("No more input."); }
  }

  private final BufferedReader reader =
      new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
  private boolean warnedAboutEcho;

  /** True when this is a real terminal, so passwords can be hidden. */
  public boolean isInteractive() { return System.console() != null; }

  /**
   * Reads one line from wherever input is coming from.
   *
   * When there is a real terminal this goes through System.console(), the SAME object that
   * readPassword() uses. That matters more than it looks: a BufferedReader over System.in reads
   * ahead, so mixing the two means the reader can swallow the line the password prompt was about
   * to ask for. It only shows up when input arrives faster than a person types - a pasted block,
   * or a test script driving a terminal - and then the password prompt silently gets nothing.
   */
  private String readRaw() {
    var console = System.console();
    if (console != null) return console.readLine();
    try {
      return reader.readLine();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // --------------------------------------------------------------------- text

  /**
   * One line, or null when the person just pressed Enter.
   *
   * A leading dot marks a SYSTEM COMMAND rather than an answer. That is the whole point of the
   * dot: half way through an intake form, "exit" is a plausible thing to write in a notes box,
   * and ".exit" is not. So anything starting with a dot is never stored as a field value - it is
   * either acted on, or refused with the list of what the dots mean.
   */
  public String line(String question) {
    while (true) {
      System.out.print("  " + question);
      System.out.flush();
      String raw = readRaw();
      if (raw == null) throw new EndOfInput();
      String value = raw.trim();

      if (value.startsWith(".")) {
        systemCommand(value);      // throws Cancelled to leave, or returns to ask again
        continue;
      }
      return value.isEmpty() ? null : value;
    }
  }

  /**
   * Acts on a dot command. Leaving throws Cancelled, which every screen already catches and
   * reports as "Cancelled. Nothing was saved." Anything else prints the list and comes back.
   */
  private void systemCommand(String typed) {
    String command = typed.toLowerCase(Locale.ROOT);
    switch (command) {
      case ".q", ".quit", ".e", ".exit" -> throw new Cancelled();
      case ".h", ".help", ".?" -> {
        Term.out();
        Term.info(Term.bold("System commands") + Term.dim("  (a leading dot is never stored as an answer)"));
        Term.out("    " + Term.pad(".q  .quit  .e  .exit", 24) + "leave this form; nothing is saved");
        Term.out("    " + Term.pad(".h  .help  .?", 24) + "this list");
        Term.out();
        Term.info(Term.dim("To answer a question, just type the answer. Press Enter to leave it blank."));
      }
      default -> {
        Term.out();
        Term.problem("'" + typed + "' is not a system command, and a leading dot is never taken "
            + "as an answer.");
        Term.info("Use " + Term.bold(".q") + " to leave without saving, or " + Term.bold(".help")
            + " for the list.");
        Term.info(Term.dim("If you really meant to write that, leave the dot off."));
        Term.out();
      }
    }
  }

  /** Keeps asking until there is an answer. */
  public String required(String question) {
    while (true) {
      String v = line(question);
      if (v != null) return v;
      Term.problem("That one is required. Type it, or .q to leave without saving.");
    }
  }

  /** Keeps asking until the answer is long enough; used for rationales and reasons. */
  public String required(String question, int minLength, String why) {
    while (true) {
      String v = line(question);
      if (v == null) {
        Term.problem("That one is required. Type it, or .q to leave without saving.");
      } else if (v.length() < minLength) {
        Term.problem("Needs at least " + minLength + " characters; you typed " + v.length() + ".");
        Term.paragraph("  " + why);
      } else {
        return v;
      }
    }
  }

  /** One line with a default if Enter is pressed. */
  public String withDefault(String question, String fallback) {
    String v = line(question + " [" + fallback + "] ");
    return v == null ? fallback : v;
  }

  // ------------------------------------------------------------------ numbers

  public Long optionalId(String question) {
    while (true) {
      String v = line(question);
      if (v == null) return null;
      try {
        return Long.parseLong(v);
      } catch (NumberFormatException e) {
        Term.problem("That should be a number. Try again, or press Enter to skip.");
      }
    }
  }

  public long requiredId(String question) {
    while (true) {
      Long v = optionalId(question);
      if (v != null) return v;
      Term.problem("A number is required here.");
    }
  }

  public Integer optionalInt(String question) {
    Long v = optionalId(question);
    return v == null ? null : v.intValue();
  }

  public int requiredInt(String question) {
    return (int) requiredId(question);
  }

  public String optionalNumber(String question) {
    while (true) {
      String v = line(question);
      if (v == null) return null;
      String cleaned = v.replace("$", "").replace(",", "").trim();
      try {
        new java.math.BigDecimal(cleaned);
        return cleaned;
      } catch (NumberFormatException e) {
        Term.problem("That should be a number, for example 2 or 1.75. Try again, or press Enter to skip.");
      }
    }
  }

  // ------------------------------------------------------------------ yes / no

  /** Yes or no, with a default. Anything unrecognised is asked again rather than guessed. */
  public boolean yesNo(String question, boolean fallback) {
    String hint = fallback ? " [Y/n] " : " [y/N] ";
    while (true) {
      String v = line(question + hint);
      if (v == null) return fallback;
      switch (v.toLowerCase(Locale.ROOT)) {
        case "y", "yes" -> { return true; }
        case "n", "no" -> { return false; }
        default -> Term.problem("Answer y or n.");
      }
    }
  }

  /** Yes or no with no default: both have consequences, so it must be typed. */
  public boolean yesNo(String question) {
    while (true) {
      String v = line(question + " [y/n] ");
      if (v != null) {
        switch (v.toLowerCase(Locale.ROOT)) {
          case "y", "yes" -> { return true; }
          case "n", "no" -> { return false; }
          default -> { }
        }
      }
      Term.problem("Answer y or n. There is no default for this one.");
    }
  }

  /** Yes, no, or Enter for "not recorded". Some answers on the paper form are genuinely blank. */
  public Boolean yesNoUnknown(String question) {
    while (true) {
      String v = line(question + " [y/n, Enter to leave blank] ");
      if (v == null) return null;
      switch (v.toLowerCase(Locale.ROOT)) {
        case "y", "yes" -> { return Boolean.TRUE; }
        case "n", "no" -> { return Boolean.FALSE; }
        default -> Term.problem("Answer y, n, or press Enter to leave it blank.");
      }
    }
  }

  // ------------------------------------------------------------------- choices

  /** A numbered menu. Returns the chosen value, or the default when Enter is pressed. */
  public String choose(String question, List<String> options, String fallback) {
    Term.out();
    for (int i = 0; i < options.size(); i++) {
      Term.out("    " + (i + 1) + ") " + Term.words(options.get(i)));
    }
    while (true) {
      // A question that already ends in a space is a ruled form field ("TYPE ....... "), which
      // does not want a colon stuck on the end of its dots.
      String suffix = fallback != null ? " [" + Term.words(fallback) + "] "
                    : question.endsWith(" ") ? ""
                    : ": ";
      String v = line(question + suffix);
      if (v == null) {
        if (fallback != null) return fallback;
        Term.problem("Pick one of the numbers above.");
        continue;
      }
      try {
        int n = Integer.parseInt(v);
        if (n >= 1 && n <= options.size()) return options.get(n - 1);
      } catch (NumberFormatException ignored) {
        // Typing the word itself works too, which is faster once somebody knows the list.
        for (String option : options) {
          if (option.equalsIgnoreCase(v) || Term.words(option).equalsIgnoreCase(v)) return option;
        }
      }
      Term.problem("Pick 1 to " + options.size() + ", or type the words.");
    }
  }

  /**
   * A time of day, checked HERE rather than when the form is sent.
   *
   * That distinction is the whole point. A time is one box on a long form, and finding out it was
   * unreadable only after the notes box has been typed means the form is thrown away for the sake
   * of two characters.
   */
  public String time(String question) {
    while (true) {
      String v = line(question);
      if (v == null) return null;
      try {
        return org.rubyhill.rhrms.util.ClockTime.normalise(v);
      } catch (IllegalArgumentException e) {
        Term.problem(e.getMessage());
      }
    }
  }

  /** A date as YYYY-MM-DD, with today as the default. */
  public String date(String question, boolean todayByDefault) {
    while (true) {
      String v = line(question + (todayByDefault ? " [today] " : " (YYYY-MM-DD) "));
      if (v == null) {
        if (todayByDefault) return java.time.LocalDate.now().toString();
        return null;
      }
      if (v.equalsIgnoreCase("today")) return java.time.LocalDate.now().toString();
      try {
        return java.time.LocalDate.parse(v).toString();
      } catch (java.time.format.DateTimeParseException e) {
        Term.problem("Write the date as YYYY-MM-DD, for example 2026-09-27.");
      }
    }
  }

  // ----------------------------------------------------------------- passwords

  /**
   * Reads a password without showing it. This is the UNIX habit the client asked for: the same
   * prompt for logging in and for confirming something dangerous.
   *
   * Dot commands are deliberately NOT honoured here: a password is allowed to start with a dot,
   * and silently treating somebody's password as a command would be worse than useless. There is
   * always an "Are you sure?" before a password prompt, and .q works there.
   */
  public String password(String question) {
    var console = System.console();
    if (console == null) {
      if (!warnedAboutEcho) {
        Term.warn("(no terminal attached, so what you type will be visible)");
        warnedAboutEcho = true;
      }
      String v = line(question);
      return v == null ? "" : v;
    }
    System.out.print("  " + question);
    System.out.flush();
    char[] chars = console.readPassword();
    if (chars == null) throw new EndOfInput();
    String value = new String(chars);
    java.util.Arrays.fill(chars, '\0');            // do not leave it in memory
    return value;
  }

  /**
   * The step-up prompt: "are you sure", and then the password. Exactly the shape the client
   * described. Returns null when the answer is no, so the caller sends nothing at all.
   */
  public String confirmAction(String whatWillHappen) {
    Term.out();
    Term.warn(whatWillHappen);
    if (!yesNo("  Are you sure?")) {
      return null;
    }
    return password("Your password: ");
  }
}
