package org.rubyhill.rhrms.api;

import org.rubyhill.rhrms.http.ApiException;
import org.rubyhill.rhrms.http.Req;
import org.rubyhill.rhrms.http.Res;
import org.rubyhill.rhrms.http.Router;
import org.rubyhill.rhrms.json.Json;
import org.rubyhill.rhrms.security.Perm;
import org.rubyhill.rhrms.security.Role;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Animals: intake (section 6.1), the animal record (6.2), kennels, restrictions, bond groups
 * and vet visits.
 *
 * The rules themselves are not here. Kennel sharing, status transitions, the name history and
 * the prescription check are all triggers in sql/02_rules.sql, so an importer or somebody with
 * psql cannot get round them. What IS here is the part a database cannot do: asking for a
 * reason, showing possible duplicates before a second record for one animal is created, and
 * refusing an action the role may not take.
 */
final class AnimalApi {
  private AnimalApi() {}

  private static final String[] SPECIES = {"DOG", "CAT", "FERRET", "SMALL_MAMMAL", "OTHER"};
  private static final String[] INTAKE_SOURCES =
      {"OWNER_SURRENDER", "STRAY", "CITY_SHELTER", "BORN_IN_CARE", "RETURN"};
  private static final String[] STATUSES =
      {"AVAILABLE", "NOT_ADOPTABLE", "PENDING_ADOPTION", "ADOPTED", "DECEASED"};
  private static final String[] RESTRICTION_TYPES = {"NO_CHILDREN", "NO_ADULT_MEN", "NO_ADULT_WOMEN",
      "NO_OTHER_DOGS", "NO_CATS", "NO_ELDERLY", "NEEDS_FENCED_YARD", "OTHER"};

