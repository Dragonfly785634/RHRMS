package org.rubyhill.rhrms.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Food: the forecast, stock movements, the order queue, diets and donations.
 *
 * Interview 2 and Interview 4 are the whole reason this exists: stock is judged by eye today, and
 * the prescription food that only Bella eats takes about ten days to arrive. So the screen that
 * matters is the forecast, and the number that matters is days of supply against lead time.
 */
final class FoodScreens {
  private FoodScreens() {}

  private static final List<String> MOVEMENT_TYPES = List.of("RECEIVE", "OPEN",
      "TRANSFER_OUT", "TRANSFER_IN", "WRITE_OFF", "COUNT_ADJUST");

  // ================================================================== forecast

  static void forecast(Ui ui) {
    Map<String, Object> reply = ui.api.get("/api/food/forecast");
    List<Map<String, Object>> foods = ApiClient.list(reply.get("foods"));

    Term.heading("Food");
    if (foods.isEmpty()) {
      Term.info("No supply items yet. 'supplies' adds one.");
      return;
    }
    Term.Table table = new Term.Table("id", "food", "kind", "bags", "lb on hand",
        "lb/day", "days left", "lead", "status");
    table.right(3, 4, 5, 6, 7);
    for (Map<String, Object> f : foods) {
      table.row(f.get("id"), f.get("name"), Term.words(f.get("kind")), f.get("sealed_bags"),
          f.get("lbs_on_hand"), f.get("lbs_per_day"),
          f.get("days_of_supply") == null ? Term.dim("-") : f.get("days_of_supply"),
          f.get("lead_time_days"),
          statusWithOrder(f));
    }
    table.print();

    Term.out();
    Term.paragraph(Term.dim("\"Days left\" counts the sealed bags plus what is left in the open "
        + "bag, divided by what the animals here actually eat. REORDER NOW means the days left "
        + "have dropped to the lead time plus "
        + Term.text(reply.get("bufferDays")) + " days of slack."));

    for (Map<String, Object> f : foods) {
      if (f.get("eaten_by") != null) {
        Term.out();
        Term.paragraph(Term.dim(Term.pad(ApiClient.str(f.get("name")) + " ", 26) + "eaten by "),
            ApiClient.str(f.get("eaten_by")));
      }
      if (f.get("prescribed_without_portion") != null) {
        Term.out();
        Term.warn(f.get("name") + ": prescribed for " + f.get("prescribed_without_portion")
            + " but no portion is recorded, so the days left above are a guess. "
            + "Use 'diet' to record how much they eat.");
      }
    }

    // The inventory view knows something the forecast does not: how many animals in care have no
    // dietary requirement recorded at all. Without that warning the "days left" column looks
    // authoritative when it is actually understating the need.
    try {
      Map<String, Object> inventory = ui.api.get("/api/inventory");
      int noDiet = ApiClient.intOf(inventory.get("animalsWithNoDietaryRequirement"), 0);
      Term.out();
      if (noDiet == 0) {
        Term.good("All " + Term.text(inventory.get("animalsInCare"))
            + " animals in care have a dietary requirement recorded, so the figures above are complete.");
      } else {
        Term.warn(noDiet + " of " + Term.text(inventory.get("animalsInCare"))
            + " animals in care have no dietary requirement recorded, so the days left above are "
            + "longer than the truth. Use 'diet <animal>' to fix it.");
      }
    } catch (ApiClient.Failure ignored) {
      // Not worth failing the whole screen over; the numbers above are still shown.
    }

    List<Map<String, Object>> stock = ApiClient.list(reply.get("stockByLocation"));
    if (!stock.isEmpty()) {
      Term.heading("Where the sealed bags are");
      Term.Table byLocation = new Term.Table("food", "location", "location id", "sealed bags");
      byLocation.right(3);
      for (Map<String, Object> s : stock) {
        byLocation.row(s.get("food"), s.get("location"), s.get("location_id"), s.get("sealed_bags"));
      }
      byLocation.print();
    }

    List<Map<String, Object>> alerts = ApiClient.list(reply.get("alerts"));
    if (!alerts.isEmpty()) {
      List<String> lines = new ArrayList<>();
      for (Map<String, Object> a : alerts) lines.add(ApiClient.str(a.get("message")));
      Term.alertBox("ORDER THIS NOW", lines);
    }
  }

  private static String statusWithOrder(Map<String, Object> food) {
    String status = ApiClient.str(food.get("food_status"));
    if ("ON ORDER".equals(status)) {
      Object expected = food.get("expected_on");
      return Term.yellow("on order") + (expected == null ? "" : Term.dim(" " + expected));
    }
    if ("NO CURRENT USE".equals(status)) return Term.dim("nobody eats it");
    return Term.status(status);
  }

  // ============================================================ stock movements

