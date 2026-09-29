package org.rubyhill.rhrms.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * People, applications, home visits, the director's decision, placements and returns.
 *
 * This is the part of the terminal the build spec cares most about, so the screens do more than
 * collect fields: the decision screen puts the animal's restrictions, the visitor's findings, the
 * discrepancies and the conflicts in front of the director before he is allowed to type anything,
 * and refuses to let an approval past a conflict without a written reason.
 */
final class AdoptionScreens {
  private AdoptionScreens() {}

  private static final List<String> DENIAL_REASONS = List.of("HOUSING_NO_PETS",
      "CHILDREN_UNSUITABLE", "YARD_UNSUITABLE", "OTHER_PETS_UNSUITABLE", "HOUSEHOLD_UNSUITABLE",
      "BONDED_PAIR_NOT_TAKEN", "BETTER_FIT_CHOSEN", "APPLICATION_MISMATCH", "WELFARE_CONCERN", "OTHER");
  private static final List<String> PAYMENT_METHODS = List.of("CASH", "CHECK", "OTHER", "WAIVED");

  private static void printPeople(List<Map<String, Object>> people) {
    Term.Table table = new Term.Table("id", "name", "phone", "town", "applications", "has now", "returns");
    table.right(4, 5, 6);
    for (Map<String, Object> p : people) {
      int returns = ApiClient.intOf(p.get("returns"), 0);
      table.row(p.get("id"), p.get("first_name") + " " + p.get("last_name"), p.get("phone"),
          p.get("city"), p.get("applications"), p.get("animals_now_with_them"),
          returns == 0 ? Term.dim("0") : Term.yellow(String.valueOf(returns)));
    }
    table.print();
  }

  static void newPerson(Ui ui) {
    Term.heading("Adding a person");
    Term.info(Term.dim("Phone numbers are stored exactly as written, so (970) 555-0142 is fine."));
    ApiClient.Body body = ApiClient.body()
        .set("firstName", ui.prompt.required("First name: "))
        .set("lastName", ui.prompt.required("Last name: "))
        .set("phone", ui.prompt.line("Phone: "))
        .set("email", ui.prompt.line("Email: "))
        .set("address", ui.prompt.line("Address: "))
        .set("city", ui.prompt.line("Town: "))
        .set("state", ui.prompt.line("State: "))
        .set("postalCode", ui.prompt.line("Postal code: "))
        .set("notes", ui.prompt.line("Notes: "))
        .set("reason", "Added at the front desk");
    Map<String, Object> reply = ui.api.post("/api/people", body.map());
    ui.showResult(reply);
    Map<String, Object> person = ApiClient.object(reply.get("person"));
    Term.info("Their record number is " + Term.bold(ApiClient.str(person.get("id"))) + ".");
    List<Map<String, Object>> alsoOnFile = ApiClient.list(reply.get("alsoOnFile"));
    if (!alsoOnFile.isEmpty()) {
      Term.out();
      Term.warn("Somebody with the same name is already on file:");
      printPeople(alsoOnFile);
    }
  }

