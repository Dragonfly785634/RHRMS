package org.rubyhill.rhrms.http;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

import java.sql.SQLException;

/**
 * Turns a PostgreSQL error into something a person can act on.
 *
 * The business rules live in triggers (02_rules.sql) and each one already raises a sentence
 * written for the front desk, such as "Kennel 2 is occupied by Otis, Pearl." This class hands
 * that sentence straight to the client together with the rule id from the trigger's HINT, and
 * translates the handful of errors PostgreSQL itself raises (unique violations, deadlocks)
 * into equally plain words. Nothing here ever exposes SQL text or a stack trace.
 */
public final class SqlErrors {
  private SqlErrors() {}

  public static ApiException translate(SQLException e) {
    String state = e.getSQLState() == null ? "" : e.getSQLState();
    ServerErrorMessage server = (e instanceof PSQLException pe) ? pe.getServerErrorMessage() : null;
    String message = server != null && server.getMessage() != null ? server.getMessage() : e.getMessage();
    String hint = server != null ? server.getHint() : null;      // the "RULE AP-1" marker

    return switch (state) {
      // Raised by our own triggers and CHECK constraints: the message is already the answer.
      case "23514" -> ApiException.ruleViolation(message, hint);
      case "23505" -> ApiException.conflict(uniqueMessage(server, message));
      case "23503" -> ApiException.badRequest(foreignKeyMessage(server, message));
      case "23502" -> ApiException.badRequest(notNullMessage(server, message));
      case "22001" -> ApiException.badRequest("One of the values is too long for its field. Shorten it and try again.");
      case "22003", "22P02" -> ApiException.badRequest("One of the values is not in the right format: " + message);
      case "42501" -> ApiException.notAllowed(message);
      case "40001", "40P01" -> ApiException.conflict(
          "Someone else was changing the same records at that moment. Nothing was saved. Please try again.");
      case "55P03", "57014" -> ApiException.conflict(
          "Another person has these records open right now. Nothing was saved. Please try again in a moment.");
      case "08000", "08003", "08006", "08001", "08004", "57P01", "57P02", "57P03" ->
          new ApiException(503, "DATABASE_DOWN",
              "The database is not answering. Nothing was saved. Ask whoever set up this computer to check that PostgreSQL is running.");
      case "28P01", "28000" -> new ApiException(503, "DATABASE_LOGIN",
          "The server could not log in to the database. Nothing was saved. Check the passwords in rhrms.properties.");
      case "3D000" -> new ApiException(503, "DATABASE_MISSING",
          "The rhrms database does not exist on this computer. Run scripts/setup_db.sh and scripts/build_db.sh.");
      default -> {
        // Anything genuinely unexpected: say so honestly, and say that nothing was saved,
        // because every request runs in one transaction that has just been rolled back.
        yield new ApiException(500, "DATABASE_ERROR",
            "The database refused this change and nothing was saved. It reported: " + firstLine(message));
      }
    };
  }

  private static String uniqueMessage(ServerErrorMessage server, String fallback) {
    String constraint = server == null ? null : server.getConstraint();
    if (constraint == null) return "That record already exists: " + firstLine(fallback);
    return switch (constraint) {
      case "one_approved_per_animal" -> "This animal already has an approved application. "
          + "Open the animal to see who it is approved for, then withdraw or override that approval first.";
      case "no_duplicate_open_application" -> "This person already has an open application for this animal.";
      case "one_active_placement_per_animal" -> "This animal is already recorded as adopted and living in a home. "
          + "Record a return before placing it again.";
      case "one_open_stay_per_animal" -> "This animal already has a stay in progress. It is already here.";
      case "one_open_order_per_product" -> "There is already an open order for this food. Mark that one received or cancelled first.";
      case "one_open_bag_per_product" -> "There is already an open bag of this food.";
      case "one_active_diet_per_food" -> "This animal already has a current portion recorded for this food. End that one first.";
      case "staff_user_username_key" -> "That username is already taken. Pick another one.";
      case "food_product_name_key" -> "A food with that name already exists.";
      case "animal_animal_code_key" -> "That animal code is already in use.";
      case "stock_location_name_key" -> "A storage location with that name already exists.";
      default -> "That would duplicate a record that must be unique (" + constraint + ").";
    };
  }

  private static String foreignKeyMessage(ServerErrorMessage server, String fallback) {
    String table = server == null ? null : server.getTable();
    if (table == null) return "That refers to a record that does not exist: " + firstLine(fallback);
    return "That refers to a " + table.replace('_', ' ') + " record that does not exist. Check the id and try again.";
  }

  private static String notNullMessage(ServerErrorMessage server, String fallback) {
    String column = server == null ? null : server.getColumn();
    if (column == null) return "A required value was left empty: " + firstLine(fallback);
    return "'" + column.replace('_', ' ') + "' is required and was left empty.";
  }

  private static String firstLine(String s) {
    if (s == null) return "no details";
    int nl = s.indexOf('\n');
    return nl < 0 ? s : s.substring(0, nl);
  }
}
