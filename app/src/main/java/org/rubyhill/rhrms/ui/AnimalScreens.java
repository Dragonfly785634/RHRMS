package org.rubyhill.rhrms.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Animals and kennels: finding them, the record screen, intake, and the edits staff may make. */
final class AnimalScreens {
  private AnimalScreens() {}

  private static final List<String> SPECIES = List.of("DOG", "CAT", "FERRET", "SMALL_MAMMAL", "OTHER");
  private static final List<String> INTAKE_SOURCES =
      List.of("OWNER_SURRENDER", "STRAY", "CITY_SHELTER", "BORN_IN_CARE");
  private static final List<String> RESTRICTION_TYPES = List.of("NO_CHILDREN", "NO_ADULT_MEN",
      "NO_ADULT_WOMEN", "NO_OTHER_DOGS", "NO_CATS", "NO_ELDERLY", "NEEDS_FENCED_YARD", "OTHER");


  static void printAnimalList(List<Map<String, Object>> animals) {
    Term.Table table = new Term.Table("code", "name", "species", "status", "kennel", "notes");
    table.right(4);
    for (Map<String, Object> a : animals) {
      List<String> notes = new ArrayList<>();
      if (a.get("former_names") != null) notes.add("formerly " + a.get("former_names"));
      int restrictions = ApiClient.intOf(a.get("active_restrictions"), 0);
      if (restrictions > 0) notes.add(Term.red(restrictions + " restriction(s)"));
      int applications = ApiClient.intOf(a.get("open_applications"), 0);
      if (applications > 0) notes.add(applications + " open application(s)");
      if (ApiClient.flag(a.get("needs_split"))) notes.add(Term.yellow("needs splitting"));
      if (!"VETTED".equals(a.get("vet_status"))) notes.add("not vetted");
      table.row(a.get("animal_code"), a.get("name"), Term.words(a.get("species")),
          Term.status(a.get("status")), a.get("kennel_id"), String.join(", ", notes));
    }
    table.print();
  }

  // ============================================================= one animal

  /** Accepts a record number or an animal code, because staff read codes off the kennel card. */
  static long resolve(Ui ui, String reference, String question) {
    String text = reference != null ? reference : ui.prompt.required(question);
    try {
      return Long.parseLong(text.trim());
    } catch (NumberFormatException ignored) {
      // Not a number: treat it as a code or a name and look it up.
    }
    List<Map<String, Object>> matches =
        ApiClient.list(ui.api.get("/api/animals?q=" + url(text)).get("animals"));
    if (matches.isEmpty()) {
      throw ApiClient.Failure.local("NOT_FOUND",
          "No animal matches \"" + text + "\". Try 'find " + text + "' to see what is on file.");
    }
    if (matches.size() == 1) return ApiClient.id(matches.get(0).get("id"));
    Term.out();
    Term.info("More than one animal matches \"" + text + "\":");
    printAnimalList(matches);
    List<String> codes = new ArrayList<>();
    for (Map<String, Object> m : matches) codes.add(ApiClient.str(m.get("animal_code")));
    String chosen = ui.prompt.choose("Which one?", codes, null);
    for (Map<String, Object> m : matches) {
      if (chosen.equals(m.get("animal_code"))) return ApiClient.id(m.get("id"));
    }
    throw new Prompt.Cancelled();
  }

