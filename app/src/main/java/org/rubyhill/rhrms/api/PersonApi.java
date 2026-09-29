package org.rubyhill.rhrms.api;

import org.rubyhill.rhrms.http.ApiException;
import org.rubyhill.rhrms.http.Res;
import org.rubyhill.rhrms.http.Router;
import org.rubyhill.rhrms.json.Json;
import org.rubyhill.rhrms.security.Perm;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * People: adopters, applicants and owners who surrender an animal.
 *
 * The interesting part is AP-4. When somebody who has applied before turns up at the desk, the
 * person who is typing has to be able to see, without hunting, what happened last time and
 * whether this person may apply again. So the person record carries the re-eligibility table
 * from the build spec (section 5.2), worked out from the actual denial reasons on file.
 */
final class PersonApi {
  private PersonApi() {}

  /**
   * Build spec section 5.2: what each denial reason means for applying again. This is guidance
   * shown to whoever is at the desk, not a rule the system enforces - only the director decides,
   * and he may always look at a case again. WELFARE_CONCERN is the one that means no.
   */
  private static final Map<String, String> RE_ELIGIBILITY = Map.ofEntries(
      Map.entry("HOUSING_NO_PETS", "May apply again if their housing has changed."),
      Map.entry("CHILDREN_UNSUITABLE", "May apply again for a different animal."),
      Map.entry("YARD_UNSUITABLE", "May apply again for a different animal."),
      Map.entry("OTHER_PETS_UNSUITABLE", "May apply again for a different animal."),
      Map.entry("HOUSEHOLD_UNSUITABLE", "May apply again for a different animal."),
      Map.entry("BONDED_PAIR_NOT_TAKEN", "May apply again for any animal."),
      Map.entry("BETTER_FIT_CHOSEN", "May apply again for any animal."),
      Map.entry("APPLICATION_MISMATCH", "The director has to review this person before a new application."),
      Map.entry("WELFARE_CONCERN", "Should NOT be given another animal. Check with the director first."),
      Map.entry("OTHER", "As set out in the director's written reason below."));