  static void register(Router r, Ctx ctx) {

    // =================================================================== kennels
    r.route("GET", "/api/kennels", Perm.VIEW, "the kennel board, 1 to 40", req -> {
      boolean freeOnly = req.queryFlag("free");
      return ctx.read(req, c -> {
        List<Map<String, Object>> rows = Ctx.query(c, """
            SELECT kennel, in_service, occupants, animals
            FROM rhrms.kennel_board
            WHERE (?::boolean = false OR (animals = 0 AND in_service))
            ORDER BY kennel
            """, freeOnly);
        long free = ((Number) Ctx.scalar(c,
            "SELECT count(*) FROM rhrms.kennel_board WHERE animals = 0 AND in_service")).longValue();
        long outOfService = ((Number) Ctx.scalar(c,
            "SELECT count(*) FROM rhrms.kennel k WHERE NOT k.in_service")).longValue();
        return Res.list("kennels", rows, Map.of(
            "free", free,
            "outOfService", outOfService,
            "total", ctx.settings().getInt("kennel.count", 40)));
      });
    });

    r.route("POST", "/api/kennels/{id}/service", Perm.KENNEL_SERVICE,
        "take a kennel out of service, or put it back", req -> {
      long rawKennelId = req.pathId("id");
      if (rawKennelId < 1 || rawKennelId > Integer.MAX_VALUE) {
        throw ApiException.badRequest("'" + rawKennelId + "' is not a valid kennel number.");
      }
      int kennelId = (int) rawKennelId;
      boolean inService = req.requireBool("inService", "In service");
      String outReason = req.optText("outOfServiceReason");
      if (!inService && (outReason == null || outReason.length() < 3)) {
        throw ApiException.badRequest("Say what is wrong with kennel " + kennelId
            + " (Interview 3: kennels only go out of service for a mechanical problem).");
      }
      return ctx.writeWithReason(req, c -> {
        Map<String, Object> before = Ctx.queryOneOr404(c, "There is no kennel " + kennelId + ".",
            "SELECT id, in_service, version FROM rhrms.kennel WHERE id = ?", kennelId);
        if (!inService) {
          Object occupants = Ctx.scalar(c,
              "SELECT occupants FROM rhrms.kennel_board WHERE kennel = ?", kennelId);
          if (occupants != null) {
            throw ApiException.ruleViolation("Kennel " + kennelId + " still has " + occupants
                + " in it. Move them to another kennel first.", "RULE IN-2");
          }
        }
        int version = ((Number) before.get("version")).intValue();
        int changed = Ctx.update(c,
            "UPDATE rhrms.kennel SET in_service = ?, out_of_service_reason = ?, version = version + 1"
                + " WHERE id = ? AND version = ?",
            inService, inService ? null : outReason, kennelId, version);
        if (changed != 1) throw Patch.staleEdit("kennel");
        return Res.done("Kennel " + kennelId + (inService ? " is back in service." : " is out of service."));
      });
    });

    // =================================================================== search
    r.route("GET", "/api/animals", Perm.VIEW,
        "search animals by name, former name or code (LK-1)", req -> {
      String q = req.queryParam("q", null);
      String status = req.queryParam("status", null);
      int limit = req.queryInt("limit", 50, 500);
      return ctx.read(req, c -> Res.list("animals", Ctx.query(c, """
          SELECT a.id, a.animal_code, a.name, a.species, a.breed, a.sex, a.status,
                 a.vet_status, a.spay_neuter, a.kennel_id, a.birth_date, a.birth_date_is_estimate,
                 a.needs_split, a.bond_group_id, a.litter_id, a.version,
                 -- AN-2: "Luna (formerly Princess)" has to be findable under either name
                 (SELECT string_agg(DISTINCT h.name, ', ' ORDER BY h.name)
                    FROM rhrms.animal_name_history h
                   WHERE h.animal_id = a.id AND h.name IS DISTINCT FROM a.name) AS former_names,
                 (SELECT count(*) FROM rhrms.application ap
                   WHERE ap.animal_id = a.id
                     AND ap.status IN ('SUBMITTED','UNDER_REVIEW','APPROVED')) AS open_applications,
                 (SELECT count(*) FROM rhrms.restriction rr
                   WHERE rr.animal_id = a.id AND rr.active) AS active_restrictions
          FROM rhrms.animal a
          WHERE (?::text IS NULL
                 OR a.animal_code ILIKE '%' || ?::text || '%'
                 OR a.name ILIKE '%' || ?::text || '%'
                 -- Breed matters as much as name at a front desk: "have we got any shepherds?"
                 -- is a question people actually ask, and /api/animals/available has always
                 -- answered it. This search did not, so the same query gave different answers
                 -- depending on which endpoint you reached it through. Found when the rescue's
                 -- own spreadsheet arrived with six shepherd mixes in it and none of them
                 -- turned up under 'shep'.
                 OR a.breed ILIKE '%' || ?::text || '%'
                 OR EXISTS (SELECT 1 FROM rhrms.animal_name_history h
                             WHERE h.animal_id = a.id AND h.name ILIKE '%' || ?::text || '%'))
            AND (?::text IS NULL OR a.status = ?::text)
          ORDER BY (a.status = 'AVAILABLE') DESC, a.kennel_id NULLS LAST, lower(a.name) NULLS LAST
          LIMIT ?::int
          """, q, q, q, q, q, status, status, limit)));
    });

    /*
     * Phase 1 item 3. The same search as above, narrowed to what somebody at the desk can
     * actually offer a walk-in: AVAILABLE, and still here. An animal that is on hold for an
     * approved applicant, not adoptable, adopted or dead is not on this list, and neither is one
     * whose stay has ended - which is the difference between this and filtering on status alone.
     */
    r.route("GET", "/api/animals/available", Perm.VIEW,
        "the animals that can be offered to the public right now, searchable", req -> {
      String q = req.queryParam("q", null);
      String species = req.queryParam("species", null);
      int limit = req.queryInt("limit", 200, 500);
      return ctx.read(req, c -> {
        List<Map<String, Object>> rows = Ctx.query(c, """
            SELECT a.id, a.animal_code, a.name, a.species, a.breed, a.sex, a.color_markings,
                   a.birth_date, a.birth_date_is_estimate, a.kennel_id, a.vet_status,
                   a.spay_neuter, a.behavior_notes, a.status, a.bond_group_id, a.version,
                   CASE WHEN a.birth_date IS NULL THEN NULL
                        ELSE trunc(extract(YEAR FROM age(a.birth_date)))::int END AS age_years,
                   CASE WHEN a.birth_date IS NULL THEN NULL
                        ELSE trunc(extract(MONTH FROM age(a.birth_date)))::int END AS age_extra_months,
                   s.intake_date,
                   (SELECT string_agg(DISTINCT h.name, ', ' ORDER BY h.name)
                      FROM rhrms.animal_name_history h
                     WHERE h.animal_id = a.id AND h.name IS DISTINCT FROM a.name) AS former_names,
                   -- Shown on the public list because it decides who may take the animal home.
                   (SELECT string_agg(r.type || coalesce(' (' || r.detail || ')', ''), '; '
                                      ORDER BY r.type)
                      FROM rhrms.restriction r
                     WHERE r.animal_id = a.id AND r.active) AS restrictions,
                   (SELECT count(*) FROM rhrms.animal o
                     WHERE o.bond_group_id = a.bond_group_id AND a.bond_group_id IS NOT NULL
                       AND o.id <> a.id AND o.status <> 'DECEASED') AS must_go_with,
                   (SELECT count(*) FROM rhrms.application ap
                     WHERE ap.animal_id = a.id
                       AND ap.status IN ('SUBMITTED','UNDER_REVIEW')) AS applications_pending
            FROM rhrms.animal a
            JOIN rhrms.stay s ON s.animal_id = a.id AND s.ended_on IS NULL
            WHERE a.status = 'AVAILABLE'
              AND (?::text IS NULL OR a.species = ?::text)
              AND (?::text IS NULL
                   OR a.name ILIKE '%' || ?::text || '%'
                   OR a.breed ILIKE '%' || ?::text || '%'
                   OR a.animal_code ILIKE '%' || ?::text || '%'
                   OR EXISTS (SELECT 1 FROM rhrms.animal_name_history h
                               WHERE h.animal_id = a.id AND h.name ILIKE '%' || ?::text || '%'))
            ORDER BY a.species, lower(a.name) NULLS LAST
            LIMIT ?::int
            """, species, species, q, q, q, q, q, limit);
        return Res.list("available", rows, Map.of(
            "searchedFor", q == null ? "everything" : q,
            "note", "Only animals that are AVAILABLE and still in care. Any restriction is listed "
                + "against the animal, because it decides who can take it."));
      });
    });

    // ---------------------------------------------------------- IN-3 duplicates
    // Registered before /api/animals/{id} so the literal path always wins.
    r.route("GET", "/api/animals/duplicate-check", Perm.VIEW,
        "possible duplicates before an intake is saved (IN-3)", req -> {
      String name = req.queryParam("name", null);
      String species = req.queryParam("species", null);
      LocalDate intakeDate = req.queryParam("intakeDate", null) == null
          ? LocalDate.now() : LocalDate.parse(req.queryParam("intakeDate", null));
      return ctx.read(req, c -> Res.list("possibleDuplicates", duplicates(c, name, species, intakeDate)));
    });

    // =============================================================== one animal
    r.route("GET", "/api/animals/{id}", Perm.VIEW, "one animal's whole record", req -> {
      long id = req.pathId("id");
      return ctx.read(req, c -> Res.ok(animalRecord(c, id)));
    });

    r.route("GET", "/api/animals/{id}/history", Perm.VIEW,
        "every recorded change to this animal (LK-5)", req -> {
      long id = req.pathId("id");
      int limit = req.queryInt("limit", 200, 1000);
      return ctx.read(req, c -> Res.list("history", ReportApi.auditFor(c, "animal", String.valueOf(id), limit)));
    });

    // ==================================================================== intake
    r.route("POST", "/api/animals/intake", Perm.INTAKE,
        "record an arrival: name, species, breed, age, medical notes and dietary requirement",
        req -> intake(ctx, req));

    r.route("POST", "/api/animals/intake/litter", Perm.INTAKE,
        "record a litter born in care: the mother's kennel, one record per young (IN-6)",
        req -> litterIntake(ctx, req));

    // ========================================================== animal record edits
    r.route("PATCH", "/api/animals/{id}", Perm.RECORD_CORRECT,
        "correct an animal's details; a reason is required (AN-4)", req -> {
      long id = req.pathId("id");
      int version = req.requireInt("version", "The version you are editing");
      return ctx.writeWithReason(req, c -> {
        Patch patch = new Patch()
            .text(req, "name", "name")
            .text(req, "breed", "breed")
            .oneOf(req, "sex", "sex", "Sex", "M", "F", "U")
            .date(req, "birthDate", "birth_date")
            .bool(req, "birthDateIsEstimate", "birth_date_is_estimate")
            .text(req, "colorMarkings", "color_markings")
            .text(req, "healthNotes", "health_notes")
            .text(req, "behaviorNotes", "behavior_notes")
            .text(req, "generalNotes", "general_notes")
            .oneOf(req, "vetStatus", "vet_status", "Vet status", "NOT_VETTED", "VETTED")
            .oneOf(req, "spayNeuter", "spay_neuter", "Spay/neuter", "INTACT", "ALTERED", "UNKNOWN")
            .bool(req, "needsSplit", "needs_split")
            .id(req, "litterId", "litter_id");
        if (req.body().containsKey("status") || req.body().containsKey("kennelId")) {
          throw ApiException.badRequest("Status and kennel are not edited here. "
              + "Use POST /api/animals/" + id + "/status or /kennel, which check the rules and ask for a reason.");
        }
        int changed = patch.run(c, "rhrms.animal", id, version);
        if (changed != 1) {
          // Either the row is gone or somebody saved first. Say which.
          if (Ctx.scalar(c, "SELECT 1 FROM rhrms.animal WHERE id = ?", id) == null) {
            throw ApiException.notFound("There is no animal number " + id + ".");
          }
          throw Patch.staleEdit("animal");
        }
        return Res.ok(Json.obj().put("animal", animalSummary(c, id))
            .put("changed", patch.fields())
            .put("message", "Saved, with your reason recorded against every field that changed."));
      });
    });

    r.route("POST", "/api/animals/{id}/status", Perm.ANIMAL_STATUS,
        "change an animal's status; the database checks the transition (AN-1)", req -> {
      long id = req.pathId("id");
      String status = req.requireOneOf("status", "Status", STATUSES);
      String notAdoptableReason = req.optText("notAdoptableReason");
      if (status.equals("NOT_ADOPTABLE") && (notAdoptableReason == null || notAdoptableReason.length() < 3)) {
        throw ApiException.badRequest("Say why this animal is not adoptable. "
            + "The reason is shown on the animal's record and in the audit log.");
      }
      // Marking an animal deceased and clearing a placement are the two irreversible ones.
      // DECEASED goes through the same step-up check as an adoption; see docs/SECURITY.md.
      if (status.equals("DECEASED")) {
        ctx.auth().confirm(req.requireSession(), req.confirmPassword(),
            "mark an animal deceased", req.clientLabel());
      }
      return ctx.writeWithReason(req, c -> {
        Map<String, Object> before = Ctx.queryOneOr404(c, "There is no animal number " + id + ".",
            "SELECT id, animal_code, name, status, kennel_id, version FROM rhrms.animal WHERE id = ?", id);
        int version = req.body().containsKey("version")
            ? req.requireInt("version", "The version you are editing")
            : ((Number) before.get("version")).intValue();

        // An animal that has died or left cannot still hold a kennel (schema CHECK), so free it
        // here rather than making the caller remember.
        boolean leaving = status.equals("DECEASED") || status.equals("ADOPTED");
        int changed = Ctx.update(c, """
            UPDATE rhrms.animal
               SET status = ?, not_adoptable_reason = ?, kennel_id = CASE WHEN ? THEN NULL ELSE kennel_id END,
                   version = version + 1
             WHERE id = ? AND version = ?
            """, status, status.equals("NOT_ADOPTABLE") ? notAdoptableReason : null, leaving, id, version);
        if (changed != 1) throw Patch.staleEdit("animal");

        if (status.equals("DECEASED")) {
          Ctx.update(c, "UPDATE rhrms.stay SET ended_on = CURRENT_DATE, end_reason = 'DECEASED'"
              + " WHERE animal_id = ? AND ended_on IS NULL", id);
          Ctx.update(c, "UPDATE rhrms.diet SET valid_to = CURRENT_DATE"
              + " WHERE animal_id = ? AND valid_to IS NULL", id);
          // AN-6: when one of a bonded pair dies the survivor is no longer a pair, and that
          // matters, because "not taking both" is a reason to deny an application (Interview 4).
          Object survivors = leftAlone(c, id);
          if (survivors != null) {
            Ctx.update(c, "UPDATE rhrms.animal SET bond_group_id = NULL, version = version + 1"
                + " WHERE bond_group_id = (SELECT bond_group_id FROM rhrms.animal WHERE id = ?)", id);
            return Res.ok(Json.obj().put("animal", animalSummary(c, id))
                .put("message", "Recorded. " + survivors
                    + " was in a bond group with this animal and is now on their own, so they can be adopted alone."));
          }
        }
        return Res.ok(Json.obj().put("animal", animalSummary(c, id)).put("message", "Status changed."));
      });
    });

    r.route("POST", "/api/animals/{id}/kennel", Perm.KENNEL_MOVE,
        "move an animal to another kennel; a reason is required (AN-3)", req -> {
      long id = req.pathId("id");
      Integer kennelId = req.optInt("kennelId");
      return ctx.writeWithReason(req, c -> {
        Map<String, Object> before = Ctx.queryOneOr404(c, "There is no animal number " + id + ".",
            "SELECT id, animal_code, name, kennel_id, status, version FROM rhrms.animal WHERE id = ?", id);
        if (kennelId == null && req.optText("reason") == null) {
          throw ApiException.badRequest("Send a kennel number, or a reason for taking this animal out of a kennel.");
        }
        int version = ((Number) before.get("version")).intValue();
        int changed = Ctx.update(c,
            "UPDATE rhrms.animal SET kennel_id = ?, version = version + 1 WHERE id = ? AND version = ?",
            kennelId, id, version);
        if (changed != 1) throw Patch.staleEdit("animal");
        return Res.ok(Json.obj().put("animal", animalSummary(c, id))
            .put("message", kennelId == null
                ? "Taken out of its kennel."
                : "Moved from kennel " + before.get("kennel_id") + " to kennel " + kennelId + "."));
      });
    });

    // ============================================================ restrictions
    r.route("GET", "/api/animals/{id}/restrictions", Perm.VIEW, "this animal's restrictions (AN-5)",
        req -> {
          long id = req.pathId("id");
          return ctx.read(req, c -> Res.list("restrictions", restrictions(c, id, req.queryFlag("all"))));
        });

    r.route("POST", "/api/animals/{id}/restrictions", Perm.RESTRICTION_EDIT,
        "add a restriction, such as no homes with young children (AN-5)", req -> {
      long id = req.pathId("id");
      String type = req.requireOneOf("type", "Restriction type", RESTRICTION_TYPES);
      Integer minChildAge = req.optInt("minChildAge");
      String detail = req.optText("detail");
      if (type.equals("OTHER") && detail == null) {
        throw ApiException.badRequest("A restriction of type OTHER has to say what it is, "
            + "because the director reads it when he decides an application.");
      }
      if (minChildAge != null && !type.equals("NO_CHILDREN")) {
        throw ApiException.badRequest("A youngest-child age only makes sense with NO_CHILDREN.");
      }
      return ctx.writeWithReason(req, c -> {
        requireAnimal(c, id);
        long newId = Ctx.insertReturningId(c,
            "INSERT INTO rhrms.restriction(animal_id, type, min_child_age, detail)"
                + " VALUES (?, ?, ?, ?) RETURNING id", id, type, minChildAge, detail);
        return Res.created(Json.obj().put("id", newId)
            .put("restrictions", restrictions(c, id, false))
            .put("message", "Restriction recorded. It will be shown to the director, in red, "
                + "against every applicant it conflicts with."));
      });
    });

    /*
     * Withdrawing a restriction is the most dangerous small action in the system. The director's
     * whole reason for wanting an audit trail is the possibility of an adopted animal biting a
     * child; "no homes with young children" quietly disappearing is exactly how that happens.
     * So: staff or director only, a reason is required, the row is kept (never deleted), and the
     * person has to re-type their password.
     */
    r.critical("POST", "/api/animals/{id}/restrictions/{restrictionId}/withdraw", Perm.RESTRICTION_EDIT,
        "withdraw an animal's restriction",
        "withdraw a restriction (kept in the record, marked inactive)", req -> {
      long id = req.pathId("id");
      long restrictionId = req.pathId("restrictionId");
      return ctx.writeWithReason(req, c -> {
        Map<String, Object> row = Ctx.queryOneOr404(c,
            "There is no restriction number " + restrictionId + " on animal " + id + ".",
            "SELECT id, type, active, version FROM rhrms.restriction WHERE id = ? AND animal_id = ?",
            restrictionId, id);
        if (!Boolean.TRUE.equals(row.get("active"))) {
          throw ApiException.badRequest("That restriction has already been withdrawn.");
        }
        int changed = Ctx.update(c,
            "UPDATE rhrms.restriction SET active = false, version = version + 1 WHERE id = ? AND version = ?",
            restrictionId, ((Number) row.get("version")).intValue());
        if (changed != 1) throw Patch.staleEdit("restriction");
        return Res.ok(Json.obj().put("restrictions", restrictions(c, id, true))
            .put("message", "Withdrawn. The record stays in the animal's history with your reason, "
                + "so the decision can still be explained later."));
      });
    });

    // ============================================================== bond groups
    r.route("POST", "/api/animals/{id}/bond", Perm.BOND_EDIT,
        "link animals that must be adopted together (AN-6)", req -> {
      long id = req.pathId("id");
      List<Object> withIds = req.optList("withAnimalIds");
      Long joinGroupId = req.optLong("bondGroupId");
      String note = req.optText("note");
      if (withIds.isEmpty() && joinGroupId == null) {
        throw ApiException.badRequest("Say which other animals are bonded to this one "
            + "(withAnimalIds), or which existing bond group to join (bondGroupId).");
      }
      return ctx.writeWithReason(req, c -> {
        Map<String, Object> animal = requireAnimal(c, id);
        long groupId;
        if (joinGroupId != null) {
          if (Ctx.scalar(c, "SELECT 1 FROM rhrms.bond_group WHERE id = ?", joinGroupId) == null) {
            throw ApiException.notFound("There is no bond group number " + joinGroupId + ".");
          }
          groupId = joinGroupId;
        } else {
          Long existing = Ctx.scalarLong(c, "SELECT bond_group_id FROM rhrms.animal WHERE id = ?", id);
          groupId = existing != null ? existing : Ctx.insertReturningId(c,
              "INSERT INTO rhrms.bond_group(note) VALUES (?) RETURNING id",
              note == null ? "Bonded; must be adopted together" : note);
        }

        List<Long> members = new ArrayList<>();
        members.add(id);
        for (Object o : withIds) members.add(((Number) o).longValue());

        List<String> warnings = new ArrayList<>();
        for (long memberId : members) {
          Map<String, Object> m = requireAnimal(c, memberId);
          if ("DECEASED".equals(m.get("status"))) {
            throw ApiException.badRequest(label(m) + " has died and cannot join a bond group.");
          }
          int changed = Ctx.update(c,
              "UPDATE rhrms.animal SET bond_group_id = ?, version = version + 1 WHERE id = ?",
              groupId, memberId);
          if (changed != 1) throw ApiException.notFound("There is no animal number " + memberId + ".");
        }
        // AN-6: they should share a kennel. Warn, do not refuse: an animal in the vet's crate
        // on the day somebody notices the bond is a real situation.
        List<Map<String, Object>> kennels = Ctx.query(c,
            "SELECT DISTINCT kennel_id FROM rhrms.animal WHERE bond_group_id = ? AND status <> 'DECEASED'",
            groupId);
        if (kennels.size() > 1) {
          warnings.add("These animals are in different kennels. A bonded group is normally housed together.");
        }
        return Res.ok(Json.obj()
            .put("bondGroupId", groupId)
            .put("members", Ctx.query(c,
                "SELECT id, animal_code, name, species, status, kennel_id FROM rhrms.animal"
                    + " WHERE bond_group_id = ? ORDER BY id", groupId))
            .putIf("warnings", warnings.isEmpty() ? null : warnings)
            .put("message", "Linked. One application now covers every living member, and the "
                + "adoption places them together." + (animal.get("name") == null ? "" : "")));
      });
    });

    r.critical("POST", "/api/animals/{id}/unbond", Perm.BOND_EDIT, "unlink a bonded animal",
        "take an animal out of its bond group", req -> {
      long id = req.pathId("id");
      return ctx.writeWithReason(req, c -> {
        Map<String, Object> animal = requireAnimal(c, id);
        if (animal.get("bond_group_id") == null) {
          throw ApiException.badRequest(label(animal) + " is not in a bond group.");
        }
        Ctx.update(c, "UPDATE rhrms.animal SET bond_group_id = NULL, version = version + 1 WHERE id = ?", id);
        return Res.done(label(animal) + " is no longer part of a bonded group and can be adopted alone.");
      });
    });

    // =============================================================== vet visits
    r.route("POST", "/api/animals/{id}/vet-visits", Perm.VET_VISIT, "record a vet visit (VV-1)", req -> {
      long id = req.pathId("id");
      LocalDate visitDate = req.has("visitDate") ? req.requireDate("visitDate", "Visit date") : LocalDate.now();
      String clinic = req.requireText("clinic", "Clinic");
      String reason = req.requireText("visitReason", "Reason for the visit");
      String outcome = req.optText("outcome");
      var cost = req.optMoney("cost");
      String reportPath = req.optText("reportPath");
      boolean marksVetted = req.flag("marksVetted");
      if (visitDate.isAfter(LocalDate.now())) {
        throw ApiException.badRequest("A vet visit cannot be in the future.");
      }
      return ctx.write(req, c -> {
        requireAnimal(c, id);
        long visitId = Ctx.insertReturningId(c, """
            INSERT INTO rhrms.vet_visit(animal_id, visit_date, clinic, reason, outcome, cost,
                                        report_path, recorded_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
            """, id, visitDate, clinic, reason, outcome, cost, reportPath, req.userId());
        if (marksVetted) {
          Ctx.update(c, "UPDATE rhrms.animal SET vet_status = 'VETTED', version = version + 1"
              + " WHERE id = ? AND vet_status = 'NOT_VETTED'", id);
        }
        return Res.created(Json.obj().put("id", visitId)
            .put("vetVisits", vetVisits(c, id))
            .put("message", marksVetted
                ? "Recorded, and the animal is now marked as vetted."
                : "Recorded. The animal is still marked as not vetted; "
                    + "send marksVetted true when the vetting is finished."));
      });
    });
  }

