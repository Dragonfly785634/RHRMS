package org.rubyhill.rhrms.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The dashboard, which is the screen anybody at the front desk should be able to read at a
 * glance: where every animal is, who is waiting for what, and whether the food is running out.
 *
 * Section 8 asks for a kennel grid of 1 to 40, counts, the applications waiting for a visit or a
 * decision, and a red banner for food that needs ordering. Interview 4 asked for "a big red
 * pop-up box" on login, which on a terminal is a red box printed before the first prompt.
 */
final class DashboardScreen {
  private DashboardScreen() {}

  static void show(Ui ui) {
    Map<String, Object> d = ui.api.get("/api/dashboard");
    Map<String, Object> counts = ApiClient.object(d.get("counts"));

    Term.heading("Ruby Hill Rescue, right now");
    Term.out("  " + Term.bold(ApiClient.str(counts.get("animals_here"))) + " animals here"
        + Term.dim("   |   ") + Term.green(ApiClient.str(counts.get("available")) + " available")
        + Term.dim("   |   ") + Term.yellow(ApiClient.str(counts.get("pending_adoption")) + " on hold")
        + Term.dim("   |   ") + Term.red(ApiClient.str(counts.get("not_adoptable")) + " not adoptable"));
    Term.out("  " + ApiClient.str(counts.get("free_kennels")) + " free kennels"
        + Term.dim("   |   ") + ApiClient.str(counts.get("kennels_out_of_service")) + " out of service"
        + Term.dim("   |   ") + ApiClient.str(counts.get("here_and_not_vetted")) + " not vetted"
        + Term.dim("   |   ") + ApiClient.str(counts.get("open_applications")) + " open applications");

    String banner = ApiClient.str(d.get("banner"));
    if (banner != null) {
      Term.out();
      Term.out("  " + Term.red("*** " + banner + "  Type 'food' to see it. ***"));
    }

    kennelGrid(ApiClient.list(d.get("kennels")));

    waiting("Waiting for a home visit", ApiClient.list(d.get("awaitingHomeVisit")), false);
    waiting("Waiting for the director's decision", ApiClient.list(d.get("awaitingDecision")), true);

    List<Map<String, Object>> ready = ApiClient.list(d.get("readyToCollect"));
    if (!ready.isEmpty()) {
      Term.heading("Approved, waiting to be collected");
      Term.Table table = new Term.Table("app", "adopter", "animal", "vetted", "decided");
      for (Map<String, Object> r : ready) {
        table.row(r.get("id"), r.get("applicant"),
            r.get("animal_name") + " (" + r.get("animal_code") + ")",
            Term.status(r.get("vet_status")), Term.when(r.get("decided_at")));
      }
      table.print();
      Term.info(Term.dim("Type 'place <app>' on the day they come in."));
    }

    List<Map<String, Object>> orders = ApiClient.list(d.get("openFoodOrders"));
    if (!orders.isEmpty()) {
      Term.heading("Food on order");
      Term.Table table = new Term.Table("order", "food", "bags", "status", "expected", "asked for by");
      table.right(2);
      for (Map<String, Object> o : orders) {
        table.row(o.get("id"), o.get("food"), o.get("bags"), Term.status(o.get("status")),
            Term.text(o.get("expected_on")), o.get("requested_by"));
      }
      table.print();
    }

    List<Map<String, Object>> needsSplit = ApiClient.list(d.get("needsSplit"));
    if (!needsSplit.isEmpty()) {
      Term.heading("Imported records that are still one row for several animals");
      Term.paragraph("The 2026 spreadsheet recorded some litters as a single line. Each of these "
          + "needs splitting into one record per animal, so one kitten can have its own diet.");
      Term.Table table = new Term.Table("code", "name", "kennel", "notes");
      for (Map<String, Object> a : needsSplit) {
        table.row(a.get("animal_code"), a.get("name"), a.get("kennel_id"), a.get("general_notes"));
      }
      table.print();
    }

    List<Map<String, Object>> activity = ApiClient.list(d.get("recentActivity"));
    if (!activity.isEmpty()) {
      Term.heading("Lately");
      Term.Table table = new Term.Table("when", "who", "what", "why");
      for (Map<String, Object> a : activity) {
        String who = ApiClient.str(a.get("who"));
        if (a.get("on_behalf_of") != null) who = who + " for " + a.get("on_behalf_of");
        table.row(Term.when(a.get("at")),
            who == null ? Term.dim("(installer)") : who,
            Term.words(a.get("action")) + " " + Term.words(a.get("table_name"))
                + " " + Term.dim(ApiClient.str(a.get("row_id"))),
            a.get("reason"));
      }
      table.print();
    }
  }

