# Pokémon Collection — Root Agent Instructions

You are implementing a fullstack coding challenge. This file is the single
source of truth for architecture decisions. Do not deviate from it without
asking the user first — every decision here was already debated and chosen
deliberately; do not "improve" it by substituting a different pattern you
consider more idiomatic (e.g. do not switch Session auth to JWT, do not
switch Thymeleaf back to pure static hosting, do not add Redis).

All code, comments, commit messages, and identifiers are in **English**.
Talk to the user in whatever language they use with you.

## 1. Scope

**In scope**
- Trainer login / logout (session-based)
- Exact-match Pokémon search by id or name (proxied + cached from PokéAPI)
- Add a searched Pokémon to the logged-in trainer's collection; the
  search result carries an `inCollection` flag so the UI can disable the
  add action for Pokémon already collected
- View only the logged-in trainer's own collection — **server-side
  paginated**, with a name filter and sorting (see §7)
- Remove a Pokémon from the collection

**Explicitly out of scope — do not build these even if it seems natural to add them**
- Self-service registration / password reset
- Fuzzy search / autocomplete **for the PokéAPI search** (PokéAPI itself
  has no fuzzy match). The collection's name filter (§7) is a different
  thing: a case-insensitive substring match over the trainer's own,
  already-stored rows — it never calls PokéAPI and is not a suggestion
  dropdown.
- i18n of actual UI copy (the `lang` attribute / meta plumbing is in scope,
  translated strings are not)
- Collection limits, sharing, social features
- Microservices, message queues, Redis, or any infra beyond what's listed below

If a task seems to require something outside this list, stop and ask
rather than expanding scope.

## 2. Architecture at a glance

Single Spring Boot process, single Postgres database. No microservices.

- Backend: Java 21, Spring Boot 3.x, Spring Data JPA + Hibernate (the
  *only* data access method — never hand-write JDBC/DAO code), Flyway,
  Spring Cache + Caffeine, Spring WebClient, Thymeleaf (page shell only),
  `spring-boot-starter-security` (used narrowly — see §4).
- Frontend: React 18 + TypeScript, Vite in **multi-page (MPA) mode** producing
  two independent bundles: `login` and `app`. The current `app` bundle has no
  client-side routes; collection search, filtering, sorting, and pagination
  are component state.
- Database: PostgreSQL, with a `jsonb` column for the PokéAPI cache payload.
- Same-origin deployment: in production the Spring Boot app serves both the
  REST API (`/api/**`) and the page shells — no CORS configuration is
  needed or wanted.
- **Single entry point: `http://localhost:8080`**, in every mode. In
  development (`docker-compose.dev.yml`, `dev` profile) Spring Boot still
  renders the page shells, but their `<script>` tags point at the Vite dev
  server on port 5173, which serves modules and the hot-reload socket
  only. 5173 is **not** an entry point: there are no HTML files in
  `frontend/`, no Vite proxy, and nothing to open there. Vite's
  `server.cors` allows only `http://localhost:8080`, `server.origin` is
  `http://localhost:5173`, and `strictPort` keeps it on that port. The
  base `docker-compose.yml` has no frontend service at all (the backend
  image contains the built bundles).
- **Ports by mode**: `docker-compose.yml` is the production-like setup
  and publishes **only 8080**; Postgres has no host port (the backend
  reaches it over the compose network). Every development-only port lives
  in `docker-compose.dev.yml`: `65432→5432` for host tools (psql, IDE)
  and `5173` for the Vite module server. Put any new dev-only port there
  too.
- Configuration: every environment-dependent value (DB credentials, L2
  cache TTL) is externalized via a root-level `.env`
  file — see §10.
- Logging: SLF4J API in code, Spring Boot's default Logback as the
  backend, console + rolling `.log` file, with a fixed event vocabulary for cache
  hits/misses and PokéAPI results — see §11.

## 3. Data model (authoritative — do not rename columns/tables)

```sql
CREATE TABLE trainer (
    id             BIGSERIAL PRIMARY KEY,
    username       VARCHAR(50) NOT NULL UNIQUE,
    password_hash  VARCHAR(100) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE pokemon_cache (
    cache_id      BIGSERIAL PRIMARY KEY,
    pokemon_id    INTEGER NOT NULL,
    pokemon_name  VARCHAR(100) NOT NULL,
    data          JSONB NOT NULL,
    cached_at     TIMESTAMPTZ NOT NULL,
    deleted       BOOLEAN NOT NULL DEFAULT FALSE,   -- added in V3
    CONSTRAINT uq_pokemon_cache_pokemon_id UNIQUE (pokemon_id),
    CONSTRAINT uq_pokemon_cache_pokemon_name UNIQUE (pokemon_name)
);

CREATE TABLE pokemon_collection_entry (
    id                BIGSERIAL PRIMARY KEY,
    trainer_id        BIGINT NOT NULL REFERENCES trainer(id),
    pokemon_cache_id  BIGINT NOT NULL REFERENCES pokemon_cache(cache_id),
    caught_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (trainer_id, pokemon_cache_id)
);

-- V3: partial lookup indexes over non-deleted cache rows + collection sort index
CREATE INDEX idx_pokemon_cache_active_pokemon_id   ON pokemon_cache (pokemon_id)   WHERE deleted = FALSE;
CREATE INDEX idx_pokemon_cache_active_pokemon_name ON pokemon_cache (pokemon_name) WHERE deleted = FALSE;
CREATE INDEX idx_pokemon_collection_entry_trainer_caught_at
    ON pokemon_collection_entry (trainer_id, caught_at DESC);
```

Current migrations (never edit an applied one — add `V4__...` next):
`V1__create_trainer_table.sql` (all three tables),
`V2__seed_trainers.sql`, `V3__add_deleted_to_pokemon_cache.sql`
(`deleted` column + the indexes above).

Rules:
- `cache_id` is a surrogate PK, used **only** for internal FK references.
  Never expose it in an API response — expose `pokemon_id` instead.
- `pokemon_name` carries a `UNIQUE` constraint specifically so lookups by
  name use an index and never touch the `jsonb` column's internals.
- **`jsonb` operators are allowed only in a `SELECT` list, never to find
  or order rows.** `WHERE`, `JOIN`, and `ORDER BY` always use the plain
  columns (`pokemon_id`, `pokemon_name`, `deleted`, `caught_at` …). The
  one place that reads *into* `data` is the collection read model's
  projection (§7.4), which extracts just the fields it displays.
  Names are stored lowercased; lookups are **exact equality** on the
  normalized value (see §6) — never `LIKE`/`ILIKE`/`IgnoreCase`, which
  would bypass the index.
- `pokemon_cache.deleted` marks a row whose PokéAPI refresh returned
  `404` (lifecycle in §6). All L2 lookups go through the partial
  `WHERE deleted = FALSE` indexes (`...AndDeletedFalse` repository
  methods). The cache lookup path fetches one row by `pokemon_id` or
  `pokemon_name` and evaluates TTL in Java; it does not need an index for
  listing by `cached_at`.
