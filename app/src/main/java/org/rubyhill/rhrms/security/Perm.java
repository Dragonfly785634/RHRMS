package org.rubyhill.rhrms.security;

import java.util.Locale;

/**
 * One entry per row of the permission table in the build spec, section 7.
 *
 * The lowest role that may do it is written next to it, so the table in the spec and the
 * table in the code can be read side by side. DECIDE and the three override permissions are
 * DIRECTOR only; DECIDE is additionally narrowed by the database setting
 * decision.allowed_roles, which the director can change without a new build.
 */
public enum Perm {
  /** Look at anything except user administration. */
  VIEW(Role.VOLUNTEER, "look at records"),

  INTAKE(Role.VOLUNTEER, "record an intake"),
  APPLICATION_CREATE(Role.VOLUNTEER, "enter an adoption application"),
  PERSON_CREATE(Role.VOLUNTEER, "add a person"),
  HOME_VISIT_CREATE(Role.VOLUNTEER, "record a home visit"),
  FOOD_OPEN_RECEIVE(Role.VOLUNTEER, "open or receive a bag of food"),
  FOOD_ORDER_REQUEST(Role.VOLUNTEER, "flag food to be ordered"),

  RECORD_CORRECT(Role.STAFF, "correct a record"),
  KENNEL_MOVE(Role.STAFF, "move an animal to another kennel"),
  KENNEL_SERVICE(Role.STAFF, "take a kennel in or out of service"),
  RESTRICTION_EDIT(Role.STAFF, "add or withdraw an animal's restrictions"),
  BOND_EDIT(Role.STAFF, "link or unlink bonded animals"),
  ANIMAL_STATUS(Role.STAFF, "change an animal's status"),
  APPLICATION_CLOSE(Role.STAFF, "withdraw or close an application"),
  PLACEMENT(Role.STAFF, "record an adoption"),
  RETURN(Role.STAFF, "record a returned animal"),
  FOOD_ORDER_MANAGE(Role.STAFF, "mark food ordered or received"),
  FOOD_PRODUCT_EDIT(Role.STAFF, "add or change a food product"),
  DIET_EDIT(Role.STAFF, "set an animal's food portions"),
  PRESCRIPTION_EDIT(Role.STAFF, "record a food prescription"),
  STOCK_ADJUST(Role.STAFF, "write off food or correct a stock count"),
  DONATION(Role.STAFF, "record a donation"),
  VET_VISIT(Role.STAFF, "record a vet visit"),

  /*
   * The FLOOR for deciding, not the answer. Interview 3 says the director makes the final call,
   * and open question Q4 asks whether anybody may stand in when he is away. The answer lives in
   * the database setting decision.allowed_roles, which defaults to DIRECTOR alone; the director
   * can add STAFF to it without a new build. This floor is why VOLUNTEER can never be added: a
   * volunteer records a *recommendation*, and the whole point of the Interview 1 to Interview 3
   * change was to separate that from the decision. Permissions.requireDecider() checks both.
   */
  DECIDE(Role.STAFF, "decide an adoption application"),
  OVERRIDE(Role.DIRECTOR, "override an earlier decision or a blocked action"),
  SETTINGS(Role.DIRECTOR, "change the system settings"),
  USER_ADMIN(Role.DIRECTOR, "manage user accounts and passwords");

  private final Role lowestRole;
  private final String description;

  Perm(Role lowestRole, String description) {
    this.lowestRole = lowestRole;
    this.description = description;
  }

  public Role lowestRole() { return lowestRole; }

  /** Used in the refusal message, so the terminal says what was refused, not just "denied". */
  public String description() { return description; }

  public String label() { return name().toLowerCase(Locale.ROOT).replace('_', ' '); }
}
