---
name: add-rest-endpoint
description: Use whenever adding, changing, or reviewing a Spring Boot REST controller endpoint in this project — auth-guarding, error-status conventions, DTO shape, and the matching MockMvc/WebMvcTest coverage. Ensures every new endpoint follows the same auth, error-mapping, and DTO conventions as the rest of the API instead of inventing a new pattern per-endpoint.
---

# Add a REST endpoint

Read `CLAUDE.md` §4 (auth), §6 (error handling), §7 (API surface) first.
This skill is the checklist for wiring a new or changed endpoint
consistently with the rest of the API.

## Before writing the endpoint

1. **Check §7 of CLAUDE.md.** If the endpoint isn't listed there and isn't
   a small, obviously-compatible variant of something listed (e.g. adding
   a query param), stop and ask the user before adding it — the API
   surface was deliberately kept small.
2. **Decide auth requirement.** Anything touching `/api/collection/**` or
   returning "who is logged in" info must be behind `AuthInterceptor`
   (already configured on `/api/collection/**` and `/api/auth/me` in
   `WebConfig`). If you add a new authenticated path prefix, add it to
   that interceptor registration — don't invent a second auth mechanism.

## Controller conventions

- Controllers only orchestrate: parse request → call a `Service` method →
  map result to a DTO → return `ResponseEntity`. No business logic, no
  direct repository access, no try/catch for the exceptions listed in
  CLAUDE.md §6 (those are handled centrally by `GlobalExceptionHandler`).
- Never resolve "who is the current trainer" from a request parameter or
  request body. Always obtain it via
  `authSessionService.getCurrentTrainerId(request)` and treat its absence
  as unauthenticated (`401`) — this is the entire mechanism that makes
  "trainers only see their own collection" true. A client-supplied
  trainerId/userId anywhere in a request body or query string for a
  collection endpoint is a bug.
- For an endpoint operating on a specific owned resource (e.g.
  `DELETE /api/collection/{entryId}`), if the resource exists but belongs
  to a different trainer, return `404`, not `403` — do not reveal that the
  resource exists to someone who doesn't own it.
- Use records for DTOs (`PokemonDetailResponseDto`, `CollectionEntryDto`,
  `CollectionPageDto`, `ErrorResponse` etc.), matching the shapes already
  in CLAUDE.md §7.
- **Read models come from SQL projections, not entities.** A response
  that needs a few fields of a `jsonb` document selects exactly those
  fields in the query (interface projection, e.g. `CollectionEntryView`,
  CLAUDE.md §7.4) instead of loading the entity and parsing JSON in
  Java. Every endpoint returning the same DTO (e.g. the list and the
  `POST` `201` body) reuses the same projection query, so the shape can't
  drift between endpoints or depend on which cache level served the data.
- **List endpoints are paginated.** Follow CLAUDE.md §7.2: `page`
  (0-based) / `size` from a fixed whitelist / optional filter / `sort`
  resolved through an enum (like `CollectionSortOption`) + `direction`,
  all validated in the controller (invalid → `IllegalArgumentException`
  → `400 BAD_REQUEST`). Page/filter/sort in the database via `Pageable`,
  never in memory; respond with a dedicated page DTO, never Spring's raw
  `Page`. Never pass a client-supplied string straight into `Sort.by`.
- **Per-trainer flags on shared data** (like `inCollection`, CLAUDE.md
  §7.1) are computed in the controller per request from the session and
  added to a response DTO — never baked into a cached/shared DTO. Do not leak entity classes (`Trainer`, `PokemonCache`,
  `PokemonCollectionEntry`) directly as response bodies — always map to a
  DTO, even if the mapping looks redundant, because entities carry
  internal fields (like `cache_id`, `password_hash`) that must never reach
  the client.

## Unknown API paths

Don't add catch-all mappings under `/api/**` (e.g. `@RequestMapping("/api/**")`)
to produce a 404 — an unmatched API path already raises
`NoHandlerFoundException`, which `NotFoundHandler` turns into `404` JSON
`NOT_FOUND` (CLAUDE.md §5.1). Don't handle `NoHandlerFoundException` /
`NoResourceFoundException` in `GlobalExceptionHandler` or in a
controller — `NotFoundHandler` is their only owner. A catch-all would also swallow
`405 METHOD_NOT_ALLOWED` for real endpoints called with the wrong method.
Controllers only return JSON; they never render the HTML 404 page.

## Logging

Follow CLAUDE.md §11: SLF4J only, `event=NAME key=value` lines. Business
events (e.g. `COLLECTION_ADD`, `AUTH_LOGIN_FAILED`) are logged in the
service/controller that owns the decision, with `trainerId` rather than
personal data. Failures are logged once, centrally, as `REQUEST_FAILED`
by `GlobalExceptionHandler` (4xx and `502` at INFO without stack trace,
`500` at ERROR with stack trace) — don't add try/catch just to log. Never
log passwords/hashes, session ids, cookies, or CSRF tokens. A new event
goes into the §11.3 table first.

## Error responses

Throw one of the project's typed exceptions (`PokemonNotFoundException`,
`PokeApiUnavailableException`, `UnauthorizedException`,
`DuplicateCollectionEntryException`, etc.) rather than building a
`ResponseEntity` with an error status inline in the controller. Add new
exception types to `GlobalExceptionHandler` if a new failure mode needs
its own status code — check CLAUDE.md's error table first so you're not
duplicating an existing one.

`ErrorResponse` body shape is always:
```json
{ "error": "SOME_CODE", "message": "human-readable text" }
```
The frontend branches on `error` (the code), not on parsing `message` —
keep `error` values stable and uppercase (e.g. `NOT_FOUND`,
`UPSTREAM_UNAVAILABLE`, `DUPLICATE_COLLECTION_ENTRY`, `BAD_REQUEST`,
`INTERNAL_ERROR`), and add any new one to the CLAUDE.md §7.3 table
together with its frontend message — every code the backend can return
must have a user-facing message.

## Tests to write alongside any new/changed endpoint

Using `@WebMvcTest` + MockMvc (mock the service layer):
- Happy path: correct status code and response body shape.
- Unauthenticated request to a protected path → `401`, service layer never
  invoked.
- Each documented error case (e.g. not-found → `404`, duplicate → `409`)
  maps to the right status and `error` code.
- For endpoints under `/api/collection/**`: a request scoped to trainer A
  cannot read/modify trainer B's data — assert this at the service-mock
  level (mock returns nothing for a mismatched id) resulting in `404`.

## Common mistakes to avoid

- Trusting a trainer/user id from the client instead of the session.
- Returning `403` for "exists but not yours" (should be `404`).
- Putting exception-to-status mapping logic inside the controller instead
  of `GlobalExceptionHandler`.
- Returning an entity instead of a DTO.
- Adding an endpoint not listed in CLAUDE.md §7 without checking first.
- Logging request bodies wholesale (the login body contains a password).
- Returning an unbounded list from a collection-like endpoint, or
  sorting/slicing it in Java instead of in SQL.
- Mapping an unvalidated `sort` query param directly onto an entity
  property path.
- Loading a whole `jsonb` payload (entity `getData()`) just to return two
  or three fields of it.
- Building a `POST` response from a different source than the matching
  `GET` (e.g. from a lookup DTO) — re-read through the shared projection.
