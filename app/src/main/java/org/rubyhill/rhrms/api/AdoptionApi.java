package org.rubyhill.rhrms.api;

import org.rubyhill.rhrms.http.ApiException;
import org.rubyhill.rhrms.http.Req;
import org.rubyhill.rhrms.http.Res;
import org.rubyhill.rhrms.http.Router;
import org.rubyhill.rhrms.json.Json;
import org.rubyhill.rhrms.security.Perm;
import org.rubyhill.rhrms.security.Permissions;
import org.rubyhill.rhrms.security.Role;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The adoption pipeline: application, home visit, the director's decision, the placement, and
 * a return. Sections 6.3 and 6.4 of the build spec.
 *
 * This is the liability-critical part of the system. Interview 3: the director's deepest worry
 * is an adopted animal biting a child and the rescue being sued, and what protects the rescue
 * is being able to show, months later, what was known at the time and why the placement was
 * made anyway. So three things are non-negotiable here and are enforced on this side, not in
 * whatever is drawing the screen:
 *
 *   - only a director records a decision (DE-1), and the database refuses it from anyone else;
 *   - an approval over the top of a restriction conflict needs a written acknowledgement, and
 *     the conflicts that existed at that moment are frozen into the decision row (DE-3);
 *   - a decision, a placement and a return each need the person's password re-typed, so none
 *     of them can happen at a terminal somebody walked away from.
 */
final class AdoptionApi {
  private AdoptionApi() {}

  private static final String[] DENIAL_REASONS = {"HOUSING_NO_PETS", "CHILDREN_UNSUITABLE",
      "YARD_UNSUITABLE", "OTHER_PETS_UNSUITABLE", "HOUSEHOLD_UNSUITABLE", "BONDED_PAIR_NOT_TAKEN",
      "BETTER_FIT_CHOSEN", "APPLICATION_MISMATCH", "WELFARE_CONCERN", "OTHER"};
  private static final String[] PAYMENT_METHODS = {"CASH", "CHECK", "OTHER", "WAIVED"};

