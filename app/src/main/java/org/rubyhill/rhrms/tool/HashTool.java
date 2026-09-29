package org.rubyhill.rhrms.tool;

import org.rubyhill.rhrms.security.Passwords;

/**
 * Prints a BCrypt hash for a password, so setup scripts never have to embed a plaintext
 * password in SQL:  java -cp ... org.rubyhill.rhrms.tool.HashTool 'Director#2026'
 *
 * With no argument it reads one line from standard input, which keeps the password out of
 * the process list and the shell history.
 */
public final class HashTool {
  public static void main(String[] args) throws Exception {
    String pw;
    if (args.length == 1) {
      pw = args[0];
    } else if (args.length == 0) {
      pw = new java.io.BufferedReader(new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8)).readLine();
    } else {
      System.err.println("usage: HashTool [password]     (or pipe the password on stdin)");
      System.exit(2);
      return;
    }
    try {
      System.out.println(Passwords.hash(pw));
    } catch (IllegalArgumentException e) {
      System.err.println(e.getMessage());
      System.exit(1);
    }
  }
}