  static void show(Ui ui, String reference) {
    long id = resolve(ui, reference, "Animal id or code: ");
    Map<String, Object> reply = ui.api.get("/api/animals/" + id);
    Map<String, Object> a = ApiClient.object(reply.get("animal"));

    Term.heading(label(a) + "   " + Term.status(a.get("status")));
    Ui.field("Species / breed", Term.words(a.get("species")) + slash(a.get("breed")));
    Ui.field("Sex", sex(a.get("sex")));
    Ui.field("Age", age(a));
    Ui.fieldIf("Colour / markings", a.get("color_markings"));
    Ui.field("Kennel", a.get("kennel_id") == null ? Term.dim("none") : a.get("kennel_id")
        + (ApiClient.flag(a.get("kennel_in_service")) ? "" : Term.red(" (out of service)")));
    Ui.field("Vetting", Term.status(a.get("vet_status")));
    Ui.field("Spay / neuter", Term.words(a.get("spay_neuter")));
    if (a.get("not_adoptable_reason") != null) {
      Ui.field("Not adoptable because", Term.red(ApiClient.str(a.get("not_adoptable_reason"))));
    }
    Ui.field("Record number", a.get("id") + Term.dim("   (version " + a.get("version") + ")"));

    List<Map<String, Object>> formerNames = ApiClient.list(reply.get("formerNames"));
    if (formerNames.size() > 1 || (formerNames.size() == 1 && !formerNames.get(0).get("name").equals(a.get("name")))) {
      List<String> names = new ArrayList<>();
      for (Map<String, Object> n : formerNames) names.add(ApiClient.str(n.get("name")));
      Ui.field("Known as", String.join(" -> ", names));
    }

    Ui.fieldIf("Health notes", a.get("health_notes"));
    Ui.fieldIf("Behaviour notes", a.get("behavior_notes"));
    Ui.fieldIf("Other notes", a.get("general_notes"));

    // Restrictions in red, because this is the list that keeps the rescue out of court.
    List<Map<String, Object>> restrictions = ApiClient.list(reply.get("restrictions"));
    if (!restrictions.isEmpty()) {
      Term.heading("Restrictions");
      Term.Table table = new Term.Table("id", "restriction", "detail", "in force");
      for (Map<String, Object> r : restrictions) {
        boolean active = ApiClient.flag(r.get("active"));
        String type = Term.words(r.get("type"))
            + (r.get("min_child_age") == null ? "" : " under " + r.get("min_child_age"));
        table.row(r.get("id"), active ? Term.red(type) : Term.dim(type),
            r.get("detail"), active ? Term.red("YES") : Term.dim("withdrawn"));
      }
      table.print();
    }

    printIfAny("Bonded with, and adopted together", ApiClient.list(reply.get("bondPartners")),
        new String[]{"code", "name", "status"},
        r -> new Object[]{r.get("animal_code"), r.get("name"), Term.status(r.get("status"))});

    printIfAny("Litter mates", ApiClient.list(reply.get("litterMates")),
        new String[]{"code", "name", "status", "kennel"},
        r -> new Object[]{r.get("animal_code"), r.get("name"), Term.status(r.get("status")),
            r.get("kennel_id")});

    printIfAny("Stays", ApiClient.list(reply.get("stays")),
        new String[]{"arrived", "time", "how", "received by", "ended", "why"},
        r -> new Object[]{r.get("intake_date"), r.get("intake_time"),
            Term.words(r.get("intake_source")), r.get("received_by"),
            r.get("ended_on"), Term.words(r.get("end_reason"))});

    printIfAny("Adoptions and returns", ApiClient.list(reply.get("placements")),
        new String[]{"placed", "adopter", "fee", "paid", "returned", "why it came back"},
        r -> new Object[]{r.get("placed_on"), r.get("adopter"), Term.money(r.get("fee_charged")),
            Term.words(r.get("payment_method")), r.get("returned_on"), r.get("return_reason")});

    printIfAny("Applications", ApiClient.list(reply.get("applications")),
        new String[]{"app", "applicant", "status", "visits", "decision", "submitted"},
        r -> new Object[]{r.get("id"), r.get("applicant"), Term.status(r.get("status")),
            r.get("home_visits"), Term.status(r.get("latest_decision")), Term.when(r.get("submitted_at"))});

    printIfAny("Food", ApiClient.list(reply.get("diets")),
        new String[]{"food", "cups/meal", "meals/day", "lb/day", "from", "until"},
        r -> new Object[]{r.get("food"), r.get("cups_per_meal"), r.get("meals_per_day"),
            r.get("lbs_per_day"), r.get("valid_from"), r.get("valid_to")});

    printIfAny("Prescriptions", ApiClient.list(reply.get("prescriptions")),
        new String[]{"food", "from", "until"},
        r -> new Object[]{r.get("food"), r.get("start_date"), r.get("end_date")});

    List<Map<String, Object>> vetVisits = ApiClient.list(reply.get("vetVisits"));
    if (!vetVisits.isEmpty()) {
      printIfAny("Vet visits", vetVisits,
          new String[]{"date", "clinic", "reason", "outcome", "cost"},
          r -> new Object[]{r.get("visit_date"), r.get("clinic"), r.get("reason"),
              r.get("outcome"), Term.money(r.get("cost"))});
      if (reply.get("vetCostTotal") != null) {
        Term.info("Total spent at the vet: " + Term.money(reply.get("vetCostTotal")));
      }
    }

    Term.out();
    Term.info(Term.dim("Every change ever made to this record is kept, and can be shown on "
        + "request; ask whoever looks after this computer."));
  }

