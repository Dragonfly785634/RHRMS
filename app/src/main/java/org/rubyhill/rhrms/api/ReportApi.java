package org.rubyhill.rhrms.api;

import org.rubyhill.rhrms.http.ApiException;
import org.rubyhill.rhrms.http.Res;
import org.rubyhill.rhrms.http.Router;
import org.rubyhill.rhrms.json.Json;
import org.rubyhill.rhrms.security.Perm;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The screens that only read: the dashboard, the decision lookup, and the history of any record.
 *
 * The decision lookup is the director's core screen (section 6.7). One search box, and what
 * comes back is the whole story behind a placement: the application, the visit, the conflicts,
 * the rationale, the other applicants, the fee, and any later return. That is what "it explains
 * itself" means in practice, and it is why the audit log exists.
 */
final class ReportApi {
  private ReportApi() {}

  /** Tables the History view may be asked for. Fixed list: the value never reaches the SQL. */
  private static final List<String> AUDITABLE = List.of("animal", "person", "application",
      "home_visit", "decision", "placement", "stay", "restriction", "kennel", "diet",
      "prescription", "food_product", "food_order", "stock_level", "stock_movement",
      "donation", "vet_visit", "setting", "staff_user", "bond_group", "litter",
      "open_bag", "stock_location", "animal_name_history");

