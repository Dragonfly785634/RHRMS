package org.rubyhill.rhrms.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Drawing on a terminal: colour, rules, boxes and tables.
 *
 * Colour is used sparingly and never as the only signal. The build spec asks for restrictions
 * and low food "in red", and red is what you get - but the word "CONFLICT" or "REORDER NOW" is
 * printed too, because a terminal that does not do colour, or a person who cannot see it, must
 * still get the whole message. Set NO_COLOR (or RHRMS_NO_COLOR) to turn styling off entirely.
 */
public final class Term {
  /** The ASCII escape character that starts every styling code. */
  private static final char ESC = 27;
  private static final String RESET = ESC + "[0m";

  private static final boolean COLOR = colorWanted();

  /** Terminal width. COLUMNS if the shell exported it, otherwise a safe 80. */
  public static final int WIDTH = detectWidth();

  private Term() {}

  private static boolean colorWanted() {
    if (System.getenv("NO_COLOR") != null || System.getenv("RHRMS_NO_COLOR") != null) return false;
    String term = System.getenv("TERM");
    if (term != null && term.equalsIgnoreCase("dumb")) return false;
    // Piped output (a test script, a log file) gets plain text: escape codes there are noise.
    return System.console() != null;
  }

  private static int detectWidth() {
    String cols = System.getenv("COLUMNS");
    if (cols != null) {
      try {
        int n = Integer.parseInt(cols.trim());
        if (n >= 60 && n <= 200) return n;
      } catch (NumberFormatException ignored) { /* fall through to the default */ }
    }
    return 80;
  }

  private static String wrap(String code, String text) {
    return COLOR ? ESC + "[" + code + "m" + text + RESET : text;
  }

  public static String bold(String s)    { return wrap("1", s); }
  public static String dim(String s)     { return wrap("2", s); }
  public static String red(String s)     { return wrap("31;1", s); }
  public static String green(String s)   { return wrap("32", s); }
  public static String yellow(String s)  { return wrap("33", s); }
  public static String blue(String s)    { return wrap("36", s); }
  /** Reversed video. Used for the section bars on form RH-1, which are printed as grey strips. */
  public static String inverse(String s) { return wrap("7", s); }

  public static void out(String s) { System.out.println(s); }
  public static void out() { System.out.println(); }

  public static void clear() {
    if (COLOR) {
      System.out.print(ESC + "[2J" + ESC + "[H");
      System.out.flush();
    }
  }

  /** A heading with a rule under it. */
  public static void heading(String text) {
    out();
    out(bold(text));
    out(dim("-".repeat(Math.min(WIDTH, Math.max(text.length(), 20)))));
  }

  public static void rule() { out(dim("-".repeat(WIDTH))); }

  /** The title box on the login screen. */
  public static void titleBox(List<String> lines) {
    int inner = Math.min(WIDTH - 2, 66);
    out(bold("+" + "-".repeat(inner) + "+"));
    out(bold("|" + " ".repeat(inner) + "|"));
    for (String line : lines) {
      out(bold("|") + "  " + pad(line, inner - 2) + bold("|"));
    }
    out(bold("|" + " ".repeat(inner) + "|"));
    out(bold("+" + "-".repeat(inner) + "+"));
  }

  /** A notice box. Used for the big red low-food warning the client asked for (section 8). */
  public static void alertBox(String title, List<String> lines) {
    int inner = Math.min(WIDTH - 2, 74);
    out();
    out(red("+" + "-".repeat(inner) + "+"));
    out(red("|  " + pad(title, inner - 3) + "|"));
    out(red("+" + "-".repeat(inner) + "+"));
    for (String line : lines) {
      for (String piece : softWrap(line, inner - 4)) {
        out(red("|") + "  " + pad(piece, inner - 3) + red("|"));
      }
    }
    out(red("+" + "-".repeat(inner) + "+"));
  }

  public static void info(String s)    { out("  " + s); }
  public static void good(String s)    { out("  " + green(s)); }
  public static void warn(String s)    { out("  " + yellow(s)); }
  public static void problem(String s) { out("  " + red(s)); }

  /** A wrapped paragraph, indented, so a long message from the server stays readable. */
  public static void paragraph(String text) {
    for (String line : softWrap(text, WIDTH - 4)) out("  " + line);
  }

  public static void paragraph(String prefix, String text) {
    List<String> lines = softWrap(text, WIDTH - 4 - prefix.length());
    for (int i = 0; i < lines.size(); i++) {
      out("  " + (i == 0 ? prefix : " ".repeat(prefix.length())) + lines.get(i));
    }
  }

  /** Breaks text at spaces to fit a width. Only splits a word that is longer than the line. */
  public static List<String> softWrap(String text, int width) {
    List<String> out = new ArrayList<>();
    if (text == null || text.isEmpty()) { out.add(""); return out; }
    int w = Math.max(20, width);
    for (String paragraph : text.split("\n", -1)) {
      StringBuilder line = new StringBuilder();
      for (String word : paragraph.split(" ")) {
        while (word.length() > w) {                 // an id or a path with no spaces in it
          if (line.length() > 0) { out.add(line.toString()); line.setLength(0); }
          out.add(word.substring(0, w));
          word = word.substring(w);
        }
        if (line.length() == 0) {
          line.append(word);
        } else if (line.length() + 1 + word.length() <= w) {
          line.append(' ').append(word);
        } else {
          out.add(line.toString());
          line.setLength(0);
          line.append(word);
        }
      }
      out.add(line.toString());
    }
    return out;
  }