  private interface RowMaker {
    Object[] cells(Map<String, Object> row);
  }

  private static void printIfAny(String title, List<Map<String, Object>> rows,
                                 String[] headers, RowMaker maker) {
    if (rows.isEmpty()) return;
    Term.heading(title);
    Term.Table table = new Term.Table(headers);
    for (Map<String, Object> r : rows) table.row(maker.cells(r));
    table.print();
  }

  // ====================================================================== intake

  static void intake(Ui ui) {
    formHeader(ui);
    ApiClient.Body body = ApiClient.body();
    try {
      fillIn(ui, body);
    } catch (ApiClient.Failure e) {
      // Something went wrong part-way through: the session timed out, the server stopped, a
      // lookup failed. Whatever it was, the boxes already filled in must not vanish with it -
      // see DECISIONS.md D-27. Show what went wrong, then show what was typed.
      ui.showFailure(e);
      showUnsaved(body);
      if (e.needsLogin()) throw e;            // the shell takes it from here and asks for a login
    }
  }

  private static void fillIn(Ui ui, ApiClient.Body body) {
    // ---- ANIMAL NAME | CAME IN AS (IF RENAMED) | KENNEL NO. --------------------
    box("ANIMAL NAME   CAME IN AS (IF RENAMED)   KENNEL NO.");
    body.set("name", ui.prompt.line(f("ANIMAL NAME")));
    hint("only if the rescue has already started calling it something else");
    body.set("cameInAs", ui.prompt.line(f("CAME IN AS (IF RENAMED)")));
    kennelPicker(ui, body);

    // ---- TYPE | BREED / DESCRIPTION -------------------------------------------
    box("TYPE   BREED / DESCRIPTION");
    body.set("species", type(ui));
    // One box, as printed. The form says "BREED / DESCRIPTION", so colour and markings belong
    // in it - they were a second question this screen invented, and the person filling the form
    // in has one line in front of them, not two.
    hint("breed if known, and anything that describes it: colour, markings, size");
    body.set("breed", ui.prompt.line(f("BREED / DESCRIPTION")));

    // ---- AGE | AGE IS | DATE IN | TIME IN --------------------------------------
    box("AGE   AGE IS   DATE IN   TIME IN");
    age(ui, body);
    body.set("intakeDate", ui.prompt.date(f("DATE IN"), true));
    hint("09:15, 0915, 9.15 or 9:15 am all work. Blank if it was not noted.");
    body.set("timeIn", ui.prompt.time(f("TIME IN")));

    // ---- WHERE FROM -------------------------------------------------------------
    box("WHERE FROM");
    printed("owner surrender", "stray", "city shelter", "born here");
    String source = ui.prompt.choose(f("WHERE FROM"), INTAKE_SOURCES, "STRAY");
    body.set("intakeSource", source);

    // ---- BONDED WITH | MOTHER (IF BORN HERE) ------------------------------------
    box("BONDED WITH   MOTHER (IF BORN HERE)");
    hint("the animal this one must be adopted with. Name or code. Blank if none.");
    String bonded = ui.prompt.line(f("BONDED WITH"));
    if (bonded != null) body.set("bondedWithAnimalId", resolve(ui, bonded, ""));

    if (source.equals("BORN_IN_CARE")) {
      hint("the mother's name or code");
      String mother = ui.prompt.line(f("MOTHER (IF BORN HERE)"));
      if (mother != null) body.set("motherAnimalId", resolve(ui, mother, ""));
    } else {
      Term.out("  " + Term.dim(Term.pad("MOTHER (IF BORN HERE)", LEADER)
          + "not asked: only for an animal born here"));
    }

    // ---- NOTES ------------------------------------------------------------------
    box("NOTES - MEDICAL, BEHAVIOUR, ANYTHING THE NEXT PERSON NEEDS TO KNOW");
    hint("one box, as on the form. A line at a time; blank line to finish.");
    body.set("generalNotes", multiLine(ui));

    // ---- TAKEN IN BY (VOLUNTEER) | ENTERED IN SPREADSHEET BY --------------------
    box("TAKEN IN BY (VOLUNTEER)   ENTERED IN SPREADSHEET BY   DATE ENTERED");
    String me = ui.session().displayName();
    hint("whoever actually met the animal, even if they have no login here");
    body.set("takenInBy", ui.prompt.withDefault(f("TAKEN IN BY (VOLUNTEER)"), me));
    Term.out("  " + Term.pad("ENTERED IN SPREADSHEET BY", LEADER) + me);
    Term.out("  " + Term.pad("DATE ENTERED", LEADER) + java.time.LocalDate.now()
        + Term.dim("   (filled in for you)"));

    // ---- beyond the paper form ---------------------------------------------------
    Term.out();
    Term.rule();
    Term.info(Term.dim("Below this line is not on form RH-1, but the system needs it."));
    dietaryRequirement(ui, body);

    // Nothing here about whether the animal is ready to be listed: that is not on form RH-1,
    // and it is not a decision to make with the animal still in the doorway. Everything arrives
    // AVAILABLE and NOT_VETTED, and 'status' changes it once somebody has actually looked.
    body.set("reason", "Intake (form RH-1)");

    submit(ui, body);
  }