  static void showPerson(Ui ui, String reference) {
    long id = reference != null ? Long.parseLong(reference.trim())
                                : ui.prompt.requiredId("Person's record number: ");
    Map<String, Object> reply = ui.api.get("/api/people/" + id);
    Map<String, Object> p = ApiClient.object(reply.get("person"));

    Term.heading(p.get("first_name") + " " + p.get("last_name"));
    Ui.field("Phone", p.get("phone"));
    Ui.fieldIf("Email", p.get("email"));
    Ui.fieldIf("Address", join(p.get("address"), p.get("city"), p.get("state"), p.get("postal_code")));
    Ui.fieldIf("Notes", p.get("notes"));
    Ui.field("Record number", p.get("id") + Term.dim("   (version " + p.get("version") + ")"));

    // AP-4: what happened before, and whether they may apply again.
    Map<String, Object> mayApply = ApiClient.object(reply.get("mayApplyAgain"));
    if (ApiClient.flag(mayApply.get("anyDenialOnFile"))) {
      Term.out();
      boolean welfare = ApiClient.flag(mayApply.get("welfareConcern"));
      if (welfare) {
        Term.alertBox("DO NOT TAKE A NEW APPLICATION WITHOUT THE DIRECTOR",
            List.of(ApiClient.str(mayApply.get("note"))));
      } else {
        Term.warn(ApiClient.str(mayApply.get("note")));
      }
      Map<String, Object> guidance = ApiClient.object(mayApply.get("guidance"));
      if (!guidance.isEmpty()) {
        Term.out();
        Term.Table table = new Term.Table("past denial", "may they apply again?");
        guidance.forEach((key, value) -> table.row(Term.words(key), value));
        table.print();
      }
    }

    List<Map<String, Object>> applications = ApiClient.list(reply.get("applications"));
    if (!applications.isEmpty()) {
      Term.heading("Applications");
      Term.Table table = new Term.Table("app", "animal", "status", "decision", "reason", "decided by");
      for (Map<String, Object> app : applications) {
        table.row(app.get("id"),
            app.get("animal_name") == null ? app.get("animal_code")
                : app.get("animal_name") + " (" + app.get("animal_code") + ")",
            Term.status(app.get("status")), Term.status(app.get("outcome")),
            app.get("denial_reason") == null ? Term.dim("-") : Term.words(app.get("denial_reason")),
            app.get("decided_by"));
      }
      table.print();
      for (Map<String, Object> app : applications) {
        if (app.get("rationale") != null) {
          Term.out();
          Term.paragraph(Term.dim("app " + app.get("id") + ": "), ApiClient.str(app.get("rationale")));
        }
      }
    }

    List<Map<String, Object>> placements = ApiClient.list(reply.get("placements"));
    if (!placements.isEmpty()) {
      Term.heading("Animals they have adopted");
      Term.Table table = new Term.Table("animal", "adopted", "fee", "paid", "returned", "why");
      for (Map<String, Object> pl : placements) {
        table.row(pl.get("animal_name") + " (" + pl.get("animal_code") + ")",
            pl.get("placed_on"), Term.money(pl.get("fee_charged")),
            Term.words(pl.get("payment_method")),
            pl.get("returned_on") == null ? Term.green("still with them") : Term.yellow(ApiClient.str(pl.get("returned_on"))),
            pl.get("return_reason"));
      }
      table.print();
    }

    List<Map<String, Object>> surrendered = ApiClient.list(reply.get("surrendered"));
    if (!surrendered.isEmpty()) {
      Term.heading("Animals they brought in");
      Term.Table table = new Term.Table("animal", "date", "how");
      for (Map<String, Object> s : surrendered) {
        table.row(s.get("animal_name") + " (" + s.get("animal_code") + ")",
            s.get("intake_date"), Term.words(s.get("intake_source")));
      }
      table.print();
    }
  }

  // =============================================================== applications