- Why the partial indexes exist next to the unique ones: their predicate
  matches the L2 query exactly, so the planner picks them and needs no
  extra `deleted` filter step. At this size (at most ~1025 rows, writes
  are rare) the read gain over "unique index + filter" is below a
  millisecond and the write cost is negligible — they document the
  access pattern and would only pay off with many soft-deleted rows. The
  `UNIQUE` constraints must stay as they are: uniqueness has to cover
  deleted rows too, because a refresh updates the same row in place.
- `idx_pokemon_collection_entry_trainer_caught_at` backs the per-trainer
  collection list sorted by `caught_at` (the default sort, §7.2).
- PostgreSQL creates an index automatically only for `PRIMARY KEY` and
  `UNIQUE` constraints — **not for foreign keys** (unlike MySQL/InnoDB).
  `trainer_id` is the leading column of two indexes; `pokemon_cache_id`
  leads none, which is fine because no query filters the collection
  table by it and cache rows are never physically deleted (soft delete).
  Add an index on it if either of those changes.
- Schema is owned by Flyway migrations under
  `backend/src/main/resources/db/migration/`. Hibernate is configured with
  `spring.jpa.hibernate.ddl-auto=validate` — it must never create or alter
  tables. If an entity needs a new column, write a new Flyway migration
  first, then update the entity to match.
- No entity changes without a corresponding migration in the same commit.

## 4. Authentication — Session-based, NOT JWT

This is a deliberate, non-negotiable choice (single-instance deployment,
no horizontal scaling need, JWT would add complexity with no payoff here).

- Session storage: Spring Boot's default in-memory `HttpSession` (Tomcat).
  No Redis, no `spring-session-data-*`.
- Business code never touches `HttpServletRequest`/`HttpSession` directly.
  It goes through an `AuthSessionService` interface:
  ```java
  public interface AuthSessionService {
      void login(HttpServletRequest request, Long trainerId);
      Optional<Long> getCurrentTrainerId(HttpServletRequest request);
      void logout(HttpServletRequest request);
  }
  ```
  The only implementation is `HttpSessionAuthService`. On login, invalidate
  any pre-existing session before creating a new one (session fixation
  protection).
- `AuthInterceptor` (a `HandlerInterceptor`) protects `/api/collection/**`,
  `/api/auth/me`, and `/api/auth/logout`. Everything else under `/api/**`
  is public.
- `spring-boot-starter-security` is a dependency **only** for:
  1. `PasswordEncoder` (BCrypt) for the seeded trainer passwords.
  2. CSRF protection (`HttpSessionCsrfTokenRepository`).
  Do **not** enable the full Security filter chain / `AuthenticationManager`
  — it would conflict with the hand-rolled session logic above.
- Login endpoint returns **JSON**, never an HTTP redirect (3xx). `200` with
  trainer info on success, `401` with an error body on failure. The
  frontend does an explicit `window.location.href = '/'` on success. Do
  not "simplify" this into a server-side redirect — see the architecture
  doc for why that breaks with `axios`/`fetch`.
- No self-registration endpoint. Trainer accounts are seeded via a Flyway
  migration (`V2__seed_trainers.sql`) with BCrypt-hashed passwords. The
  plaintext test credentials must be documented in the README.
- CSRF token is delivered to the browser via a `<meta name="_csrf">` tag
  rendered by Thymeleaf (see §5) — not via a JS-readable cookie read
  directly. The frontend reads the meta tag once at startup and sets it as
  a default Axios header. `HttpSessionCsrfTokenRepository` stores the token
  in the `HttpSession`, binding it to the session. Only a page-shell request
  that renders the meta tag creates the anonymous session; API, asset, and
  other requests without a cookie do not create one just to prepare CSRF.
  Login and logout invalidate the identity session, which naturally
  invalidates the old CSRF token.
- The page shell's meta tags are the **only** source of the CSRF token.
  There is no fallback that fetches or refreshes a token (no loading
  `/login` from JavaScript, no `invalidateCsrfToken`): a write request on
  a page without a token fails before it is sent, and a `403
  CSRF_FORBIDDEN` is answered by asking the user to reload the page.
- CSRF is not brute-force protection. Do not conflate the two. Login
  rate-limiting is a nice-to-have listed in the task backlog (§7 of the
  task list), not a blocker for the core deliverable.

## 5. Frontend delivery — Thymeleaf shell + two independent React SPAs

- Two Vite entry points: `src/login/main.tsx` and `src/app/main.tsx`,
  built with `build.manifest: true` so Vite emits `manifest.json` mapping
  each entry to its hashed output filename.
- Spring Boot resolves `manifest.json` at startup (`ViteManifestService`)
  and a `PageController` renders `templates/login.html` /
  `templates/index.html` via Thymeleaf, passing in: the CSRF token, the
  resolved `lang`/locale, and the correct hashed JS/CSS paths. It maps
  only `/` and `/login` — unknown URLs get the 404 page (§5.1).
- **Strict division of responsibility — exactly two kinds of template:**

  | Kind | Templates | May contain | Must not contain |
  |---|---|---|---|
  | **Page shell** | `templates/login.html`, `templates/index.html` (via `layout/base.html`) | meta tags (CSRF, `lang`, language), favicon link, hashed script/link tags, the empty `#root` mount point | any visible content, business data, conditionals on business state |
  | **Static error page** | `error/404.html` only | fixed, hard-coded copy (heading, message, link to `/`), favicon link, `lang`/language meta, static CSS from `/assets/` or inline | business data, React bundles, CSRF token, login-state checks, any `th:if` other than locale |

  In both kinds, `th:*` expressions may only read the locale, the CSRF
  token (page shells only), and asset paths. Everything the user
  interacts with — auth-state decisions, what to show, rendering actual
  content — happens in React. Server-side routing decisions are limited
  to two things in `PageController`: the page-route whitelist (§5.1) and
  one entry guard (an already authenticated `GET /login` redirects to
  `/`). A new template that fits neither kind needs the user's approval.
- Login page (`/login`): minimal, no router. On `POST /api/auth/login`
  returning `200`, do `window.location.href = '/'` (a real navigation, so
  the browser loads the `app` bundle fresh). On `401`, show the error
  inline, no navigation.
- App shell (`/`): on mount, calls `GET /api/auth/me`; a non-200 response
  redirects to `/login`. This is defense-in-depth, not the security
  boundary — the real boundary is always `AuthInterceptor` on the backend.
- Locale: `CookieLocaleResolver` (cookie `APP_LOCALE`) with a fixed
  default of `Locale.ENGLISH`, `LocaleChangeInterceptor` on `?lang=`.
  Priority: `?lang=` (writes the cookie) > cookie > English. This only
  drives `<html lang>` and `<meta name="language">` — it does not mean
  translated UI copy needs to exist.
  **`Accept-Language` is deliberately not used.** `lang` must describe
  the language the content is actually in, and all UI copy is English.
  Deriving `lang="de"` from a German browser would mislabel English text
  (screen readers would read it with German pronunciation, search engines
  would misclassify the page). In Spring 6, `setDefaultLocale(...)`
  replaces the `Accept-Language` fallback, which is exactly the intended
  behaviour — do not remove it. Enable the `Accept-Language` fallback only
  when translated copy actually exists (that is a scope change; ask
  first).

