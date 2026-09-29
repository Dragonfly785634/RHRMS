package org.rubyhill.rhrms.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The command loop: one line in, one screen out.
 *
 * A command line rather than a menu tree, because the front desk does the same six things all
 * day and typing "find bella" beats walking down three menus. Everything is discoverable with
 * "help", and "help" only lists what this person is actually allowed to do.
 *
 * Nothing in here decides anything. Every command turns into one or more API calls; a refusal
 * comes back as a sentence and is printed. There is no rule, no permission comparison and no
 * password check on this side.
 */
final class Shell {
  /** What happened when the loop ended. */
  enum Outcome { QUIT, LOGGED_OUT, SESSION_ENDED }

  private record Command(String name, String usage, String what, String permission,
                         Action action) {}

  private interface Action {
    void run(List<String> args) throws Exception;
  }

  private final Ui ui;
  private final Map<String, Command> commands = new LinkedHashMap<>();
  private final List<String> order = new ArrayList<>();

  /** When this session's command loop started, so "clear" can say how long you have been on. */
  private final java.time.Instant startedAt = java.time.Instant.now();

  /**
   * The last few commands typed, newest first. This is what "clear" reprints: wiping the screen
   * should not wipe your place, and on a shared front-desk terminal the question "what was I in
   * the middle of?" comes up constantly.
   */
  private final java.util.ArrayDeque<String> recent = new java.util.ArrayDeque<>();
  private static final int RECENT_KEPT = 6;

  Shell(Ui ui) {
    this.ui = ui;
    register();
  }

  private void remember(String name, String line) {
    // "clear" and "help" are housekeeping, not work. Listing them under "what you were doing"
    // would push the actual answer off the bottom, which is the one thing this list is for.
    if (name.equals("clear") || name.equals("help")) return;
    recent.addFirst(line);
    while (recent.size() > RECENT_KEPT) recent.removeLast();
  }

  // ------------------------------------------------------------------ the loop

  Outcome run() {
    Term.clear();
    banner();
    DashboardScreen.lowFoodAlert(ui);            // section 8: the red warning box on login
    Term.out();
    Term.info(Term.dim("Type 'help' for the list of commands, or 'quit' to finish."));

    while (true) {
      String line;
      try {
        Term.out();
        line = ui.prompt.line(prompt());
      } catch (Prompt.Cancelled e) {
        continue;                                 // .q at the top level has nothing to leave
      } catch (Prompt.EndOfInput e) {
        Term.out();
        Term.info("Goodbye.");
        return Outcome.QUIT;
      }
      if (line == null) continue;

      List<String> parts = split(line);
      if (parts.isEmpty()) continue;
      String name = parts.get(0).toLowerCase(Locale.ROOT);
      List<String> args = parts.subList(1, parts.size());

      if (name.equals("cls")) name = "clear";       // what half the world types instead

      if (name.equals("quit") || name.equals("exit") || name.equals("bye")) {
        logout();
        return Outcome.QUIT;
      }
      if (name.equals("logout")) {
        logout();
        return Outcome.LOGGED_OUT;
      }

      Command command = commands.get(name);
      if (command == null) {
        Term.out();
        Term.problem("There is no '" + name + "' command.");
        List<String> near = similar(name);
        if (!near.isEmpty()) Term.info("Did you mean: " + String.join(", ", near) + "?");
        Term.info("Type 'help' for the list.");
        continue;
      }

      remember(name, line.trim());
      try {
        command.action().run(args);
      } catch (ApiClient.Failure e) {
        ui.showFailure(e);
        if (e.needsLogin()) {
          Term.out();
          Term.info("Log in again to carry on.");
          return Outcome.SESSION_ENDED;
        }
      } catch (Prompt.Cancelled e) {
        Term.out();
        Term.info("Left the form. Nothing was saved.");
      } catch (Prompt.EndOfInput e) {
        Term.out();
        Term.info("Goodbye.");
        return Outcome.QUIT;
      } catch (Throwable e) {
        // A bug on this side. Say so plainly rather than showing a stack trace to a volunteer.
        // An ordinary bug leaves the session usable, so the loop carries on; a broken classpath
        // or an exhausted heap does not, and Crash.report says which of the two it was.
        if (Crash.report(e, "running '" + name + "'")) {
          return Outcome.QUIT;
        }
      }
    }
  }