  static void apply(Ui ui) {
    Term.heading("An adoption application");
    Term.paragraph("This mirrors the paper form. Anything the applicant left blank can be left "
        + "blank here; the home visit is what confirms it all anyway.");

    String search = ui.prompt.required("Applicant's name (or their record number): ");
    Long personId = findPerson(ui, search);
    if (personId == null) return;

    long animalId = AnimalScreens.resolve(ui, null, "Which animal? (name or code) ");
    Map<String, Object> animal = ApiClient.object(ui.api.get("/api/animals/" + animalId).get("animal"));
    Term.out();
    Term.info("Applying for " + AnimalScreens.label(animal) + ", which is "
        + Term.status(animal.get("status")) + ".");

    ApiClient.Body body = ApiClient.body().set("personId", personId).set("animalId", animalId);

    // RC-1 and RC-2: reconsidering somebody who applied before.
    List<Map<String, Object>> earlier = ApiClient.list(
        ui.api.get("/api/applications?person=" + personId).get("applications"));
    if (!earlier.isEmpty() && ui.may("RECORD_CORRECT")) {
      Term.out();
      Term.info("This person has applied before:");
      Term.Table table = new Term.Table("app", "animal", "status", "decision", "when");
      for (Map<String, Object> e : earlier) {
        table.row(e.get("id"), e.get("animal_name"), Term.status(e.get("status")),
            Term.status(e.get("latest_outcome")), Term.when(e.get("submitted_at")));
      }
      table.print();
      Term.out();
      if (ui.prompt.yesNo("Is this a reconsideration of an earlier application?", false)) {
        body.set("priorApplicationId", ui.prompt.requiredId("Which earlier application id? "));
        Term.paragraph("Interview 4: ring them. If nothing has changed and a visit already "
            + "exists, a new visit is usually not needed. If anything has changed - a move, a "
            + "new child, a new person in the house - a new visit is required.");
        body.set("situationCheckNote", ui.prompt.required("What did they say on the phone? ", 10,
            "This is the record that a check was actually made."));
        if (ui.prompt.yesNo("Reuse an earlier home visit?", false)) {
          body.set("reusedHomeVisitId", ui.prompt.requiredId("Which home visit id? "));
        }
      }
    }

    Term.out();
    Term.info(Term.bold("The home"));
    body.set("housingType", ui.prompt.choose("House, apartment or other?",
        List.of("HOUSE", "APARTMENT", "OTHER"), "HOUSE"));
    body.set("landlordAllowsPets", ui.prompt.yesNoUnknown("Does the landlord allow pets?"));
    Boolean hasYard = ui.prompt.yesNoUnknown("Is there a yard?");
    body.set("hasYard", hasYard);
    if (Boolean.TRUE.equals(hasYard)) {
      body.set("yardFenced", ui.prompt.yesNoUnknown("Is the yard fenced?"));
    }
    Term.out();
    Term.info(Term.bold("The household"));
    Integer children = ui.prompt.optionalInt("How many children live there? ");
    body.set("childrenCount", children);
    if (children != null && children > 0) {
      body.set("youngestChildAge", ui.prompt.optionalInt("How old is the youngest? "));
    }
    body.set("elderlyInHome", ui.prompt.yesNoUnknown("Is there an elderly resident?"));
    body.set("adultsInHome", ui.prompt.line("Which adults live there? (e.g. \"1 man, 1 woman\") "));
    body.set("otherPets", ui.prompt.line("What other pets do they have? "));
    body.set("reasonForAdopting", ui.prompt.line("Why do they want this animal? "));
    body.set("reason", "Paper application entered at the front desk");

    Map<String, Object> reply = ui.api.post("/api/applications", body.map());
    ui.showResult(reply);

    List<Map<String, Object>> partners = ApiClient.list(reply.get("bondPartners"));
    if (!partners.isEmpty()) {
      Term.out();
      Term.warn("Tell the applicant now: these animals go together.");
      Term.Table table = new Term.Table("code", "name", "status");
      for (Map<String, Object> p : partners) {
        table.row(p.get("animal_code"), p.get("name"), Term.status(p.get("status")));
      }
      table.print();
    }
    List<Map<String, Object>> restrictions = ApiClient.list(reply.get("animalRestrictions"));
    if (!restrictions.isEmpty()) {
      Term.out();
      Term.problem("This animal has restrictions the home visit must check:");
      for (Map<String, Object> r : restrictions) {
        Term.paragraph("  " + Term.red("- ") , Term.words(r.get("type"))
            + (r.get("detail") == null ? "" : ": " + r.get("detail")));
      }
    }
    List<Map<String, Object>> competing = ApiClient.list(reply.get("otherOpenApplications"));
    if (!competing.isEmpty()) {
      Term.out();
      Term.info("Other people have applied for this animal too. The director compares them all "
          + "side by side; applying first does not win.");
    }
    Map<String, Object> app = ApiClient.object(reply.get("application"));
    Term.out();
    Term.info("Application number " + Term.bold(ApiClient.str(app.get("id")))
        + ". Next: 'visit " + app.get("id") + "'.");
  }

  private static Long findPerson(Ui ui, String search) {
    try {
      return Long.parseLong(search.trim());
    } catch (NumberFormatException ignored) {
      // A name: look them up.
    }
    List<Map<String, Object>> people =
        ApiClient.list(ui.api.get("/api/people?q=" + AnimalScreens.url(search)).get("people"));
    if (people.isEmpty()) {
      Term.out();
      Term.info("Nobody on file matches \"" + search + "\".");
      if (!ui.prompt.yesNo("Add them now?", true)) return null;
      newPerson(ui);
      return ui.prompt.requiredId("Their record number (shown above): ");
    }
    if (people.size() == 1) {
      Map<String, Object> only = people.get(0);
      Term.info("Found " + only.get("first_name") + " " + only.get("last_name")
          + " (record " + only.get("id") + ").");
      return ApiClient.id(only.get("id"));
    }
    Term.out();
    printPeople(people);
    return ui.prompt.requiredId("Which record number? ");
  }