  // ------------------------------------------------------- drawing the form

  /** Where every answer starts, so the questions line up like the ruled boxes on paper. */
  private static final int LEADER = 44;

  /** The masthead of form RH-1, so the screen and the paper in front of you match. */
  private static void formHeader(Ui ui) {
    int width = Math.min(Term.WIDTH - 2, 76);
    int inner = width - 4;                       // two spaces of padding each side
    String rightTop = "ANIMAL INTAKE";
    String rightSub = "No. ______";
    Term.out();
    Term.out(Term.bold("+" + "-".repeat(width) + "+"));
    Term.out(Term.bold("|") + "  "
        + Term.pad(Term.bold("RUBY HILL ANIMAL RESCUE"), inner - rightTop.length())
        + Term.bold(rightTop) + "  " + Term.bold("|"));
    Term.out(Term.bold("|") + "  "
        + Term.pad(Term.dim("1400 S. Platte River Dr.  ·  Denver, CO  ·  (303) 555-0100"),
                   inner - rightSub.length())
        + Term.dim(rightSub) + "  " + Term.bold("|"));
    Term.out(Term.bold("+" + "-".repeat(width) + "+"));
    Term.out("  " + Term.dim("RH-1 (rev. 3/24)  ·  office copy retained"));
    Term.out();
    Term.info(Term.bold(".q") + " or " + Term.bold(".exit") + " at any box leaves the form and "
        + "saves nothing.");
    Term.info(Term.dim("Enter alone leaves a box blank, as on paper.  ")
        + Term.bold(".help") + Term.dim(" lists the dot commands."));
  }

  /** A section bar carrying the form's own heading, like the grey strips on the paper. */
  private static void box(String heading) {
    int width = Math.min(Term.WIDTH - 4, 72);
    Term.out();
    Term.out("  " + Term.inverse(" " + Term.pad(heading, width) + " "));
  }

  /**
   * Prints everything the form collected, after a refusal, so it can be read off the screen and
   * retyped rather than remembered. Nothing was saved, and this says so.
   */
  private static void showUnsaved(ApiClient.Body body) {
    Term.out();
    if (body.map().isEmpty()) {
      Term.warn("Nothing was saved, and nothing had been filled in yet.");
      return;
    }
    Term.warn("Nothing was saved. This is what you had filled in:");
    Term.out();
    for (Map.Entry<String, Object> entry : body.map().entrySet()) {
      String label = FORM_LABELS.get(entry.getKey());
      if (label == null || entry.getValue() == null) continue;
      String value = String.valueOf(entry.getValue()).replace("\n", " / ");
      Term.paragraph(Term.dim(Term.pad(label, 30)), value);
    }
    Term.out();
    Term.info("Nothing here is lost from the screen. Deal with whatever the message above says, "
        + "then run 'intake' again.");
  }