  static void register(Router r, Ctx ctx) {

    // ============================================================ applications
    r.route("POST", "/api/applications", Perm.APPLICATION_CREATE,
        "enter an adoption application from the paper form (AP-1 to AP-3, RC-1, RC-2)",
        req -> createApplication(ctx, req));

    r.route("GET", "/api/applications", Perm.VIEW, "list applications", req -> {
      Long animalId = req.queryId("animal");
      Long personId = req.queryId("person");
      String status = req.queryParam("status", null);
      boolean openOnly = req.queryFlag("open");
      boolean awaitingVisit = req.queryFlag("awaitingVisit");
      boolean awaitingDecision = req.queryFlag("awaitingDecision");
      int limit = req.queryInt("limit", 100, 500);
      return ctx.read(req, c -> Res.list("applications", Ctx.query(c, """
          SELECT ap.id, ap.status, ap.submitted_at, ap.closed_reason, ap.prior_application_id,
                 ap.reused_home_visit_id,
                 pe.id AS person_id, pe.first_name || ' ' || pe.last_name AS applicant, pe.phone, pe.city,
                 an.id AS animal_id, an.animal_code, an.name AS animal_name, an.species,
                 an.status AS animal_status, an.bond_group_id,
                 u.display_name AS received_by,
                 (SELECT count(*) FROM rhrms.home_visit hv WHERE hv.application_id = ap.id) AS home_visits,
                 (SELECT count(*) FROM rhrms.restriction_conflicts rc
                   WHERE rc.application_id = ap.id) AS conflicts,
                 d.outcome AS latest_outcome, d.denial_reason, d.decided_at
          FROM rhrms.application ap
          JOIN rhrms.person pe    ON pe.id = ap.person_id
          JOIN rhrms.animal an    ON an.id = ap.animal_id
          JOIN rhrms.staff_user u ON u.id = ap.received_by
          LEFT JOIN LATERAL (
                SELECT * FROM rhrms.decision d2
                 WHERE d2.application_id = ap.id
                   AND NOT EXISTS (SELECT 1 FROM rhrms.decision s WHERE s.supersedes_decision_id = d2.id)
                 ORDER BY d2.decided_at DESC LIMIT 1) d ON TRUE
          WHERE (?::bigint IS NULL OR ap.animal_id = ?::bigint)
            AND (?::bigint IS NULL OR ap.person_id = ?::bigint)
            AND (?::text IS NULL OR ap.status = ?::text)
            AND (?::boolean = false OR ap.status IN ('SUBMITTED','UNDER_REVIEW','APPROVED'))
            AND (?::boolean = false OR (ap.status = 'SUBMITTED'
                               AND NOT EXISTS (SELECT 1 FROM rhrms.home_visit hv
                                                WHERE hv.application_id = ap.id)
                               AND ap.reused_home_visit_id IS NULL))
            AND (?::boolean = false OR ap.status = 'UNDER_REVIEW')
          ORDER BY ap.submitted_at
          LIMIT ?::int
          """, animalId, animalId, personId, personId, status, status, openOnly,
              awaitingVisit, awaitingDecision, limit)));
    });

    r.route("GET", "/api/applications/{id}", Perm.VIEW,
        "one application with its visits, conflicts, discrepancies and decisions", req -> {
      long id = req.pathId("id");
      return ctx.read(req, c -> Res.ok(applicationRecord(c, id)));
    });

    r.route("GET", "/api/applications/{id}/conflicts", Perm.VIEW,
        "restriction conflicts between this animal and this home (DE-2)", req -> {
      long id = req.pathId("id");
      return ctx.read(req, c -> {
        requireApplication(c, id);
        return Res.list("conflicts", conflicts(c, id));
      });
    });

    /*
     * DE-2: the director's screen. Every open application for one animal, side by side, with
     * each visit's findings, the discrepancies and the restriction conflicts. Submission order
     * is shown because Interview 1 said first come first served, and is NOT enforced because
     * Interview 2 overruled that with the Bella story: best fit wins.
     */
    r.route("GET", "/api/animals/{id}/decision-board", Perm.VIEW,
        "all open applications for one animal, side by side (DE-2)", req -> {
      long animalId = req.pathId("id");
      return ctx.read(req, c -> {
        Map<String, Object> animal = AnimalApi.requireAnimal(c, animalId);
        List<Map<String, Object>> open = Ctx.query(c, """
            SELECT ap.id, ap.status, ap.submitted_at,
                   row_number() OVER (ORDER BY ap.submitted_at) AS applied_order,
                   pe.id AS person_id, pe.first_name || ' ' || pe.last_name AS applicant,
                   pe.phone, pe.city,
                   ap.housing_type, ap.landlord_allows_pets, ap.has_yard, ap.yard_fenced,
                   ap.children_count, ap.youngest_child_age, ap.elderly_in_home, ap.adults_in_home,
                   ap.other_pets, ap.reason_for_adopting, ap.reused_home_visit_id,
                   ap.situation_check_note
            FROM rhrms.application ap
            JOIN rhrms.person pe ON pe.id = ap.person_id
            WHERE ap.animal_id = ? AND ap.status IN ('SUBMITTED','UNDER_REVIEW','APPROVED')
            ORDER BY ap.submitted_at
            """, animalId);
        List<Object> board = new ArrayList<>();
        for (Map<String, Object> app : open) {
          long appId = ((Number) app.get("id")).longValue();
          List<Map<String, Object>> visits = homeVisits(c, appId);
          board.add(Json.obj()
              .put("application", app)
              .put("homeVisits", visits)
              .put("conflicts", conflicts(c, appId))
              .put("discrepancies", discrepancies(app, visits))
              .put("decisions", decisions(c, appId)));
        }
        return Res.ok(Json.obj()
            .put("animal", animal)
            .put("restrictions", AnimalApi.restrictions(c, animalId, false))
            .put("bondPartners", Ctx.query(c, """
                SELECT o.id, o.animal_code, o.name, o.status
                FROM rhrms.animal o
                WHERE o.bond_group_id = (SELECT bond_group_id FROM rhrms.animal WHERE id = ?)
                  AND o.bond_group_id IS NOT NULL AND o.id <> ? AND o.status <> 'DECEASED'
                ORDER BY o.id
                """, animalId, animalId))
            .put("applicants", board)
            .put("count", board.size())
            .put("note", "The order shown is the order they applied. It is not binding: "
                + "the best fit for the animal wins (Interview 2)."));
      });
    });

    r.critical("POST", "/api/applications/{id}/withdraw", Perm.APPLICATION_CLOSE,
        "withdraw or close an application",
        "close an application: the applicant backed out, or the animal went elsewhere", req -> {
      long id = req.pathId("id");
      String status = req.has("status")
          ? req.requireOneOf("status", "Status", "WITHDRAWN", "CLOSED") : "WITHDRAWN";
      String closedReason = req.requireText("closedReason", "The reason for closing it", 5);
      return ctx.writeWithReason(req, c -> {
        Map<String, Object> app = requireApplication(c, id);
        String was = String.valueOf(app.get("status"));
        if (List.of("PLACED", "WITHDRAWN", "CLOSED", "DENIED").contains(was)) {
          throw ApiException.badRequest("Application " + id + " is already " + was.toLowerCase()
              + ", so there is nothing to close.");
        }
        int version = ((Number) app.get("version")).intValue();
        int changed = Ctx.update(c,
            "UPDATE rhrms.application SET status = ?, closed_reason = ?, version = version + 1"
                + " WHERE id = ? AND version = ?", status, closedReason, id, version);
        if (changed != 1) throw Patch.staleEdit("application");
        // The trigger in 02_rules.sql puts the animal (and its bond partners) back to AVAILABLE
        // if this was the approved application, so nothing to do here.
        return Res.ok(Json.obj()
            .put("application", Ctx.queryOne(c, "SELECT id, status, closed_reason, version"
                + " FROM rhrms.application WHERE id = ?", id))
            .put("animal", AnimalApi.animalSummary(c, ((Number) app.get("animal_id")).longValue()))
            .put("message", "Application " + id + " is " + status.toLowerCase()
                + (was.equals("APPROVED") ? ". The animal is available again." : ".")));
      });
    });

    // ============================================================== home visits
    r.route("POST", "/api/applications/{id}/visit", Perm.HOME_VISIT_CREATE,
        "record a home visit; moves the application to UNDER_REVIEW (HV-1 to HV-4)", req -> {
      long appId = req.pathId("id");
      LocalDate visitedOn = req.has("visitedOn") ? req.requireDate("visitedOn", "Date of the visit")
                                                 : LocalDate.now();
      if (visitedOn.isAfter(LocalDate.now())) {
        throw ApiException.badRequest("A home visit cannot be in the future.");
      }
      // HV-2: the visitor's name is always required. If the person typing is not the visitor,
      // the audit records both of them through rhrms.on_behalf_of.
      String visitorName = req.requireText("visitorName", "The name of whoever made the visit");
      Long visitorUserId = req.optLong("visitorUserId");
      boolean childrenPresent = req.requireBool("childrenPresent", "Were there children in the home");
      Integer youngestChildAge = req.optInt("youngestChildAge");
      if (childrenPresent && youngestChildAge == null) {
        throw ApiException.badRequest("How old is the youngest child? "
            + "The director needs it to judge an animal with a no-children restriction.");
      }
      boolean yard = req.requireBool("yard", "Is there a yard");
      Boolean yardFenced = req.optBool("yardFenced");
      if (yard && yardFenced == null) {
        throw ApiException.badRequest("Is the yard fenced? Some animals may only go to a fenced yard.");
      }
      String recommendation = req.requireOneOf("recommendation", "Your recommendation",
          "APPROVE", "DENY", "UNSURE");
      // The visitor's own words, required by HV-1 and by the 15-character CHECK in the schema.
      String notes = req.requireText("notes", "Your notes, in your own words", 15);

      // HV-2. rhrms.on_behalf_of is set only when the visitor is genuinely somebody other than
      // the person typing, so the audit row names both of them. Display names carry labels the
      // visitor would not write down ("Sam (volunteer)" typing "Sam"), so a name that is
      // contained in the other counts as the same person rather than a stand-in.
      String onBehalfOf = req.onBehalfOf();
      if (onBehalfOf == null && !samePerson(visitorName, req.requireSession().displayName())
          && (visitorUserId == null || visitorUserId != req.userId())) {
        onBehalfOf = visitorName;
      }
      final String behalf = onBehalfOf;

      return ctx.db().tx(req.role(), req.userId(),
          req.reason() == null ? "Home visit recorded" : req.reason(), behalf, c -> {
        Map<String, Object> app = requireApplication(c, appId);
        String status = String.valueOf(app.get("status"));
        if (!List.of("SUBMITTED", "UNDER_REVIEW", "APPROVED").contains(status)) {
          throw ApiException.badRequest("Application " + appId + " is " + status.toLowerCase()
              + ", so a home visit would not be used. Nothing was saved.");
        }
        long visitId = Ctx.insertReturningId(c, """
            INSERT INTO rhrms.home_visit(application_id, visited_on, visitor_user_id, visitor_name,
                   entered_by, children_present, youngest_child_age, yard, yard_fenced, other_pets,
                   elderly_residents, adult_men, adult_women, housing_confirmed, other_concerns,
                   recommendation, notes)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """, appId, visitedOn, visitorUserId, visitorName, req.userId(),
            childrenPresent, youngestChildAge, yard, yard ? yardFenced : null,
            req.optText("otherPets"),
            req.requireBool("elderlyResidents", "Are there elderly residents"),
            req.requireInt("adultMen", "How many adult men live there"),
            req.requireInt("adultWomen", "How many adult women live there"),
            req.requireBool("housingConfirmed", "Did you confirm the housing"),
            req.optText("otherConcerns"), recommendation, notes);

        // HV-4: what the visit found that the paper application did not say.
        List<Map<String, Object>> visits = homeVisits(c, appId);
        List<String> mismatch = discrepancies(app, visits);

        return Res.created(Json.obj()
            .put("homeVisitId", visitId)
            .put("application", Ctx.queryOne(c,
                "SELECT id, status, version FROM rhrms.application WHERE id = ?", appId))
            .put("conflicts", conflicts(c, appId))
            .put("discrepancies", mismatch)
            .putIf("enteredOnBehalfOf", behalf)
            .put("message", "Visit recorded" + (behalf == null ? "" : " on behalf of " + behalf)
                + ". The application is now with the director."
                + (mismatch.isEmpty() ? "" : " " + mismatch.size()
                    + " difference(s) from the paper form were found and are shown to him.")));
      });
    });

    // ================================================================ decisions
    r.critical("POST", "/api/applications/{id}/decision", Perm.DECIDE,
        "record a decision on an adoption application",
        "the director approves or denies an application, with his reasons (DE-1 to DE-6)",
        req -> decide(ctx, req));

    r.route("GET", "/api/decisions/{id}", Perm.VIEW, "one decision, with everything behind it (LK-2)",
        req -> {
      long id = req.pathId("id");
      return ctx.read(req, c -> Res.ok(Json.obj()
          .put("decision", Ctx.queryOneOr404(c, "There is no decision number " + id + ".",
              "SELECT * FROM rhrms.decision_record WHERE decision_id = ?", id))
          .put("supersededBy", Ctx.query(c, """
              SELECT d.id, d.outcome, d.denial_reason, d.rationale, d.decided_at,
                     u.display_name AS decided_by
              FROM rhrms.decision d JOIN rhrms.staff_user u ON u.id = d.decided_by
              WHERE d.supersedes_decision_id = ? ORDER BY d.decided_at
              """, id))));
    });

    // =============================================================== placements
    r.critical("POST", "/api/applications/{id}/place", Perm.PLACEMENT,
        "record an adoption",
        "adoption day: record the placement, the fee and the food that went with the animal (PL-1 to PL-5)",
        req -> place(ctx, req));

    r.critical("POST", "/api/animals/{id}/return", Perm.RETURN,
        "record a returned animal",
        "an adopted animal has come back; record why (IN-4)", req -> returnAnimal(ctx, req));
  }