  static void printApplication(Map<String, Object> reply, boolean full) {
    Map<String, Object> app = ApiClient.object(reply.get("application"));
    Term.heading("Application " + app.get("id") + "   " + Term.status(app.get("status")));
    Ui.field("Applicant", app.get("applicant") + "   " + Term.text(app.get("phone")));
    Ui.fieldIf("Where they live", join(app.get("address"), app.get("city"), app.get("state")));
    Ui.field("Animal", app.get("animal_name") + " (" + app.get("animal_code") + "), "
        + Term.words(app.get("species")) + ", " + Term.status(app.get("animal_status")));
    Ui.field("Taken by", app.get("received_by_name") + " on " + Term.when(app.get("submitted_at")));
    if (app.get("closed_reason") != null) Ui.field("Closed because", app.get("closed_reason"));

    Term.out();
    Term.info(Term.bold("What the paper form said"));
    Ui.field("Housing", Term.words(app.get("housing_type"))
        + (app.get("landlord_allows_pets") == null ? ""
           : ", landlord allows pets: " + Term.text(app.get("landlord_allows_pets"))));
    Ui.field("Yard", Term.text(app.get("has_yard"))
        + (app.get("yard_fenced") == null ? "" : ", fenced: " + Term.text(app.get("yard_fenced"))));
    Ui.field("Children", app.get("children_count") == null ? Term.dim("not stated")
        : app.get("children_count") + (app.get("youngest_child_age") == null ? ""
           : ", youngest " + app.get("youngest_child_age")));
    Ui.field("Elderly in the home", app.get("elderly_in_home"));
    Ui.fieldIf("Adults in the home", app.get("adults_in_home"));
    Ui.fieldIf("Other pets", app.get("other_pets"));
    Ui.fieldIf("Why they want it", app.get("reason_for_adopting"));
    Ui.fieldIf("Phone check (RC-1)", app.get("situation_check_note"));

    List<Map<String, Object>> restrictions = ApiClient.list(reply.get("animalRestrictions"));
    if (!restrictions.isEmpty()) {
      Term.out();
      Term.out("  " + Term.red(Term.bold("The animal's restrictions")));
      for (Map<String, Object> r : restrictions) {
        Term.paragraph("  " + Term.red("- "), Term.words(r.get("type"))
            + (r.get("min_child_age") == null ? "" : " (under " + r.get("min_child_age") + ")")
            + (r.get("detail") == null ? "" : ": " + r.get("detail")));
      }
    }

    printVisits(ApiClient.list(reply.get("homeVisits")));
    Map<String, Object> reused = ApiClient.object(reply.get("reusedHomeVisit"));
    if (!reused.isEmpty()) {
      Term.heading("Reused home visit from " + reused.get("visited_on"));
      Term.info("Visited by " + reused.get("visitor_name") + ", recommended "
          + Term.status(reused.get("recommendation")) + ".");
      Term.paragraph(ApiClient.str(reused.get("notes")));
    }

    printConflicts(ApiClient.list(reply.get("conflicts")));

    List<String> discrepancies = ApiClient.strings(reply.get("discrepancies"));
    if (!discrepancies.isEmpty()) {
      Term.heading("Differences between the form and the visit");
      for (String d : discrepancies) Term.paragraph(Term.yellow("- "), d);
    }

    printDecisions(ApiClient.list(reply.get("decisions")));

    if (full) {
      List<Map<String, Object>> competing = ApiClient.list(reply.get("competingApplications"));
      if (!competing.isEmpty()) {
        Term.heading("Everybody else who applied for this animal");
        Term.Table table = new Term.Table("app", "applicant", "status", "decision", "submitted");
        for (Map<String, Object> c : competing) {
          table.row(c.get("id"), c.get("applicant"), Term.status(c.get("status")),
              Term.status(c.get("latest_outcome")), Term.when(c.get("submitted_at")));
        }
        table.print();
      }
      List<Map<String, Object>> placements = ApiClient.list(reply.get("placements"));
      if (!placements.isEmpty()) {
        Term.heading("The adoption");
        Term.Table table = new Term.Table("animal id", "placed", "fee", "paid", "returned", "why");
        for (Map<String, Object> p : placements) {
          table.row(p.get("animal_id"), p.get("placed_on"), Term.money(p.get("fee_charged")),
              Term.words(p.get("payment_method")), p.get("returned_on"), p.get("return_reason"));
        }
        table.print();
      }
    }
  }

  private static void printVisits(List<Map<String, Object>> visits) {
    if (visits.isEmpty()) {
      Term.out();
      Term.warn("No home visit yet. Interview 3: every application gets one before approval.");
      return;
    }
    for (Map<String, Object> v : visits) {
      Term.heading("Home visit on " + v.get("visited_on")
          + "   recommended " + Term.status(v.get("recommendation")));
      String visitor = ApiClient.str(v.get("visitor_name"));
      String enteredBy = ApiClient.str(v.get("entered_by"));
      Ui.field("Visited by", visitor + (visitor.equals(enteredBy) ? ""
          : "   " + Term.dim("(typed in by " + enteredBy + ")")));
      Ui.field("Children", ApiClient.flag(v.get("children_present"))
          ? "yes, youngest " + Term.text(v.get("youngest_child_age")) : "none");
      Ui.field("Yard", Term.text(v.get("yard"))
          + (v.get("yard_fenced") == null ? "" : ", fenced: " + Term.text(v.get("yard_fenced"))));
      Ui.field("Adults", v.get("adult_men") + " man/men, " + v.get("adult_women") + " woman/women");
      Ui.field("Elderly residents", v.get("elderly_residents"));
      Ui.fieldIf("Other pets", v.get("other_pets"));
      Ui.field("Housing confirmed", v.get("housing_confirmed"));
      Ui.fieldIf("Other concerns", v.get("other_concerns"));
      Term.out();
      Term.paragraph(Term.dim("In their own words: "), ApiClient.str(v.get("notes")));
    }
  }

