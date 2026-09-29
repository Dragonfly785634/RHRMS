package org.rubyhill.rhrms.json;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A very small JSON reader/writer.
 *
 * Why hand-written instead of Jackson or Gson: the build spec says every library has to be
 * justified to the instructor, and the whole API speaks flat objects, arrays, strings, numbers
 * and booleans. Two hundred lines here are easier to defend than a third-party dependency.
 * See docs/DECISIONS.md (D-07).
 */
public final class Json {
  private Json() {}

  // ------------------------------------------------------------------ writing

  /** A JSON object that keeps its keys in insertion order, so output is stable and readable. */
  public static final class Obj {
    private final Map<String, Object> map = new LinkedHashMap<>();

    public Obj put(String key, Object value) { map.put(key, value); return this; }
    /** Adds the key only when the value is not null, to keep responses free of nulls. */
    public Obj putIf(String key, Object value) { if (value != null) map.put(key, value); return this; }
    public Map<String, Object> map() { return map; }
    @Override public String toString() { return write(map); }
  }

  public static Obj obj() { return new Obj(); }

  /** Serialises maps, lists, Obj, numbers, booleans, null and anything else via toString(). */
  public static String write(Object value) {
    StringBuilder out = new StringBuilder();
    writeValue(value, out);
    return out.toString();
  }