  // ======================================================================= intake

  private static Res intake(Ctx ctx, Req req) throws SQLException {
    String species = req.requireOneOf("species", "Species", SPECIES);
    String intakeSource = req.requireOneOf("intakeSource", "Where the animal came from", INTAKE_SOURCES);
    LocalDate intakeDate = req.has("intakeDate") ? req.requireDate("intakeDate", "Intake date") : LocalDate.now();
    Integer kennelId = req.optInt("kennelId");
    String noKennelReason = req.optText("noKennelReason");
    String name = req.optText("name");

    if (intakeDate.isAfter(LocalDate.now())) {
      throw ApiException.badRequest("An intake date cannot be in the future.");
    }
    if (intakeSource.equals("RETURN")) {
      throw ApiException.badRequest("A returning animal keeps its own record and its own animal code. "
          + "Use POST /api/animals/{id}/return on the animal that is coming back (IN-4).");
    }
    // IN-1: a kennel is part of an intake. IN-2 lets the director record an arrival with no
    // kennel when all 40 are full, with a reason; nobody else can.
    if (kennelId == null) {
      if (req.role() != Role.DIRECTOR || noKennelReason == null || noKennelReason.length() < 5) {
        throw ApiException.badRequest("Which kennel is this animal going into? "
            + "(If every kennel is full, the director can record the arrival without one by sending "
            + "noKennelReason.)");
      }
    }

    /*
     * AGE and "AGE IS: exact / estimated" are two separate boxes on form RH-1, and the tick is
     * what says whether the age is trusted. The database stores a birth DATE, so:
     *
     *   - a date of birth, when somebody actually knows it, is used as given (IN-8: an owner
     *     surrender usually does);
     *   - otherwise the age in years and months is worked back from today;
     *   - and birth_date_is_estimate follows the tick on the form, not a guess about the source.
     *
     * An age worked back from "5 years" pins down the year but not the day, so ticking "exact"
     * against an age rather than a date is recorded as exact but is only as good as the form.
     * That is the form's claim to make, not this code's to second-guess. See DECISIONS.md D-19.
     */
    LocalDate birthDate = req.optDate("birthDate");
    Integer ageYears = req.optInt("ageYears");
    Integer ageMonths = req.optInt("ageMonths");
    Boolean ageIsExact = req.optBool("ageIsExact");
    boolean estimate;
    if (birthDate != null) {
      if (birthDate.isAfter(LocalDate.now())) {
        throw ApiException.badRequest("A date of birth cannot be in the future.");
      }
      if (ageIsExact != null) estimate = !ageIsExact;
      else {
        Boolean told = req.optBool("birthDateIsEstimate");
        estimate = told != null ? told : !intakeSource.equals("OWNER_SURRENDER");
      }
    } else if (ageYears != null || ageMonths != null) {
      int months = (ageYears == null ? 0 : ageYears * 12) + (ageMonths == null ? 0 : ageMonths);
      if (months <= 0) throw ApiException.badRequest("An age has to be more than zero.");
      if (months > 360) {
        throw ApiException.badRequest("An age of " + months + " months is not plausible. "
            + "Enter the years and months separately.");
      }
      birthDate = LocalDate.now().minusMonths(months);
      estimate = ageIsExact == null || !ageIsExact;
    } else {
      estimate = true;                       // age box left blank: nothing known but that it is a guess
    }

    String status = req.has("status") ? req.requireOneOf("status", "Status", "AVAILABLE", "NOT_ADOPTABLE")
                                      : "AVAILABLE";
    String notAdoptableReason = req.optText("notAdoptableReason");
    if (status.equals("NOT_ADOPTABLE") && notAdoptableReason == null) {
      throw ApiException.badRequest("Say why this animal is not adoptable yet "
          + "(for example \"not vetted, kittens too young\").");
    }

    // Phase 1 item 1: the dietary requirement, given as a supply item plus how much of it.
    final Long dietFoodId = req.optLong("foodProductId");
    final java.math.BigDecimal dietCups = dietFoodId == null ? null
        : (req.has("cupsPerMeal") ? req.optDecimal("cupsPerMeal") : new java.math.BigDecimal("1"));
    final int dietMeals = dietFoodId == null ? 2
        : (req.has("mealsPerDay") ? req.requireInt("mealsPerDay", "Meals per day") : 2);
    if (dietFoodId != null) {
      if (dietCups == null || dietCups.signum() <= 0 || dietCups.compareTo(new java.math.BigDecimal("4")) > 0) {
        throw ApiException.badRequest("Cups per meal has to be more than 0 and no more than 4.");
      }
      if (dietMeals < 1 || dietMeals > 4) {
        throw ApiException.badRequest("Meals per day has to be between 1 and 4.");
      }
    }

    // ---- the remaining boxes on form RH-1 ----
    // "CAME IN AS (IF RENAMED)": the name on the collar or the surrender paperwork, when the
    // rescue has already started calling the animal something else.
    final String cameInAs = req.optText("cameInAs");
    // "TIME IN", next to DATE IN. Accepted in whatever shape it was written and stored as
    // HH:MM; see util/ClockTime. Only genuinely unreadable text is refused.
    final String timeIn;
    try {
      timeIn = org.rubyhill.rhrms.util.ClockTime.normalise(req.optText("timeIn"));
    } catch (IllegalArgumentException e) {
      throw ApiException.badRequest(e.getMessage());
    }
    // "BONDED WITH": another animal already on file that this one must be adopted with.
    final Long bondedWith = req.optLong("bondedWithAnimalId");
    // "MOTHER (IF BORN HERE)".
    final Long motherId = req.optLong("motherAnimalId");
    // "TAKEN IN BY (VOLUNTEER)", which is often not the person typing it in. Recorded the same
    // way a home visit entered for somebody else is: through rhrms.on_behalf_of.
    final String takenInBy = req.optText("takenInBy");

    if (motherId != null && !intakeSource.equals("BORN_IN_CARE")) {
      throw ApiException.badRequest("A mother is only recorded for an animal born here. "
          + "Change 'where from' to born here, or leave the mother blank.");
    }

    boolean acknowledgedDuplicates = req.flag("duplicateAcknowledged");
    String duplicateChoice = req.optText("duplicateChoice");
    final LocalDate birth = birthDate;
    final boolean isEstimate = estimate;

    // The form separates "TAKEN IN BY (VOLUNTEER)" from "ENTERED IN SPREADSHEET BY". So does the
    // audit trail: received_by is whoever is logged in, on_behalf_of is whoever met the animal.
    String onBehalf = req.onBehalfOf();
    if (onBehalf == null && takenInBy != null
        && !takenInBy.equalsIgnoreCase(req.requireSession().displayName())) {
      onBehalf = takenInBy;
    }
    final String behalf = onBehalf;

    return ctx.db().tx(req.role(), req.userId(), intakeReason(req, duplicateChoice), behalf, c -> {
      // Serialize duplicate checks for the same intake signature. Without this transaction-level
      // lock, two staff members can both check an empty result and create the same animal twice.
      Ctx.run(c, "SELECT pg_advisory_xact_lock(hashtextextended(coalesce(?, '') || '|' || ? || '|' || ?::text, 0))",
          name, species, intakeDate);
      // IN-3. Done on the server so it cannot be skipped by a different frontend: if there are
      // possible duplicates, the caller is told and has to come back having chosen.
      if (!acknowledgedDuplicates) {
        List<Map<String, Object>> dupes = duplicates(c, name, species, intakeDate);
        if (!dupes.isEmpty()) {
          List<String> described = new ArrayList<>();
          for (Map<String, Object> d : dupes) described.add(label(d) + " - " + d.get("why"));
          return new Res(409, Json.obj()
              .put("ok", false)
              .put("error", Json.obj().put("code", "POSSIBLE_DUPLICATE")
                  .put("message", "This might already be on file: " + String.join("; ", described)
                      + ". Open the existing record, or, if this really is a different animal, send "
                      + "the intake again with duplicateAcknowledged true (the choice is logged)."))
              .put("possibleDuplicates", dupes));
        }
      }

      // IN-2: say plainly when the shelter is full, rather than letting the kennel trigger
      // answer a question nobody asked.
      if (kennelId == null) {
        long free = ((Number) Ctx.scalar(c,
            "SELECT count(*) FROM rhrms.kennel_board WHERE animals = 0 AND in_service")).longValue();
        if (free > 0) {
          throw ApiException.badRequest("There " + (free == 1 ? "is 1 free kennel" : "are " + free + " free kennels")
              + ". Put the animal in one of them rather than recording it with no kennel.");
        }
      }

      // "BONDED WITH": join the partner's group if it already has one, otherwise start a group
      // and put the partner in it too. Interview 4: a bonded pair must be adopted together, so
      // the link has to exist from the moment both animals are on file.
      Long bondGroupId = req.optLong("bondGroupId");
      Map<String, Object> partner = null;
      if (bondedWith != null) {
        partner = Ctx.queryOne(c, "SELECT id, animal_code, name, status, bond_group_id, kennel_id"
            + " FROM rhrms.animal WHERE id = ?", bondedWith);
        if (partner == null) {
          throw ApiException.notFound("There is no animal number " + bondedWith
              + " to bond this one with. Leave 'bonded with' blank and link them later.");
        }
        if ("DECEASED".equals(partner.get("status"))) {
          throw ApiException.badRequest(label(partner) + " has died, so there is no pair to record.");
        }
        Long existing = partner.get("bond_group_id") == null ? null
            : ((Number) partner.get("bond_group_id")).longValue();
        if (existing != null) {
          bondGroupId = existing;
        } else {
          bondGroupId = Ctx.insertReturningId(c,
              "INSERT INTO rhrms.bond_group(note) VALUES (?) RETURNING id",
              "Came in together; must be adopted together");
          Ctx.update(c, "UPDATE rhrms.animal SET bond_group_id = ? WHERE id = ?",
              bondGroupId, bondedWith);
        }
      }

      // "MOTHER (IF BORN HERE)": reuse the mother's litter for that date if there is one, so a
      // whole litter written up one animal at a time still ends up as one litter.
      Long litterId = req.optLong("litterId");
      Map<String, Object> mother = null;
      if (motherId != null) {
        mother = Ctx.queryOne(c, "SELECT id, animal_code, name, species, kennel_id"
            + " FROM rhrms.animal WHERE id = ?", motherId);
        if (mother == null) {
          throw ApiException.notFound("There is no animal number " + motherId
              + " to record as the mother.");
        }
        if (litterId == null) {
          litterId = Ctx.scalarLong(c,
              "SELECT id FROM rhrms.litter WHERE mother_animal_id = ?"
                  + " AND born_on IS NOT DISTINCT FROM ?::date ORDER BY id DESC LIMIT 1",
              motherId, birth);
          if (litterId == null) {
            litterId = Ctx.insertReturningId(c,
                "INSERT INTO rhrms.litter(mother_animal_id, born_on, note)"
                    + " VALUES (?, ?, ?) RETURNING id",
                motherId, birth, "Born here to " + label(mother));
          }
        }
      }

      long animalId = Ctx.insertReturningId(c, """
          INSERT INTO rhrms.animal(name, species, breed, sex, birth_date, birth_date_is_estimate,
                                   color_markings, status, not_adoptable_reason, vet_status,
                                   spay_neuter, health_notes, behavior_notes, general_notes,
                                   kennel_id, bond_group_id, litter_id)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          RETURNING id
          """,
          name, species, req.optText("breed"), req.optOneOf("sex", "Sex", "M", "F", "U"),
          birth, isEstimate, req.optText("colorMarkings"), status,
          status.equals("NOT_ADOPTABLE") ? notAdoptableReason : null,
          // IN-7: everything arrives NOT_VETTED unless somebody says otherwise.
          req.has("vetStatus") ? req.requireOneOf("vetStatus", "Vet status", "NOT_VETTED", "VETTED") : "NOT_VETTED",
          req.has("spayNeuter") ? req.requireOneOf("spayNeuter", "Spay/neuter", "INTACT", "ALTERED", "UNKNOWN")
                                : "UNKNOWN",
          req.optText("healthNotes"), req.optText("behaviorNotes"), req.optText("generalNotes"),
          kennelId, bondGroupId, litterId);

      // "CAME IN AS (IF RENAMED)". The AN-2 trigger has just written the animal's current name
      // into the history; this adds the name it arrived under, dated to the day it arrived, so
      // the record reads in the right order and a search on the old name still finds it.
      if (cameInAs != null && !cameInAs.equalsIgnoreCase(name == null ? "" : name)) {
        Ctx.update(c, "INSERT INTO rhrms.animal_name_history(animal_id, name, valid_from)"
            + " VALUES (?, ?, ?::date)", animalId, cameInAs, intakeDate);
      }

      Ctx.insertReturningId(c, """
          INSERT INTO rhrms.stay(animal_id, intake_date, intake_time, intake_source,
                                 surrendered_by, intake_notes, received_by)
          VALUES (?, ?, ?::time, ?, ?, ?, ?) RETURNING id
          """, animalId, intakeDate, timeIn, intakeSource, req.optLong("surrenderedByPersonId"),
          req.optText("intakeNotes"), req.userId());

      // The dietary requirement is part of the intake form, and it is a LINK to a supply item,
      // not a note: that is what makes the inventory view able to say how much food the animals
      // currently in care actually need. Optional, because an animal can arrive before anybody
      // knows what it eats.
      Map<String, Object> diet = null;
      if (dietFoodId != null) {
        Object kind = Ctx.scalar(c, "SELECT kind FROM rhrms.food_product WHERE id = ?", dietFoodId);
        if (kind == null) {
          throw ApiException.notFound("There is no supply item number " + dietFoodId
              + ". GET /api/food/products lists them.");
        }
        long dietId = Ctx.insertReturningId(c, """
            INSERT INTO rhrms.diet(animal_id, food_product_id, cups_per_meal, meals_per_day)
            VALUES (?, ?, ?, ?) RETURNING id
            """, animalId, dietFoodId, dietCups, dietMeals);
        diet = Ctx.queryOne(c, """
            SELECT d.id, f.id AS food_product_id, f.name AS food, f.kind, f.unit,
                   d.cups_per_meal, d.meals_per_day,
                   round(d.cups_per_meal * d.meals_per_day * f.lbs_per_cup, 2) AS lbs_per_day
            FROM rhrms.diet d JOIN rhrms.food_product f ON f.id = d.food_product_id
            WHERE d.id = ?
            """, dietId);
      }

      Map<String, Object> saved = animalSummary(c, animalId);
      StringBuilder message = new StringBuilder("Recorded as " + saved.get("animal_code")
          + (name == null ? " (no name yet)" : " (" + name + ")")
          + (kennelId == null ? ", with no kennel." : ", in kennel " + kennelId + "."));
      if (cameInAs != null) message.append(" Came in as ").append(cameInAs).append('.');
      if (partner != null) message.append(" Bonded with ").append(label(partner))
          .append("; they are adopted together.");
      if (mother != null) message.append(" Born here to ").append(label(mother)).append('.');
      if (diet == null) {
        message.append(" No dietary requirement recorded yet.");
      } else {
        message.append(" Eats ").append(diet.get("food")).append(", ")
               .append(diet.get("lbs_per_day"))
               .append(" lb a day, which now counts towards what the rescue needs to have in.");
      }
      if (behalf != null) message.append(" Taken in by ").append(behalf).append('.');

      return Res.created(Json.obj()
          .put("animal", saved)
          .putIf("diet", diet)
          .putIf("bondedWith", partner)
          .putIf("mother", mother)
          .putIf("cameInAs", cameInAs)
          .putIf("takenInBy", behalf)
          // The "No." box at the top right of form RH-1. Writing it on the paper copy is what
          // ties the office copy to this record.
          .put("formNumber", saved.get("animal_code"))
          .put("message", message.toString()));
    });
  }

