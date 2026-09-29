package org.rubyhill.rhrms.util;

import java.util.Locale;

/**
 * Reads a time of day the way somebody actually writes one on a paper form.
 *
 * The TIME IN box on form RH-1 is a blank line, not a set of boxes, so what turns up in it is
 * whatever the person had in their head: "9:15", "0915", "9.15", "9:15 am", "2:30 PM". An earlier
 * version accepted only "HH:MM" on a 24-hour clock and refused the rest, which meant somebody
 * filled in the whole form and was told at the end that "9:15 AM" was not a time.
 *
 * So this accepts all of those and hands back one shape, "HH:MM", for the database. It does NOT
 * guess: "morning" is refused, because a made-up time is worse than an empty box.
 *
 * Lives here rather than in the terminal or the API because both need the same answer. The
 * terminal uses it to refuse a bad time at the box, where it can still be fixed; the API uses it
 * because a different frontend tomorrow must not be able to store something else.
 */
public final class ClockTime {
  private ClockTime() {}

  /** What to tell somebody who typed something unusable. */
  public static final String GUIDANCE =
      "Write the time as HH:MM on a 24-hour clock (09:15, 23:40), or with am/pm (9:15 am). "
          + "Leave it blank if it was not noted.";

  /**
   * Turns whatever was typed into "HH:MM", or null when the box was left blank.
   *
   * @throws IllegalArgumentException with a message worth showing, when it is not a time
   */
  public static String normalise(String raw) {
    if (raw == null) return null;
    String text = raw.trim().toLowerCase(Locale.ROOT);
    if (text.isEmpty()) return null;

    // am / pm, written any of the usual ways, at either end of the digits.
    boolean pm = false;
    boolean am = false;
    if (text.endsWith("am") || text.endsWith("a.m.") || text.endsWith("a.m")) {
      am = true;
      text = text.replaceAll("a\\.?m\\.?$", "").trim();
    } else if (text.endsWith("pm") || text.endsWith("p.m.") || text.endsWith("p.m")) {
      pm = true;
      text = text.replaceAll("p\\.?m\\.?$", "").trim();
    }

    // Separator, or none at all: 9:15, 9.15, 9 15, 0915.
    String digits = text.replaceAll("[:.\\s]", "");
    if (!digits.matches("\\d{1,4}")) {
      throw new IllegalArgumentException("'" + raw.trim() + "' is not a time. " + GUIDANCE);
    }

    int hour;
    int minute;
    if (digits.length() <= 2) {
      hour = Integer.parseInt(digits);          // "9" or "09", meaning nine o'clock
      minute = 0;
    } else {
      // "915" is 9:15, "0915" is 09:15, "1430" is 14:30. The last two digits are the minutes.
      minute = Integer.parseInt(digits.substring(digits.length() - 2));
      hour = Integer.parseInt(digits.substring(0, digits.length() - 2));
    }

    if (pm && hour < 12) hour += 12;
    if (am && hour == 12) hour = 0;             // 12:30 am is half past midnight

    if (hour > 23) {
      throw new IllegalArgumentException("There is no hour " + hour + " in a day. " + GUIDANCE);
    }
    if (minute > 59) {
      throw new IllegalArgumentException("There is no minute " + minute + " in an hour. " + GUIDANCE);
    }
    return String.format("%02d:%02d", hour, minute);
  }
}
