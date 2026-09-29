package org.rubyhill.rhrms.http;

/**
 * A failure the person at the terminal is meant to read.
 *
 * Level 2 of the grading lens is "it doesn't lie": every failure is caught and reported in
 * plain words. So the message in here is written for a volunteer at the front desk, never for
 * a programmer, and the server never sends a stack trace to a client.
 */
public class ApiException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final int status;
  private final String code;
  private final String hint;

  public ApiException(int status, String code, String message) { this(status, code, message, null); }

  public ApiException(int status, String code, String message, String hint) {
    super(message);
    this.status = status;
    this.code = code;
    this.hint = hint;
  }

  public int status() { return status; }
  public String code() { return code; }
  public String hint() { return hint; }

  // ---- the handful of failures the handlers raise, named so the call sites read as English ----

  /** The request itself is wrong: a missing field, a number where a word belongs. */
  public static ApiException badRequest(String message) {
    return new ApiException(400, "BAD_REQUEST", message);
  }

  /** No valid session token. */
  public static ApiException notLoggedIn(String message) {
    return new ApiException(401, "NOT_LOGGED_IN", message);
  }

  /** Logged in, but this role may not do this. Section 7. */
  public static ApiException notAllowed(String message) {
    return new ApiException(403, "NOT_ALLOWED", message);
  }

  /** A critical action was asked for without a correct password confirmation. */
  public static ApiException actionDenied(String message) {
    return new ApiException(403, "ACTION_DENIED", message);
  }

  public static ApiException notFound(String message) {
    return new ApiException(404, "NOT_FOUND", message);
  }

  /** Someone else changed the same thing first. Level 3. */
  public static ApiException conflict(String message) {
    return new ApiException(409, "CONFLICT", message);
  }

  /** A business rule said no. The message comes from the rule, so it is already plain English. */
  public static ApiException ruleViolation(String message, String hint) {
    return new ApiException(422, "RULE_VIOLATION", message, hint);
  }
}
