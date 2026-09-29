package org.rubyhill.rhrms.api;

import org.rubyhill.rhrms.http.ApiException;
import org.rubyhill.rhrms.http.Req;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Builds a safe UPDATE from only the fields a request actually sent.
 *
 * Two reasons this exists rather than a pile of coalesce(?, column) in the SQL:
 *
 *  - "leave it alone" and "clear it" are different instructions. A note that should be emptied
 *    has to be settable to NULL, and coalesce() can never do that.
 *  - Level 3. The UPDATE always carries "AND version = ?", so two people editing the same
 *    animal from the same screen state cannot silently overwrite each other; the second one
 *    changes no rows and is told so.
 *
 * Column names are never taken from the request. Each one is named in the handler's own
 * whitelist, so the SQL text is fixed by the code and only values are ever bound.
 */
final class Patch {
  private final Map<String, Object> columns = new LinkedHashMap<>();
  private final List<String> fieldsSeen = new ArrayList<>();

  /** Adds "column = value" when the request mentioned this field at all, null included. */
  <T> Patch set(Req req, String jsonField, String column, Function<String, T> reader) {
    if (!req.body().containsKey(jsonField)) return this;
    columns.put(column, reader.apply(jsonField));
    fieldsSeen.add(jsonField);
    return this;
  }

  Patch text(Req req, String jsonField, String column)  { return set(req, jsonField, column, req::optText); }
  Patch bool(Req req, String jsonField, String column)  { return set(req, jsonField, column, req::optBool); }
  Patch integer(Req req, String jsonField, String column) { return set(req, jsonField, column, req::optInt); }
  Patch date(Req req, String jsonField, String column)  { return set(req, jsonField, column, req::optDate); }
  Patch id(Req req, String jsonField, String column)    { return set(req, jsonField, column, req::optLong); }
  Patch decimal(Req req, String jsonField, String column) { return set(req, jsonField, column, req::optDecimal); }

  /** One of a fixed list of words, upper-cased; nothing else gets through. */
  Patch oneOf(Req req, String jsonField, String column, String humanName, String... allowed) {
    return set(req, jsonField, column, f -> req.optOneOf(f, humanName, allowed));
  }

  boolean isEmpty() { return columns.isEmpty(); }

  List<String> fields() { return fieldsSeen; }

  /**
   * Runs the update. Returns the number of rows changed: 0 means somebody else saved first.
   * Always bumps version, so the next person's stale write is caught the same way.
   */
  int run(java.sql.Connection c, String table, long id, int version) throws java.sql.SQLException {
    if (columns.isEmpty()) {
      throw ApiException.badRequest("Nothing to change. Send at least one field.");
    }
    StringBuilder sql = new StringBuilder("UPDATE ").append(table).append(" SET ");
    List<Object> params = new ArrayList<>();
    for (Map.Entry<String, Object> e : columns.entrySet()) {
      if (!params.isEmpty()) sql.append(", ");
      sql.append(e.getKey()).append(" = ?");
      params.add(e.getValue());
    }
    sql.append(", version = version + 1 WHERE id = ? AND version = ?");
    params.add(id);
    params.add(version);
    return Ctx.update(c, sql.toString(), params.toArray());
  }

  /** The Level 3 message, worded so the person knows what to do next. */
  static ApiException staleEdit(String what) {
    return ApiException.conflict("Somebody else saved a change to this " + what
        + " while you were typing. Nothing of yours was saved, and their change is still there. "
        + "Open it again, look at what changed, and re-apply your edit.");
  }
}
