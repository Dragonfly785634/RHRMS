package org.rubyhill.rhrms.api;

import org.rubyhill.rhrms.http.ApiException;
import org.rubyhill.rhrms.http.Req;
import org.rubyhill.rhrms.http.Res;
import org.rubyhill.rhrms.http.Router;
import org.rubyhill.rhrms.json.Json;
import org.rubyhill.rhrms.security.Perm;
import org.rubyhill.rhrms.security.Permissions;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Food, diets, stock and orders. Section 6.5.
 *
 * The forecast itself is the food_forecast view in sql/03_audit.sql, and every change to stock
 * goes through the move_stock() function, which does the guarded update and writes the ledger
 * row in one step. Two people cannot both take the last bag: the update refuses to go below
 * zero and the CHECK constraint is there behind it (FD-5, level 3).
 *
 * Interview 4 is why "order more" is a queue rather than an email: anybody flags it, and Diane
 * marks it ordered when she gets to it.
 */
final class FoodApi {
  private FoodApi() {}

  private static final String[] MOVEMENT_TYPES = {"RECEIVE", "OPEN", "SEND_WITH_ANIMAL",
      "TRANSFER_OUT", "TRANSFER_IN", "WRITE_OFF", "COUNT_ADJUST"};

  static void register(Router r, Ctx ctx) {

    // ================================================================ forecast
    r.route("GET", "/api/food/forecast", Perm.VIEW,
        "days of supply and reorder status for every food (FD-1 to FD-4)", req -> ctx.read(req, c -> {
      List<Map<String, Object>> rows = Ctx.query(c, """
          SELECT f.id, f.name, f.kind, f.lead_time_days, f.sealed_bags, f.lbs_on_hand,
                 f.lbs_per_day, f.days_of_supply, f.eaten_by, f.open_order_status, f.expected_on,
                 f.food_status,
                 (SELECT count(*) FROM rhrms.open_bag ob
                   WHERE ob.food_product_id = f.id AND ob.finished_at IS NULL) AS open_bags,
                 -- FD-3: an animal eating this food with no portion recorded makes the whole
                 -- forecast a guess, so say so instead of quietly under-counting.
                 (SELECT string_agg(coalesce(a.name, a.animal_code), ', ')
                    FROM rhrms.prescription p
                    JOIN rhrms.animal a ON a.id = p.animal_id
                   WHERE p.food_product_id = f.id
                     AND (p.end_date IS NULL OR p.end_date >= CURRENT_DATE)
                     AND EXISTS (SELECT 1 FROM rhrms.stay s
                                  WHERE s.animal_id = a.id AND s.ended_on IS NULL)
                     AND NOT EXISTS (SELECT 1 FROM rhrms.diet d
                                      WHERE d.animal_id = a.id AND d.food_product_id = f.id
                                        AND d.valid_to IS NULL)) AS prescribed_without_portion
          FROM rhrms.food_forecast f
          ORDER BY CASE f.food_status WHEN 'REORDER NOW' THEN 1 WHEN 'REORDER SOON' THEN 2
                                      WHEN 'ON ORDER' THEN 3 WHEN 'OK' THEN 4 ELSE 5 END,
                   f.days_of_supply NULLS LAST, f.name
          """);
      List<Map<String, Object>> stock = Ctx.query(c, """
          SELECT l.food_product_id, f.name AS food, loc.id AS location_id, loc.name AS location,
                 l.sealed_bags, l.version
          FROM rhrms.stock_level l
          JOIN rhrms.food_product f   ON f.id = l.food_product_id
          JOIN rhrms.stock_location loc ON loc.id = l.location_id
          ORDER BY f.name, loc.name
          """);
      // FD-4: what the red pop-up is for. REORDER NOW with nothing on order.
      List<Object> alerts = new ArrayList<>();
      for (Map<String, Object> row : rows) {
        if ("REORDER NOW".equals(row.get("food_status"))) {
          alerts.add(Json.obj()
              .put("foodProductId", row.get("id"))
              .put("name", row.get("name"))
              .put("kind", row.get("kind"))
              .put("daysOfSupply", row.get("days_of_supply"))
              .put("leadTimeDays", row.get("lead_time_days"))
              .put("eatenBy", row.get("eaten_by"))
              .put("message", row.get("name") + ": about " + row.get("days_of_supply")
                  + " days left and it takes about " + row.get("lead_time_days")
                  + " days to arrive. Order it now."));
        }
      }
      return Res.ok(Json.obj()
          .put("count", rows.size())
          .put("foods", rows)
          .put("stockByLocation", stock)
          .put("alerts", alerts)
          .put("bufferDays", ctx.settings().getInt("food.buffer_days", 5)));
    }));

    /*
     * Phase 1 item 10. Two numbers side by side for every supply item: how much is on hand, and
     * how much the animals currently in care actually need. The need is not a guess or a
     * historical average - it is the sum of the dietary requirements recorded against animals
     * with a stay in progress (item 9), which is why item 1 puts the diet on the intake form.
     */
    r.route("GET", "/api/inventory", Perm.VIEW,
        "supplies on hand against what the animals now in care need", req -> ctx.read(req, c -> {
      List<Map<String, Object>> rows = Ctx.query(c, """
          SELECT p.id, p.name, p.kind AS type, p.unit, p.bag_lbs AS lbs_per_unit,
                 p.lead_time_days, p.active,
                 coalesce(onhand.quantity, 0)                       AS quantity_on_hand,
                 round(coalesce(onhand.quantity, 0) * p.bag_lbs, 1) AS lbs_sealed_on_hand,
                 f.lbs_on_hand,
                 coalesce(f.lbs_per_day, 0)                         AS lbs_needed_per_day,
                 f.days_of_supply,
                 f.food_status                                      AS status,
                 coalesce(need.animals, 0)                          AS animals_needing_it,
                 need.who                                           AS eaten_by,
                 round(coalesce(f.lbs_per_day, 0) * 7, 1)           AS lbs_needed_this_week,
                 round(coalesce(f.lbs_per_day, 0) * 30, 1)          AS lbs_needed_this_month
          FROM rhrms.food_product p
          LEFT JOIN rhrms.food_forecast f ON f.id = p.id
          LEFT JOIN (SELECT l.food_product_id, sum(l.sealed_bags) AS quantity
                       FROM rhrms.stock_level l GROUP BY l.food_product_id) onhand
                 ON onhand.food_product_id = p.id
          LEFT JOIN (SELECT d.food_product_id, count(*) AS animals,
                            string_agg(coalesce(a.name, a.animal_code), ', ' ORDER BY a.id) AS who
                       FROM rhrms.diet d
                       JOIN rhrms.animal a ON a.id = d.animal_id
                       JOIN rhrms.stay st  ON st.animal_id = a.id AND st.ended_on IS NULL
                      WHERE d.valid_to IS NULL
                      GROUP BY d.food_product_id) need
                 ON need.food_product_id = p.id
          WHERE p.active
          ORDER BY CASE f.food_status WHEN 'REORDER NOW' THEN 1 WHEN 'REORDER SOON' THEN 2
                                      WHEN 'ON ORDER' THEN 3 ELSE 4 END,
                   f.days_of_supply NULLS LAST, p.name
          """);
      long animalsInCare = ((Number) Ctx.scalar(c,
          "SELECT count(*) FROM rhrms.stay WHERE ended_on IS NULL")).longValue();
      long withoutDiet = ((Number) Ctx.scalar(c, """
          SELECT count(*) FROM rhrms.animal a
          JOIN rhrms.stay s ON s.animal_id = a.id AND s.ended_on IS NULL
          WHERE NOT EXISTS (SELECT 1 FROM rhrms.diet d
                             WHERE d.animal_id = a.id AND d.valid_to IS NULL)
          """)).longValue();
      return Res.list("supplies", rows, Map.of(
          "animalsInCare", animalsInCare,
          "animalsWithNoDietaryRequirement", withoutDiet,
          "note", withoutDiet == 0
              ? "Every animal in care has a dietary requirement recorded, so the needed figures "
                + "cover all of them."
              : withoutDiet + " animal(s) in care have no dietary requirement recorded, so the "
                + "needed figures are lower than the truth. Record their food to fix it."));
    }));

    // ================================================================ products
    r.route("GET", "/api/food/products", Perm.VIEW,
        "the supply items: type, unit and quantity on hand, plus the storage locations",
        req -> ctx.read(req, c -> Res.ok(Json.obj()
            .put("products", Ctx.query(c, """
                SELECT p.id, p.name, p.kind, p.unit, p.species, p.bag_lbs, p.lbs_per_cup,
                       p.lead_time_days, p.active, p.version,
                       -- type = kind, unit = unit, quantity = this count of units on hand
                       (SELECT coalesce(sum(l.sealed_bags), 0) FROM rhrms.stock_level l
                         WHERE l.food_product_id = p.id) AS quantity_on_hand,
                       (SELECT coalesce(sum(l.sealed_bags), 0) FROM rhrms.stock_level l
                         WHERE l.food_product_id = p.id) AS sealed_bags
                FROM rhrms.food_product p
                ORDER BY p.active DESC, p.kind, p.name
                """))
            .put("locations", Ctx.query(c,
                "SELECT id, name FROM rhrms.stock_location ORDER BY id")))));

    r.route("POST", "/api/food/products", Perm.FOOD_PRODUCT_EDIT, "add a food product", req -> {
      String name = req.requireText("name", "The food's name");
      String kind = req.requireOneOf("kind", "Kind", "REGULAR", "PRESCRIPTION");
      // Phase 1 item 7: type, unit, quantity. The unit is whatever the rescue counts in.
      String unit = req.has("unit") ? req.requireText("unit", "The unit it is counted in") : "bag";
      // Defaults come from the settings, because Interview 2 and Interview 4 gave different
      // lead times and Diane may change them again.
      int leadTime = req.has("leadTimeDays") ? req.requireInt("leadTimeDays", "Lead time in days")
          : ctx.settings().getInt("food.default_lead_days." + kind.toLowerCase(java.util.Locale.ROOT),
              kind.equals("PRESCRIPTION") ? 10 : 4);
      BigDecimal bagLbs = req.has("bagLbs") ? req.optDecimal("bagLbs") : new BigDecimal("40");
      BigDecimal lbsPerCup = req.has("lbsPerCup") ? req.optDecimal("lbsPerCup")
          : ctx.settings().getDecimal("food.default_lbs_per_cup", new BigDecimal("0.25"));
      if (bagLbs.signum() <= 0) throw ApiException.badRequest("A bag has to weigh more than zero.");
      if (lbsPerCup.signum() <= 0) throw ApiException.badRequest("A cup has to weigh more than zero.");
      if (leadTime < 0) throw ApiException.badRequest("A lead time cannot be negative.");
      return ctx.write(req, c -> {
        long id = Ctx.insertReturningId(c, """
            INSERT INTO rhrms.food_product(name, kind, unit, species, bag_lbs, lbs_per_cup,
                                           lead_time_days)
            VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
            """, name, kind, unit,
            req.optOneOf("species", "Species", "DOG", "CAT", "FERRET", "SMALL_MAMMAL", "OTHER"),
            bagLbs, lbsPerCup, leadTime);
        return Res.created(Json.obj()
            .put("product", Ctx.queryOne(c, "SELECT id, name, kind, unit, species, bag_lbs,"
                + " lbs_per_cup, lead_time_days, active, version FROM rhrms.food_product WHERE id = ?", id))
            .put("message", name + " added, counted in " + unit + "s of "
                + bagLbs.toPlainString() + " lb. Pounds per cup is " + lbsPerCup.toPlainString()
                + "; weigh a cup and correct it if that is wrong, because the whole forecast rests on it."));
      });
    });

    r.route("PATCH", "/api/food/products/{id}", Perm.FOOD_PRODUCT_EDIT, "change a food product", req -> {
      long id = req.pathId("id");
      int version = req.requireInt("version", "The version you are editing");
      return ctx.writeWithReason(req, c -> {
        Patch patch = new Patch()
            .text(req, "name", "name")
            .text(req, "unit", "unit")
            .oneOf(req, "species", "species", "Species", "DOG", "CAT", "FERRET", "SMALL_MAMMAL", "OTHER")
            .decimal(req, "bagLbs", "bag_lbs")
            .decimal(req, "lbsPerCup", "lbs_per_cup")
            .integer(req, "leadTimeDays", "lead_time_days")
            .bool(req, "active", "active");
        if (patch.run(c, "rhrms.food_product", id, version) != 1) {
          if (Ctx.scalar(c, "SELECT 1 FROM rhrms.food_product WHERE id = ?", id) == null) {
            throw ApiException.notFound("There is no supply item number " + id + ".");
          }
          throw Patch.staleEdit("supply item");
        }
        return Res.ok(Json.obj()
            .put("product", Ctx.queryOne(c, "SELECT id, name, kind, unit, species, bag_lbs,"
                + " lbs_per_cup, lead_time_days, active, version FROM rhrms.food_product WHERE id = ?", id))
            .put("changed", patch.fields()).put("message", "Saved."));
      });
    });

    r.route("POST", "/api/food/locations", Perm.FOOD_PRODUCT_EDIT, "add a storage location", req -> {
      String name = req.requireText("name", "The location's name");
      return ctx.write(req, c -> {
        long id = Ctx.insertReturningId(c,
            "INSERT INTO rhrms.stock_location(name) VALUES (?) RETURNING id", name);
        return Res.created(Json.obj().put("id", id).put("name", name)
            .put("message", name + " added as a place food is kept."));
      });
    });

    // ============================================================== stock moves
    /*
     * One endpoint for every kind of stock movement, because they are all the same operation
     * underneath: change the sealed count, write a ledger row, never go below zero.
     *
     * The two that need a director-or-staff hand AND a password are WRITE_OFF and COUNT_ADJUST.
     * Those are the two that can make stock disappear without anything leaving the building, so
     * they are exactly where an audit trail earns its keep (FD-8).
     */
    r.route("POST", "/api/food/movements", Perm.FOOD_OPEN_RECEIVE,
        "receive, open, transfer, write off or recount food (FD-5 to FD-8)", req -> {
      long productId = req.requireLong("foodProductId", "Which food");
      int locationId = req.requireInt("locationId", "Which storage location");
      String type = req.requireOneOf("type", "The kind of movement", MOVEMENT_TYPES);
      String movementReason = req.optText("movementReason");
      Long animalId = req.optLong("animalId");

      // Section 7: a volunteer may open and receive food, nothing else. Each kind is checked
      // against its own permission, so the refusal names the thing that was actually refused
      // rather than whichever check happened to run first.
      if (type.equals("WRITE_OFF") || type.equals("COUNT_ADJUST")) {
        Permissions.require(req.role(), Perm.STOCK_ADJUST);
      } else if (!type.equals("RECEIVE") && !type.equals("OPEN")) {
        Permissions.require(req.role(), Perm.FOOD_ORDER_MANAGE);
      }
      if (type.equals("WRITE_OFF") || type.equals("COUNT_ADJUST")) {
        if (movementReason == null || movementReason.length() < 5) {
          throw ApiException.badRequest("A " + type.toLowerCase().replace('_', ' ')
              + " has to say why, in words. It is the only record of where the food went.");
        }
        ctx.auth().confirm(req.requireSession(), req.confirmPassword(),
            type.equals("WRITE_OFF") ? "write off food" : "correct a stock count", req.clientLabel());
      }
      if (type.equals("SEND_WITH_ANIMAL") && animalId == null) {
        throw ApiException.badRequest("Which animal is the food going with?");
      }

      // FD-8: a count adjustment is "the shelf says N", not "add or remove N".
      if (type.equals("COUNT_ADJUST")) {
        int counted = req.requireInt("countedBags", "The number of bags you counted");
        if (counted < 0) throw ApiException.badRequest("A counted number cannot be negative.");
        return ctx.db().tx(req.role(), req.userId(),
            "Stock count: " + movementReason, null, c -> countAdjust(c, req, productId, locationId,
                counted, movementReason));
      }

      int bags = req.requireInt("bags", "How many bags");
      if (bags <= 0) throw ApiException.badRequest("Enter a number of bags greater than zero.");
      if (type.equals("OPEN") && bags != 1) {
        throw ApiException.badRequest("You open one bag at a time.");
      }

      return ctx.db().tx(req.role(), req.userId(),
          req.reason() == null ? describeMovement(type) : req.reason(), null, c -> {
        Object left = Ctx.scalar(c, "SELECT rhrms.move_stock(?, ?::smallint, ?, ?, ?, ?, ?)",
            productId, locationId, type, bags, req.userId(), movementReason, animalId);
        return Res.created(Json.obj()
            .put("sealedBagsLeftHere", left)
            .put("food", foodStatus(c, productId))
            .put("message", describeMovement(type) + ": " + bags + " bag(s). "
                + left + " sealed bag(s) left at that location."));
      });
    });

    r.route("GET", "/api/food/movements", Perm.VIEW, "the stock ledger", req -> {
      Long productId = req.queryId("product");
      int limit = req.queryInt("limit", 100, 1000);
      return ctx.read(req, c -> Res.list("movements", Ctx.query(c, """
          SELECT m.id, m.at, m.type, m.bags, m.reason,
                 f.id AS food_product_id, f.name AS food,
                 loc.name AS location, u.display_name AS recorded_by,
                 a.animal_code, a.name AS animal_name
          FROM rhrms.stock_movement m
          JOIN rhrms.food_product f     ON f.id = m.food_product_id
          JOIN rhrms.stock_location loc ON loc.id = m.location_id
          JOIN rhrms.staff_user u       ON u.id = m.recorded_by
          LEFT JOIN rhrms.animal a      ON a.id = m.animal_id
          WHERE ?::bigint IS NULL OR m.food_product_id = ?::bigint
          ORDER BY m.at DESC, m.id DESC
          LIMIT ?::int
          """, productId, productId, limit)));
    });

    // =================================================================== diets
    r.route("POST", "/api/food/diets", Perm.DIET_EDIT,
        "set how much of a food an animal eats (FD-1)", req -> {
      long animalId = req.requireLong("animalId", "Which animal");
      long productId = req.requireLong("foodProductId", "Which food");
      BigDecimal cups = req.optDecimal("cupsPerMeal");
      int meals = req.has("mealsPerDay") ? req.requireInt("mealsPerDay", "Meals per day") : 2;
      if (cups == null) throw ApiException.badRequest("How many cups per meal?");
      if (cups.signum() <= 0 || cups.compareTo(new BigDecimal("4")) > 0) {
        throw ApiException.badRequest("Cups per meal has to be between 0 and 4.");
      }
      if (meals < 1 || meals > 4) throw ApiException.badRequest("Meals per day has to be 1 to 4.");
      return ctx.write(req, c -> {
        AnimalApi.requireAnimal(c, animalId);
        Map<String, Object> food = Ctx.queryOne(c,
            "SELECT id, name, active FROM rhrms.food_product WHERE id = ?", productId);
        if (food == null) throw ApiException.notFound("There is no food product number " + productId + ".");
        if (!Boolean.TRUE.equals(food.get("active"))) {
          throw ApiException.badRequest("Food product " + food.get("name")
              + " is inactive. Choose an active supply item for this diet.");
        }
        // One current portion per animal per food (unique index). Replacing a portion ends the
        // old one rather than editing it, so the forecast history stays honest.
        Ctx.update(c, "UPDATE rhrms.diet SET valid_to = CURRENT_DATE"
            + " WHERE animal_id = ? AND food_product_id = ? AND valid_to IS NULL", animalId, productId);
        long id = Ctx.insertReturningId(c, """
            INSERT INTO rhrms.diet(animal_id, food_product_id, cups_per_meal, meals_per_day)
            VALUES (?, ?, ?, ?) RETURNING id
            """, animalId, productId, cups, meals);
        return Res.created(Json.obj()
            .put("dietId", id)
            .put("food", foodStatus(c, productId))
            .put("message", "Recorded. That is now counted in the daily use for this food."));
      });
    });

    r.route("POST", "/api/food/diets/{id}/end", Perm.DIET_EDIT, "stop a food portion", req -> {
      long id = req.pathId("id");
      return ctx.write(req, c -> {
        Map<String, Object> diet = Ctx.queryOneOr404(c, "There is no food portion number " + id + ".",
            "SELECT id, animal_id, food_product_id, valid_to FROM rhrms.diet WHERE id = ?", id);
        if (diet.get("valid_to") != null) {
          throw ApiException.badRequest("That food portion already ended on " + diet.get("valid_to") + ".");
        }
        Ctx.update(c, "UPDATE rhrms.diet SET valid_to = CURRENT_DATE WHERE id = ?", id);
        return Res.done("Stopped. It is no longer counted in the daily use.");
      });
    });

    // =========================================================== prescriptions
    r.route("POST", "/api/food/prescriptions", Perm.PRESCRIPTION_EDIT,
        "record that a vet has prescribed a food for an animal (FD-7)", req -> {
      long animalId = req.requireLong("animalId", "Which animal");
      long productId = req.requireLong("foodProductId", "Which food");
      LocalDate start = req.has("startDate") ? req.requireDate("startDate", "Start date") : LocalDate.now();
      LocalDate end = req.optDate("endDate");
      if (end != null && end.isBefore(start)) {
        throw ApiException.badRequest("The end date is before the start date.");
      }
      return ctx.write(req, c -> {
        AnimalApi.requireAnimal(c, animalId);
        Map<String, Object> food = Ctx.queryOne(c,
            "SELECT name, kind, active FROM rhrms.food_product WHERE id = ?", productId);
        if (food == null) throw ApiException.notFound("There is no food product number " + productId + ".");
        if (!Boolean.TRUE.equals(food.get("active"))) {
          throw ApiException.badRequest("Food product " + food.get("name")
              + " is inactive and cannot receive a new prescription.");
        }
        if (!"PRESCRIPTION".equals(food.get("kind"))) {
          throw ApiException.badRequest("That food is not a prescription food, so no prescription is needed. "
              + "Just set a portion for the animal.");
        }
        if (Ctx.scalar(c, "SELECT 1 FROM rhrms.prescription WHERE animal_id = ? AND food_product_id = ?"
            + " AND (end_date IS NULL OR end_date >= CURRENT_DATE)", animalId, productId) != null) {
          throw ApiException.conflict("That prescription is already active for this animal. "
              + "End the existing prescription before recording another one.");
        }
        long id = Ctx.insertReturningId(c,
            "INSERT INTO rhrms.prescription(food_product_id, animal_id, start_date, end_date)"
                + " VALUES (?, ?, ?, ?) RETURNING id", productId, animalId, start, end);
        return Res.created(Json.obj().put("prescriptionId", id)
            .put("message", "Recorded. This animal may now be fed that food, and a bag can go home "
                + "with it. No other animal can."));
      });
    });

    // ================================================================== orders
    r.route("GET", "/api/food/orders", Perm.VIEW, "the food order queue (FD-9)", req -> {
      boolean openOnly = req.queryFlag("open");
      return ctx.read(req, c -> Res.list("orders", Ctx.query(c, """
          SELECT o.id, o.bags, o.status, o.requested_at, o.ordered_at, o.expected_on,
                 o.received_at, o.cost, o.note, o.version,
                 f.id AS food_product_id, f.name AS food, f.kind, f.lead_time_days,
                 ru.display_name AS requested_by, ou.display_name AS ordered_by
          FROM rhrms.food_order o
          JOIN rhrms.food_product f ON f.id = o.food_product_id
          JOIN rhrms.staff_user ru  ON ru.id = o.requested_by
          LEFT JOIN rhrms.staff_user ou ON ou.id = o.ordered_by
          WHERE ?::boolean = false OR o.status IN ('REQUESTED','ORDERED')
          ORDER BY CASE o.status WHEN 'REQUESTED' THEN 1 WHEN 'ORDERED' THEN 2 ELSE 3 END,
                   o.requested_at
          """, openOnly)));
    });

    r.route("POST", "/api/food/orders", Perm.FOOD_ORDER_REQUEST,
        "flag a food to be ordered; anybody may do this (FD-9)", req -> {
      long productId = req.requireLong("foodProductId", "Which food");
      int bags = req.has("bags") ? req.requireInt("bags", "How many bags") : 1;
      if (bags <= 0) throw ApiException.badRequest("Ask for at least one bag.");
      return ctx.write(req, c -> {
        Map<String, Object> product = Ctx.queryOne(c,
            "SELECT name, active FROM rhrms.food_product WHERE id = ?", productId);
        if (product == null) throw ApiException.notFound("There is no food product number " + productId + ".");
        if (!Boolean.TRUE.equals(product.get("active"))) {
          throw ApiException.badRequest("Food product " + product.get("name")
              + " is inactive and cannot be ordered.");
        }
        long id = Ctx.insertReturningId(c,
            "INSERT INTO rhrms.food_order(food_product_id, bags, requested_by, note)"
                + " VALUES (?, ?, ?, ?) RETURNING id", productId, bags, req.userId(), req.optText("note"));
        return Res.created(Json.obj().put("orderId", id)
            .put("food", foodStatus(c, productId))
            .put("message", "Flagged for ordering. It is on the list for staff to order."));
      });
    });

    r.route("POST", "/api/food/orders/{id}/ordered", Perm.FOOD_ORDER_MANAGE,
        "mark a food order as actually ordered (FD-9)", req -> {
      long id = req.pathId("id");
      LocalDate expected = req.optDate("expectedOn");
      return ctx.write(req, c -> {
        Map<String, Object> order = requireOrder(c, id);
        if (!"REQUESTED".equals(order.get("status"))) {
          throw ApiException.badRequest("Order " + id + " is already "
              + String.valueOf(order.get("status")).toLowerCase() + ".");
        }
        Integer bags = req.optInt("bags");
        int changed = Ctx.update(c, """
            UPDATE rhrms.food_order
               SET status = 'ORDERED', ordered_by = ?, ordered_at = now(), expected_on = ?,
                   bags = coalesce(?, bags), note = coalesce(?, note), version = version + 1
             WHERE id = ? AND version = ?
            """, req.userId(), expected, bags, req.optText("note"), id,
            ((Number) order.get("version")).intValue());
        if (changed != 1) throw Patch.staleEdit("food order");
        return Res.ok(Json.obj().put("order", Ctx.queryOne(c,
                "SELECT id, status, bags, expected_on, version FROM rhrms.food_order WHERE id = ?", id))
            .put("message", "Marked as ordered"
                + (expected == null ? ". Add the expected date when you know it." : ", due " + expected + ".")
                + " The red warning for this food stops until then."));
      });
    });

    r.route("POST", "/api/food/orders/{id}/received", Perm.FOOD_ORDER_MANAGE,
        "mark a food order received; adds the bags to stock (FD-9)", req -> {
      long id = req.pathId("id");
      int locationId = req.requireInt("locationId", "Which storage location it went into");
      return ctx.write(req, c -> {
        Map<String, Object> order = requireOrder(c, id);
        String status = String.valueOf(order.get("status"));
        if (!List.of("REQUESTED", "ORDERED").contains(status)) {
          throw ApiException.badRequest("Order " + id + " is already " + status.toLowerCase() + ".");
        }
        int bags = req.has("bags") ? req.requireInt("bags", "How many bags arrived")
                                   : ((Number) order.get("bags")).intValue();
        if (bags <= 0) throw ApiException.badRequest("At least one bag has to have arrived.");
        long productId = ((Number) order.get("food_product_id")).longValue();

        // Receiving is two facts: the order is closed, and the bags are on the shelf. One
        // transaction, so the shelf and the paperwork can never disagree.
        int changed = Ctx.update(c, """
            UPDATE rhrms.food_order
               SET status = 'RECEIVED', received_at = now(), bags = ?, cost = coalesce(?, cost),
                   ordered_by = coalesce(ordered_by, ?), ordered_at = coalesce(ordered_at, now()),
                   note = coalesce(?, note), version = version + 1
             WHERE id = ? AND version = ?
            """, bags, req.optMoney("cost"), req.userId(), req.optText("note"), id,
            ((Number) order.get("version")).intValue());
        if (changed != 1) throw Patch.staleEdit("food order");

        Object left = Ctx.scalar(c, "SELECT rhrms.move_stock(?, ?::smallint, 'RECEIVE', ?, ?, ?, NULL, NULL, ?)",
            productId, locationId, bags, req.userId(), "Order " + id + " arrived", id);
        return Res.ok(Json.obj()
            .put("sealedBagsAtLocation", left)
            .put("food", foodStatus(c, productId))
            .put("message", bags + " bag(s) received and added to stock. "
                + left + " sealed bag(s) there now."));
      });
    });

    r.route("POST", "/api/food/orders/{id}/cancel", Perm.FOOD_ORDER_MANAGE, "cancel a food order", req -> {
      long id = req.pathId("id");
      String note = req.requireText("note", "Why it is cancelled", 3);
      return ctx.write(req, c -> {
        Map<String, Object> order = requireOrder(c, id);
        String status = String.valueOf(order.get("status"));
        if (!List.of("REQUESTED", "ORDERED").contains(status)) {
          throw ApiException.badRequest("Order " + id + " is already " + status.toLowerCase() + ".");
        }
        int changed = Ctx.update(c, "UPDATE rhrms.food_order SET status = 'CANCELLED',"
                + " note = ?, version = version + 1 WHERE id = ? AND version = ?",
            note, id, ((Number) order.get("version")).intValue());
        if (changed != 1) throw Patch.staleEdit("food order");
        return Res.done("Order " + id + " cancelled. The reorder warning for this food comes back.");
      });
    });

    // =============================================================== donations
    r.route("GET", "/api/donations", Perm.VIEW, "the donation log with a year-to-date total (DN-1)",
        req -> {
      int year = req.queryInt("year", LocalDate.now().getYear(), 3000);
      return ctx.read(req, c -> {
        List<Map<String, Object>> rows = Ctx.query(c, """
            SELECT d.id, d.received_on, d.donor_name, d.kind, d.amount, d.description,
                   u.display_name AS recorded_by
            FROM rhrms.donation d JOIN rhrms.staff_user u ON u.id = d.recorded_by
            WHERE extract(YEAR FROM d.received_on) = ?::int
            ORDER BY d.received_on DESC, d.id DESC
            """, year);
        return Res.ok(Json.obj()
            .put("year", year)
            .put("count", rows.size())
            .put("donations", rows)
            .put("moneyTotal", Ctx.scalar(c, "SELECT coalesce(sum(amount), 0) FROM rhrms.donation"
                + " WHERE kind = 'MONEY' AND extract(YEAR FROM received_on) = ?::int", year))
            .put("note", "A record of what came in, so the director knows what money is available. "
                + "Nothing is deducted here and there are no bank features (Interview 3)."));
      });
    });

    r.route("POST", "/api/donations", Perm.DONATION, "record a donation (DN-1, FD-10)", req -> {
      String kind = req.requireOneOf("kind", "Kind of donation", "MONEY", "FOOD", "OTHER");
      LocalDate receivedOn = req.has("receivedOn") ? req.requireDate("receivedOn", "Date received")
                                                   : LocalDate.now();
      BigDecimal amount = req.optMoney("amount");
      if (kind.equals("MONEY") && amount == null) {
        throw ApiException.badRequest("How much money was donated?");
      }
      if (amount != null && amount.signum() < 0) throw ApiException.badRequest("An amount cannot be negative.");
      if (receivedOn.isAfter(LocalDate.now())) {
        throw ApiException.badRequest("A donation cannot be received in the future.");
      }
      // FD-10: donated food is both a donation record and bags on the shelf.
      Long productId = req.optLong("foodProductId");
      Integer bags = req.optInt("bags");
      Integer locationId = req.optInt("locationId");
      if (kind.equals("FOOD") && productId != null && (bags == null || locationId == null)) {
        throw ApiException.badRequest("For donated food, say how many bags and which storage location, "
            + "so the stock count and the forecast stay right.");
      }

      return ctx.write(req, c -> {
        long id = Ctx.insertReturningId(c, """
            INSERT INTO rhrms.donation(received_on, donor_name, kind, amount, description, recorded_by)
            VALUES (?, ?, ?, ?, ?, ?) RETURNING id
            """, receivedOn, req.optText("donorName"), kind, amount, req.optText("description"),
            req.userId());
        Json.Obj out = Json.obj().put("donationId", id);
        if (kind.equals("FOOD") && productId != null) {
          Object left = Ctx.scalar(c,
              "SELECT rhrms.move_stock(?, ?::smallint, 'RECEIVE', ?, ?, ?, NULL, ?)",
              productId, locationId, bags, req.userId(), "Donated food", id);
          out.put("sealedBagsAtLocation", left).put("food", foodStatus(c, productId));
          return Res.created(out.put("message", "Donation recorded, and " + bags
              + " bag(s) added to stock."));
        }
        return Res.created(out.put("message", "Donation recorded."));
      });
    });
  }

