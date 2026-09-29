package org.rubyhill.rhrms.security;

/**
 * The three application roles from the build spec (section 5.2 and section 7).
 *
 * The rank exists so "STAFF or above" can be written once instead of listed everywhere.
 * These are NOT the PostgreSQL roles; each of these maps to one PostgreSQL login role
 * (see docs/ROLES_AND_PASSWORDS.md) so the database refuses what the API would refuse.
 */
public enum Role {
  VOLUNTEER(1, "rhrms_volunteer"),
  STAFF(2, "rhrms_staff"),
  DIRECTOR(3, "rhrms_director");

  private final int rank;
  private final String dbRole;

  Role(int rank, String dbRole) { this.rank = rank; this.dbRole = dbRole; }

  public int rank() { return rank; }

  /** The PostgreSQL login role the server uses for a session with this application role. */
  public String dbRole() { return dbRole; }

  public boolean atLeast(Role other) { return this.rank >= other.rank; }

  public static Role of(String name) {
    if (name == null) throw new IllegalArgumentException("No role given.");
    return Role.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
  }
}