  /** DE-2: conflicts in red, because this is the list that has to be dealt with, not skimmed. */
  static void printConflicts(List<Map<String, Object>> conflicts) {
    if (conflicts.isEmpty()) return;
    Term.out();
    Term.out("  " + Term.red(Term.bold("*** RESTRICTION CONFLICTS ***")));
    for (Map<String, Object> c : conflicts) {
      Term.paragraph("  " + Term.red("- "), Term.words(c.get("type")) + " -- " + c.get("conflict")
          + Term.dim("  [" + c.get("animal_code") + "]"));
    }
    Term.out();
    Term.paragraph(Term.dim("These are the animal's recorded restrictions set against what the "
        + "visit actually found. Approving anyway needs a written reason (DE-3)."));
  }

  private static void printDecisions(List<Map<String, Object>> decisions) {
    if (decisions.isEmpty()) return;
    Term.heading("Decisions");
    for (Map<String, Object> d : decisions) {
      boolean superseded = ApiClient.flag(d.get("superseded"));
      String header = "  " + Term.status(d.get("outcome"))
          + (d.get("denial_reason") == null ? "" : "  " + Term.words(d.get("denial_reason")))
          + Term.dim("   by " + d.get("decided_by") + " on " + Term.when(d.get("decided_at")));
      Term.out(superseded ? Term.dim("  [superseded]") + header : header);
      Term.paragraph("    ", ApiClient.str(d.get("rationale")));
      if (d.get("conflicts_snapshot") != null) {
        Term.paragraph("    " + Term.red("conflicts at the time: "),
            ApiClient.str(d.get("conflicts_snapshot")));
      }
      if (d.get("conflicts_acknowledgement") != null) {
        Term.paragraph("    " + Term.yellow("acknowledged: "),
            ApiClient.str(d.get("conflicts_acknowledgement")));
      }
      if (d.get("supersedes_decision_id") != null) {
        Term.info(Term.dim("    this replaced decision " + d.get("supersedes_decision_id")));
      }
      Term.out();
    }
  }

  // ================================================================ home visits

  static void homeVisit(Ui ui, String reference) {
    long appId = reference != null ? Long.parseLong(reference.trim())
                                   : ui.prompt.requiredId("Application number: ");
    Map<String, Object> reply = ui.api.get("/api/applications/" + appId);
    Map<String, Object> app = ApiClient.object(reply.get("application"));

    Term.heading("Home visit for application " + appId);
    Term.info(app.get("applicant") + ", applying for " + app.get("animal_name")
        + " (" + app.get("animal_code") + ").");
    List<Map<String, Object>> restrictions = ApiClient.list(reply.get("animalRestrictions"));
    if (!restrictions.isEmpty()) {
      Term.out();
      Term.problem("Look out for these while you are there:");
      for (Map<String, Object> r : restrictions) {
        Term.paragraph("  " + Term.red("- "), Term.words(r.get("type"))
            + (r.get("detail") == null ? "" : ": " + r.get("detail")));
      }
    }
    Term.out();
    Term.paragraph("This is the director's checklist. Every question is on it because he asked "
        + "for it, and the notes at the end are what he actually reads (HV-1).");

    ApiClient.Body body = ApiClient.body();
    body.set("visitedOn", ui.prompt.date("When was the visit", true));

    String me = ui.session().displayName();
    // HV-2: whoever made the visit is named, whether or not they have a login.
    if (ui.prompt.yesNo("Did you make this visit yourself?", true)) {
      body.set("visitorName", me).set("visitorUserId", ui.session().userId());
    } else {
      body.set("visitorName", ui.prompt.required("Who made the visit? "));
      Term.info(Term.dim("Recorded as entered on their behalf, with your name alongside."));
    }

    Term.out();
    boolean children = ui.prompt.yesNo("Were there children in the home?");
    body.set("childrenPresent", children);
    if (children) body.set("youngestChildAge", ui.prompt.requiredInt("How old is the youngest? "));
    boolean yard = ui.prompt.yesNo("Is there a yard?");
    body.set("yard", yard);
    if (yard) body.set("yardFenced", ui.prompt.yesNo("Is it fenced?"));
    body.set("otherPets", ui.prompt.line("What other pets did you see? "));
    body.set("elderlyResidents", ui.prompt.yesNo("Are there elderly residents?"));
    body.set("adultMen", ui.prompt.requiredInt("How many adult men live there? "));
    body.set("adultWomen", ui.prompt.requiredInt("How many adult women live there? "));
    body.set("housingConfirmed", ui.prompt.yesNo("Did you confirm the housing is as described?"));
    body.set("otherConcerns", ui.prompt.line("Any other concerns? "));
    Term.out();
    body.set("recommendation", ui.prompt.choose("What do you recommend?",
        List.of("APPROVE", "DENY", "UNSURE"), null));
    Term.out();
    Term.info("Now the part the director reads. Say what you saw and what you think, in your own words.");
    body.set("notes", ui.prompt.required("Notes: ", 15,
        "At least a sentence. \"Fine\" tells the director nothing he can defend later."));
    body.set("reason", "Home visit recorded");

    Map<String, Object> result = ui.api.post("/api/applications/" + appId + "/visit", body.map());
    ui.showResult(result);
    printConflicts(ApiClient.list(result.get("conflicts")));
    List<String> discrepancies = ApiClient.strings(result.get("discrepancies"));
    if (!discrepancies.isEmpty()) {
      Term.out();
      Term.warn("Differences from the paper form, which the director will see:");
      for (String d : discrepancies) Term.paragraph(Term.yellow("- "), d);
    }
  }