  static void register(Router r, Ctx ctx) {

    // =============================================================== dashboard
    r.route("GET", "/api/dashboard", Perm.VIEW, "the front-desk dashboard (section 8)",
        req -> ctx.read(req, c -> {
      Map<String, Object> counts = Ctx.queryOne(c, """
          SELECT
            (SELECT count(*) FROM rhrms.animal WHERE status = 'AVAILABLE')        AS available,
            (SELECT count(*) FROM rhrms.animal WHERE status = 'PENDING_ADOPTION') AS pending_adoption,
            (SELECT count(*) FROM rhrms.animal WHERE status = 'NOT_ADOPTABLE')    AS not_adoptable,
            (SELECT count(*) FROM rhrms.animal a
              JOIN rhrms.stay s ON s.animal_id = a.id AND s.ended_on IS NULL
             WHERE a.vet_status = 'NOT_VETTED')                                   AS here_and_not_vetted,
            (SELECT count(*) FROM rhrms.animal WHERE needs_split)                 AS needs_split,
            (SELECT count(*) FROM rhrms.stay WHERE ended_on IS NULL)              AS animals_here,
            (SELECT count(*) FROM rhrms.kennel_board WHERE animals = 0 AND in_service) AS free_kennels,
            (SELECT count(*) FROM rhrms.kennel WHERE NOT in_service)              AS kennels_out_of_service,
            (SELECT count(*) FROM rhrms.application
              WHERE status IN ('SUBMITTED','UNDER_REVIEW','APPROVED'))            AS open_applications
          """);

      // Section 8: the red banner and the pop-up on login. Both come from the same list.
      List<Map<String, Object>> reorderNow = Ctx.query(c, """
          SELECT id, name, kind, days_of_supply, lead_time_days, sealed_bags, eaten_by
          FROM rhrms.food_forecast
          WHERE food_status = 'REORDER NOW'
          ORDER BY days_of_supply NULLS FIRST, name
          """);

      return Res.ok(Json.obj()
          .put("counts", counts)
          .put("kennels", Ctx.query(c,
              "SELECT kennel, in_service, occupants, animals FROM rhrms.kennel_board ORDER BY kennel"))
          .put("awaitingHomeVisit", Ctx.query(c, """
              SELECT ap.id, ap.submitted_at,
                     pe.first_name || ' ' || pe.last_name AS applicant, pe.phone,
                     an.animal_code, an.name AS animal_name,
                     (CURRENT_DATE - ap.submitted_at::date) AS days_waiting
              FROM rhrms.application ap
              JOIN rhrms.person pe ON pe.id = ap.person_id
              JOIN rhrms.animal an ON an.id = ap.animal_id
              WHERE ap.status = 'SUBMITTED' AND ap.reused_home_visit_id IS NULL
                AND NOT EXISTS (SELECT 1 FROM rhrms.home_visit hv WHERE hv.application_id = ap.id)
              ORDER BY ap.submitted_at
              """))
          .put("awaitingDecision", Ctx.query(c, """
              SELECT ap.id, ap.submitted_at,
                     pe.first_name || ' ' || pe.last_name AS applicant,
                     an.animal_code, an.name AS animal_name,
                     (SELECT hv.recommendation FROM rhrms.home_visit hv
                       WHERE hv.application_id = ap.id ORDER BY hv.visited_on DESC LIMIT 1) AS recommendation,
                     (SELECT count(*) FROM rhrms.restriction_conflicts rc
                       WHERE rc.application_id = ap.id) AS conflicts,
                     (CURRENT_DATE - ap.submitted_at::date) AS days_waiting
              FROM rhrms.application ap
              JOIN rhrms.person pe ON pe.id = ap.person_id
              JOIN rhrms.animal an ON an.id = ap.animal_id
              WHERE ap.status = 'UNDER_REVIEW'
              ORDER BY ap.submitted_at
              """))
          .put("readyToCollect", Ctx.query(c, """
              SELECT ap.id, pe.first_name || ' ' || pe.last_name AS applicant, pe.phone,
                     an.animal_code, an.name AS animal_name, an.vet_status,
                     d.decided_at
              FROM rhrms.application ap
              JOIN rhrms.person pe ON pe.id = ap.person_id
              JOIN rhrms.animal an ON an.id = ap.animal_id
              LEFT JOIN LATERAL (SELECT * FROM rhrms.decision d2
                                  WHERE d2.application_id = ap.id AND d2.outcome = 'APPROVE'
                                  ORDER BY d2.decided_at DESC LIMIT 1) d ON TRUE
              WHERE ap.status = 'APPROVED'
              ORDER BY d.decided_at
              """))
          .put("foodAlerts", reorderNow)
          .put("openFoodOrders", Ctx.query(c, """
              SELECT o.id, o.bags, o.status, o.expected_on, f.name AS food, f.kind,
                     ru.display_name AS requested_by
              FROM rhrms.food_order o
              JOIN rhrms.food_product f ON f.id = o.food_product_id
              JOIN rhrms.staff_user ru  ON ru.id = o.requested_by
              WHERE o.status IN ('REQUESTED','ORDERED')
              ORDER BY o.requested_at
              """))
          .put("needsSplit", Ctx.query(c, """
              SELECT id, animal_code, name, species, kennel_id, general_notes
              FROM rhrms.animal WHERE needs_split ORDER BY id
              """))
          .put("recentActivity", Ctx.query(c, """
              SELECT l.at, l.action, l.table_name, l.row_id, l.reason,
                     u.display_name AS who, l.on_behalf_of
              FROM rhrms.audit_log l
              LEFT JOIN rhrms.staff_user u ON u.id = l.user_id
              ORDER BY l.at DESC, l.id DESC
              LIMIT 15
              """))
          .putIf("banner", reorderNow.isEmpty() ? null
              : reorderNow.size() + " food item(s) need ordering now."));
    }));

    // =========================================================== decision lookup
    /*
     * LK-1: one search box. A name, a former name, an animal code, or an applicant. LK-2: what
     * comes back is the decision with everything behind it, on one screen, because the director
     * asked to be able to pull up a placement and show why it was made.
     */
    r.route("GET", "/api/lookup", Perm.VIEW,
        "decision lookup: search by applicant, animal name, former name or code (LK-1, LK-2)",
        req -> {
      String q = req.queryParam("q", null);
      if (q == null || q.length() < 2) {
        throw ApiException.badRequest("Type at least two characters: an applicant's name, "
            + "an animal's name (now or in the past), or an animal code like RH-000001.");
      }
      int limit = req.queryInt("limit", 50, 200);
      return ctx.read(req, c -> Res.list("decisions", Ctx.query(c, """
          SELECT dr.decision_id, dr.decided_at, dr.outcome, dr.denial_reason, dr.rationale,
                 dr.conflicts_snapshot, dr.conflicts_acknowledgement, dr.supersedes_decision_id,
                 dr.decided_by, dr.application_id, dr.application_status, dr.submitted_at,
                 dr.applicant, dr.phone, dr.animal_code, dr.animal_name,
                 dr.visited_on, dr.visitor_name, dr.visit_entered_by,
                 dr.visitor_recommendation, dr.visitor_notes,
                 EXISTS (SELECT 1 FROM rhrms.decision s
                          WHERE s.supersedes_decision_id = dr.decision_id) AS superseded
          FROM rhrms.decision_record dr
          JOIN rhrms.application ap ON ap.id = dr.application_id
          WHERE dr.applicant    ILIKE '%' || ?::text || '%'
             OR dr.animal_name  ILIKE '%' || ?::text || '%'
             OR dr.animal_code  ILIKE '%' || ?::text || '%'
             OR EXISTS (SELECT 1 FROM rhrms.animal_name_history h
                         WHERE h.animal_id = ap.animal_id AND h.name ILIKE '%' || ?::text || '%')
          ORDER BY dr.decided_at DESC
          LIMIT ?::int
          """, q, q, q, q, limit)));
    });

    /*
     * LK-2 in full, for one application: everything the director needs to defend the decision,
     * assembled in one place. This is what "Print / Save" (LK-4) would put on a page.
     */
    r.route("GET", "/api/lookup/application/{id}", Perm.VIEW,
        "the complete record behind one application (LK-2)", req -> {
      long id = req.pathId("id");
      return ctx.read(req, c -> {
        Json.Obj record = AdoptionApi.applicationRecord(c, id);
        // Read the two ids from the database rather than digging them back out of the JSON we
        // just built: one query, and no cast that the compiler cannot check.
        Map<String, Object> ids = Ctx.queryOneOr404(c, "There is no application number " + id + ".",
            "SELECT animal_id, person_id FROM rhrms.application WHERE id = ?", id);
        long animalId = ((Number) ids.get("animal_id")).longValue();
        long personId = ((Number) ids.get("person_id")).longValue();
        record.put("animalRecordSummary", AnimalApi.animalSummary(c, animalId));
        record.put("everyApplicantForThisAnimal", Ctx.query(c, """
            SELECT ap.id, ap.status, ap.submitted_at, ap.closed_reason,
                   pe.first_name || ' ' || pe.last_name AS applicant,
                   d.outcome, d.denial_reason, d.rationale, d.decided_at,
                   du.display_name AS decided_by
            FROM rhrms.application ap
            JOIN rhrms.person pe ON pe.id = ap.person_id
            LEFT JOIN LATERAL (SELECT * FROM rhrms.decision d2
                                WHERE d2.application_id = ap.id
                                  AND NOT EXISTS (SELECT 1 FROM rhrms.decision s
                                                   WHERE s.supersedes_decision_id = d2.id)
                                ORDER BY d2.decided_at DESC LIMIT 1) d ON TRUE
            LEFT JOIN rhrms.staff_user du ON du.id = d.decided_by
            WHERE ap.animal_id = ?
            ORDER BY ap.submitted_at
            """, animalId));
        record.put("applicantHistory", PersonApi.personRecord(c, personId).map().get("mayApplyAgain"));
        record.put("history", auditFor(c, "application", String.valueOf(id), 200));
        record.put("note", "Everything on this page was recorded at the time, by the people named, "
            + "with their reasons. Nothing here can be edited: a correction is a new record that "
            + "supersedes the old one, and both stay visible.");
        return Res.ok(record);
      });
    });

    // ================================================================== audit
    r.route("GET", "/api/audit", Perm.VIEW,
        "the change history of one record: who, when, old to new, and why (LK-5, AU-1)", req -> {
      String table = req.queryParam("table", null);
      String rowId = req.queryParam("rowId", null);
      int limit = req.queryInt("limit", 100, 1000);
      if (table == null) {
        return Res.ok(Json.obj()
            .put("tables", AUDITABLE)
            .put("message", "Ask for one record: /api/audit?table=animal&rowId=3"));
      }
      if (!AUDITABLE.contains(table)) {
        throw ApiException.badRequest("'" + table + "' is not one of the tables with a history. "
            + "Ask /api/audit with no parameters for the list.");
      }
      return ctx.read(req, c -> Res.list("history", auditFor(c, table, rowId, limit)));
    });

    r.route("GET", "/api/audit/recent", Perm.VIEW, "everything that changed lately", req -> {
      int limit = req.queryInt("limit", 100, 1000);
      Long userId = req.queryId("user");
      return ctx.read(req, c -> Res.list("history", Ctx.query(c, """
          SELECT l.id, l.at, l.action, l.table_name, l.row_id, l.reason, l.on_behalf_of,
                 l.user_id, u.display_name AS who, u.role AS who_role
          FROM rhrms.audit_log l
          LEFT JOIN rhrms.staff_user u ON u.id = l.user_id
          WHERE ?::bigint IS NULL OR l.user_id = ?::bigint
          ORDER BY l.at DESC, l.id DESC
          LIMIT ?::int
          """, userId, userId, limit)));
    });
  }