### 5.1 Page routes, static assets, favicon, and 404 handling

**Decision: the backend decides 404 for pages, using a fixed whitelist.**
The frontend has **no client-side router** (no React Router): the app is
one page at `/`, and search/filter/sort/pagination live in component
state, not in the URL. So the complete set of page URLs is known on the
server — `/` and `/login` — and the simplest correct design is: the
server serves exactly those, and everything else is a real `404`. There
is **no SPA catch-all route** (an unknown URL never gets the app shell
with `200`), and no client-side "not found" screen.

Adding a client-side router, or any URL-addressable app state, is a scope
change: ask the user first, then add every new route to the
`PageController` whitelist in the same change.

| Request | Handled by | Response |
|---|---|---|
| `GET /`, `GET /login` | `PageController` (explicit whitelist) | Thymeleaf shell, `200` (`/login` redirects to `/` when already logged in, §5) |
| `/api/**` with a matching controller | controllers | JSON per §7 |
| `/api/**` with **no** matching handler | `NoHandlerFoundException` → exception handler | `404` + `application/json` `{"error":"NOT_FOUND","message":"…"}` (§7.3) — never HTML |
| `GET /assets/**` | Spring's static resource handler | the file (`200`/`304`); missing file → `404` with an **empty body** — never the HTML 404 page, never JSON |
| any other URL (incl. `/favicon.ico`, `/foo`, `/login/x`) | `NoHandlerFoundException` → exception handler | Thymeleaf `templates/error/404.html`, status **`404`**, `text/html` |

Rules:
- **All static files live under `/assets/`.** Vite's hashed build output
  already goes to `dist/assets/`; hand-placed, unhashed files (favicon,
  images) go in `frontend/public/assets/`, which Vite copies verbatim to
  `dist/assets/`. Nothing is served from the site root.
- **Static resource mapping is restricted to `/assets/**`**, so any other
  unmatched URL reaches the `DispatcherServlet` as "no handler":
  ```yaml
  spring:
    mvc:
      static-path-pattern: /assets/**
    web:
      resources:
        static-locations: classpath:/static/assets/
  ```
  The two go together: with `static-path-pattern: /assets/**`, the URL
  `/assets/app-x1y2.js` resolves to `<location>/app-x1y2.js`, so the
  location must be the `static/assets/` folder the build copies into.
  Spring MVC 6.1+ throws `NoHandlerFoundException` by default for
  unmatched requests once the default `/**` resource mapping is gone.
- **Every page references the favicon explicitly**:
  `<link rel="icon" type="image/x-icon" href="/assets/favicon.ico">` in
  the Thymeleaf layout (`th:href`, which points at the Vite dev server in
  the `dev` profile) and in the 404 template — the only HTML the app has.
  `type` must match the file (`image/x-icon` for `.ico`, `image/svg+xml`
  only for `.svg`). A request to `/favicon.ico` is just an unknown URL →
  404 page.
- **Page routes are an explicit whitelist in `PageController`**:
  `@GetMapping("/")` and `@GetMapping("/login")`, no regex/wildcard
  patterns.
- **One component owns "no such URL":** `NotFoundHandler`, a
  `@ControllerAdvice` with `@Order(Ordered.HIGHEST_PRECEDENCE)`, handles
  **only** `NoHandlerFoundException` and `NoResourceFoundException` and
  dispatches **by request path**:

  | Path | Response |
  |---|---|
  | `/api/**` | `ResponseEntity<ErrorResponse>` — `404`, `application/json`, `{"error":"NOT_FOUND","message":"The requested resource was not found."}` |
  | `/assets/**` | `ResponseEntity.notFound().build()` — `404`, empty body |
  | anything else | `ModelAndView("error/404")` with `setStatus(HttpStatus.NOT_FOUND)` |

  - Dispatch is by path, not by `Accept` header: the path already says
    what the client is (API client, browser loading a file, or a person
    navigating), and it stays deterministic for curl, fetch, and
    crawlers alike.
  - `GlobalExceptionHandler` (a `@RestControllerAdvice`, JSON only) must
    **not** handle these two exceptions — that keeps it purely JSON and
    avoids two handlers competing for the same exception. Business "not
    found" (`PokemonNotFoundException`, `CollectionEntryNotFoundException`)
    stays in `GlobalExceptionHandler` as JSON `404`.
  - Log once as `event=REQUEST_FAILED status=404 error=NOT_FOUND path=…`
    at INFO, no stack trace (§11).
  - Don't set `spring.mvc.throw-exception-if-no-handler-found` (deprecated;
    it is the default behaviour since Spring MVC 6.1) and don't disable
    `spring.web.resources.add-mappings` — the `/assets/**` resource
    handler is needed.
- **Missing asset = bare `404`.** `/assets/**` is served only by Spring's
  `ResourceHttpRequestHandler` (conditional GET / `304` included). A
  missing file surfaces as `NoResourceFoundException` and gets an empty
  `404` — returning HTML or JSON there would hand a `<script>`/`<link>`/
  `<img>` the wrong content type. No controller may map anything under
  `/assets/**`.
- **404 responses and asset requests never create an `HttpSession`.** With
  `HttpSessionCsrfTokenRepository` (§4), generating a CSRF token creates a
  session. The token is generated lazily, only when a page shell renders
  its `_csrf` meta tag (`GET /`, `GET /login`); the 404 template doesn't
  reference it, and there is **no** filter that eagerly loads the token
  on every request — don't add one.
- **The 404 page is a static error page** (the second template kind in
  §5) — the one template that renders visible content, allowed because it
  carries no business data:
  a heading, a short message, a plain `<a href="/">` link home, the
  favicon link, `<html lang>` / `<meta name="language">` from the locale,
  and styling via a static stylesheet under `/assets/` (or inline CSS).
  It loads **no React bundle**, reads no CSRF token, checks no login
  state, and must work with JavaScript disabled. Name it
  `templates/error/404.html`: Spring Boot's error view resolver uses the
  same file for 404s that reach `/error` without passing through the
  exception handler, so both paths render one page.
- Never return a "soft 404" (the 404 page or the app shell with `200`).

## 6. PokéAPI integration — three-level cache, exact match only

Search is **exact match only** (by id or name) — PokéAPI itself has no
fuzzy search, so don't build autocomplete or partial matching.

`PokemonController` decides the lookup type from the query: after
`trim()`, a value matching `\d+` goes to `getById`, anything else to
`getByName`; a blank query is `400 BAD_REQUEST`. `getByName` normalizes
with `trim().toLowerCase(Locale.ROOT)` in Java and then matches
**exactly** — L1 key equality, L2 `findByPokemonNameAndDeletedFalse`
(`pokemon_name = ?`), L3 `/pokemon/{name}`. A partial name such as
`pika` must return `404`, never `pikachu`.