  // =================================================================== decision

  static void decide(Ui ui, String reference) {
    long appId = reference != null ? Long.parseLong(reference.trim())
                                   : ui.prompt.requiredId("Application number: ");
    Map<String, Object> reply = ui.api.get("/api/applications/" + appId);
    Map<String, Object> app = ApiClient.object(reply.get("application"));

    // Everything is put in front of the decider before a single word is typed.
    printApplication(reply, false);
    List<Map<String, Object>> conflicts = ApiClient.list(reply.get("conflicts"));
    List<Map<String, Object>> visits = ApiClient.list(reply.get("homeVisits"));
    boolean hasVisit = !visits.isEmpty() || app.get("reused_home_visit_id") != null;

    Term.out();
    Term.rule();
    Term.heading("Your decision on application " + appId);
    if (!hasVisit) {
      Term.problem("There is no home visit on this application.");
      Term.paragraph("It cannot be approved without one. A denial before a visit is allowed, and "
          + "is marked as such on the record.");
    }

    List<Map<String, Object>> decisions = ApiClient.list(reply.get("decisions"));
    Long supersedes = null;
    if (!decisions.isEmpty()) {
      Term.out();
      Term.warn("This application has already been decided.");
      if (!ui.may("OVERRIDE")) {
        Term.info("Only the director can change a decision. Nothing was done.");
        return;
      }
      if (!ui.prompt.yesNo("Record a new decision that replaces the old one?", false)) return;
      Term.paragraph("Both decisions stay visible. That is the point: the record shows what was "
          + "decided, and then what was decided instead, and why (DE-6).");
      supersedes = ui.prompt.requiredId("Which decision id does this replace? ");
    }

    String outcome = ui.prompt.choose("Approve or deny?",
        hasVisit ? List.of("APPROVE", "DENY") : List.of("DENY"), null);
    ApiClient.Body body = ApiClient.body().set("outcome", outcome);
    if (supersedes != null) body.set("supersedesDecisionId", supersedes);

    if (outcome.equals("DENY")) {
      Term.out();
      Term.info("The reason category decides whether this person may apply again, so pick it carefully.");
      body.set("denialReason", ui.prompt.choose("Reason", DENIAL_REASONS, null));
    } else if (!conflicts.isEmpty()) {
      // DE-3: this is the sentence that would be read out if the rescue were ever sued.
      Term.out();
      Term.alertBox("THIS HOME CONFLICTS WITH THE ANIMAL'S RESTRICTIONS", conflictLines(conflicts));
      Term.out();
      Term.paragraph("To approve anyway, write why this placement is still right for this animal. "
          + "It is stored with the decision, alongside the conflicts exactly as they are now.");
      body.set("conflictsAcknowledgement", ui.prompt.required("Why is it still right? ", 15,
          "A sentence that would stand up if somebody asked about it in a year."));
    }

    Term.out();
    Term.info("Now your own reasons. This is the record that explains the decision later.");
    body.set("rationale", ui.prompt.required("Your reasons: ", 15,
        "At least a sentence. \"Good home\" does not defend anything."));
    body.set("reason", "Decision on application " + appId);

    String password = ui.confirm("Recording a decision on application " + appId
        + " is permanent. A change later is a new decision, and both stay on the record.");
    if (password == null) {
      Term.info("Nothing was recorded.");
      return;
    }
    Map<String, Object> result = ui.api.post("/api/applications/" + appId + "/decision",
        body.map(), password);
    ui.showResult(result);
    List<Map<String, Object>> stillOpen = ApiClient.list(result.get("stillOpenForThisAnimal"));
    if (!stillOpen.isEmpty()) {
      Term.out();
      Term.info("Still open for this animal, and closed automatically when the adoption is recorded:");
      Term.Table table = new Term.Table("app", "applicant", "status");
      for (Map<String, Object> o : stillOpen) {
        table.row(o.get("id"), o.get("applicant"), Term.status(o.get("status")));
      }
      table.print();
    }
  }