  private static void writeValue(Object v, StringBuilder out) {
    if (v == null)                     { out.append("null"); return; }
    if (v instanceof Obj o)            { writeValue(o.map(), out); return; }
    if (v instanceof Raw r)            { out.append(r.json()); return; }
    if (v instanceof Boolean b)        { out.append(b.booleanValue()); return; }
    if (v instanceof BigDecimal d)     { out.append(d.toPlainString()); return; }
    if (v instanceof Double || v instanceof Float) {
      double d = ((Number) v).doubleValue();
      if (Double.isNaN(d) || Double.isInfinite(d)) { out.append("null"); return; }
      out.append(new BigDecimal(Double.toString(d)).stripTrailingZeros().toPlainString());
      return;
    }
    if (v instanceof Number n)         { out.append(n.toString()); return; }
    if (v instanceof Map<?, ?> m) {
      out.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> e : m.entrySet()) {
        if (!first) out.append(',');
        first = false;
        writeString(String.valueOf(e.getKey()), out);
        out.append(':');
        writeValue(e.getValue(), out);
      }
      out.append('}');
      return;
    }
    if (v instanceof Iterable<?> it) {
      out.append('[');
      boolean first = true;
      for (Object e : it) { if (!first) out.append(','); first = false; writeValue(e, out); }
      out.append(']');
      return;
    }
    if (v instanceof Object[] arr) {
      out.append('[');
      for (int i = 0; i < arr.length; i++) { if (i > 0) out.append(','); writeValue(arr[i], out); }
      out.append(']');
      return;
    }
    writeString(v.toString(), out);
  }

  /** Wraps text that is already JSON (a jsonb column, for example) so it is not quoted again. */
  public record Raw(String json) {}

  private static void writeString(String s, StringBuilder out) {
    out.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"'  -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        default -> {
          if (c < 0x20 || c == 0x7f) out.append(String.format("\\u%04x", (int) c));
          else out.append(c);
        }
      }
    }
    out.append('"');
  }

  // ------------------------------------------------------------------ reading

  /** Parses a JSON document. Returns Map, List, String, BigDecimal, Boolean or null. */
  public static Object parse(String text) {
    Parser p = new Parser(text);
    p.skipWhitespace();
    Object v = p.value();
    p.skipWhitespace();
    if (!p.atEnd()) throw new JsonException("Unexpected text after the JSON value at position " + p.pos);
    return v;
  }

  /** Parses a document that must be a JSON object; an empty body counts as an empty object. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> parseObject(String text) {
    if (text == null || text.isBlank()) return new LinkedHashMap<>();
    Object v = parse(text);
    if (!(v instanceof Map)) throw new JsonException("Expected a JSON object.");
    return (Map<String, Object>) v;
  }

  public static final class JsonException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public JsonException(String message) { super(message); }
  }

  private static final class Parser {
    private final String s;
    private int pos;
    Parser(String s) { this.s = s; }

    boolean atEnd() { return pos >= s.length(); }

    void skipWhitespace() {
      while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
    }

    Object value() {
      if (atEnd()) throw new JsonException("The JSON document ended early.");
      char c = s.charAt(pos);
      return switch (c) {
        case '{' -> object();
        case '[' -> array();
        case '"' -> string();
        case 't' -> literal("true", Boolean.TRUE);
        case 'f' -> literal("false", Boolean.FALSE);
        case 'n' -> literal("null", null);
        default  -> number();
      };
    }

    Map<String, Object> object() {
      Map<String, Object> m = new LinkedHashMap<>();
      expect('{');
      skipWhitespace();
      if (peek() == '}') { pos++; return m; }
      while (true) {
        skipWhitespace();
        String key = string();
        skipWhitespace();
        expect(':');
        skipWhitespace();
        if (m.containsKey(key)) {
          throw new JsonException("Duplicate object key '" + key + "' at position " + (pos - 1));
        }
        m.put(key, value());
        skipWhitespace();
        char c = next();
        if (c == '}') return m;
        if (c != ',') throw new JsonException("Expected ',' or '}' at position " + (pos - 1));
      }
    }

    List<Object> array() {
      List<Object> list = new ArrayList<>();
      expect('[');
      skipWhitespace();
      if (peek() == ']') { pos++; return list; }
      while (true) {
        skipWhitespace();
        list.add(value());
        skipWhitespace();
        char c = next();
        if (c == ']') return list;
        if (c != ',') throw new JsonException("Expected ',' or ']' at position " + (pos - 1));
      }
    }

    String string() {
      expect('"');
      StringBuilder sb = new StringBuilder();
      while (true) {
        char c = next();
        if (c == '"') return sb.toString();
        if (c != '\\') { sb.append(c); continue; }
        char esc = next();
        switch (esc) {
          case '"'  -> sb.append('"');
          case '\\' -> sb.append('\\');
          case '/'  -> sb.append('/');
          case 'b'  -> sb.append('\b');
          case 'f'  -> sb.append('\f');
          case 'n'  -> sb.append('\n');
          case 'r'  -> sb.append('\r');
          case 't'  -> sb.append('\t');
          case 'u'  -> {
            if (pos + 4 > s.length()) throw new JsonException("Truncated \\u escape.");
            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
            pos += 4;
          }
          default -> throw new JsonException("Unknown escape \\" + esc + " at position " + (pos - 1));
        }
      }
    }

    Object literal(String word, Object result) {
      if (!s.startsWith(word, pos)) throw new JsonException("Expected " + word + " at position " + pos);
      pos += word.length();
      return result;
    }

    BigDecimal number() {
      int start = pos;
      if (peek() == '-' || peek() == '+') pos++;
      while (!atEnd() && (Character.isDigit(s.charAt(pos)) || "+-.eE".indexOf(s.charAt(pos)) >= 0)) pos++;
      String raw = s.substring(start, pos);
      try {
        return new BigDecimal(raw);
      } catch (NumberFormatException e) {
        throw new JsonException("'" + raw + "' is not a number (position " + start + ").");
      }
    }

    char peek() { return atEnd() ? '\0' : s.charAt(pos); }
    char next() {
      if (atEnd()) throw new JsonException("The JSON document ended early.");
      return s.charAt(pos++);
    }
    void expect(char c) {
      if (next() != c) throw new JsonException("Expected '" + c + "' at position " + (pos - 1));
    }
  }

  // ------------------------------------------------------- JDBC result sets

  /**
   * Turns a whole result set into a list of ordered maps, so any query can be returned
   * without writing a mapper for it. Column labels become the JSON keys.
   */
  public static List<Map<String, Object>> rows(ResultSet rs) throws SQLException {
    List<Map<String, Object>> out = new ArrayList<>();
    ResultSetMetaData md = rs.getMetaData();
    int n = md.getColumnCount();
    while (rs.next()) {
      Map<String, Object> row = new LinkedHashMap<>();
      for (int i = 1; i <= n; i++) row.put(md.getColumnLabel(i), cell(rs, i, md.getColumnTypeName(i)));
      out.add(row);
    }
    return out;
  }

  /** Reads the first row, or null when the query found nothing. */
  public static Map<String, Object> row(ResultSet rs) throws SQLException {
    List<Map<String, Object>> rows = rows(rs);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private static Object cell(ResultSet rs, int i, String typeName) throws SQLException {
    Object v = rs.getObject(i);
    if (v == null) return null;
    if ("json".equalsIgnoreCase(typeName) || "jsonb".equalsIgnoreCase(typeName)) {
      return new Raw(rs.getString(i));
    }
    if (v instanceof Timestamp ts) {
      // TIMESTAMPTZ is stored in UTC; hand the client an ISO-8601 instant and let it localise.
      return ts.toInstant().toString();
    }
    if (v instanceof java.sql.Date d) return d.toLocalDate().toString();
    if (v instanceof LocalDate d) return d.toString();
    if (v instanceof java.sql.Time t) return t.toLocalTime().toString();
    if (v instanceof java.time.OffsetDateTime odt) return odt.toInstant().toString();
    if (v instanceof BigDecimal || v instanceof Number || v instanceof Boolean) return v;
    if (v instanceof java.sql.Array arr) {
      Object[] items = (Object[]) arr.getArray();
      List<Object> list = new ArrayList<>(items.length);
      for (Object o : items) list.add(o == null ? null : o.toString());
      return list;
    }
    return v.toString();
  }

  /** Formats an instant string for the terminal, in the machine's own time zone. */
  public static String localTime(String isoInstant) {
    if (isoInstant == null) return "";
    try {
      return java.time.Instant.parse(isoInstant)
          .atZone(ZoneId.systemDefault())
          .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    } catch (RuntimeException e) {
      return isoInstant;
    }
  }
}
