# Phase 3 — PokéAPI integration

Depends on: Phase 1 (data layer). Use skill `add-cached-pokeapi-lookup`
for this entire phase — it contains the detailed pattern; this file is
just the phase checklist. Use skill `add-rest-endpoint` for the
controller.

Read `CLAUDE.md` §6 before starting.

## Goal

`GET /api/pokemon/search?query=` and `GET /api/pokemon/{id}` working end
to end through the full L1 → L2 → L3 chain, with correct error
classification, and a test suite that proves each branch of the chain
independently (not just a happy-path smoke test).

## Steps

1. `CacheConfig`: define the `pokemonById`/`pokemonByName` Caffeine caches
   per the skill.
2. `PokeApiClient`: `WebClient`-based, base URL and timeout read from
   the Spring properties `app.pokeapi.base-url` /
   `app.pokeapi.timeout-seconds` (fixed values in `application.yml`, not
   env vars — CLAUDE.md §10) rather than Java constants, so tests can
   point it at MockWebServer. Throws
   `PokemonNotFoundException` on `404`, `PokeApiUnavailableException` on
   anything else that isn't a clean success (network error, timeout,
   other 4xx, 5xx).
3. `PokemonLookupService`: implement `getById`/`getByName` following the
   skill's lookup-order pattern exactly, including the manual L1
   management and the Java-side TTL comparison.
4. `PokemonController`: `GET /api/pokemon/search?query=` (trim; `\d+`
   → `getById`, anything else → `getByName` with exact, normalized name
   matching; blank → `400`) and `GET /api/pokemon/{id}`.
   Map to `PokemonDetailDto`. (Phase 4 wraps this in
   `PokemonDetailResponseDto` with the per-trainer `inCollection` flag —
   `PokemonLookupService` and the L1/L2 caches keep returning the
   trainer-agnostic `PokemonDetailDto`.)
5. Extend `GlobalExceptionHandler` with `PokemonNotFoundException` → `404`
   and `PokeApiUnavailableException` → `502`, matching CLAUDE.md §6's
   table exactly (including the response body's `error` code strings:
   `NOT_FOUND`, `UPSTREAM_UNAVAILABLE`), plus `REQUEST_FAILED` logging
   (CLAUDE.md §11.3).
6. Logging per the skill's step 8 / CLAUDE.md §11.3: L1/L2 hit/miss
   events in `PokemonLookupService`, one `POKEAPI_SUCCESS`/
   `POKEAPI_FAILED` (with `status` and `durationMs`) per PokéAPI call in
   `PokeApiClient`, and the `deleted` mark/restore events.

## Tests to write

Follow the skill's test list precisely — it's written for this exact
phase. In particular, don't skip the TTL-boundary test or the
MockWebServer-based failure-classification tests; a lookup service that
only has a happy-path test has not actually verified the cache chain does
anything. Include the skill's logging assertions.

## Definition of done

- [ ] Searching a valid, previously-unseen Pokémon name/id returns `200`
      with correct data, and a second identical search does not hit the
      real PokéAPI (verify via test double / call-count assertion, not
      just "it's fast").
- [ ] Searching a nonsense name/id returns `404` with `error: "NOT_FOUND"`.
- [ ] Simulated PokéAPI outage (MockWebServer 500 or timeout) returns
      `502` with `error: "UPSTREAM_UNAVAILABLE"` — and does **not** get
      reported as `404`.
- [ ] The TTL is read from `app.pokemon-cache.ttl-days` (bound to
      `APP_POKEMON_CACHE_TTL_DAYS` in `application.yml`), not hard-coded;
      changing it in `.env` and restarting changes the freshness window.
- [ ] A stale L2 row (cached_at older than the configured TTL) triggers a
      fresh L3 fetch and updates `cached_at`.
- [ ] Looking up the same Pokémon first by id then by name (or vice
      versa) only hits PokéAPI once.
- [ ] `$LOG_PATH/pokemon-collection.log` shows the L1 miss / L2 hit-miss
      trail for a search (`CACHE_L1_HIT` only when DEBUG is enabled), and PokéAPI results with their status (`200`, `404`,
      `500`, `TIMEOUT` …) — asserted in tests, not just inspected.
- [ ] Name search is exact: `PIKACHU` finds `pikachu`, `pika` returns
      `404` (no `LIKE`/`IgnoreCase` query anywhere in the lookup path).
- [ ] A stale row whose refresh returns PokéAPI `404` is marked
      `deleted = true` and evicted from L1; a later successful refresh
      sets it back to `deleted = false`.