  static void register(Router r, Ctx ctx) {

    r.route("GET", "/api/people", Perm.VIEW, "search people by name, phone or town", req -> {
      String q = req.queryParam("q", null);
      int limit = req.queryInt("limit", 50, 500);
      return ctx.read(req, c -> Res.list("people", Ctx.query(c, """
          SELECT p.id, p.first_name, p.last_name, p.phone, p.email, p.city, p.state, p.version,
                 (SELECT count(*) FROM rhrms.application ap WHERE ap.person_id = p.id) AS applications,
                 (SELECT count(*) FROM rhrms.placement pl
                   WHERE pl.person_id = p.id AND pl.returned_on IS NULL) AS animals_now_with_them,
                 (SELECT count(*) FROM rhrms.placement pl
                   WHERE pl.person_id = p.id AND pl.returned_on IS NOT NULL) AS returns
          FROM rhrms.person p
          WHERE ?::text IS NULL
             OR p.last_name  ILIKE '%' || ?::text || '%'
             OR p.first_name ILIKE '%' || ?::text || '%'
             OR (p.first_name || ' ' || p.last_name) ILIKE '%' || ?::text || '%'
             OR replace(replace(replace(coalesce(p.phone,''), ' ', ''), '-', ''), '(', '')
                  ILIKE '%' || replace(replace(replace(?::text, ' ', ''), '-', ''), '(', '') || '%'
             OR p.city ILIKE '%' || ?::text || '%'
          ORDER BY lower(p.last_name), lower(p.first_name)
          LIMIT ?::int
          """, q, q, q, q, q == null ? "" : q, q, limit)));
    });

    r.route("POST", "/api/people", Perm.PERSON_CREATE, "add a person", req -> {
      String first = req.requireText("firstName", "First name");
      String last = req.requireText("lastName", "Last name");
      String phone = cleanPhone(req.optText("phone"));
      return ctx.write(req, c -> {
        // Not an error, just worth knowing: the same name may already be on file. The caller
        // decides; nothing is blocked, because two people really can share a name.
        List<Map<String, Object>> sameName = Ctx.query(c,
            "SELECT id, first_name, last_name, phone, city FROM rhrms.person"
                + " WHERE lower(first_name) = lower(?) AND lower(last_name) = lower(?) LIMIT 5",
            first, last);
        long id = Ctx.insertReturningId(c, """
            INSERT INTO rhrms.person(first_name, last_name, phone, email, address, city, state,
                                     postal_code, notes)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
            """, first, last, phone, req.optText("email"), req.optText("address"),
            req.optText("city"), req.optText("state"), req.optText("postalCode"), req.optText("notes"));
        Json.Obj out = Json.obj()
            .put("person", Ctx.queryOne(c, PERSON_COLUMNS + " WHERE p.id = ?", id))
            .put("message", first + " " + last + " added.");
        if (!sameName.isEmpty()) {
          out.put("alsoOnFile", sameName)
             .put("message", first + " " + last + " added. Note: somebody with the same name was "
                 + "already on file - check you did not mean them.");
        }
        return Res.created(out);
      });
    });

    r.route("GET", "/api/people/{id}", Perm.VIEW,
        "one person, with everything that ever happened (AP-4)", req -> {
      long id = req.pathId("id");
      return ctx.read(req, c -> Res.ok(personRecord(c, id)));
    });

    r.route("PATCH", "/api/people/{id}", Perm.RECORD_CORRECT,
        "correct a person's contact details; a reason is required", req -> {
      long id = req.pathId("id");
      int version = req.requireInt("version", "The version you are editing");
      if (req.body().containsKey("phone")) {
        cleanPhone(req.optText("phone"));            // fail early with a readable message
      }
      return ctx.writeWithReason(req, c -> {
        Patch patch = new Patch()
            .text(req, "firstName", "first_name")
            .text(req, "lastName", "last_name")
            .set(req, "phone", "phone", f -> cleanPhone(req.optText(f)))
            .text(req, "email", "email")
            .text(req, "address", "address")
            .text(req, "city", "city")
            .text(req, "state", "state")
            .text(req, "postalCode", "postal_code")
            .text(req, "notes", "notes");
        if (patch.run(c, "rhrms.person", id, version) != 1) {
          if (Ctx.scalar(c, "SELECT 1 FROM rhrms.person WHERE id = ?", id) == null) {
            throw ApiException.notFound("There is no person number " + id + ".");
          }
          throw Patch.staleEdit("person");
        }
        return Res.ok(Json.obj()
            .put("person", Ctx.queryOne(c, PERSON_COLUMNS + " WHERE p.id = ?", id))
            .put("changed", patch.fields())
            .put("message", "Saved."));
      });
    });

    r.route("GET", "/api/people/{id}/history", Perm.VIEW, "every recorded change to this person",
        req -> {
          long id = req.pathId("id");
          return ctx.read(req, c -> Res.list("history",
              ReportApi.auditFor(c, "person", String.valueOf(id), req.queryInt("limit", 200, 1000))));
        });
  }

  private static final String PERSON_COLUMNS = """
      SELECT p.id, p.first_name, p.last_name, p.phone, p.email, p.address, p.city, p.state,
             p.postal_code, p.notes, p.created_at, p.version
      FROM rhrms.person p
      """;

  /**
   * Phones are stored as a string on purpose (Interview 4, and spec section 13): the rescue
   * writes them in several styles and none of them is arithmetic. Anything that is not a digit,
   * space, dash, bracket or a leading plus is refused, which is the same rule the database
   * CHECK applies, just with a sentence attached.
   */
  static String cleanPhone(String raw) {
    if (raw == null) return null;
    String phone = raw.trim();
    if (phone.isEmpty()) return null;
    if (!phone.matches("\\+?[0-9 ()\\-]{7,24}")) {
      throw ApiException.badRequest("'" + raw + "' does not look like a phone number. "
          + "Use digits, spaces, dashes and brackets, for example (970) 555-0142.");
    }
    return phone;
  }

