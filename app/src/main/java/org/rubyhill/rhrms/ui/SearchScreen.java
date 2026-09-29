package org.rubyhill.rhrms.ui;

import java.util.List;
import java.util.Map;

/**
 * One search box.
 *
 * LK-1 asks for exactly that: "One search box: applicant name, animal name (current or former) or
 * animal code." An earlier version had three - `find` for animals, `people` for people, `lookup`
 * for past decisions - which meant knowing which kind of thing you were after before you could
 * look for it. At a front desk with somebody standing in front of you holding a lead, you often
 * do not: "Morgan" might be the dog or the person who brought it in.
 *
 * So this asks all three and shows whatever came back, leaving out the sections that found
 * nothing. The endpoints behind it are unchanged.
 */
final class SearchScreen {
  private SearchScreen() {}

  static void find(Ui ui, String query) {
    String q = query != null ? query
        : ui.prompt.required("Name, code, phone or town: ");

    List<Map<String, Object>> animals = search(ui, "/api/animals?q=", q, "animals");
    List<Map<String, Object>> people = search(ui, "/api/people?q=", q, "people");
    // The decision lookup needs something to go on; one letter matches half the rescue.
    List<Map<String, Object>> decisions = q.length() < 2 ? List.of()
        : search(ui, "/api/lookup?q=", q, "decisions");

    Term.heading("\"" + q + "\"");
    if (animals.isEmpty() && people.isEmpty() && decisions.isEmpty()) {
      Term.info("Nothing matched.");
      Term.out();
      Term.paragraph(Term.dim("Animals are searched by name, by any name they used to have, and "
          + "by code. People are searched by name, phone and town. Past decisions are searched by "
          + "applicant and by animal."));
      return;
    }

    if (!animals.isEmpty()) {
      Term.out();
      Term.out("  " + Term.bold("Animals") + Term.dim("  (" + animals.size() + ")"));
      AnimalScreens.printAnimalList(animals);
      Term.info(Term.dim("'animal <code>' opens one."));
    }

    if (!people.isEmpty()) {
      Term.out();
      Term.out("  " + Term.bold("People") + Term.dim("  (" + people.size() + ")"));
      Term.Table table = new Term.Table("id", "name", "phone", "town", "applications",
          "has now", "returns");
      table.right(4, 5, 6);
      for (Map<String, Object> p : people) {
        int returns = ApiClient.intOf(p.get("returns"), 0);
        table.row(p.get("id"), p.get("first_name") + " " + p.get("last_name"), p.get("phone"),
            p.get("city"), p.get("applications"), p.get("animals_now_with_them"),
            returns == 0 ? Term.dim("0") : Term.yellow(String.valueOf(returns)));
      }
      table.print();
      Term.info(Term.dim("'person <id>' opens one, with what happened last time."));
    }

    if (!decisions.isEmpty()) {
      Term.out();
      Term.out("  " + Term.bold("Past decisions") + Term.dim("  (" + decisions.size() + ")"));
      Term.Table table = new Term.Table("when", "outcome", "applicant", "animal", "reason",
          "decided by");
      for (Map<String, Object> d : decisions) {
        String outcome = Term.status(d.get("outcome"));
        if (ApiClient.flag(d.get("superseded"))) outcome = outcome + Term.dim(" (replaced)");
        table.row(Term.when(d.get("decided_at")), outcome, d.get("applicant"),
            d.get("animal_name") + " (" + d.get("animal_code") + ")",
            d.get("denial_reason") == null ? Term.dim("-") : Term.words(d.get("denial_reason")),
            d.get("decided_by"));
      }
      table.print();
      for (Map<String, Object> d : decisions) {
        if (d.get("rationale") != null) {
          Term.out();
          Term.paragraph(Term.dim(d.get("applicant") + " / " + d.get("animal_name") + ": "),
              ApiClient.str(d.get("rationale")));
        }
      }
    }
  }

  /**
   * Runs one of the three searches. A search that fails must not take the whole screen with it:
   * a volunteer has no privilege on the login trail, for instance, and that is not an error worth
   * showing them in the middle of looking for a dog.
   */
  private static List<Map<String, Object>> search(Ui ui, String path, String q, String key) {
    try {
      return ApiClient.list(ui.api.get(path + AnimalScreens.url(q)).get(key));
    } catch (ApiClient.Failure e) {
      if (e.needsLogin()) throw e;
      return List.of();
    }
  }
}