  static void movement(Ui ui) {
    Term.heading("Recording a change to the food stock");
    Map<String, Object> products = ui.api.get("/api/food/products");
    printProducts(ApiClient.list(products.get("products")));
    printLocations(ApiClient.list(products.get("locations")));

    long productId = ui.prompt.requiredId("Which food id? ");
    int locationId = ui.prompt.requiredInt("Which location id? ");

    // Only offer what this person may actually do, then let the server check it again.
    List<String> allowed = new ArrayList<>();
    for (String type : MOVEMENT_TYPES) {
      boolean basic = type.equals("RECEIVE") || type.equals("OPEN");
      boolean adjust = type.equals("WRITE_OFF") || type.equals("COUNT_ADJUST");
      if (basic || (adjust && ui.may("STOCK_ADJUST")) || (!basic && !adjust && ui.may("FOOD_ORDER_MANAGE"))) {
        allowed.add(type);
      }
    }
    Term.out();
    String type = ui.prompt.choose("What happened?", allowed, null);
    ApiClient.Body body = ApiClient.body()
        .set("foodProductId", productId).set("locationId", locationId).set("type", type);

    String password = null;
    if (type.equals("COUNT_ADJUST")) {
      Term.out();
      Term.paragraph("A count never silently overwrites what the system thought. Type the number "
          + "of sealed bags actually on the shelf; the difference and your reason go in the ledger.");
      body.set("countedBags", ui.prompt.requiredInt("How many bags did you count? "));
      body.set("movementReason", ui.prompt.required("Why is it different? ", 5,
          "For example: \"monthly count\", or \"two bags were never recorded when they arrived\"."));
      password = ui.confirm("Correcting a stock count changes the numbers the reorder warnings "
          + "are based on.");
      if (password == null) {
        Term.info("Nothing was changed.");
        return;
      }
    } else if (type.equals("WRITE_OFF")) {
      body.set("bags", ui.prompt.requiredInt("How many bags are being written off? "));
      body.set("movementReason", ui.prompt.required("What happened to them? ", 5,
          "This is the only record of where the food went."));
      password = ui.confirm("Writing food off removes it from stock with no trace except your reason.");
      if (password == null) {
        Term.info("Nothing was changed.");
        return;
      }
    } else if (type.equals("OPEN")) {
      body.set("bags", 1);
      Term.info(Term.dim("One bag at a time. The previous open bag of this food is marked finished."));
    } else {
      body.set("bags", ui.prompt.requiredInt("How many bags? "));
      body.set("movementReason", ui.prompt.line("Any note: "));
    }
    body.set("reason", "Stock change at the front desk");

    Map<String, Object> reply = ui.api.post("/api/food/movements", body.map(), password);
    ui.showResult(reply);
    printFoodStatus(ApiClient.object(reply.get("food")));
  }