  private void logout() {
    try {
      ui.api.post("/api/auth/logout", Map.of());
    } catch (ApiClient.Failure ignored) {
      // Already gone, or the server has stopped. Either way this session is over.
    }
    ui.api.clearToken();
    Term.out();
    Term.info("Logged out.");
  }

  private void banner() {
    var session = ui.session();
    Term.out();
    Term.out(Term.bold("  RHRMS") + Term.dim("  |  ") + session.displayName()
        + Term.dim("  |  ") + session.role()
        + Term.dim("  |  ") + ui.api.baseUrl());
    Term.rule();
  }

  /**
   * "clear": wipe the screen, keep your bearings.
   *
   * A front desk terminal fills up fast - one kennel board and one audit history and the top of
   * the screen is gone. But clearing it on a shared machine should never leave somebody staring
   * at a blank window wondering who is logged in. So this reprints the header, says who you are
   * and how long you have been on, and lists the last few things you did.
   *
   * Scroll-back is deliberately left alone: this scrolls the screen, it does not destroy what
   * came before, so anything you cleared too early can still be scrolled up to.
   */
  private void clearScreen() {
    Term.clear();
    banner();

    var session = ui.session();
    long minutes = java.time.Duration.between(startedAt, java.time.Instant.now()).toMinutes();
    String on = minutes < 1 ? "just now"
        : minutes == 1 ? "1 minute ago"
        : minutes < 60 ? minutes + " minutes ago"
        : (minutes / 60) + "h " + (minutes % 60) + "m ago";

    Term.out();
    Ui.field("Logged in as", session.displayName() + Term.dim("  (" + session.username() + ")"));
    Ui.field("Your role", session.role() + Term.dim("  -- 'help' lists what that lets you do"));
    Ui.field("Session started", on + Term.dim("  -- locks after " + session.idleMinutes()
        + " idle minutes"));
    Ui.field("Server", ui.api.baseUrl());

    if (recent.isEmpty()) {
      Term.out();
      Term.info(Term.dim("You have not run anything yet this session."));
    } else {
      Term.heading("What you were doing");
      int n = 0;
      for (String line : recent) {
        n++;
        Term.out("    " + Term.dim(n == 1 ? "last  " : "      ") + line);
      }
      Term.out();
      Term.info(Term.dim("Scroll up if you need the output again; clearing does not erase it."));
    }

    Term.out();
    Term.info(Term.dim("Type 'help' for the list of commands, or 'quit' to finish."));
  }

  private String prompt() {
    return Term.bold("rhrms> ");
  }

  /** Splits a command line, honouring double quotes so a name with a space can be typed. */
  static List<String> split(String line) {
    List<String> parts = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inQuotes = false;
    for (char c : line.trim().toCharArray()) {
      if (c == '"') {
        inQuotes = !inQuotes;
      } else if (Character.isWhitespace(c) && !inQuotes) {
        if (current.length() > 0) { parts.add(current.toString()); current.setLength(0); }
      } else {
        current.append(c);
      }
    }
    if (current.length() > 0) parts.add(current.toString());
    return parts;
  }

  /** Commands within one letter of a typo, so "aminal" suggests "animal". */
  private List<String> similar(String typed) {
    List<String> near = new ArrayList<>();
    for (String name : order) {
      if (name.startsWith(typed.substring(0, Math.min(2, typed.length())))
          || distance(name, typed) <= 2) {
        near.add(name);
      }
      if (near.size() == 4) break;
    }
    return near;
  }

  private static int distance(String a, String b) {
    int[] previous = new int[b.length() + 1];
    int[] current = new int[b.length() + 1];
    for (int j = 0; j <= b.length(); j++) previous[j] = j;
    for (int i = 1; i <= a.length(); i++) {
      current[0] = i;
      for (int j = 1; j <= b.length(); j++) {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
      }
      int[] swap = previous; previous = current; current = swap;
    }
    return previous[b.length()];
  }

  // ------------------------------------------------------------- the commands

  private void add(String name, String usage, String what, String permission, Action action) {
    commands.put(name, new Command(name, usage, what, permission, action));
    order.add(name);
  }