  /** IN-6: a litter born in care. The mother's kennel, one record per young. */
  private static Res litterIntake(Ctx ctx, Req req) throws SQLException {
    long motherId = req.requireLong("motherAnimalId", "The mother's record number");
    int count = req.requireInt("count", "How many were born");
    if (count < 1 || count > 15) {
      throw ApiException.badRequest("A litter of " + count + " is not plausible. Enter 1 to 15.");
    }
    LocalDate bornOn = req.has("bornOn") ? req.requireDate("bornOn", "Date of birth") : LocalDate.now();
    if (bornOn.isAfter(LocalDate.now())) throw ApiException.badRequest("A litter cannot be born in the future.");
    String note = req.optText("note");
    List<Object> names = req.optList("names");

    return ctx.write(req, c -> {
      Map<String, Object> mother = requireAnimal(c, motherId);
      if (mother.get("kennel_id") == null) {
        throw ApiException.badRequest(label(mother) + " is not in a kennel, "
            + "so there is nowhere to record the litter. Put the mother in a kennel first.");
      }
      String species = String.valueOf(mother.get("species"));
      int kennelId = ((Number) mother.get("kennel_id")).intValue();

      long litterId = Ctx.insertReturningId(c,
          "INSERT INTO rhrms.litter(mother_animal_id, born_on, note) VALUES (?, ?, ?) RETURNING id",
          motherId, bornOn, note == null ? "Born in care, " + count + " young" : note);

      List<Map<String, Object>> young = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        String name = i < names.size() && names.get(i) != null ? String.valueOf(names.get(i)).trim() : null;
        if (name != null && name.isEmpty()) name = null;
        long id = Ctx.insertReturningId(c, """
            INSERT INTO rhrms.animal(name, species, breed, sex, birth_date, birth_date_is_estimate,
                                     status, not_adoptable_reason, kennel_id, litter_id)
            VALUES (?, ?, ?, 'U', ?, false, 'NOT_ADOPTABLE', ?, ?, ?)
            RETURNING id
            """, name, species, mother.get("breed"), bornOn,
            "Born in care " + bornOn + "; too young to be adopted", kennelId, litterId);
        Ctx.update(c, """
            INSERT INTO rhrms.stay(animal_id, intake_date, intake_source, intake_notes, received_by)
            VALUES (?, ?, 'BORN_IN_CARE', ?, ?)
            """, id, bornOn, "Born in care to " + label(mother), req.userId());
        young.add(animalSummary(c, id));
      }
      return Res.created(Json.obj()
          .put("litterId", litterId)
          .put("mother", animalSummary(c, motherId))
          .put("young", young)
          .put("message", count + " records created in kennel " + kennelId
              + ", one per animal, linked as a litter. Each is marked not adoptable until they are "
              + "old enough and vetted; a special diet can be set on any one of them on its own."));
    });
  }

  private static String intakeReason(Req req, String duplicateChoice) {
    String reason = req.reason();
    if (duplicateChoice != null) {
      // IN-3: the choice itself is part of the record.
      return (reason == null ? "Intake" : reason) + " [duplicate check: " + duplicateChoice + "]";
    }
    return reason == null ? "Intake" : reason;
  }

  // =================================================================== queries

  /** IN-3: same species arriving today, or a name that has ever belonged to another animal. */
  static List<Map<String, Object>> duplicates(Connection c, String name, String species, LocalDate intakeDate)
      throws SQLException {
    return Ctx.query(c, """
        SELECT a.id, a.animal_code, a.name, a.species, a.breed, a.status, a.kennel_id,
               s.intake_date, s.intake_source,
               (SELECT string_agg(DISTINCT h.name, ', ') FROM rhrms.animal_name_history h
                 WHERE h.animal_id = a.id AND h.name IS DISTINCT FROM a.name) AS former_names,
               CASE
                 WHEN ?::text IS NOT NULL AND (a.name ILIKE ?::text OR EXISTS (
                        SELECT 1 FROM rhrms.animal_name_history h
                         WHERE h.animal_id = a.id AND h.name ILIKE ?::text))
                   THEN 'same name'
                 ELSE 'same species, arrived the same day'
               END AS why
        FROM rhrms.animal a
        LEFT JOIN rhrms.stay s ON s.animal_id = a.id AND s.ended_on IS NULL
        WHERE (?::text IS NOT NULL AND (a.name ILIKE ?::text OR EXISTS (
                 SELECT 1 FROM rhrms.animal_name_history h
                  WHERE h.animal_id = a.id AND h.name ILIKE ?::text)))
           OR (a.species = ?::text AND EXISTS (
                 SELECT 1 FROM rhrms.stay s2 WHERE s2.animal_id = a.id AND s2.intake_date = ?::date))
        ORDER BY a.id DESC
        LIMIT 20
        """, name, name, name, name, name, name, species, intakeDate);
  }

  static Map<String, Object> requireAnimal(Connection c, long id) throws SQLException {
    return Ctx.queryOneOr404(c, "There is no animal number " + id + ".",
        "SELECT id, animal_code, name, species, breed, status, kennel_id, bond_group_id, litter_id,"
            + " vet_status, version FROM rhrms.animal WHERE id = ?", id);
  }

  static Map<String, Object> animalSummary(Connection c, long id) throws SQLException {
    return Ctx.queryOne(c, """
        SELECT a.id, a.animal_code, a.name, a.species, a.breed, a.sex, a.status,
               a.not_adoptable_reason, a.vet_status, a.spay_neuter, a.kennel_id,
               a.birth_date, a.birth_date_is_estimate, a.bond_group_id, a.litter_id,
               a.needs_split, a.version
        FROM rhrms.animal a WHERE a.id = ?
        """, id);
  }

  static List<Map<String, Object>> restrictions(Connection c, long animalId, boolean includeWithdrawn)
      throws SQLException {
    return Ctx.query(c, """
        SELECT id, type, min_child_age, detail, active, created_at, version
        FROM rhrms.restriction
        WHERE animal_id = ? AND (?::boolean OR active)
        ORDER BY active DESC, created_at
        """, animalId, includeWithdrawn);
  }

  static List<Map<String, Object>> vetVisits(Connection c, long animalId) throws SQLException {
    return Ctx.query(c, """
        SELECT v.id, v.visit_date, v.clinic, v.reason, v.outcome, v.cost, v.report_path,
               u.display_name AS recorded_by
        FROM rhrms.vet_visit v
        JOIN rhrms.staff_user u ON u.id = v.recorded_by
        WHERE v.animal_id = ?
        ORDER BY v.visit_date DESC, v.id DESC
        """, animalId);
  }

  /** The surviving member of a two-animal bond group, or null when there is no such case. */
  private static Object leftAlone(Connection c, long dyingAnimalId) throws SQLException {
    return Ctx.scalar(c, """
        SELECT string_agg(coalesce(o.name, o.animal_code), ', ')
        FROM rhrms.animal o
        WHERE o.bond_group_id = (SELECT bond_group_id FROM rhrms.animal WHERE id = ?)
          AND o.bond_group_id IS NOT NULL
          AND o.id <> ?
          AND o.status <> 'DECEASED'
        """, dyingAnimalId, dyingAnimalId);
  }

  static String label(Map<String, Object> animal) {
    Object name = animal.get("name");
    Object code = animal.get("animal_code");
    return name == null ? String.valueOf(code) : name + " (" + code + ")";
  }

  /** Everything the animal screen shows, in one round trip. */
  static Json.Obj animalRecord(Connection c, long id) throws SQLException {
    Map<String, Object> animal = Ctx.queryOneOr404(c, "There is no animal number " + id + ".", """
        SELECT a.id, a.animal_code, a.name, a.species, a.breed, a.sex, a.birth_date,
               a.birth_date_is_estimate, a.color_markings, a.status, a.not_adoptable_reason,
               a.vet_status, a.spay_neuter, a.health_notes, a.behavior_notes, a.general_notes,
               a.kennel_id, a.bond_group_id, a.litter_id, a.needs_split, a.created_at, a.version,
               k.in_service AS kennel_in_service,
               bg.note AS bond_note,
               l.born_on AS litter_born_on, l.note AS litter_note,
               CASE WHEN a.birth_date IS NULL THEN NULL
                    ELSE trunc(extract(YEAR FROM age(a.birth_date)))::int END AS age_years,
               CASE WHEN a.birth_date IS NULL THEN NULL
                    ELSE trunc(extract(MONTH FROM age(a.birth_date)))::int END AS age_extra_months
        FROM rhrms.animal a
        LEFT JOIN rhrms.kennel k     ON k.id = a.kennel_id
        LEFT JOIN rhrms.bond_group bg ON bg.id = a.bond_group_id
        LEFT JOIN rhrms.litter l      ON l.id = a.litter_id
        WHERE a.id = ?
        """, id);

    return Json.obj()
        .put("animal", animal)
        .put("formerNames", Ctx.query(c,
            "SELECT DISTINCT h.name, min(h.valid_from) AS first_used FROM rhrms.animal_name_history h"
                + " WHERE h.animal_id = ? GROUP BY h.name ORDER BY min(h.valid_from)", id))
        .put("restrictions", restrictions(c, id, true))
        .put("bondPartners", Ctx.query(c, """
            SELECT o.id, o.animal_code, o.name, o.species, o.status, o.kennel_id
            FROM rhrms.animal o
            WHERE o.bond_group_id = (SELECT bond_group_id FROM rhrms.animal WHERE id = ?)
              AND o.bond_group_id IS NOT NULL AND o.id <> ?
            ORDER BY o.id
            """, id, id))
        .put("litterMates", Ctx.query(c, """
            SELECT o.id, o.animal_code, o.name, o.status, o.kennel_id
            FROM rhrms.animal o
            WHERE o.litter_id = (SELECT litter_id FROM rhrms.animal WHERE id = ?)
              AND o.litter_id IS NOT NULL AND o.id <> ?
            ORDER BY o.id
            """, id, id))
        .put("stays", Ctx.query(c, """
            SELECT s.id, s.intake_date, s.intake_time, s.intake_source, s.intake_notes,
                   s.ended_on, s.end_reason,
                   s.prior_placement_id, u.display_name AS received_by,
                   p.first_name || ' ' || p.last_name AS surrendered_by
            FROM rhrms.stay s
            JOIN rhrms.staff_user u ON u.id = s.received_by
            LEFT JOIN rhrms.person p ON p.id = s.surrendered_by
            WHERE s.animal_id = ?
            ORDER BY s.intake_date DESC, s.id DESC
            """, id))
        .put("placements", Ctx.query(c, """
            SELECT pl.id, pl.placed_on, pl.fee_baseline, pl.fee_charged, pl.fee_reason,
                   pl.payment_method, pl.vet_clinic_told, pl.returned_on, pl.return_reason,
                   pl.application_id,
                   pe.id AS person_id, pe.first_name || ' ' || pe.last_name AS adopter,
                   pe.phone, pe.city,
                   u.display_name AS recorded_by, ru.display_name AS return_recorded_by
            FROM rhrms.placement pl
            JOIN rhrms.person pe     ON pe.id = pl.person_id
            JOIN rhrms.staff_user u  ON u.id = pl.recorded_by
            LEFT JOIN rhrms.staff_user ru ON ru.id = pl.return_recorded_by
            WHERE pl.animal_id = ?
            ORDER BY pl.placed_on DESC, pl.id DESC
            """, id))
        .put("applications", Ctx.query(c, """
            SELECT ap.id, ap.status, ap.submitted_at, ap.closed_reason,
                   pe.id AS person_id, pe.first_name || ' ' || pe.last_name AS applicant, pe.phone,
                   (SELECT count(*) FROM rhrms.home_visit hv WHERE hv.application_id = ap.id) AS home_visits,
                   (SELECT d.outcome FROM rhrms.decision d WHERE d.application_id = ap.id
                     ORDER BY d.decided_at DESC LIMIT 1) AS latest_decision
            FROM rhrms.application ap
            JOIN rhrms.person pe ON pe.id = ap.person_id
            WHERE ap.animal_id = ?
            ORDER BY ap.submitted_at DESC
            """, id))
        .put("diets", Ctx.query(c, """
            SELECT d.id, d.cups_per_meal, d.meals_per_day, d.valid_from, d.valid_to,
                   f.id AS food_product_id, f.name AS food, f.kind, f.unit, f.lbs_per_cup,
                   round(d.cups_per_meal * d.meals_per_day * f.lbs_per_cup, 2) AS lbs_per_day
            FROM rhrms.diet d
            JOIN rhrms.food_product f ON f.id = d.food_product_id
            WHERE d.animal_id = ?
            ORDER BY d.valid_to NULLS FIRST, d.valid_from DESC
            """, id))
        .put("prescriptions", Ctx.query(c, """
            SELECT p.id, p.start_date, p.end_date, f.id AS food_product_id, f.name AS food
            FROM rhrms.prescription p
            JOIN rhrms.food_product f ON f.id = p.food_product_id
            WHERE p.animal_id = ?
            ORDER BY p.start_date DESC
            """, id))
        .put("vetVisits", vetVisits(c, id))
        .putIf("vetCostTotal", Ctx.scalar(c,
            "SELECT sum(cost) FROM rhrms.vet_visit WHERE animal_id = ?", id));
  }
}