  /** The form's own words for each field, for the list shown after a refusal. */
  private static final Map<String, String> FORM_LABELS = Map.ofEntries(
      Map.entry("name", "ANIMAL NAME"),
      Map.entry("cameInAs", "CAME IN AS"),
      Map.entry("kennelId", "KENNEL NO."),
      Map.entry("species", "TYPE"),
      Map.entry("breed", "BREED / DESCRIPTION"),
      Map.entry("colorMarkings", "COLOUR / MARKINGS"),
      Map.entry("sex", "SEX"),
      Map.entry("ageYears", "AGE - years"),
      Map.entry("ageMonths", "AGE - months"),
      Map.entry("ageIsExact", "AGE IS exact"),
      Map.entry("birthDate", "DATE OF BIRTH"),
      Map.entry("intakeDate", "DATE IN"),
      Map.entry("timeIn", "TIME IN"),
      Map.entry("intakeSource", "WHERE FROM"),
      Map.entry("bondedWithAnimalId", "BONDED WITH"),
      Map.entry("motherAnimalId", "MOTHER"),
      Map.entry("generalNotes", "NOTES"),
      Map.entry("takenInBy", "TAKEN IN BY"),
      Map.entry("foodProductId", "dietary requirement"),
      Map.entry("cupsPerMeal", "  cups per meal"),
      Map.entry("mealsPerDay", "  meals per day"),
      Map.entry("notAdoptableReason", "not adoptable because"));

  /** A field label with a dotted leader, the way a printed form rules its boxes. */
  private static String f(String label) {
    int dots = Math.max(3, LEADER - label.length() - 2);
    return label + " " + Term.dim(".".repeat(dots)) + " ";
  }

  /** A dim note under a section bar, for anything the paper form leaves to common sense. */
  private static void hint(String text) {
    Term.out("  " + Term.dim("    " + text));
  }

  /** Shows the tick-boxes exactly as they are printed on the form, above the numbered choice. */
  private static void printed(String... options) {
    StringBuilder out = new StringBuilder("    ");
    for (String option : options) out.append("[ ] ").append(option).append("   ");
    Term.out("  " + Term.dim(out.toString().stripTrailing()));
  }

  /**
   * TYPE on the form is three boxes: dog, cat, other. The database keeps a longer list, so
   * "other" asks one follow-up rather than losing what the animal actually is.
   */
  private static String type(Ui ui) {
    printed("dog", "cat", "other");
    String chosen = ui.prompt.choose(f("TYPE"), List.of("DOG", "CAT", "OTHER"), null);
    if (!chosen.equals("OTHER")) return chosen;
    return ui.prompt.choose(f("   which kind of other?"),
        List.of("FERRET", "SMALL_MAMMAL", "OTHER"), "OTHER");
  }

  /**
   * AGE and AGE IS, in the order the form asks them. The tick is what decides whether the age is
   * trusted; a date of birth is offered too, because that is the only way to be exact to the day.
   */
  private static void age(Ui ui, ApiClient.Body body) {
    Integer years = ui.prompt.optionalInt(f("AGE - years"));
    Integer months = ui.prompt.optionalInt(f("    - and months"));
    if (years != null) body.set("ageYears", years);
    if (months != null) body.set("ageMonths", months);

    if (years == null && months == null) {
      Term.out("  " + Term.dim(Term.pad("AGE IS", LEADER) + "nothing to say: recorded as unknown"));
      return;
    }
    printed("exact", "estimated");
    String ageIs = ui.prompt.choose(f("AGE IS"), List.of("EXACT", "ESTIMATED"), "ESTIMATED");
    body.set("ageIsExact", ageIs.equals("EXACT"));

    if (ageIs.equals("EXACT")) {
      hint("an age in years fixes the year, not the day. A date of birth is better if known.");
      String dob = ui.prompt.date(f("   DATE OF BIRTH"), false);
      if (dob != null) body.set("birthDate", dob);
    }
  }