Lookup order for both `getById` and `getByName`:

1. **L1 — Caffeine**, two separate named caches: `pokemonById` (key =
   pokemonId) and `pokemonByName` (key = lowercased name).
   `maximumSize=1000`, `expireAfterWrite=1h`. Managed manually via
   `CacheManager` — **do not** use `@Cacheable`, because a single lookup
   must write through to *both* keys, which the declarative annotation
   cannot express.
2. **L2 — Postgres `pokemon_cache`**, queried by the unique
   `pokemon_id`/`pokemon_name` columns (never filter on the `jsonb`),
   **active rows only** (`findByPokemonIdAndDeletedFalse` /
   `findByPokemonNameAndDeletedFalse`). A `deleted` row counts as an L2
   miss.
   Freshness is **not** a SQL `WHERE` clause — fetch the row, then compare
   `cached_at` against a TTL (`app.pokemon-cache.ttl-days`, default 7,
   bound to env var `APP_POKEMON_CACHE_TTL_DAYS` — see §10) in
   Java. This is deliberate: it keeps the TTL boundary unit-testable with a
   mocked clock.
3. **L3 — PokéAPI** via `WebClient`, explicit timeout (~5s). Base URL and
   timeout are plain Spring properties `app.pokeapi.base-url` /
   `app.pokeapi.timeout-seconds` (fixed values in `application.yml`, not
   env vars — see §10), so tests can point at MockWebServer. On success,
   upsert the full raw JSON into `pokemon_cache` (updating `cached_at`),
   then write through to both L1 caches.

**`deleted` lifecycle** (L3 refresh of a missing/stale/deleted row; the
existing row is looked up without the `deleted` filter so it is updated
in place, not duplicated):
- PokéAPI `404` → if a row exists, set `deleted = true`, save, and evict
  its id/name keys from both L1 caches; then throw
  `PokemonNotFoundException` (`404`). No row exists → nothing written.
- PokéAPI success → upsert and **always set `deleted = false`**. This is
  what restores a row wrongly marked deleted by an earlier bogus `404`
  (e.g. a PokéAPI outage returning 404).
- PokéAPI unavailable (timeout/5xx) → `502`; the row and its `deleted`
  flag are left untouched. The stale row is **not** returned as a
  fallback — see §6.2.

### 6.1 Where each cache level is used

L1 (Caffeine) is reserved for the **search/lookup path** (`getById` /
`getByName`) — deterministic single-Pokémon keys that are hit
repeatedly. The **collection list** (every page, every sort, and the
name filter):
- reads straight from Postgres via the collection repository join on
  `pokemon_cache` — **never through L1**, and never writes into L1.
  Paged/filtered results are long-tail data; letting them into L1 would
  evict the hot search keys and collapse the hit rate;
- **never calls PokéAPI and never checks the TTL** — rows are shown as
  stored even if `cached_at` is past the TTL. Refreshing only happens
  when that Pokémon is searched again.

### 6.2 Staleness principle — lenient reads, strict writes

Pokémon data is reference data that is effectively static (when it does
change, it is mostly presentation such as sprites). That drives one rule
with two sides:

- **Reads of existing collection entries tolerate stale data.** The
  collection list shows rows as stored, ignores the TTL, and never calls
  PokéAPI (§6.1). Stale data there is harmless, and it keeps the list
  independent of PokéAPI — the list works during a PokéAPI outage.
- **Creating a new collection entry requires valid data.** A new entry is
  only created on a cache row that is within the TTL or was just
  confirmed by PokéAPI. If the row is stale and PokéAPI is unavailable,
  the system cannot confirm the Pokémon still exists upstream (it may
  have been removed — the case the `deleted` flag handles), so search
  and add fail explicitly with `502` + "retry later" instead of building
  a new relation on unverified data.

Consequences and decisions:
- **No stale-if-error on the lookup path.** Returning the stale row when
  PokéAPI times out or returns 5xx was considered and deliberately
  rejected: it would let a trainer add a Pokémon that may no longer
  exist. Do not "improve" `PokemonLookupService` into a stale fallback
  without asking the user.
- Showing the stale search result but disabling "Add" was also
  considered and rejected for now: it adds a UI state and copy for
  little benefit within the current scope.
- **Refresh is demand-driven.** A stale row is refreshed the next time
  someone searches that Pokémon (a failed attempt leaves it stale, so the
  following search tries again). Rarely used Pokémon therefore refresh
  exactly when a user needs them — no scheduled refresh job.
- Retries are user-initiated only (the frontend queries use
  `retry: false`), so an outage doesn't turn into an automatic retry storm
  against a recovering PokéAPI.

Error handling — **do not collapse these into one generic error**:

| Situation | Exception | HTTP status | User-facing meaning |
|---|---|---|---|
| PokéAPI returns 404 | `PokemonNotFoundException` | `404` | "not found, check spelling" |
| PokéAPI unreachable / timeout / 5xx | `PokeApiUnavailableException` | `502` | "service temporarily unavailable, retry" |

A `GlobalExceptionHandler` (`@RestControllerAdvice`) maps exceptions to
responses centrally — no try/catch in controllers for these cases.

**L1/L2 hold trainer-agnostic data only.** `PokemonLookupService` returns
and caches `PokemonDetailDto` (`pokemonId`, `name`, `spriteUrl`, `types`).
The per-trainer `inCollection` flag is **never** stored in Caffeine or in
`pokemon_cache` — it is computed per request in `PokemonController` after
the lookup (see §7). Caching it would leak one trainer's collection state
to every other trainer hitting the same cache key.