  /** The server's way of saying this animal has no prescription for that food (FD-7). */
  private static boolean looksLikeMissingPrescription(ApiClient.Failure e) {
    String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.ROOT);
    return message.contains("prescri");
  }

  private static void printProducts(List<Map<String, Object>> products) {
    Term.out();
    Term.Table table = new Term.Table("id", "food", "kind", "bag lb", "lb/cup", "lead days", "sealed bags");
    table.right(3, 4, 5, 6);
    for (Map<String, Object> p : products) {
      if (!ApiClient.flag(p.get("active"))) continue;
      table.row(p.get("id"), p.get("name"), Term.words(p.get("kind")), p.get("bag_lbs"),
          p.get("lbs_per_cup"), p.get("lead_time_days"), p.get("sealed_bags"));
    }
    table.print();
  }

  private static void printLocations(List<Map<String, Object>> locations) {
    List<String> parts = new ArrayList<>();
    for (Map<String, Object> l : locations) parts.add(l.get("id") + " = " + l.get("name"));
    Term.out();
    Term.info("Locations: " + String.join(", ", parts));
  }

  private static void printFoodStatus(Map<String, Object> food) {
    if (food.isEmpty()) return;
    Term.out();
    Term.info(food.get("name") + ": " + Term.text(food.get("sealed_bags")) + " sealed bags, "
        + Term.text(food.get("lbs_on_hand")) + " lb on hand, "
        + Term.text(food.get("days_of_supply")) + " days left -- " + Term.status(food.get("food_status")));
  }


  // ================================================================ diets

  static void diet(Ui ui, String reference) {
    long animalId = AnimalScreens.resolve(ui, reference, "Animal id or code: ");
    Map<String, Object> record = ui.api.get("/api/animals/" + animalId);
    Map<String, Object> animal = ApiClient.object(record.get("animal"));

    Term.heading("What " + AnimalScreens.label(animal) + " eats");
    List<Map<String, Object>> diets = ApiClient.list(record.get("diets"));
    if (diets.isEmpty()) {
      Term.info(Term.dim("Nothing recorded, so this animal is not counted in the food forecast."));
    } else {
      Term.Table table = new Term.Table("id", "food", "cups/meal", "meals/day", "lb/day", "from", "until");
      table.right(2, 3, 4);
      for (Map<String, Object> d : diets) {
        table.row(d.get("id"), d.get("food"), d.get("cups_per_meal"), d.get("meals_per_day"),
            d.get("lbs_per_day"), d.get("valid_from"),
            d.get("valid_to") == null ? Term.green("current") : Term.dim(ApiClient.str(d.get("valid_to"))));
      }
      table.print();
    }

    Term.out();
    String what = ui.prompt.choose("What would you like to do?",
        List.of("SET_A_PORTION", "STOP_A_PORTION", "NOTHING"), "NOTHING");
    if (what.equals("NOTHING")) return;

    if (what.equals("STOP_A_PORTION")) {
      long dietId = ui.prompt.requiredId("Which portion id? ");
      ui.showResult(ui.api.post("/api/food/diets/" + dietId + "/end",
          ApiClient.body().set("reason", "Portion stopped").map()));
      return;
    }

    printProducts(ApiClient.list(ui.api.get("/api/food/products").get("products")));
    Term.out();
    Term.paragraph("Pounds per cup matters more than it looks: the whole forecast is built from "
        + "it. If nobody has weighed a cup of this food, do that before trusting the days left "
        + "(open question Q8).");
    long foodId = ui.prompt.requiredId("Which food id? ");
    ApiClient.Body body = ApiClient.body()
        .set("animalId", animalId)
        .set("foodProductId", foodId)
        .set("cupsPerMeal", ui.prompt.optionalNumber("How many cups per meal? "))
        .set("mealsPerDay", ui.prompt.optionalInt("How many meals a day? (Enter for 2) "))
        .set("reason", "Food portion recorded");
    try {
      Map<String, Object> reply = ui.api.post("/api/food/diets", body.map());
      ui.showResult(reply);
      printFoodStatus(ApiClient.object(reply.get("food")));
    } catch (ApiClient.Failure e) {
      // FD-7: prescription food needs a prescription behind it. That used to be a separate
      // command somebody had to know existed; the moment you actually need it is right here.
      if (!looksLikeMissingPrescription(e)) throw e;
      ui.showFailure(e);
      Term.out();
      if (!ui.prompt.yesNo("Has the vet prescribed it? Record the prescription now?", false)) {
        Term.info("Nothing was changed.");
        return;
      }
      ui.showResult(ui.api.post("/api/food/prescriptions", ApiClient.body()
          .set("animalId", animalId).set("foodProductId", foodId)
          .set("startDate", ui.prompt.date("Prescribed from", true))
          .set("endDate", ui.prompt.date("Until (Enter for open-ended)", false))
          .set("reason", "Vet prescription recorded").map()));
      Map<String, Object> reply = ui.api.post("/api/food/diets", body.map());
      ui.showResult(reply);
      printFoodStatus(ApiClient.object(reply.get("food")));
    }
  }

  /**
   * Phase 1 item 7: the supply items, each with its type, its unit and the quantity on hand,
   * and a way to add one. Listing and adding were two commands; they are one screen.
   */
  static void supplies(Ui ui) {
    Map<String, Object> reply = ui.api.get("/api/food/products");
    List<Map<String, Object>> products = ApiClient.list(reply.get("products"));

    Term.heading("Supply items");
    Term.Table table = new Term.Table("id", "item", "type", "unit", "lb per unit",
        "quantity on hand", "days to arrive");
    table.right(4, 5, 6);
    for (Map<String, Object> p : products) {
      String name = ApiClient.str(p.get("name"));
      table.row(p.get("id"), ApiClient.flag(p.get("active")) ? name : Term.dim(name + " (retired)"),
          Term.words(p.get("kind")), p.get("unit"), p.get("bag_lbs"),
          p.get("quantity_on_hand"), p.get("lead_time_days"));
    }
    table.print();
    printLocations(ApiClient.list(reply.get("locations")));
    Term.out();
    Term.paragraph(Term.dim("Quantity is a count of units. 'food' shows it against what the "
        + "animals in care actually need; 'move' changes it."));

    Term.out();
    if (ui.prompt.yesNo("Add a supply item?", false)) newProduct(ui);
  }

  static void newProduct(Ui ui) {
    Term.heading("Adding a food product");
    String kind = ui.prompt.choose("Regular or prescription?", List.of("REGULAR", "PRESCRIPTION"), "REGULAR");
    Map<String, Object> settings = AdoptionScreens.settingsMap(ui);
    String defaultLead = String.valueOf(settings.getOrDefault(
        "food.default_lead_days." + kind.toLowerCase(java.util.Locale.ROOT),
        kind.equals("PRESCRIPTION") ? "10" : "4"));
    ApiClient.Body body = ApiClient.body()
        .set("name", ui.prompt.required("What is it called? "))
        .set("kind", kind)
        .set("species", ui.prompt.choose("Which animals eat it?",
            List.of("DOG", "CAT", "FERRET", "SMALL_MAMMAL", "OTHER"), "DOG"))
        .set("bagLbs", ui.prompt.withDefault("How many pounds in a bag?", "40"))
        .set("lbsPerCup", ui.prompt.withDefault("How much does a cup weigh, in pounds?",
            String.valueOf(settings.getOrDefault("food.default_lbs_per_cup", "0.25"))))
        .set("leadTimeDays", ui.prompt.withDefault("How many days does it take to arrive?", defaultLead))
        .set("reason", "Food product added");
    ui.showResult(ui.api.post("/api/food/products", body.map()));
  }

}