  /**
   * One record's history, as field-by-field changes rather than two lumps of JSON.
   *
   * jsonb_each_text on both sides and a join on the key turns "old row / new row" into
   * "this field went from X to Y", which is what LK-5 asks the History tab to show. The table
   * name comes from the fixed list above, never from the request.
   */
  static List<Map<String, Object>> auditFor(Connection c, String table, String rowId, int limit)
      throws SQLException {
    List<Map<String, Object>> entries = Ctx.query(c, """
        SELECT l.id, l.at, l.action, l.table_name, l.row_id, l.reason, l.on_behalf_of,
               u.display_name AS who, u.role AS who_role,
               (SELECT json_agg(json_build_object('field', k, 'from', o, 'to', n) ORDER BY k)
                  FROM (
                    SELECT coalesce(nw.key, od.key) AS k, od.value AS o, nw.value AS n
                    FROM jsonb_each_text(coalesce(l.new_row, '{}'::jsonb)) nw
                    FULL JOIN jsonb_each_text(coalesce(l.old_row, '{}'::jsonb)) od
                           ON od.key = nw.key
                    WHERE l.action = 'UPDATE'
                      AND nw.value IS DISTINCT FROM od.value
                      AND coalesce(nw.key, od.key) <> 'version'
                  ) diff) AS changes,
               CASE WHEN l.action = 'INSERT' THEN l.new_row END AS created_as
        FROM rhrms.audit_log l
        LEFT JOIN rhrms.staff_user u ON u.id = l.user_id
        WHERE l.table_name = ?::text
          AND (?::text IS NULL OR l.row_id = ?::text)
        ORDER BY l.at DESC, l.id DESC
        LIMIT ?::int
        """, table, rowId, rowId, limit);
    // An UPDATE that only bumped the version number is noise: it means a trigger elsewhere
    // touched the row. Keep the entry, but say so plainly.
    List<Map<String, Object>> out = new ArrayList<>(entries.size());
    for (Map<String, Object> e : entries) {
      if ("UPDATE".equals(e.get("action")) && e.get("changes") == null) {
        e.put("note", "No field changed, only the row's version, because a related record changed.");
      }
      out.add(e);
    }
    return out;
  }
}