  /** The big notes box: as many lines as it takes, ended by a blank one. */
  private static String multiLine(Ui ui) {
    StringBuilder out = new StringBuilder();
    while (true) {
      String line = ui.prompt.line("  " + Term.dim("|") + " ");
      if (line == null) break;
      if (out.length() > 0) out.append('\n');
      out.append(line);
      if (out.length() > 4000) {
        Term.warn("That is as much as the box holds.");
        break;
      }
    }
    return out.length() == 0 ? null : out.toString();
  }

  /** Sends the form, and handles the duplicate check the server may come back with (IN-3). */
  private static void submit(Ui ui, ApiClient.Body body) {
    Map<String, Object> reply;
    try {
      reply = ui.api.post("/api/animals/intake", body.map());
    } catch (ApiClient.Failure e) {
      if (!"POSSIBLE_DUPLICATE".equals(e.code())) {
        // Anything else means the form was refused after every box had been filled in. Show the
        // refusal, and then show what was typed: losing ten minutes of someone's work because of
        // two characters in one box is not acceptable, and "nothing was saved" on its own is
        // exactly the message that makes people stop using a system.
        ui.showFailure(e);
        showUnsaved(body);
        return;
      }
      // IN-3. The server will not save until somebody has looked at these and chosen.
      Term.out();
      Term.warn("This might already be on file.");
      List<Map<String, Object>> dupes = ApiClient.list(e.detail().get("possibleDuplicates"));
      Term.out();
      Term.Table table = new Term.Table("code", "name", "type", "status", "kennel", "arrived",
          "why shown");
      for (Map<String, Object> dupe : dupes) {
        table.row(dupe.get("animal_code"), dupe.get("name"), Term.words(dupe.get("species")),
            Term.status(dupe.get("status")), dupe.get("kennel_id"), dupe.get("intake_date"),
            dupe.get("why"));
      }
      table.print();
      Term.out();
      if (!ui.prompt.yesNo("Is the animal in front of you a DIFFERENT animal from all of those?")) {
        Term.out();
        Term.info("Nothing was saved. Open the existing record with 'animal <code>' instead. "
            + "If that animal was adopted and has come back, use 'return <code>', which keeps "
            + "the same animal code.");
        return;
      }
      body.set("duplicateAcknowledged", true)
          .set("duplicateChoice", "staff confirmed this is a different animal")
          .set("reason", "Intake (form RH-1; possible duplicate reviewed and rejected)");
      reply = ui.api.post("/api/animals/intake", body.map());
    }

    ui.showResult(reply);
    Object formNumber = reply.get("formNumber");
    if (formNumber != null) {
      Term.out();
      Term.out("  " + Term.bold("Write " + formNumber + " in the No. box on the paper form."));
      Term.info(Term.dim("That is what ties the office copy to this record."));
    }
  }

  /**
   * The dietary requirement, asked as part of the intake and stored as a link to a supply item
   * rather than a note. Skippable: an animal often arrives before anybody knows what it eats.
   */
  private static void dietaryRequirement(Ui ui, ApiClient.Body body) {
    Term.out();
    Term.info(Term.bold("Dietary requirement"));
    Term.paragraph("What this animal eats, and how much. It is linked to a supply item, so it "
        + "counts towards what the rescue has to keep in stock. Press Enter to skip if nobody "
        + "knows yet.");

    List<Map<String, Object>> products =
        ApiClient.list(ui.api.get("/api/food/products").get("products"));
    if (products.isEmpty()) {
      Term.info(Term.dim("No supply items on file yet, so there is nothing to link to."));
      return;
    }
    Term.out();
    Term.Table table = new Term.Table("id", "food", "type", "unit", "on hand");
    table.right(4);
    for (Map<String, Object> product : products) {
      if (!ApiClient.flag(product.get("active"))) continue;
      table.row(product.get("id"), product.get("name"), Term.words(product.get("kind")),
          product.get("unit"), product.get("quantity_on_hand"));
    }
    table.print();

    Long foodId = ui.prompt.optionalId("Which food id? (Enter to skip) ");
    if (foodId == null) {
      Term.info(Term.dim("Skipped. Record it later with 'diet <animal>'."));
      return;
    }
    body.set("foodProductId", foodId)
        .set("cupsPerMeal", ui.prompt.withDefault("How many cups per meal?", "1"))
        .set("mealsPerDay", ui.prompt.withDefault("How many meals a day?", "2"));
  }

