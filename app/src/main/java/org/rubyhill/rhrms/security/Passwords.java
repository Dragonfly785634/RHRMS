package org.rubyhill.rhrms.security;

import org.mindrot.jbcrypt.BCrypt;

/**
 * Password hashing and checking. One place, so nothing anywhere else touches a plaintext
 * password for longer than the few lines it takes to hash or verify it.
 *
 * BCrypt (jBCrypt) is the algorithm named in the build spec, section 3. It salts every hash
 * and is deliberately slow, so a stolen copy of staff_user is not a list of passwords. The
 * cost of 12 is roughly a quarter of a second per check on this machine: unnoticeable to a
 * person typing at a terminal, expensive for someone guessing.
 */
public final class Passwords {
  /** BCrypt work factor. 12 means 2^12 key-expansion rounds. */
  public static final int COST = 12;

  /** Section 7: passwords are at least 8 characters. */
  public static final int MIN_LENGTH = 8;

  private Passwords() {}

  public static String hash(String plaintext) {
    requireAcceptable(plaintext);
    return BCrypt.hashpw(plaintext, BCrypt.gensalt(COST));
  }

  /**
   * True when the password matches the stored hash. Never throws for bad input: an unparsable
   * or empty hash simply does not match, so a damaged row cannot become a way in.
   */
  public static boolean matches(String plaintext, String storedHash) {
    if (plaintext == null || plaintext.isEmpty()) return false;
    if (storedHash == null || storedHash.length() < 4 || !storedHash.startsWith("$2")) return false;
    try {
      return BCrypt.checkpw(plaintext, storedHash);
    } catch (IllegalArgumentException e) {
      return false;                       // malformed hash in the row: treat as "does not match"
    }
  }

  /** Throws IllegalArgumentException with a message the terminal can show as-is. */
  public static void requireAcceptable(String plaintext) {
    if (plaintext == null || plaintext.isEmpty()) {
      throw new IllegalArgumentException("Enter a password.");
    }
    if (plaintext.length() < MIN_LENGTH) {
      throw new IllegalArgumentException("The password must be at least " + MIN_LENGTH + " characters.");
    }
    if (plaintext.trim().isEmpty()) {
      throw new IllegalArgumentException("A password cannot be only spaces.");
    }
    if (plaintext.length() > 72) {
      // BCrypt silently ignores anything past 72 bytes, which would make a long password
      // weaker than it looks. Refuse instead of quietly truncating.
      throw new IllegalArgumentException("The password must be 72 characters or fewer.");
    }
  }
}