  /** The kennel grid, 1 to 40, in rows of four so 80 columns is enough. */
  private static void kennelGrid(List<Map<String, Object>> kennels) {
    if (kennels.isEmpty()) return;
    Term.heading("Kennels");
    int perRow = Term.WIDTH >= 100 ? 5 : 4;
    int cell = (Term.WIDTH - 4) / perRow - 1;
    StringBuilder line = new StringBuilder("  ");
    int inRow = 0;
    for (Map<String, Object> k : kennels) {
      String number = Term.leftPad(ApiClient.str(k.get("kennel")), 2);
      String occupants = ApiClient.str(k.get("occupants"));
      String body;
      if (!ApiClient.flag(k.get("in_service"))) {
        body = Term.red("out of service");
      } else if (occupants == null) {
        body = Term.dim("empty");
      } else {
        body = shortOccupants(occupants);
      }
      line.append(Term.dim(number + " ")).append(Term.pad(Term.truncate(body, cell - 3), cell - 3))
          .append(' ');
      if (++inRow == perRow) {
        Term.out(line.toString().stripTrailing());
        line = new StringBuilder("  ");
        inRow = 0;
      }
    }
    if (inRow > 0) Term.out(line.toString().stripTrailing());
  }

  /** "Bella (RH-000001), Otis (RH-000002)" is too long for a grid cell; names alone are enough. */
  private static String shortOccupants(String occupants) {
    List<String> names = new ArrayList<>();
    for (String piece : occupants.split(",")) {
      String name = piece.trim();
      int bracket = name.indexOf(" (");
      names.add(bracket > 0 ? name.substring(0, bracket) : name);
    }
    return String.join(", ", names);
  }

  private static void waiting(String title, List<Map<String, Object>> rows, boolean showConflicts) {
    if (rows.isEmpty()) return;
    Term.heading(title);
    Term.Table table = showConflicts
        ? new Term.Table("app", "applicant", "animal", "visitor said", "conflicts", "days")
        : new Term.Table("app", "applicant", "phone", "animal", "days");
    for (Map<String, Object> r : rows) {
      String animal = r.get("animal_name") == null
          ? ApiClient.str(r.get("animal_code"))
          : r.get("animal_name") + " (" + r.get("animal_code") + ")";
      if (showConflicts) {
        int conflicts = ApiClient.intOf(r.get("conflicts"), 0);
        table.row(r.get("id"), r.get("applicant"), animal,
            Term.status(r.get("recommendation")),
            conflicts == 0 ? Term.dim("none") : Term.red(conflicts + " CONFLICT"),
            r.get("days_waiting"));
      } else {
        table.row(r.get("id"), r.get("applicant"), r.get("phone"), animal, r.get("days_waiting"));
      }
    }
    table.print();
  }

  /**
   * The red box the client asked for, shown once at login. Only food that needs ordering NOW and
   * has nothing already on order: a warning that appears when there is nothing to do gets
   * ignored, and then so does the one that matters.
   */
  static void lowFoodAlert(Ui ui) {
    List<Map<String, Object>> alerts;
    try {
      alerts = ApiClient.list(ui.api.get("/api/food/forecast").get("alerts"));
    } catch (ApiClient.Failure e) {
      return;                          // the dashboard will report it; do not block the login
    }
    if (alerts.isEmpty()) return;

    List<String> lines = new ArrayList<>();
    for (Map<String, Object> a : alerts) {
      lines.add(ApiClient.str(a.get("name")) + " (" + Term.words(a.get("kind")) + ")");
      lines.add("   about " + Term.text(a.get("daysOfSupply")) + " days left, and it takes about "
          + Term.text(a.get("leadTimeDays")) + " days to arrive.");
      if (a.get("eatenBy") != null) {
        lines.add("   eaten by: " + a.get("eatenBy"));
      }
    }
    lines.add("");
    lines.add("Type 'food' to see it, and tell whoever orders the food.");
    Term.alertBox("FOOD IS RUNNING OUT", lines);
  }
}
