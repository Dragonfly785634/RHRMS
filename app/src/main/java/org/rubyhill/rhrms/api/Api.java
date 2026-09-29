package org.rubyhill.rhrms.api;

import org.rubyhill.rhrms.http.Router;

/** Builds the whole route table. One line per area, so the shape of the API is visible here. */
public final class Api {
  private Api() {}

  public static Router build(Ctx ctx) {
    Router r = new Router(ctx);
    AuthApi.register(r, ctx);        // login, first run, passwords, health
    AdminApi.register(r, ctx);       // users, settings, sessions, login trail
    AnimalApi.register(r, ctx);      // intake, animals, kennels, restrictions, bonds, vet visits
    PersonApi.register(r, ctx);      // applicants and their history
    AdoptionApi.register(r, ctx);    // applications, home visits, decisions, placements, returns
    FoodApi.register(r, ctx);        // products, diets, stock, orders, forecast, donations
    ReportApi.register(r, ctx);      // dashboard, decision lookup, audit history
    return r;
  }
}