  static Json.Obj personRecord(Connection c, long id) throws SQLException {
    Map<String, Object> person = Ctx.queryOneOr404(c, "There is no person number " + id + ".",
        PERSON_COLUMNS + " WHERE p.id = ?", id);

    List<Map<String, Object>> applications = Ctx.query(c, """
        SELECT ap.id, ap.status, ap.submitted_at, ap.closed_reason, ap.prior_application_id,
               ap.situation_check_note, ap.reused_home_visit_id,
               an.id AS animal_id, an.animal_code, an.name AS animal_name, an.species, an.status AS animal_status,
               u.display_name AS received_by,
               (SELECT count(*) FROM rhrms.home_visit hv WHERE hv.application_id = ap.id) AS home_visits,
               d.outcome, d.denial_reason, d.rationale, d.decided_at,
               du.display_name AS decided_by
        FROM rhrms.application ap
        JOIN rhrms.animal an     ON an.id = ap.animal_id
        JOIN rhrms.staff_user u  ON u.id = ap.received_by
        LEFT JOIN LATERAL (
              SELECT * FROM rhrms.decision d2
               WHERE d2.application_id = ap.id
                 AND NOT EXISTS (SELECT 1 FROM rhrms.decision s WHERE s.supersedes_decision_id = d2.id)
               ORDER BY d2.decided_at DESC LIMIT 1) d ON TRUE
        LEFT JOIN rhrms.staff_user du ON du.id = d.decided_by
        WHERE ap.person_id = ?
        ORDER BY ap.submitted_at DESC
        """, id);

    // AP-4: the guidance the desk needs, drawn from the denial reasons actually on file.
    Map<String, Object> reapply = new LinkedHashMap<>();
    boolean anyDenial = false;
    boolean welfareConcern = false;
    for (Map<String, Object> app : applications) {
      Object reason = app.get("denial_reason");
      if (reason == null) continue;
      anyDenial = true;
      String key = String.valueOf(reason);
      reapply.put(key, RE_ELIGIBILITY.getOrDefault(key, "Ask the director."));
      if (key.equals("WELFARE_CONCERN")) welfareConcern = true;
    }

    return Json.obj()
        .put("person", person)
        .put("applications", applications)
        .put("placements", Ctx.query(c, """
            SELECT pl.id, pl.placed_on, pl.fee_charged, pl.fee_baseline, pl.fee_reason,
                   pl.payment_method, pl.returned_on, pl.return_reason,
                   an.id AS animal_id, an.animal_code, an.name AS animal_name, an.species,
                   u.display_name AS recorded_by
            FROM rhrms.placement pl
            JOIN rhrms.animal an    ON an.id = pl.animal_id
            JOIN rhrms.staff_user u ON u.id = pl.recorded_by
            WHERE pl.person_id = ?
            ORDER BY pl.placed_on DESC
            """, id))
        .put("surrendered", Ctx.query(c, """
            SELECT s.id, s.intake_date, s.intake_source, an.animal_code, an.name AS animal_name
            FROM rhrms.stay s
            JOIN rhrms.animal an ON an.id = s.animal_id
            WHERE s.surrendered_by = ?
            ORDER BY s.intake_date DESC
            """, id))
        .put("mayApplyAgain", Json.obj()
            .put("anyDenialOnFile", anyDenial)
            .put("welfareConcern", welfareConcern)
            .put("guidance", reapply)
            .put("note", welfareConcern
                ? "A past denial was for a welfare concern. Do not take a new application without the director."
                : anyDenial
                    ? "This person has been denied before. Read the reasons above before taking a new application."
                    : "Nothing on file that would stop a new application."));
  }
}