  private static List<String> conflictLines(List<Map<String, Object>> conflicts) {
    List<String> lines = new ArrayList<>();
    for (Map<String, Object> c : conflicts) {
      lines.add(Term.words(c.get("type")) + ": " + c.get("conflict"));
    }
    return lines;
  }

  // ================================================================= placement

  static void place(Ui ui, String reference) {
    long appId = reference != null ? Long.parseLong(reference.trim())
                                   : ui.prompt.requiredId("Application number: ");
    Map<String, Object> reply = ui.api.get("/api/applications/" + appId);
    Map<String, Object> app = ApiClient.object(reply.get("application"));

    Term.heading("Adoption day: application " + appId);
    Term.info(app.get("applicant") + " is collecting " + app.get("animal_name")
        + " (" + app.get("animal_code") + ").");
    List<Map<String, Object>> partners = ApiClient.list(reply.get("bondPartners"));
    if (!partners.isEmpty()) {
      List<String> names = new ArrayList<>();
      for (Map<String, Object> p : partners) names.add(AnimalScreens.label(p));
      Term.warn("...and " + String.join(", ", names) + ", who go with it. One fee covers them all.");
    }
    if (!"APPROVED".equals(app.get("status"))) {
      Term.out();
      Term.problem("This application is " + Term.words(app.get("status"))
          + ", not approved, so the adoption cannot be recorded.");
      return;
    }

    Map<String, Object> settings = settingsMap(ui);
    String baseline = settings.getOrDefault("fee.baseline", "250.00").toString();

    Term.out();
    Term.info("The baseline fee is " + Term.money(baseline)
        + ". Interview 4: it is set case by case, and any other amount needs a reason.");
    ApiClient.Body body = ApiClient.body();
    String fee = ui.prompt.withDefault("Fee actually charged:", baseline);
    body.set("feeCharged", fee.replace("$", "").trim());
    if (!fee.replace("$", "").trim().equals(baseline)) {
      body.set("feeReason", ui.prompt.required("Why is it different? ", 3,
          "This is the only record of why this adopter paid a different amount."));
    }
    body.set("paymentMethod", ui.prompt.choose("How did they pay?", PAYMENT_METHODS,
        fee.replace("$", "").trim().equals("0") ? "WAIVED" : "CASH"));
    body.set("vetClinicTold", ui.prompt.line("Which vet clinic are you telling them about? "));

    // PL-3: the vetting block, and the director's override.
    if (!"VETTED".equals(app.get("vet_status"))) {
      Term.out();
      Term.problem("This animal is not marked as vetted.");
      if (ui.may("OVERRIDE")) {
        Term.paragraph("The system blocks this by default (it is a guessed rule, open question "
            + "Q3). As director you can go ahead anyway, and the reason goes on the record.");
        if (ui.prompt.yesNo("Go ahead without the vetting?", false)) {
          body.set("overrideVetting", true)
              .set("overrideReason", ui.prompt.required("Why is it all right? ", 10,
                  "For example: \"vet appointment booked for Thursday, adopter informed\"."));
        }
      } else {
        Term.info("Only the director can go ahead without it. Ask him.");
      }
    }

    // PL-1: prescription food that goes home with the animal.
    List<Object> foodToSend = new ArrayList<>();
    Map<String, Object> animalRecord = ui.api.get("/api/animals/" + app.get("animal_id"));
    List<Map<String, Object>> prescriptions = ApiClient.list(animalRecord.get("prescriptions"));
    if (!prescriptions.isEmpty()) {
      Term.out();
      Term.info("This animal is on prescription food:");
      for (Map<String, Object> p : prescriptions) {
        Term.info("  - " + p.get("food"));
      }
      if (ui.prompt.yesNo("Is any of it going home with them?", true)) {
        for (Map<String, Object> p : prescriptions) {
          Integer bags = ui.prompt.optionalInt("How many bags of " + p.get("food") + "? ");
          if (bags == null || bags <= 0) continue;
          Integer location = ui.prompt.optionalInt("Taken from which storage location id? (1 = shelter) ");
          foodToSend.add(Map.of(
              "animalId", app.get("animal_id"),
              "foodProductId", p.get("food_product_id"),
              "locationId", location == null ? 1 : location,
              "bags", bags));
        }
      }
    }
    if (!foodToSend.isEmpty()) body.set("prescriptionFood", foodToSend);
    body.set("reason", "Adoption recorded at the front desk");

    String password = ui.confirm("Recording this adoption marks the animal adopted, frees the "
        + "kennel, ends its food portions and closes the other applications. It is one step, all "
        + "or nothing.");
    if (password == null) {
      Term.info("Nothing was recorded.");
      return;
    }
    Map<String, Object> result = ui.api.post("/api/applications/" + appId + "/place",
        body.map(), password);
    ui.showResult(result);
    Term.out();
    Term.Table table = new Term.Table("animal", "placed", "baseline", "charged", "paid", "reason");
    for (Map<String, Object> p : ApiClient.list(result.get("placements"))) {
      table.row(p.get("animal_name") + " (" + p.get("animal_code") + ")", p.get("placed_on"),
          Term.money(p.get("fee_baseline")), Term.money(p.get("fee_charged")),
          Term.words(p.get("payment_method")), p.get("fee_reason"));
    }
    table.print();
  }