  // -------------------------------------------------------------------- tables

  /**
   * A simple column table. Columns are sized to their content, and the widest one is shrunk
   * until the row fits, so nothing runs off the right-hand side of an 80-column window.
   */
  public static final class Table {
    private final List<String> headers = new ArrayList<>();
    private final List<List<String>> rows = new ArrayList<>();
    private final List<Boolean> rightAlign = new ArrayList<>();

    public Table(String... columnHeaders) {
      for (String h : columnHeaders) {
        headers.add(h);
        rightAlign.add(false);
      }
    }

    /** Marks a column as right-aligned, for numbers. */
    public Table right(int... columns) {
      for (int c : columns) rightAlign.set(c, true);
      return this;
    }

    public Table row(Object... cells) {
      List<String> r = new ArrayList<>();
      for (int i = 0; i < headers.size(); i++) {
        r.add(i < cells.length ? text(cells[i]) : "");
      }
      rows.add(r);
      return this;
    }

    public boolean isEmpty() { return rows.isEmpty(); }
    public int size() { return rows.size(); }

    public void print() { print("  "); }

    public void print(String indent) {
      if (rows.isEmpty()) {
        out(indent + dim("(nothing to show)"));
        return;
      }
      int n = headers.size();
      int[] widths = new int[n];
      for (int i = 0; i < n; i++) widths[i] = visibleLength(headers.get(i));
      for (List<String> r : rows) {
        for (int i = 0; i < n; i++) widths[i] = Math.max(widths[i], visibleLength(r.get(i)));
      }
      int available = WIDTH - indent.length() - (n - 1) * 2;
      int total = 0;
      for (int w : widths) total += w;
      while (total > available) {
        int widest = 0;
        for (int i = 1; i < n; i++) if (widths[i] > widths[widest]) widest = i;
        if (widths[widest] <= 8) break;
        widths[widest]--;
        total--;
      }

      StringBuilder head = new StringBuilder(indent);
      for (int i = 0; i < n; i++) {
        head.append(pad(headers.get(i), widths[i]));
        if (i < n - 1) head.append("  ");
      }
      out(bold(head.toString().stripTrailing()));

      StringBuilder underline = new StringBuilder(indent);
      for (int i = 0; i < n; i++) {
        underline.append("-".repeat(widths[i]));
        if (i < n - 1) underline.append("  ");
      }
      out(dim(underline.toString()));

      for (List<String> r : rows) {
        StringBuilder line = new StringBuilder(indent);
        for (int i = 0; i < n; i++) {
          String cell = truncate(r.get(i), widths[i]);
          line.append(rightAlign.get(i) ? leftPad(cell, widths[i]) : pad(cell, widths[i]));
          if (i < n - 1) line.append("  ");
        }
        out(line.toString().stripTrailing());
      }
    }
  }

  // ------------------------------------------------------------------ padding
  // Styled text carries invisible escape codes, so padding has to measure what a person sees.

  public static int visibleLength(String s) {
    if (s == null) return 0;
    return s.replaceAll(ESC + "\\[[0-9;]*m", "").length();
  }

  public static String pad(String s, int width) {
    if (s == null) s = "";
    int len = visibleLength(s);
    return len >= width ? s : s + " ".repeat(width - len);
  }

  public static String leftPad(String s, int width) {
    if (s == null) s = "";
    int len = visibleLength(s);
    return len >= width ? s : " ".repeat(width - len) + s;
  }

  public static String truncate(String s, int width) {
    if (s == null) return "";
    if (visibleLength(s) <= width) return s;
    if (visibleLength(s) != s.length()) return s;     // styled: leave it rather than cut a code
    return width <= 1 ? s.substring(0, width) : s.substring(0, width - 1) + ".";
  }

  /** Turns anything into display text, with a dash for nothing at all. */
  public static String text(Object v) {
    if (v == null) return dim("-");
    if (v instanceof Boolean b) return b ? "yes" : "no";
    String s = String.valueOf(v);
    return s.isEmpty() ? dim("-") : s;
  }

  /** Enum-ish values read better as words: NOT_ADOPTABLE becomes "not adoptable". */
  public static String words(Object v) {
    if (v == null) return dim("-");
    return String.valueOf(v).toLowerCase(Locale.ROOT).replace('_', ' ');
  }

  /** Colours a status the way the screens in section 8 do. */
  public static String status(Object v) {
    String s = v == null ? "" : String.valueOf(v);
    return switch (s) {
      case "AVAILABLE", "OK", "VETTED", "APPROVE", "APPROVED", "RECEIVED", "PLACED" -> green(words(s));
      case "PENDING_ADOPTION", "UNDER_REVIEW", "SUBMITTED", "REORDER SOON", "UNSURE",
           "ORDERED", "REQUESTED", "ON ORDER" -> yellow(words(s));
      case "NOT_ADOPTABLE", "DECEASED", "DENIED", "DENY", "REORDER NOW", "NOT_VETTED" -> red(words(s));
      case "" -> dim("-");
      default -> words(s);
    };
  }

  /** A date-time from the API (an ISO instant) shown in this machine's own time. */
  public static String when(Object isoInstant) {
    if (isoInstant == null) return dim("-");
    return org.rubyhill.rhrms.json.Json.localTime(String.valueOf(isoInstant));
  }

  public static String money(Object v) {
    return v == null ? dim("-") : "$" + v;
  }
}