  // =================================================================== helpers

  /** FD-8: set a location to the number actually on the shelf, recording the difference. */
  private static Res countAdjust(Connection c, Req req, long productId, int locationId,
                                 int counted, String reason) throws SQLException {
    // Claim the row first so two people counting the same shelf cannot interleave.
    Ctx.update(c, "INSERT INTO rhrms.stock_level(food_product_id, location_id) VALUES (?, ?)"
        + " ON CONFLICT DO NOTHING", productId, locationId);
    Object onHandObj = Ctx.scalar(c, "SELECT sealed_bags FROM rhrms.stock_level"
        + " WHERE food_product_id = ? AND location_id = ? FOR UPDATE", productId, locationId);
    int onHand = onHandObj == null ? 0 : ((Number) onHandObj).intValue();
    int difference = counted - onHand;
    if (difference == 0) {
      return Res.ok(Json.obj()
          .put("sealedBags", counted).put("difference", 0)
          .put("food", foodStatus(c, productId))
          .put("message", "The count matches what the system said (" + counted
              + " bag(s)). Nothing changed, so nothing was recorded."));
    }
    Ctx.update(c, "UPDATE rhrms.stock_level SET sealed_bags = ?, version = version + 1"
        + " WHERE food_product_id = ? AND location_id = ?", counted, productId, locationId);
    Ctx.update(c, """
        INSERT INTO rhrms.stock_movement(food_product_id, location_id, type, bags, reason, recorded_by)
        VALUES (?, ?, 'COUNT_ADJUST', ?, ?, ?)
        """, productId, locationId, difference,
        "Counted " + counted + ", system said " + onHand + ". " + reason, req.userId());
    return Res.ok(Json.obj()
        .put("sealedBags", counted)
        .put("wasRecordedAs", onHand)
        .put("difference", difference)
        .put("food", foodStatus(c, productId))
        .put("message", "Count corrected from " + onHand + " to " + counted + " bag(s). "
            + "The difference of " + (difference > 0 ? "+" : "") + difference
            + " and your reason are in the ledger; nothing was overwritten silently."));
  }

  private static Map<String, Object> requireOrder(Connection c, long id) throws SQLException {
    return Ctx.queryOneOr404(c, "There is no food order number " + id + ".",
        "SELECT id, food_product_id, bags, status, version FROM rhrms.food_order WHERE id = ?", id);
  }

  /** The one forecast row for a food, so a reply can say what the change did to the outlook. */
  static Map<String, Object> foodStatus(Connection c, long productId) throws SQLException {
    return Ctx.queryOne(c, """
        SELECT id, name, kind, sealed_bags, lbs_on_hand, lbs_per_day, days_of_supply,
               food_status, open_order_status, expected_on, lead_time_days, eaten_by
        FROM rhrms.food_forecast WHERE id = ?
        """, productId);
  }

  private static String describeMovement(String type) {
    return switch (type) {
      case "RECEIVE" -> "Received into stock";
      case "OPEN" -> "Opened a bag";
      case "SEND_WITH_ANIMAL" -> "Sent home with an animal";
      case "TRANSFER_OUT" -> "Moved out of this location";
      case "TRANSFER_IN" -> "Moved into this location";
      case "WRITE_OFF" -> "Written off";
      case "COUNT_ADJUST" -> "Stock count corrected";
      default -> type;
    };
  }
}