## 7. API surface (do not add/rename without checking with the user)

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/api/auth/login` | no | `{username,password}` → `200 {id,username}` / `401` |
| POST | `/api/auth/logout` | yes | `204`, invalidates session |
| GET | `/api/auth/me` | yes | `200 {id,username}` / `401` |
| GET | `/api/pokemon/search?query=` | no | exact id or name → `200 PokemonDetailResponseDto` / `404` / `502`; blank query → `400` |
| GET | `/api/pokemon/{id}` | no | same response shape and error semantics |
| GET | `/api/collection?page=&size=&query=&sort=&direction=` | yes | only the current trainer's rows, paginated → `200 CollectionPageDto`; invalid params → `400` |
| POST | `/api/collection` | yes | `{pokemonId}` → `201` / `409` on duplicate |
| DELETE | `/api/collection/{entryId}` | yes | `204`; non-owned entry → `404`, never `403` |

Any `/api/**` path not in this table → `404` JSON `NOT_FOUND` (§5.1),
never the HTML 404 page.

Every collection-related query/mutation filters by the trainer id obtained
from `AuthSessionService.getCurrentTrainerId()` — never trust a
trainer/user id passed from the client.

### 7.1 `inCollection` on Pokémon responses

```json
// PokemonDetailResponseDto
{ "pokemonId": 25, "name": "pikachu", "spriteUrl": "https://...",
  "types": ["electric"], "inCollection": true }
```

- Purpose: the frontend marks a search result that the trainer already
  owns and disables its "Add" button, instead of letting the user click
  and hit a `409`.
- Computed in `PokemonController` per request:
  `authSessionService.getCurrentTrainerId(request)` →
  `collectionService.contains(trainerId, pokemonId)`
  (`existsByTrainerIdAndPokemonCache_PokemonId`). Both search endpoints
  stay **public**: with no session, `inCollection` is simply `false`.
- The flag is a UX hint, not a guarantee. `POST /api/collection` still
  enforces uniqueness and returns `409` on a duplicate; the frontend must
  keep handling that `409` (and then mark the result as added).
- After a successful add/remove the frontend updates the cached search
  result's `inCollection` locally (React Query `setQueriesData`) so the
  button state is correct without re-running the search.

Add-button states on the search result (prevents double submits):

| State | Button |
|---|---|
| `inCollection: false`, idle | enabled, "Add" |
| request in flight | **disabled**, spinner + "Adding…" |
| `201` success | disabled, "Already added" (+ success message) |
| `409 DUPLICATE_COLLECTION_ENTRY` | disabled, "Already added" + duplicate message |
| any other error | re-enabled, error message per §7.3 |
| `inCollection: true` from search | disabled, "Already added"; no request is ever sent |

### 7.2 Collection pagination, filter, and sort

Returning the whole collection in one response doesn't scale (payload
size, backend query/mapping cost, browser rendering cost), so
`GET /api/collection` is paginated server-side.

| Param | Default | Allowed values | Meaning |
|---|---|---|---|
| `page` | `0` | integer ≥ 0 | **0-based** page index (the UI shows it 1-based) |
| `size` | `6` | `6`, `18`, `30` | page size — a fixed whitelist, so the client can't request unbounded pages |
| `query` | `""` | any string (trimmed) | case-insensitive substring match on `pokemon_name`; blank = no filter |
| `sort` | `caughtAt` | `caughtAt`, `pokemonId`, `name` | mapped via `CollectionSortOption` to the projection's columns `caught_at` / `pokemon_id` / `pokemon_name` (§7.4), with `id` as tie-breaker — never pass the raw string to `Sort.by` |
| `direction` | `desc` | `asc`, `desc` | sort direction |

Any other value → `IllegalArgumentException` → `400 {"error":"BAD_REQUEST"}`
via `GlobalExceptionHandler`.

```json
// CollectionPageDto
{ "content": [ { "entryId": 12, "pokemonId": 25, "name": "pikachu",
                 "spriteUrl": "https://...", "types": ["electric"],
                 "caughtAt": "2026-09-20T10:00:00Z" } ],
  "page": 0, "size": 6, "totalElements": 13, "totalPages": 3 }
```

Rules:
- Paging, filtering and sorting happen in the database via `Pageable` on
  the collection read-model query (§7.4). Never load all rows and
  slice/sort in Java or in the browser.
- The trainer filter is always part of the query — pagination never
  widens the ownership scope.
- Do not expose Spring's raw `Page` JSON; always map to `CollectionPageDto`.
- Default order is `caughtAt desc` (newest first), served by
  `idx_pokemon_collection_entry_trainer_caught_at`. The sort dropdown
  offers added time, Pokémon id, and name, each ascending/descending.
- The name filter is a case-insensitive substring match over the
  trainer's own rows only; like the rest of the list it skips L1, never
  calls PokéAPI, and ignores the TTL (§6.1).
- The frontend keeps page/size/query/sort in the React Query key, resets
  to page 1 when filter/sort/size change, and clamps the current page
  when `totalPages` shrinks (e.g. after removing the last item on the
  last page).

### 7.3 Frontend error messages

The frontend branches on the `error` code, then on transport state —
never on parsing `message`. Every failure shows a user-facing message;
none is swallowed.

| Condition | Message (meaning) | Notes |
|---|---|---|
| `NOT_FOUND` on search | not found, check spelling/number | `role="status"`, no retry |
| `UPSTREAM_UNAVAILABLE` | data service temporarily unavailable | `role="alert"` + **Retry** button (refetch) |
| `DUPLICATE_COLLECTION_ENTRY` | already in your collection | also marks the result as added |
| `BAD_REQUEST` | request invalid, check input | |
| `METHOD_NOT_ALLOWED` | HTTP method is not supported for the endpoint | |
| `UNSUPPORTED_MEDIA_TYPE` | request content type is not supported | |
| `NOT_ACCEPTABLE` | requested response format is not available | |
| `INTERNAL_ERROR` or any `5xx` | server could not complete the request | |
| no response (network down, CORS, timeout) | unable to reach the server | |
| other 4xx | generic "request could not be completed" | |
| non-Axios error thrown in the client | context-specific fallback ("search failed" / "unable to add" / "unable to remove" / "unable to load your collection" / "unable to log out") | |
| `CSRF_FORBIDDEN` (`403`, returned by Spring Security's `AccessDeniedHandler`) | login page: "security token expired, reload the page"; logout: "unable to log out" | no redirect |
| `401` from an authenticated call | — | global Axios interceptor redirects to `/login` (except the login call itself) |
| `GET /api/collection` fails with a response (any non-401 status) | the `collectionLoad` message ("unable to load your collection") | shown as a **toast**, see §7.5 — never the "collection is empty" state |
| `GET /api/collection` gets no response (network down, timeout) | the network message ("unable to reach the server") — more actionable than the generic load message | same toast and rollback behaviour as above |

Error code strings are stable API: `NOT_FOUND`, `UPSTREAM_UNAVAILABLE`,
`UNAUTHORIZED`, `CSRF_FORBIDDEN`, `DUPLICATE_COLLECTION_ENTRY`, `BAD_REQUEST`,
`METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE`, `NOT_ACCEPTABLE`,
`INTERNAL_ERROR`. Add a new one here before using it.

### 7.4 Collection read model — one `jsonb` projection for every collection read

Every endpoint that returns collection entries — `GET /api/collection`
**and** the `201` body of `POST /api/collection` — builds
`CollectionEntryDto` from **one** read model, `CollectionEntryView`,
produced by native queries in `PokemonCollectionRepository` that all
share the same `SELECT` list. PostgreSQL extracts only the displayed
fields from `data`; the full `jsonb` document never leaves the database
on these paths.

```sql
SELECT e.id                                    AS "entryId",
       e.pokemon_id                            AS "pokemonId",
       e.pokemon_name                          AS name,
       e.data -> 'sprites' ->> 'front_default' AS "spriteUrl",
       ARRAY(SELECT t.elem -> 'type' ->> 'name'
             FROM jsonb_array_elements(COALESCE(e.data -> 'types', '[]'::jsonb))
                  WITH ORDINALITY AS t(elem, ord)
             ORDER BY t.ord)                   AS types,
       e.caught_at                             AS "caughtAt"
FROM (
    SELECT ce.id, ce.trainer_id, ce.caught_at,
           pc.pokemon_id, pc.pokemon_name, pc.data, pc.deleted
    FROM pokemon_collection_entry ce
    JOIN pokemon_cache pc ON pc.cache_id = ce.pokemon_cache_id
) e
WHERE e.trainer_id = :trainerId
  AND e.deleted = FALSE
  AND (:nameQuery = '' OR e.pokemon_name LIKE '%' || :nameQuery || '%' ESCAPE '\')
```

The join sits in the subquery `e` so the `ORDER BY` that Spring Data
appends from the `Pageable` can use plain column names (`caught_at`,
`pokemon_id`, `pokemon_name`, `id`, prefixed with `e.`) without telling
the two tables apart. PostgreSQL flattens this simple subquery, so the
plan is the same as a direct join.

```java
// CollectionEntryDto — the only collection entry shape the API returns
public record CollectionEntryDto(Long entryId, Integer pokemonId, String name,
                                 String spriteUrl, List<String> types, Instant caughtAt) {}
```

- `spriteUrl` is `null` when PokéAPI has no sprite (`->>` yields SQL
  `NULL`); `types` keeps PokéAPI's slot order and is `[]` (never `null`)
  when the array is missing/empty. `types` maps from the Postgres
  `text[]` to `String[]` in the projection, then to `List<String>` in the
  DTO.
- Repository methods (native, `@Query(nativeQuery = true)`, interface
  projection `CollectionEntryView`):
  - `findEntryViews(trainerId, nameQuery, pageable)` — the paginated
    list. Name filter: `(:nameQuery = '' OR e.pokemon_name LIKE
    '%' || :nameQuery || '%' ESCAPE '\')`, where the service lowercases
    the input and escapes `\`, `%`, `_` first (native `LIKE` doesn't do
    that for you). Needs an explicit `countQuery` that joins/filters the
    same way but selects **no** `jsonb` expressions.
  - `findEntryView(entryId, trainerId)` — one entry, used to build the
    `POST` response.
  Sorting comes from the `Pageable`'s `Sort` on the SQL column names
  above; a `@DataJpaTest` must prove every sort option actually orders
  the rows.
- **`POST /api/collection` returns the re-read view**, not a DTO
  assembled from whatever the lookup returned. `CollectionService.add`
  first calls `PokemonLookupService.getById` **outside any transaction**,
  only to make sure the row exists and is fresh (that result may come
  from L1, L2, or PokéAPI). Keeping the external HTTP call out of the
  transaction avoids holding a DB connection for up to 5 s, and keeps a
  `deleted = true` written on a PokéAPI `404` from being rolled back with
  the business exception. The write then runs in a short transaction in
  a separate bean, `CollectionEntryWriteService.addAfterLookup` (separate
  because `@Transactional` is proxy-based — a call inside the same class
  would bypass it): resolve the `cache_id` with a scalar query (`select
  c.cacheId … where pokemonId = ? and deleted = false`), check for a
  duplicate (`409`), save the entry via
  `PokemonCacheRepository.getReferenceById(cacheId)` (no `SELECT`, no
  `jsonb` load) and flush — a violation of the collection's unique
  constraint `pokemon_collection_entry_trainer_id_pokemon_cache_id_key`
  (concurrent duplicate add) also becomes `409`, any other integrity
  error propagates — then return `findEntryView(newId, trainerId)`. So
  the response shape never depends on which cache level answered.
- Forbidden on collection paths: loading the `PokemonCache` entity or
  calling `getData()`, parsing `JsonNode` in Java, mapping from
  `PokemonDetailDto`, and `jsonb` expressions in `WHERE`/`ORDER BY`/the
  count query. `PokemonCollectionEntry.pokemonCache` stays
  `@ManyToOne(fetch = LAZY)` and `remove` never touches it, so the
  ownership check + delete never loads `data`.
- Collection reads intentionally include `deleted = FALSE` on the joined
  `pokemon_cache` row. If a PokéAPI refresh marks a cached row deleted, its
  collection entries are hidden from the list and its count/pagination until
  a later successful refresh restores the row. The relationship itself is
  never deleted, so once the row is restored the entry reappears in the list
  unchanged. Search cannot show a hidden entry as "already added": a deleted
  row is an L2 miss, so searching that Pokémon re-queries PokéAPI — still
  `404` returns `404`, success restores the row (and the entry). This is the
  chosen soft-delete visibility behavior.
- Search is the lookup path, not a collection read: its response still
  comes from `PokemonDetailDto`, built by the single
  `PokemonLookupService.toDto(PokemonCache)` for both L2 hits and fresh
  L3 results (L3 JSON is upserted first, then mapped from the saved row),
  so it too has one shape regardless of the level that answered.
- Frontend: `CollectionEntry.types: string[]`; every collection card and
  the search result render types with the same shared type-tag
  component.

### 7.5 Collection list loading overlay and load-error toast (frontend)

Every **user-initiated list update** — submitting or clearing the name
filter, changing the sort, going to another page, changing the page size
— and the list refresh after an add/remove shows a **loading overlay**
over the collection card. A failed `GET /api/collection` shows a
**toast**.

Loading overlay:
- Driven by the collection query's `isFetching`. It covers only the
  collection card (`absolute inset-0` inside the card, not the page),
  with a spinner and the text "Loading collection…" in a
  `role="status"` / `aria-live="polite"` element; the card carries
  `aria-busy="true"`.
- While it is shown, the card's controls (filter, sort, page size,
  pagination, remove buttons) are non-interactive: `inert` on the card
  content (set via a ref — React 18 has no `inert` prop) plus
  `pointer-events-none`. Header actions outside the card (logout, add)
  stay usable.
- **The current list stays visible underneath** — the overlay dims it, it
  does not replace it. Keep the last successfully loaded page in state
  (`lastCollectionData`) and render `collectionQuery.data ??
  lastCollectionData`, so the previous rows, count, and pagination stay
  on screen while a new page/sort/filter key loads **and** after a
  failure. (Not `placeholderData: keepPreviousData` — it only covers the
  pending state and drops the old rows once the request errors.) Don't
  hide the grid while fetching (that collapses the card and makes the
  overlay a thin strip). Only the very first load, with nothing to show
  yet, may render an empty card area under the overlay.
- No overlay for refetches the user didn't cause: set
  `refetchOnWindowFocus: false` on the collection query (the list only
  changes through this user's own actions).

Load-error toast:
- One shared `Toast` component (`src/components/ui/toast.tsx`):
  `role="alert"`, fixed at the top of the viewport **above the search
  dialog's overlay**, dismissible with a close button (`aria-label` from
  i18n). No auto-dismiss.
- Message (§7.3): the network message when there is no response
  (network down, timeout); the `collectionLoad` text for every other
  failure except `401` (which redirects — no toast, no control
  rollback).
- Fail fast: the collection query uses `retry: false` (the
  `QueryClient` default of 3 retries would keep the overlay spinning for
  several seconds before the toast appears). The user retries by acting
  again or via the retry action below.
- **After a failed list update, the user is never left with a blank or
  stuck card, and controls always match the rows shown:** record the
  parameters of every successful load (page — clamped to `totalPages` —
  size, filter, sort) and, on error, restore all list controls to them
  (the filter input is reset to the last applied term). The previous
  list is shown again, pagination included, and the toast explains that
  the change failed; the user retries by repeating the action. No inline
  error or Retry button is shown in that case (the toast is the single
  `role="alert"`). Only if nothing has ever loaded (the initial request
  failed) does the card show an inline error state with a **Retry**
  button (not the empty-collection message, not "0 Pokémon collected").
- The toast is cleared by the next user list action (page, size, filter,
  sort, Retry) or a successful add, and when closed.
- Remove failures keep their inline message in the card (§7.3); the toast
  is only for loading the list.

## 8. Testing expectations

Do not consider a phase "done" until its tests exist and pass. See
`tasks/` for phase-by-phase detail. Minimum bar:
- Backend: JUnit 5 + Mockito for service logic (especially the L1/L2/L3
  branch coverage and TTL boundary), `@DataJpaTest` for repository/jsonb
  behavior, MockWebServer for simulated PokéAPI failures (never hit the
  real PokeAPI in automated tests), `@WebMvcTest`/MockMvc for
  controllers, one `@SpringBootTest` + Testcontainers-Postgres happy-path
  integration test.
- Frontend: Vitest + React Testing Library, MSW for mocking API calls.

- Logging: the §11 events are asserted in tests (cache hit/miss path,
  PokéAPI success and each failure status), not just eyeballed.

## 9. How to work through this project

1. Read `tasks/00-scaffold.md` through `tasks/06-wrapup.md` in order —
   they are meant to be executed sequentially, each depending on the
   previous one's output.
2. Before starting a phase, re-read the relevant section(s) of this file.
3. Use the skills in `.claude/skills/` for the three recurring
   implementation patterns (cached PokéAPI lookups, REST endpoints, Flyway
   migrations) instead of re-deriving the pattern each time.
4. When a task file's instructions and this file conflict, this file wins
   — ask the user rather than guessing.
5. A design change updates `ARCHITECTURE.md` in the same commit as this
   file.
6. Do not invent additional scope. If something isn't covered here or in
   the task files, ask before building it.

## 10. Configuration — `.env` + `application.yml` placeholders

All environment-dependent configuration is externalized as environment
variables, managed centrally in a `.env` file at the repo root. Direct local
Spring Boot startup may use safe development fallbacks for database connection
settings, while Docker Compose still requires `.env` values. Production must
override the local defaults; no production password is hard-coded in Java code.

- `.env.example` is **committed**: the complete list of variables, each
  with an English comment. Required database values are intentionally blank;
  non-sensitive operational settings may document safe defaults.
- `.env` is **git-ignored**: the real local/deployment values, created
  with `cp .env.example .env`.

Authoritative variable list (`.env.example` must match it exactly):

| Env var | Requirement | Spring property |
|---|---|---|
| `POSTGRES_HOST` | `localhost` for direct local startup; required in Compose | host part of `spring.datasource.url` |
| `POSTGRES_PORT` | `5432` for direct local startup; required in Compose | port part of `spring.datasource.url` |
| `POSTGRES_DB` | `pokemon_collection` for direct local startup; required in Compose | db part of `spring.datasource.url` |
| `POSTGRES_USER` | `app` for direct local startup; required in Compose | `spring.datasource.username` |
| `POSTGRES_PASSWORD` | `app` for direct local startup; required in Compose | `spring.datasource.password` |
| `APP_POKEMON_CACHE_TTL_DAYS` | `7` | `app.pokemon-cache.ttl-days` (L2 freshness, days) |
| `LOG_PATH` | `logs` | `logging.file.name: ${LOG_PATH:logs}/pokemon-collection.log` and the rolling `file-name-pattern` — log directory, relative to the backend's working directory or absolute (§11) |
| `SESSION_COOKIE_SECURE` | `false` | `server.servlet.session.cookie.secure` — set to `true` when serving over HTTPS |
| `THYMELEAF_CACHE` | `true` in the main configuration; `false` in the dev profile | `spring.thymeleaf.cache` — cache templates in production, disable them for development |

The same `POSTGRES_*` values initialize the Postgres container and are
used by the backend to connect — one source of truth.

Binding rules:
- `application.yml` references database variables explicitly with local
  development fallbacks (`localhost`, `5432`, `pokemon_collection`, `app`,
  `app`) so direct startup works without `.env`. Docker Compose keeps explicit
  required-variable interpolation and therefore fails fast when `.env` values
  are missing.
- Non-sensitive operational settings may keep explicit safe fallbacks, for
  example `ttl-days: ${APP_POKEMON_CACHE_TTL_DAYS:7}` and
  `logging.file.name: ${LOG_PATH:logs}/pokemon-collection.log`. Do not rely
  on Spring's relaxed binding — the relaxed form of
  `app.pokemon-cache.ttl-days` is `APP_POKEMONCACHE_TTLDAYS`, which is
  unreadable and easy to get wrong.
- `application.yml` keeps
  `spring.config.import: optional:file:../.env[.properties]` for optional
  property loading. The supported application startup path is Docker Compose,
  which reads the root `.env` and injects the variables directly (using
  `${VAR:?message}` for required database interpolation). For a single-container
  production run: `docker run --env-file .env ...`.
- Java code reads configuration only through Spring properties
  (`@ConfigurationProperties` or constructor-injected `@Value`) — never
  `System.getenv()`. This keeps the TTL logic unit-testable with a plain
  value + mocked clock.
- Compose must fail fast with a clear error when its required database
  variables are missing. Direct Spring Boot startup uses the documented local
  fallback values; deployment environments must provide explicit overrides.
- `spring.jpa.hibernate.ddl-auto` is hard-coded to `validate` and must
  **not** be exposed as an environment variable — schema is Flyway-only.
- `app.pokeapi.base-url` (`https://pokeapi.co/api/v2`),
  `app.pokeapi.timeout-seconds` (`5`) and `server.port` (default `8080`)
  are ordinary Spring properties with fixed values in `application.yml`,
  **not** `.env` variables — they don't differ between environments.
  `app.pokeapi.*` exists as properties only so tests can override them.
- The frontend has no externalized config (same-origin, relative `/api`
  paths). Do not define `VITE_*` variables, and never let any `.env`
  value (especially DB credentials) reach the frontend bundle.
- Automated tests never depend on `.env`: Testcontainers supplies the
  datasource (`@ServiceConnection` / `@DynamicPropertySource`), and tests
  set `app.pokemon-cache.ttl-days` / `app.pokeapi.base-url` (pointing at
  MockWebServer) explicitly via test properties.
- `POSTGRES_USER`/`POSTGRES_PASSWORD` are the **database** credentials,
  not trainer login credentials. Trainer accounts come only from the
  Flyway seed migration (§4); the README must keep the two clearly
  separate.
- Adding a new variable means updating, in the same change:
  `.env.example`, `application.yml`, `docker-compose.yml` (if the
  container needs it), and the README's environment-variable section.
  Do not add variables beyond the table above without asking the user.

## 11. Logging — SLF4J + Spring Boot's default Logback, console + `.log` file

### 11.1 Setup

- **Code uses the SLF4J API only** — Spring Boot's standard logging
  facade:
  `private static final Logger log = LoggerFactory.getLogger(Foo.class);`
  with `{}` placeholders. Never `System.out`/`printStackTrace`, never
  string concatenation in log calls, never the Logback API
  (`ch.qos.logback.*`) in business code.
- **Logback is the logging backend** — Spring Boot's default via
  `spring-boot-starter-logging`, which every starter already pulls in.
  Do **not** add Log4j2 (`spring-boot-starter-log4j2`) or any other
  backend, and don't exclude `spring-boot-starter-logging`.
- **Configure through `application.yml` only** — no `logback-spring.xml`
  unless a requirement can't be expressed with Spring Boot's
  `logging.*` properties (ask first). Spring Boot's default console and
  file patterns are used as-is.
  ```yaml
  logging:
    file:
      name: ${LOG_PATH:logs}/pokemon-collection.log
    logback:
      rollingpolicy:
        file-name-pattern: ${LOG_PATH:logs}/pokemon-collection-%d{yyyy-MM-dd}.%i.log.gz
        max-file-size: 10MB
        max-history: 30
    level:
      root: INFO
      com.example.pokemoncollection: INFO
  ```
  Output goes to the console and to `$LOG_PATH/pokemon-collection.log`,
  rolled daily and at 10 MB (Logback's size-and-time policy), gzipped,
  kept 30 days. The directory comes from the `LOG_PATH` env var (§10,
  default `logs`, relative to the process working directory or
  absolute). Never hard-code the directory.
- Levels: root and `com.example.pokemoncollection` at `INFO`, so
  `DEBUG`-level events (currently only `CACHE_L1_HIT`) are off by
  default. Turn them on only when needed, e.g.
  `logging.level.com.example.pokemoncollection.pokemon.PokemonLookupService: DEBUG`
  in `application-dev.yml`, or
  `--logging.level.com.example.pokemoncollection.pokemon.PokemonLookupService=DEBUG`
  on the command line. No `.env` variable for the log level without
  asking (§10).
- `logs/` is git-ignored (the existing `*.log` rule doesn't cover the
  rotated `.log.gz` files).
- Tests: `backend/src/test/resources/logback-test.xml` with a console
  appender only and root at `INFO`. It matters for two reasons: without
  any config, plain JUnit/Mockito tests (no Spring context) run Logback's
  fallback config at **DEBUG**, which would make the "`CACHE_L1_HIT` is
  off by default" test meaningless; and Spring Boot picks
  `logback-test.xml` up as its config in `@SpringBootTest`/`@WebMvcTest`,
  so tests never write to `LOG_PATH`. Assert log output with Spring
  Boot's `OutputCaptureExtension` (or a Logback `ListAppender` attached
  to the class logger). The DEBUG-only `CACHE_L1_HIT` is tested both
  ways: absent at the default INFO level, present after raising the
  `PokemonLookupService` logger to DEBUG in the test (cast to
  `ch.qos.logback.classic.Logger` and `setLevel(Level.DEBUG)`, restored
  afterwards — Logback API is allowed in test code only).

### 11.2 Message format

One line per event, starting with a stable event name followed by
`key=value` pairs, so the file can be grepped/parsed:

```
event=CACHE_L1_MISS by=name key=pikachu
event=POKEAPI_FAILED identifier=pikachu status=503 durationMs=812
```

### 11.3 Required events

| Event | Where | Level | Keys |
|---|---|---|---|
| `CACHE_L1_HIT` | `PokemonLookupService` | **DEBUG** — only written when DEBUG is enabled for this logger (it is the most frequent event and would dominate the file) | `by` (`id`/`name`), `key` |
| `CACHE_L1_MISS` | `PokemonLookupService` | INFO | `by` (`id`/`name`), `key` |
| `CACHE_L2_HIT` | `PokemonLookupService` | INFO | `by`, `key`, `pokemonId` |
| `CACHE_L2_MISS` | `PokemonLookupService` | INFO | `by`, `key`, `reason` (`absent` = no active row, incl. `deleted`; `stale` = past TTL, plus `cachedAt`) |
| `POKEAPI_SUCCESS` | `PokeApiClient` | INFO | `identifier`, `status=200`, `durationMs` |
| `POKEAPI_FAILED` (not found) | `PokeApiClient` | INFO | `identifier`, `status=404`, `durationMs` — an expected outcome (typos), no stack trace |
| `POKEAPI_FAILED` (upstream error) | `PokeApiClient` | WARN | `identifier`, `status` = HTTP code (`500`, `503`, other 4xx …) or `TIMEOUT` / `CONNECTION_ERROR`, `durationMs`; exception attached |
| `POKEMON_CACHE_MARKED_DELETED` | `PokemonLookupService` | WARN | `pokemonId`, `name` |
| `POKEMON_CACHE_RESTORED` | `PokemonLookupService` | INFO | `pokemonId`, `name` (a previously `deleted` row refreshed successfully) |
| `POKEMON_CACHE_CONCURRENT_WRITE` | `PokemonLookupService` | INFO | `pokemonId`, `name` (another request won the cache upsert race) |
| `COLLECTION_LIST` | `CollectionService` | INFO | `trainerId`, `page`, `size`, `query`, `sort`, `direction`, `returned`, `totalElements` |
| `COLLECTION_ADD` / `COLLECTION_ADD_DUPLICATE` | `CollectionEntryWriteService` | INFO | `trainerId`, `pokemonId`, `entryId` (add only) |
| `COLLECTION_REMOVE` / `COLLECTION_REMOVE_NOT_FOUND` | `CollectionService` | INFO | `trainerId`, `entryId` |
| `AUTH_LOGIN_SUCCESS` / `AUTH_LOGIN_FAILED` / `AUTH_LOGOUT` | `AuthController` | INFO / WARN / INFO | `username` (login), `trainerId` |
| `REQUEST_FAILED` | `GlobalExceptionHandler`; `NotFoundHandler` for unknown URLs (404, INFO) | 4xx and `502 UPSTREAM_UNAVAILABLE` (already logged by `PokeApiClient`): INFO, no stack trace; `500 INTERNAL_ERROR`: ERROR with stack trace | `status`, `error` (code), `path` |

Rules:
- DEBUG events use `log.debug("event=... key={}", ...)` with `{}`
  placeholders, so nothing is formatted when DEBUG is off. Wrap in
  `if (log.isDebugEnabled())` only if an argument is expensive to compute.
- The collection list never emits `CACHE_*` or `POKEAPI_*` events — it
  doesn't use those paths (§6.1); a test asserts this.
- `PokeApiClient` logs each call **once** (the result event). Don't also
  log the same failure again in the service or the exception handler at
  WARN/ERROR.
- **Never log**: passwords or password hashes, session ids, cookies, CSRF
  tokens, DB credentials, or the full PokéAPI JSON payload.
- Adding a new event: add it to this table first, keep the
  `event=NAME key=value` shape.