  // ========================================================= create application

  private static Res createApplication(Ctx ctx, Req req) throws SQLException {
    long personId = req.requireLong("personId", "The applicant");
    long animalId = req.requireLong("animalId", "The animal they want");
    Long priorApplicationId = req.optLong("priorApplicationId");
    Long reusedHomeVisitId = req.optLong("reusedHomeVisitId");
    String situationCheckNote = req.optText("situationCheckNote");

    // RC-2. Deciding that an old visit still describes this home is a judgement call about a
    // liability-critical record, so it is not something a volunteer does.
    if (reusedHomeVisitId != null) {
      Permissions.require(req.role(), Perm.RECORD_CORRECT);
      if (situationCheckNote == null || situationCheckNote.length() < 10) {
        throw ApiException.badRequest("Reusing an earlier home visit needs a note about the phone call: "
            + "what you asked, and what they said had or had not changed (RC-1).");
      }
    }

    return ctx.write(req, c -> {
      Map<String, Object> animal = AnimalApi.requireAnimal(c, animalId);
      if (Ctx.scalar(c, "SELECT 1 FROM rhrms.person WHERE id = ?", personId) == null) {
        throw ApiException.notFound("There is no person number " + personId + ".");
      }

      if (reusedHomeVisitId != null) validateVisitReuse(c, ctx, reusedHomeVisitId, personId);

      if (priorApplicationId != null && Ctx.scalar(c,
          "SELECT 1 FROM rhrms.application WHERE id = ? AND person_id = ?",
          priorApplicationId, personId) == null) {
        throw ApiException.badRequest("Application " + priorApplicationId
            + " is not an earlier application by this person.");
      }

      long id = Ctx.insertReturningId(c, """
          INSERT INTO rhrms.application(person_id, animal_id, received_by, housing_type,
                 landlord_allows_pets, has_yard, yard_fenced, children_count, youngest_child_age,
                 elderly_in_home, adults_in_home, other_pets, reason_for_adopting,
                 prior_application_id, reused_home_visit_id, situation_check_note)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          RETURNING id
          """, personId, animalId, req.userId(),
          req.optOneOf("housingType", "Housing type", "HOUSE", "APARTMENT", "OTHER"),
          req.optBool("landlordAllowsPets"), req.optBool("hasYard"), req.optBool("yardFenced"),
          req.optInt("childrenCount"), req.optInt("youngestChildAge"), req.optBool("elderlyInHome"),
          req.optText("adultsInHome"), req.optText("otherPets"), req.optText("reasonForAdopting"),
          priorApplicationId, reusedHomeVisitId, situationCheckNote);

      // AP-2: an application for one of a bonded pair covers the whole group, and whoever is at
      // the desk has to be able to say so to the applicant, out loud, now.
      List<Map<String, Object>> partners = Ctx.query(c, """
          SELECT o.id, o.animal_code, o.name, o.species, o.status
          FROM rhrms.animal o
          WHERE o.bond_group_id = ?::bigint AND o.bond_group_id IS NOT NULL AND o.id <> ?
            AND o.status <> 'DECEASED'
          ORDER BY o.id
          """, animal.get("bond_group_id"), animalId);

      List<Map<String, Object>> restrictions = AnimalApi.restrictions(c, animalId, false);
      List<Map<String, Object>> competing = Ctx.query(c, """
          SELECT ap.id, ap.status, ap.submitted_at,
                 pe.first_name || ' ' || pe.last_name AS applicant
          FROM rhrms.application ap JOIN rhrms.person pe ON pe.id = ap.person_id
          WHERE ap.animal_id = ? AND ap.id <> ? AND ap.status IN ('SUBMITTED','UNDER_REVIEW','APPROVED')
          ORDER BY ap.submitted_at
          """, animalId, id);

      StringBuilder message = new StringBuilder("Application " + id + " recorded.");
      if (!partners.isEmpty()) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> p : partners) names.add(AnimalApi.label(p));
        message.append(" This animal is bonded to ").append(String.join(", ", names))
               .append("; they are adopted together, and this one application covers all of them.");
      }
      if (!restrictions.isEmpty()) {
        message.append(" The animal has ").append(restrictions.size())
               .append(" restriction(s) the home visit needs to check.");
      }
      if (!competing.isEmpty()) {
        message.append(" ").append(competing.size())
               .append(" other application(s) are already open for this animal.");
      }
      if (reusedHomeVisitId != null) {
        message.append(" The earlier home visit is reused, so no new visit is needed.");
      } else {
        message.append(" Next step: a home visit.");
      }

      return Res.created(Json.obj()
          .put("application", Ctx.queryOne(c,
              "SELECT id, person_id, animal_id, status, submitted_at, version"
                  + " FROM rhrms.application WHERE id = ?", id))
          .put("animal", animal)
          .putIf("bondPartners", partners.isEmpty() ? null : partners)
          .putIf("animalRestrictions", restrictions.isEmpty() ? null : restrictions)
          .putIf("otherOpenApplications", competing.isEmpty() ? null : competing)
          .put("message", message.toString()));
    });
  }

  /** RC-2: the reused visit has to be this person's, and recent enough to still be true. */
  private static void validateVisitReuse(Connection c, Ctx ctx, long visitId, long personId)
      throws SQLException {
    Map<String, Object> visit = Ctx.queryOne(c, """
        SELECT hv.id, hv.visited_on, hv.visitor_name, ap.person_id,
               (CURRENT_DATE - hv.visited_on) AS days_ago
        FROM rhrms.home_visit hv
        JOIN rhrms.application ap ON ap.id = hv.application_id
        WHERE hv.id = ?
        """, visitId);
    if (visit == null) throw ApiException.notFound("There is no home visit number " + visitId + ".");
    if (((Number) visit.get("person_id")).longValue() != personId) {
      throw ApiException.badRequest("Home visit " + visitId + " was at a different applicant's home. "
          + "A visit can only be reused for the person it was made for.");
    }
    int months = ctx.settings().visitReuseMaxMonths();
    long daysAgo = ((Number) visit.get("days_ago")).longValue();
    if (daysAgo > months * 31L) {
      throw ApiException.ruleViolation("That home visit is from " + visit.get("visited_on")
          + ", more than " + months + " months ago, so it cannot be reused. A new visit is needed.",
          "RULE RC-2");
    }
  }

  // ================================================================== decision

  private static Res decide(Ctx ctx, Req req) throws SQLException {
    long appId = req.pathId("id");
    // DE-1 plus the decision.allowed_roles setting, which is the answer to open question Q4
    // (who decides when the director is away) without a new build.
    Permissions.requireDecider(req.role(), ctx.settings().decisionAllowedRoles());

    String outcome = req.requireOneOf("outcome", "The decision", "APPROVE", "DENY");
    String rationale = req.requireText("rationale", "Your reasons, in your own words", 15);
    String denialReason = outcome.equals("DENY")
        ? req.requireOneOf("denialReason", "The reason category", DENIAL_REASONS) : null;
    String acknowledgement = req.optText("conflictsAcknowledgement");
    Long supersedes = req.optLong("supersedesDecisionId");

    if (supersedes != null) Permissions.require(req.role(), Perm.OVERRIDE);

    return ctx.db().tx(req.role(), req.userId(),
        req.reason() == null ? "Decision on application " + appId : req.reason(), null, c -> {
      Map<String, Object> app = requireApplication(c, appId);
      String was = String.valueOf(app.get("status"));

      if (supersedes == null && List.of("PLACED", "WITHDRAWN", "CLOSED").contains(was)) {
        throw ApiException.badRequest("Application " + appId + " is " + was.toLowerCase()
            + " and cannot be decided. Nothing was saved.");
      }
      if (supersedes != null && Ctx.scalar(c,
          "SELECT 1 FROM rhrms.decision WHERE id = ? AND application_id = ?", supersedes, appId) == null) {
        throw ApiException.badRequest("Decision " + supersedes + " is not a decision on application " + appId + ".");
      }

      List<Map<String, Object>> visits = homeVisits(c, appId);
      Long reusedVisitId = app.get("reused_home_visit_id") == null ? null
          : ((Number) app.get("reused_home_visit_id")).longValue();
      boolean hasVisit = !visits.isEmpty() || reusedVisitId != null;

      // DE-4: a denial with no home visit is the director's call alone, and it is labelled.
      String noVisitNote = null;
      if (!hasVisit) {
        if (outcome.equals("APPROVE")) {
          throw ApiException.ruleViolation("Application " + appId + " has no home visit. "
              + "Every application gets one before it is approved (Interview 3).", "RULE HV-1");
        }
        if (req.role() != Role.DIRECTOR) {
          throw ApiException.notAllowed("Only the director may deny an application before a home visit.");
        }
        noVisitNote = "Denied before any home visit.";
      }

      // DE-2 and DE-3. The conflicts are frozen into the decision row, so the record shows what
      // the director was looking at, not what the restrictions happen to say years later.
      List<Map<String, Object>> conflictRows = conflicts(c, appId);
      String snapshot = null;
      if (!conflictRows.isEmpty()) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> row : conflictRows) {
          lines.add(row.get("type") + ": " + row.get("conflict"));
        }
        snapshot = String.join("; ", lines);
      }
      if (outcome.equals("APPROVE") && snapshot != null
          && (acknowledgement == null || acknowledgement.trim().length() < 15)) {
        throw ApiException.ruleViolation("This home conflicts with the animal's restrictions: "
            + snapshot + ". To approve anyway, write why the placement is still right for this animal "
            + "(at least 15 characters). That sentence is what defends the rescue later.", "RULE DE-3");
      }

      Long visitId = visits.isEmpty() ? reusedVisitId : ((Number) visits.get(0).get("id")).longValue();
      String fullRationale = noVisitNote == null ? rationale : noVisitNote + " " + rationale;

      long decisionId = Ctx.insertReturningId(c, """
          INSERT INTO rhrms.decision(application_id, outcome, denial_reason, rationale,
                 conflicts_snapshot, conflicts_acknowledgement, home_visit_id, decided_by,
                 supersedes_decision_id)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
          RETURNING id
          """, appId, outcome, denialReason, fullRationale, snapshot,
          outcome.equals("APPROVE") ? acknowledgement : null, visitId, req.userId(), supersedes);

      // DE-5. The status change comes after the decision row, because the trigger in
      // 02_rules.sql refuses an APPROVED or DENIED application that has no decision behind it.
      String newStatus = outcome.equals("APPROVE") ? "APPROVED" : "DENIED";
      int version = ((Number) app.get("version")).intValue();
      if (!was.equals(newStatus)) {
        int changed = Ctx.update(c,
            "UPDATE rhrms.application SET status = ?, version = version + 1 WHERE id = ? AND version = ?",
            newStatus, appId, version);
        if (changed != 1) throw Patch.staleEdit("application");
      }

      long animalId = ((Number) app.get("animal_id")).longValue();
      Json.Obj out = Json.obj()
          .put("decisionId", decisionId)
          .put("application", Ctx.queryOne(c,
              "SELECT id, status, version FROM rhrms.application WHERE id = ?", appId))
          .put("animal", AnimalApi.animalSummary(c, animalId))
          .putIf("conflictsRecorded", snapshot);

      if (outcome.equals("APPROVE")) {
        List<Map<String, Object>> closed = Ctx.query(c, """
            SELECT ap.id, pe.first_name || ' ' || pe.last_name AS applicant, ap.status
            FROM rhrms.application ap JOIN rhrms.person pe ON pe.id = ap.person_id
            WHERE ap.animal_id = ? AND ap.id <> ? AND ap.status IN ('SUBMITTED','UNDER_REVIEW')
            """, animalId, appId);
        out.putIf("stillOpenForThisAnimal", closed.isEmpty() ? null : closed);
        out.put("message", "Approved. The animal is on hold as PENDING_ADOPTION"
            + (Ctx.scalar(c, "SELECT 1 FROM rhrms.animal WHERE bond_group_id = "
                + "(SELECT bond_group_id FROM rhrms.animal WHERE id = ?) AND id <> ?", animalId, animalId) != null
                ? ", along with its bonded partner(s)" : "")
            + ". Record the adoption on the day they come to collect."
            + (closed.isEmpty() ? "" : " " + closed.size()
                + " other application(s) are still open and will be closed automatically when the "
                + "adoption is recorded."));
      } else {
        out.put("message", "Denied, with your reason and rationale on the record. "
            + "If this person applies again, the desk will be shown this decision.");
      }
      if (supersedes != null) {
        out.put("supersedes", supersedes)
           .put("message", out.map().get("message") + " The earlier decision stays visible in the record.");
      }
      return Res.created(out);
    });
  }

  // ================================================================= placement

  private static Res place(Ctx ctx, Req req) throws SQLException {
    long appId = req.pathId("id");
    String paymentMethod = req.requireOneOf("paymentMethod", "Payment method", PAYMENT_METHODS);
    BigDecimal baseline = ctx.settings().feeBaseline();
    BigDecimal fee = req.has("feeCharged") ? req.requireMoney("feeCharged", "The fee charged")
                                           : (paymentMethod.equals("WAIVED") ? BigDecimal.ZERO : baseline);
    String feeReason = req.optText("feeReason");
    String vetClinic = req.optText("vetClinicTold");
    boolean overrideVetting = req.flag("overrideVetting");
    String overrideReason = req.optText("overrideReason");
    List<Object> foodToSend = req.optList("prescriptionFood");

    // PL-2, checked here so the person gets a sentence rather than a constraint name.
    if (fee.compareTo(BigDecimal.ZERO) < 0) throw ApiException.badRequest("A fee cannot be negative.");
    if (fee.compareTo(baseline) != 0 && (feeReason == null || feeReason.length() < 3)) {
      throw ApiException.badRequest("The baseline fee is $" + baseline.toPlainString()
          + " and you entered $" + fee.toPlainString()
          + ". Say why (Interview 4: the fee is set case by case, and the reason has to be on the record).");
    }
    if (paymentMethod.equals("WAIVED") && fee.compareTo(BigDecimal.ZERO) != 0) {
      throw ApiException.badRequest("A waived fee has to be $0.00. "
          + "If money changed hands, pick cash, check or other.");
    }
    if (!paymentMethod.equals("WAIVED") && fee.compareTo(BigDecimal.ZERO) == 0) {
      throw ApiException.badRequest("A fee of $0.00 is recorded as WAIVED, with a reason.");
    }
    // PL-3: the vetting block is the director's to lift, and only with a reason.
    if (overrideVetting) {
      Permissions.require(req.role(), Perm.OVERRIDE);
      if (overrideReason == null || overrideReason.length() < 10) {
        throw ApiException.badRequest("Placing an animal that has not been vetted needs a written reason.");
      }
    }

    String reason = req.reason() == null ? "Adoption recorded" : req.reason();
    if (overrideVetting) reason = reason + " [vetting block overridden: " + overrideReason + "]";
    final String auditReason = reason;

    return ctx.db().tx(req.role(), req.userId(), auditReason, null, c -> {
      Map<String, Object> app = requireApplication(c, appId);
      if (!"APPROVED".equals(app.get("status"))) {
        throw ApiException.ruleViolation("Application " + appId + " is "
            + String.valueOf(app.get("status")).toLowerCase()
            + ", not approved. The adoption was not recorded.", "RULE PL-1");
      }
      long animalId = ((Number) app.get("animal_id")).longValue();

      // Which animals this covers: the one applied for plus every living bond partner (AN-6).
      List<Map<String, Object>> covered = Ctx.query(c, """
          SELECT a.id, a.animal_code, a.name, a.vet_status
          FROM rhrms.animal a
          WHERE (a.id = ? OR (a.bond_group_id IS NOT NULL
                 AND a.bond_group_id = (SELECT bond_group_id FROM rhrms.animal WHERE id = ?)))
            AND a.status <> 'DECEASED'
          ORDER BY a.id
          """, animalId, animalId);

      if (overrideVetting) {
        // place_animal() reads this, so a director's override lives for exactly this
        // transaction and is spelled out in the audit reason above. See sql/02_rules.sql.
        Ctx.run(c, "SELECT set_config('rhrms.override_vetting', 'true', true)");
      }

      // PL-1 and PL-5: one call, one transaction. Placement rows, ADOPTED status, kennels
      // cleared, stays ended, diets ended, other applications closed - or none of it.
      Object placed = Ctx.scalar(c, "SELECT rhrms.place_animal(?, ?, ?, ?, ?, ?)",
          appId, req.userId(), fee, paymentMethod, feeReason, vetClinic);
      int placedCount = placed == null ? 0 : ((Number) placed).intValue();

      // PL-1: the prescription food that goes home with the animal, as a stock movement so the
      // forecast stops counting food nobody here is eating.
      List<Object> foodSent = new ArrayList<>();
      for (Object entry : foodToSend) {
        if (!(entry instanceof Map<?, ?> m)) {
          throw ApiException.badRequest("prescriptionFood should be a list of "
              + "{animalId, foodProductId, locationId, bags}.");
        }
        long sentAnimal = number(m.get("animalId"), "animalId");
        long product = number(m.get("foodProductId"), "foodProductId");
        int location = (int) number(m.get("locationId"), "locationId");
        int bags = (int) number(m.get("bags"), "bags");
        Ctx.scalar(c, "SELECT rhrms.move_stock(?, ?::smallint, 'SEND_WITH_ANIMAL', ?, ?, ?, ?)",
            product, location, bags, req.userId(), "Went home with the adopter", sentAnimal);
        foodSent.add(Json.obj().put("animalId", sentAnimal).put("foodProductId", product)
            .put("bags", bags));
      }

      List<Map<String, Object>> closedOthers = Ctx.query(c, """
          SELECT ap.id, pe.first_name || ' ' || pe.last_name AS applicant, ap.closed_reason
          FROM rhrms.application ap JOIN rhrms.person pe ON pe.id = ap.person_id
          WHERE ap.animal_id = ANY (SELECT a.id FROM rhrms.animal a
                                     WHERE a.id = ? OR a.bond_group_id =
                                       (SELECT bond_group_id FROM rhrms.animal WHERE id = ?))
            AND ap.id <> ? AND ap.status = 'CLOSED'
            AND ap.closed_reason = 'Animal placed with another applicant'
          """, animalId, animalId, appId);

      StringBuilder message = new StringBuilder("Adoption recorded for ");
      List<String> names = new ArrayList<>();
      for (Map<String, Object> a : covered) names.add(AnimalApi.label(a));
      message.append(String.join(" and ", names)).append(". Fee $").append(fee.toPlainString())
             .append(" (").append(paymentMethod.toLowerCase()).append(").");
      if (placedCount > 1) message.append(" One fee covers the bonded pair.");
      if (!closedOthers.isEmpty()) {
        message.append(" ").append(closedOthers.size())
               .append(" other application(s) were closed with the reason \"animal placed with another applicant\".");
      }
      if (!foodSent.isEmpty()) message.append(" Prescription food was recorded as going with them.");
      if (overrideVetting) message.append(" The vetting block was overridden and the reason is on the record.");

      return Res.created(Json.obj()
          .put("animalsPlaced", placedCount)
          .put("placements", Ctx.query(c, """
              SELECT pl.id, pl.animal_id, an.animal_code, an.name AS animal_name, pl.placed_on,
                     pl.fee_baseline, pl.fee_charged, pl.fee_reason, pl.payment_method,
                     pl.vet_clinic_told
              FROM rhrms.placement pl JOIN rhrms.animal an ON an.id = pl.animal_id
              WHERE pl.application_id = ? ORDER BY pl.animal_id
              """, appId))
          .putIf("prescriptionFoodSent", foodSent.isEmpty() ? null : foodSent)
          .putIf("otherApplicationsClosed", closedOthers.isEmpty() ? null : closedOthers)
          .put("message", message.toString()));
    });
  }

  // ==================================================================== return

  private static Res returnAnimal(Ctx ctx, Req req) throws SQLException {
    long animalId = req.pathId("id");
    String returnReason = req.requireText("returnReason", "Why the animal is back", 5);
    Integer kennelId = req.optInt("kennelId");
    if (kennelId == null) {
      throw ApiException.badRequest("Which kennel is the animal going into? "
          + "GET /api/kennels?free=true lists the empty ones.");
    }
    return ctx.db().tx(req.role(), req.userId(), "Return: " + returnReason, null, c -> {
      Map<String, Object> animal = AnimalApi.requireAnimal(c, animalId);
      Object stayId = Ctx.scalar(c, "SELECT rhrms.return_animal(?, ?, ?, ?::smallint)",
          animalId, req.userId(), returnReason, kennelId);

      List<Map<String, Object>> restrictions = AnimalApi.restrictions(c, animalId, false);
      return Res.created(Json.obj()
          .put("newStayId", stayId)
          .put("animal", AnimalApi.animalSummary(c, animalId))
          .put("restrictions", restrictions)
          .put("placement", Ctx.queryOne(c, """
              SELECT pl.id, pl.placed_on, pl.returned_on, pl.return_reason,
                     pe.first_name || ' ' || pe.last_name AS adopter
              FROM rhrms.placement pl JOIN rhrms.person pe ON pe.id = pl.person_id
              WHERE pl.animal_id = ? ORDER BY pl.returned_on DESC NULLS FIRST LIMIT 1
              """, animalId))
          .put("nextStep", "Review the restrictions. Whatever brought " + AnimalApi.label(animal)
              + " back may be a restriction now. When you are satisfied, set the status to AVAILABLE.")
          .put("message", AnimalApi.label(animal) + " is back, in kennel " + kennelId
              + ", on the same record and the same animal code. A new stay has started and the "
              + "reason is on the closed adoption. The animal is marked not adoptable until "
              + "somebody reviews its restrictions."));
    });
  }

  // =================================================================== queries

  static Map<String, Object> requireApplication(Connection c, long id) throws SQLException {
    return Ctx.queryOneOr404(c, "There is no application number " + id + ".", """
        SELECT ap.id, ap.person_id, ap.animal_id, ap.status, ap.submitted_at, ap.version,
               ap.housing_type, ap.landlord_allows_pets, ap.has_yard, ap.yard_fenced,
               ap.children_count, ap.youngest_child_age, ap.elderly_in_home, ap.adults_in_home,
               ap.other_pets, ap.reason_for_adopting, ap.prior_application_id,
               ap.reused_home_visit_id, ap.situation_check_note, ap.closed_reason
        FROM rhrms.application ap WHERE ap.id = ?
        """, id);
  }

  static List<Map<String, Object>> homeVisits(Connection c, long applicationId) throws SQLException {
    return Ctx.query(c, """
        SELECT hv.id, hv.visited_on, hv.visitor_name, hv.visitor_user_id,
               eu.display_name AS entered_by, hv.children_present, hv.youngest_child_age,
               hv.yard, hv.yard_fenced, hv.other_pets, hv.elderly_residents, hv.adult_men,
               hv.adult_women, hv.housing_confirmed, hv.other_concerns, hv.recommendation,
               hv.notes, hv.created_at
        FROM rhrms.home_visit hv
        JOIN rhrms.staff_user eu ON eu.id = hv.entered_by
        WHERE hv.application_id = ?
        ORDER BY hv.visited_on DESC, hv.id DESC
        """, applicationId);
  }

  static List<Map<String, Object>> decisions(Connection c, long applicationId) throws SQLException {
    return Ctx.query(c, """
        SELECT d.id, d.outcome, d.denial_reason, d.rationale, d.conflicts_snapshot,
               d.conflicts_acknowledgement, d.home_visit_id, d.decided_at,
               d.supersedes_decision_id, u.display_name AS decided_by,
               EXISTS (SELECT 1 FROM rhrms.decision s WHERE s.supersedes_decision_id = d.id) AS superseded
        FROM rhrms.decision d
        JOIN rhrms.staff_user u ON u.id = d.decided_by
        WHERE d.application_id = ?
        ORDER BY d.decided_at
        """, applicationId);
  }

  /** DE-2: the restriction conflicts view, for one application. */
  static List<Map<String, Object>> conflicts(Connection c, long applicationId) throws SQLException {
    return Ctx.query(c, """
        SELECT rc.home_visit_id, rc.animal_id, rc.type, rc.conflict,
               a.animal_code, a.name AS animal_name,
               r.detail, r.min_child_age
        FROM rhrms.restriction_conflicts rc
        JOIN rhrms.animal a ON a.id = rc.animal_id
        LEFT JOIN rhrms.restriction r ON r.animal_id = rc.animal_id AND r.type = rc.type AND r.active
        WHERE rc.application_id = ?
        ORDER BY rc.animal_id, rc.type
        """, applicationId);
  }

  /**
   * HV-4: differences between what the paper application said and what the visit found.
   *
   * Not a rule, and nothing is refused for it: people forget to tick boxes. It is put in front
   * of the director because "the application said no children, the visit found a four-year-old"
   * is exactly the kind of thing that must not go unnoticed.
   */
  static List<String> discrepancies(Map<String, Object> application, List<Map<String, Object>> visits) {
    List<String> out = new ArrayList<>();
    if (visits.isEmpty()) return out;
    Map<String, Object> visit = visits.get(0);              // the most recent one

    Integer appChildren = intOrNull(application.get("children_count"));
    boolean visitChildren = Boolean.TRUE.equals(visit.get("children_present"));
    if (visitChildren && appChildren != null && appChildren == 0) {
      out.add("The application said there are no children; the visit found children"
          + (visit.get("youngest_child_age") == null ? "" : " (youngest " + visit.get("youngest_child_age") + ")")
          + ".");
    }
    if (!visitChildren && appChildren != null && appChildren > 0) {
      out.add("The application said " + appChildren + " child(ren); the visit found none at home.");
    }
    Integer appYoungest = intOrNull(application.get("youngest_child_age"));
    Integer visitYoungest = intOrNull(visit.get("youngest_child_age"));
    if (appYoungest != null && visitYoungest != null && !appYoungest.equals(visitYoungest)) {
      out.add("The application said the youngest child is " + appYoungest
          + "; the visit found " + visitYoungest + ".");
    }

    compare(out, application.get("has_yard"), visit.get("yard"),
        "The application said there is a yard; the visit found none.",
        "The application said there is no yard; the visit found one.");
    compare(out, application.get("yard_fenced"), visit.get("yard_fenced"),
        "The application said the yard is fenced; the visit found it is not.",
        "The application said the yard is not fenced; the visit found it is.");
    compare(out, application.get("elderly_in_home"), visit.get("elderly_residents"),
        "The application said there is an elderly resident; the visit found none.",
        "The application did not mention an elderly resident; the visit found one.");

    String appPets = text(application.get("other_pets"));
    String visitPets = text(visit.get("other_pets"));
    if (appPets == null && visitPets != null) {
      out.add("The application listed no other pets; the visit found: " + visitPets + ".");
    }
    if (Boolean.FALSE.equals(visit.get("housing_confirmed"))) {
      out.add("The visitor could not confirm the housing.");
    }
    if ("APARTMENT".equals(application.get("housing_type"))
        && !Boolean.TRUE.equals(application.get("landlord_allows_pets"))) {
      out.add("The application says an apartment, and does not confirm the landlord allows pets.");
    }
    return out;
  }

  private static void compare(List<String> out, Object claimed, Object found,
                              String claimedTrueFoundFalse, String claimedFalseFoundTrue) {
    if (!(claimed instanceof Boolean c) || !(found instanceof Boolean f)) return;
    if (c && !f) out.add(claimedTrueFoundFalse);
    if (!c && f) out.add(claimedFalseFoundTrue);
  }

  private static Integer intOrNull(Object o) {
    return o instanceof Number n ? n.intValue() : null;
  }

  private static String text(Object o) {
    if (o == null) return null;
    String s = String.valueOf(o).trim();
    return s.isEmpty() ? null : s;
  }

  /** True when these two names plainly belong to the same person. */
  private static boolean samePerson(String visitorName, String displayName) {
    if (visitorName == null || displayName == null) return false;
    String a = visitorName.trim().toLowerCase(java.util.Locale.ROOT);
    String b = displayName.trim().toLowerCase(java.util.Locale.ROOT);
    return a.equals(b) || b.contains(a) || a.contains(b);
  }

  private static long number(Object o, String field) {
    if (o instanceof Number n) return n.longValue();
    try {
      return Long.parseLong(String.valueOf(o).trim());
    } catch (RuntimeException e) {
      throw ApiException.badRequest("'" + field + "' must be a number.");
    }
  }

  /** Everything the application screen shows. */
  static Json.Obj applicationRecord(Connection c, long id) throws SQLException {
    Map<String, Object> app = Ctx.queryOneOr404(c, "There is no application number " + id + ".", """
        SELECT ap.*, u.display_name AS received_by_name,
               pe.first_name || ' ' || pe.last_name AS applicant,
               pe.phone, pe.email, pe.address, pe.city, pe.state, pe.postal_code,
               an.animal_code, an.name AS animal_name, an.species, an.breed,
               an.status AS animal_status, an.vet_status, an.kennel_id, an.bond_group_id
        FROM rhrms.application ap
        JOIN rhrms.staff_user u ON u.id = ap.received_by
        JOIN rhrms.person pe    ON pe.id = ap.person_id
        JOIN rhrms.animal an    ON an.id = ap.animal_id
        WHERE ap.id = ?
        """, id);

    List<Map<String, Object>> visits = homeVisits(c, id);
    long animalId = ((Number) app.get("animal_id")).longValue();

    Json.Obj out = Json.obj()
        .put("application", app)
        .put("homeVisits", visits)
        .put("decisions", decisions(c, id))
        .put("conflicts", conflicts(c, id))
        .put("discrepancies", discrepancies(app, visits))
        .put("animalRestrictions", AnimalApi.restrictions(c, animalId, false))
        .put("bondPartners", Ctx.query(c, """
            SELECT o.id, o.animal_code, o.name, o.status
            FROM rhrms.animal o
            WHERE o.bond_group_id = ?::bigint AND o.bond_group_id IS NOT NULL AND o.id <> ?
              AND o.status <> 'DECEASED'
            ORDER BY o.id
            """, app.get("bond_group_id"), animalId))
        .put("competingApplications", Ctx.query(c, """
            SELECT ap.id, ap.status, ap.submitted_at,
                   pe.first_name || ' ' || pe.last_name AS applicant,
                   (SELECT d.outcome FROM rhrms.decision d WHERE d.application_id = ap.id
                     ORDER BY d.decided_at DESC LIMIT 1) AS latest_outcome
            FROM rhrms.application ap JOIN rhrms.person pe ON pe.id = ap.person_id
            WHERE ap.animal_id = ? AND ap.id <> ?
            ORDER BY ap.submitted_at
            """, animalId, id))
        .put("placements", Ctx.query(c, """
            SELECT pl.id, pl.animal_id, pl.placed_on, pl.fee_charged, pl.payment_method,
                   pl.returned_on, pl.return_reason
            FROM rhrms.placement pl WHERE pl.application_id = ? ORDER BY pl.animal_id
            """, id));

    if (app.get("reused_home_visit_id") != null) {
      out.put("reusedHomeVisit", Ctx.queryOne(c, """
          SELECT hv.id, hv.visited_on, hv.visitor_name, hv.recommendation, hv.notes,
                 hv.children_present, hv.youngest_child_age, hv.yard, hv.yard_fenced,
                 hv.elderly_residents, hv.adult_men, hv.adult_women, hv.other_pets
          FROM rhrms.home_visit hv WHERE hv.id = ?
          """, app.get("reused_home_visit_id")));
    }
    return out;
  }
}