  static void returnAnimal(Ui ui, String reference) {
    long animalId = AnimalScreens.resolve(ui, reference, "Animal id or code: ");
    Map<String, Object> record = ui.api.get("/api/animals/" + animalId);
    Map<String, Object> animal = ApiClient.object(record.get("animal"));

    Term.heading(AnimalScreens.label(animal) + " has come back");
    Term.paragraph("It keeps the same record and the same animal code (Interview 2). The adoption "
        + "is closed with your reason, and a new stay starts.");
    Term.out();
    Term.info("Interview 3: the reason matters, because it has to justify any later placement.");

    ApiClient.Body body = ApiClient.body();
    body.set("returnReason", ui.prompt.required("Why is it back? ", 5,
        "Be specific. \"Did not work out\" tells the next director nothing."));

    List<String> numbers = new ArrayList<>();
    for (Map<String, Object> k : ApiClient.list(ui.api.get("/api/kennels?free=true").get("kennels"))) {
      numbers.add(ApiClient.str(k.get("kennel")));
    }
    Term.out();
    Term.info("Free kennels: " + (numbers.isEmpty() ? Term.red("none") : String.join(" ", numbers)));
    body.set("kennelId", ui.prompt.requiredInt("Which kennel is it going into? "));

    String password = ui.confirm("Recording the return closes the adoption and starts a new stay.");
    if (password == null) {
      Term.info("Nothing was recorded.");
      return;
    }
    Map<String, Object> result = ui.api.post("/api/animals/" + animalId + "/return",
        body.map(), password);
    ui.showResult(result);
    Term.out();
    Term.warn(ApiClient.str(result.get("nextStep")));
    List<Map<String, Object>> restrictions = ApiClient.list(result.get("restrictions"));
    Term.out();
    if (restrictions.isEmpty()) {
      Term.info("There are no restrictions on this animal at the moment.");
    } else {
      Term.info("Restrictions already on file:");
      for (Map<String, Object> r : restrictions) {
        Term.paragraph("  " + Term.red("- "), Term.words(r.get("type"))
            + (r.get("detail") == null ? "" : ": " + r.get("detail")));
      }
    }
    if (ui.may("RESTRICTION_EDIT") && ui.prompt.yesNo("Add a restriction now?", true)) {
      AnimalScreens.restrictions(ui, String.valueOf(animalId));
    }
  }


  // =================================================================== helpers

  static Map<String, Object> settingsMap(Ui ui) {
    Map<String, Object> out = new java.util.LinkedHashMap<>();
    for (Map<String, Object> s : ApiClient.list(ui.api.get("/api/settings").get("settings"))) {
      out.put(ApiClient.str(s.get("key")), s.get("value"));
    }
    return out;
  }

  private static String join(Object... parts) {
    List<String> kept = new ArrayList<>();
    for (Object part : parts) {
      if (part != null && !String.valueOf(part).isBlank()) kept.add(String.valueOf(part));
    }
    return kept.isEmpty() ? null : String.join(", ", kept);
  }
}