  private static void approximateAge(Ui ui, ApiClient.Body body) {
    Term.info("An approximate age is fine; it is recorded as an estimate.");
    Integer years = ui.prompt.optionalInt("Roughly how many years old? ");
    Integer months = ui.prompt.optionalInt("...and how many extra months? ");
    if (years != null) body.set("ageYears", years);
    if (months != null) body.set("ageMonths", months);
  }

  private static void kennelPicker(Ui ui, ApiClient.Body body) {
    Map<String, Object> free = ui.api.get("/api/kennels?free=true");
    List<Map<String, Object>> kennels = ApiClient.list(free.get("kennels"));
    if (kennels.isEmpty()) {
      Term.out();
      Term.problem("Every kennel is occupied or out of service.");
      Term.paragraph("Interview 3 and 4: there are exactly " + Term.text(free.get("total"))
          + " kennels, and capacity is counted by kennel, not by animal.");
      if (ui.may("OVERRIDE")) {
        if (ui.prompt.yesNo("Record the arrival with no kennel anyway?")) {
          body.set("noKennelReason", ui.prompt.required("Where is the animal, and why? ", 5,
              "This is recorded, because an animal with no kennel is easy to lose track of."));
          return;
        }
      }
      throw new Prompt.Cancelled();
    }
    List<String> numbers = new ArrayList<>();
    for (Map<String, Object> k : kennels) numbers.add(ApiClient.str(k.get("kennel")));
    Term.out();
    Term.info("Free kennels: " + String.join(" ", numbers));
    body.set("kennelId", ui.prompt.requiredInt("Which kennel? "));
  }


  static void status(Ui ui, String reference) {
    long id = resolve(ui, reference, "Animal id or code: ");
    Map<String, Object> a = ApiClient.object(ui.api.get("/api/animals/" + id).get("animal"));

    Term.heading("Changing the status of " + label(a));
    Term.info("It is " + Term.status(a.get("status")) + " now.");
    Term.out();
    Term.paragraph("Allowed moves are fixed (AN-1): available and not adoptable swap freely, an "
        + "approved application makes an animal pending, a recorded adoption makes it adopted, "
        + "and a return brings it back. The database refuses anything else.");

    String status = ui.prompt.choose("Change it to", List.of("AVAILABLE", "NOT_ADOPTABLE", "DECEASED"), null);
    ApiClient.Body body = ApiClient.body().set("status", status).set("version", a.get("version"));
    if (status.equals("NOT_ADOPTABLE")) {
      body.set("notAdoptableReason", ui.prompt.required("Why is it not adoptable? ", 5,
          "This is shown on the record and explains why the animal is not being offered."));
    }
    body.set("reason", ui.prompt.required("Reason for the record: ", 3,
        "Every status change is recorded with a reason."));

    String password = null;
    if (status.equals("DECEASED")) {
      // Irreversible, and it ends the stay and the diets. Worth asking twice.
      password = ui.confirm("Marking " + label(a) + " as deceased cannot be undone. "
          + "It ends the stay, ends the food portions, and frees the kennel.");
      if (password == null) {
        Term.info("Nothing was changed.");
        return;
      }
    }
    ui.showResult(ui.api.post("/api/animals/" + id + "/status", body.map(), password));
  }