  /*
   * The command list, kept deliberately short.
   *
   * Build spec rule 8: "The client values simplicity above everything. When in doubt, build fewer
   * screens and fewer fields." An earlier version had forty-eight commands, of which a director
   * could see fifty-four lines of help. The Phase 1 scope is ten features. That gap was not
   * capability, it was noise: several commands were second routes to somewhere you could already
   * get, and the rest were beyond the scope entirely.
   *
   * What was cut from HERE still exists in the API and is still tested - see docs/DECISIONS.md
   * (D-30). Nothing was deleted from the server; a later phase can put a screen back on top of an
   * endpoint that already works.
   */
  private void register() {
    // ---- getting your bearings: no permission needed, everybody sees these ----
    add("help", "help", "this list of commands", null, args -> help());
    add("clear", "clear", "clear the screen, keeping the header and where you were", null,
        args -> clearScreen());
    add("dash", "dash", "the front desk: kennels, who is waiting, what is running out", null,
        args -> DashboardScreen.show(ui));

    // ---- one search box (LK-1), across animals, people and past decisions ----
    add("find", "find <name, code, phone or town>", "find an animal, a person or a past decision",
        null, args -> SearchScreen.find(ui, join(args)));
    add("animal", "animal <id or code>", "one animal's whole record", null,
        args -> AnimalScreens.show(ui, join(args)));
    add("person", "person <id>", "one person, and everything that ever happened", null,
        args -> AdoptionScreens.showPerson(ui, join(args)));

    // ---- 1. intake  2. status lifecycle ----
    add("intake", "intake", "record an animal arriving (form RH-1)", "INTAKE",
        args -> AnimalScreens.intake(ui));
    add("status", "status <animal>", "change an animal's status", "ANIMAL_STATUS",
        args -> AnimalScreens.status(ui, join(args)));

    // ---- 4. application  5. decision  6. placement ----
    add("apply", "apply", "enter an adoption application from the paper form", "APPLICATION_CREATE",
        args -> AdoptionScreens.apply(ui));
    add("visit", "visit <application>", "record a home visit", "HOME_VISIT_CREATE",
        args -> AdoptionScreens.homeVisit(ui, join(args)));
    add("decide", "decide <application>", "approve or deny an application, with the reason",
        "DECIDE", args -> AdoptionScreens.decide(ui, join(args)));
    add("place", "place <application>", "record an adoption", "PLACEMENT",
        args -> AdoptionScreens.place(ui, join(args)));
    add("return", "return <animal>", "record an adopted animal coming back", "RETURN",
        args -> AdoptionScreens.returnAnimal(ui, join(args)));

    // ---- 7. supply items  8. stock movements  9. dietary link  10. inventory ----
    add("food", "food", "what is on hand against what the animals in care need", null,
        args -> FoodScreens.forecast(ui));
    add("supplies", "supplies", "the supply items: type, unit and quantity; add one",
        "FOOD_PRODUCT_EDIT", args -> FoodScreens.supplies(ui));
    add("move", "move", "receive or dispense supplies", "FOOD_OPEN_RECEIVE",
        args -> FoodScreens.movement(ui));
    add("diet", "diet <animal>", "what an animal eats, and how much", "DIET_EDIT",
        args -> FoodScreens.diet(ui, join(args)));

    // ---- the rest ----
    add("passwd", "passwd", "change your own password", null,
        args -> AdminScreens.changePassword(ui));
    add("admin", "admin", "user accounts, settings, who is logged in, the login trail",
        "USER_ADMIN", args -> AdminScreens.admin(ui));
  }

  private static String join(List<String> args) {
    return args.isEmpty() ? null : String.join(" ", args);
  }

  // ------------------------------------------------------------------- screens

  private void help() {
    var session = ui.session();
    Term.heading("Commands");
    Term.paragraph("Your account is " + session.role() + ", so this is everything you can do. "
        + "Anything you cannot do is left out rather than shown and refused.");
    Term.out();

    Term.Table table = new Term.Table("command", "what it does");
    for (String name : order) {
      Command command = commands.get(name);
      if (command.permission() != null && !session.may(command.permission())) continue;
      table.row(command.usage(), command.what());
    }
    table.row("logout", "log out, so somebody else can log in");
    table.row("quit", "log out and close this terminal");
    table.print();

    Term.out();
    Term.info(Term.dim("At any question, press Enter to leave it blank."));
    Term.info(Term.dim("Inside a form, " + Term.bold(".q") + Term.dim(" leaves it without saving; "
        + Term.bold(".help") + Term.dim(" lists the dot commands."))));
    Term.info(Term.dim("A few actions ask for your password again before they happen."));

    // Deliberately no list of what you cannot do. Telling a volunteer the names of eleven
    // commands they will be refused is noise dressed up as helpfulness.
  }

}
