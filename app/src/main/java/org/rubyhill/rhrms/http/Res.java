package org.rubyhill.rhrms.http;

import org.rubyhill.rhrms.json.Json;

import java.util.List;
import java.util.Map;

/** What a handler sends back: an HTTP status and a JSON body. */
public record Res(int status, Object body) {

  public static Res ok(Object body) { return new Res(200, body); }

  public static Res ok(Json.Obj body) { return new Res(200, body); }

  public static Res created(Object body) { return new Res(201, body); }

  /** A short confirmation for actions that have nothing to return. */
  public static Res done(String message) {
    return ok(Json.obj().put("ok", true).put("message", message));
  }

  /** A list result, always wrapped so a count can travel with it. */
  public static Res list(String name, List<?> items) {
    return ok(Json.obj().put("count", items.size()).put(name, items));
  }

  public static Res list(String name, List<?> items, Map<String, Object> extra) {
    Json.Obj o = Json.obj().put("count", items.size()).put(name, items);
    extra.forEach(o::put);
    return ok(o);
  }
}