  static void restrictions(Ui ui, String reference) {
    long id = resolve(ui, reference, "Animal id or code: ");
    Map<String, Object> reply = ui.api.get("/api/animals/" + id);
    Map<String, Object> a = ApiClient.object(reply.get("animal"));
    List<Map<String, Object>> current = ApiClient.list(reply.get("restrictions"));

    Term.heading("Restrictions on " + label(a));
    Term.paragraph("An animal is assumed to be fine with children, other pets and everybody else "
        + "unless a restriction says otherwise (AN-5, Interview 3). These are what the director "
        + "is shown, in red, against every applicant they clash with.");
    Term.out();
    if (current.isEmpty()) {
      Term.info(Term.dim("None recorded."));
    } else {
      Term.Table table = new Term.Table("id", "restriction", "detail", "in force", "added");
      for (Map<String, Object> r : current) {
        boolean active = ApiClient.flag(r.get("active"));
        table.row(r.get("id"), active ? Term.red(Term.words(r.get("type"))) : Term.dim(Term.words(r.get("type"))),
            r.get("detail"), active ? Term.red("YES") : Term.dim("withdrawn"),
            Term.when(r.get("created_at")));
      }
      table.print();
    }

    Term.out();
    String what = ui.prompt.choose("What would you like to do?",
        List.of("ADD_ONE", "WITHDRAW_ONE", "NOTHING"), "NOTHING");
    if (what.equals("NOTHING")) return;

    if (what.equals("ADD_ONE")) {
      String type = ui.prompt.choose("Which restriction?", RESTRICTION_TYPES, null);
      ApiClient.Body body = ApiClient.body().set("type", type);
      if (type.equals("NO_CHILDREN")) {
        Term.info("If the animal is only unsuitable for young children, give the youngest age "
            + "that would be all right. Leave blank for no children at all.");
        body.set("minChildAge", ui.prompt.optionalInt("Youngest child age that is acceptable: "));
      }
      if (type.equals("OTHER")) {
        body.set("detail", ui.prompt.required("What is the restriction? ", 5,
            "The director reads this when he decides. Be specific."));
      } else {
        body.set("detail", ui.prompt.line("Any detail worth recording: "));
      }
      body.set("reason", ui.prompt.required("Why is this being added? ", 3,
          "For example: \"growled at a child during the meet-and-greet on 12 Sept\"."));
      ui.showResult(ui.api.post("/api/animals/" + id + "/restrictions", body.map()));
      return;
    }

    long restrictionId = ui.prompt.requiredId("Which restriction id do you want to withdraw? ");
    String password = ui.confirm("Withdrawing a restriction changes which homes this animal can "
        + "go to. The record is kept and marked withdrawn, with your reason, so the decision can "
        + "still be explained later.");
    if (password == null) {
      Term.info("Nothing was changed.");
      return;
    }
    ApiClient.Body body = ApiClient.body().set("reason",
        ui.prompt.required("Why is it no longer needed? ", 5,
            "This is the sentence that will have to justify the change if it is ever questioned."));
    ui.showResult(ui.api.post("/api/animals/" + id + "/restrictions/" + restrictionId + "/withdraw",
        body.map(), password));
  }



  // ==================================================================== helpers

  static String label(Map<String, Object> animal) {
    Object name = animal.get("name");
    Object code = animal.get("animal_code");
    return name == null ? String.valueOf(code) : name + " (" + code + ")";
  }

  private static String slash(Object value) {
    return value == null ? "" : " / " + value;
  }

  private static String sex(Object value) {
    if (value == null) return Term.dim("-");
    return switch (String.valueOf(value)) {
      case "M" -> "male";
      case "F" -> "female";
      default -> "unknown";
    };
  }

  private static String age(Map<String, Object> a) {
    if (a.get("birth_date") == null) return Term.dim("not known");
    int years = ApiClient.intOf(a.get("age_years"), 0);
    int months = ApiClient.intOf(a.get("age_extra_months"), 0);
    String span = years > 0
        ? years + " year" + (years == 1 ? "" : "s") + (months > 0 ? " " + months + " month" + (months == 1 ? "" : "s") : "")
        : months + " month" + (months == 1 ? "" : "s");
    return span + "  " + Term.dim("(born " + a.get("birth_date")
        + (ApiClient.flag(a.get("birth_date_is_estimate")) ? ", estimated)" : ", exact)"));
  }

  static String url(String value) {
    return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
  }
}
