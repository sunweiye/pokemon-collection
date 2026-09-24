---
name: add-cached-pokeapi-lookup
description: Use when adding or modifying any code path that fetches Pokémon data — implementing the L1 (Caffeine) / L2 (Postgres jsonb) / L3 (PokéAPI) lookup chain, its error handling, or its tests. Also use when asked to add a new way to look up a Pokémon (e.g. by another identifier) so the same three-level pattern is reused instead of a new ad-hoc fetch path.
---

# Add a cached PokéAPI lookup

This skill encodes the exact-match, three-level cache lookup pattern used
for all Pokémon data access. Read `CLAUDE.md` §6 first for the full
rationale — this skill is the "how", that section is the "why".

## When this applies

- Adding a new query path for Pokémon data (e.g. "look up by id", "look up
  by name", or a future third identifier).
- Fixing or extending the existing `PokemonLookupService`.
- Writing tests for any of the above.

## When this does NOT apply

Collection reads are **not** lookups: the list (pagination, sort, name
filter) and the `POST /api/collection` response come from the
`CollectionEntryView` `jsonb` projection (CLAUDE.md §7.4) — never through
L1, never with a TTL check, never calling PokéAPI (§6.1), never via
`PokemonDetailDto`. The add flow calls `getById` only to guarantee the
row exists; its return value is not used to build the response.

## The pattern, step by step

1. **Never call PokéAPI directly from a Controller or from ad-hoc code.**
   All access goes through `PokemonLookupService`.

2. **L1 (Caffeine) is managed manually, not via `@Cacheable`.** A lookup by
   id must also populate the by-name cache (and vice versa), which a single
   declarative annotation cannot express. Use `CacheManager.getCache(name)`
   directly:
   ```java
   Cache.ValueWrapper hit = l1Cache.get(l1Key);
   if (hit != null) return (PokemonDetailDto) hit.get();
   ```
   Two named caches must exist in `CacheConfig`: `pokemonById` (key =
   `Integer` pokemonId) and `pokemonByName` (key = lowercased `String`
   name). Both `maximumSize=1000`, `expireAfterWrite=1h` unless the user
   says otherwise.

3. **L2 (Postgres) query must use an indexed column, never the `jsonb`
   column, and only active rows.** Use `findByPokemonIdAndDeletedFalse` /
   `findByPokemonNameAndDeletedFalse` on `PokemonCacheRepository` (`jsonb`
   operators never appear in `WHERE`/`ORDER BY`; the only `jsonb`
   extraction in SQL is the collection projection's `SELECT` list). Names
   are normalized (`trim().toLowerCase(Locale.ROOT)`) in Java first and
   then matched by **exact equality** — never `IgnoreCase`, `Containing`,
   `LIKE`, or `ILIKE`. A `deleted` row is an L2 miss. Do not write a query that filters or searches
   inside the `data` jsonb column — that's the whole point of having
   `pokemon_id`/`pokemon_name` as separate indexed columns.

4. **Freshness check happens in Java, not SQL.** After fetching the L2
   row, compare `cachedAt` to `Instant.now()` using a TTL duration read
   from `app.pokemon-cache.ttl-days` (default 7; bound in
   `application.yml` to env var `APP_POKEMON_CACHE_TTL_DAYS` from `.env`,
   CLAUDE.md §10). Inject it via `@ConfigurationProperties`/`@Value`,
   never `System.getenv()`. Write this as a small
   private method (`isFresh(Instant cachedAt)`) so it's easy to unit test
   with a fixed/mocked clock. Do not embed the TTL logic in a repository
   query.

5. **L3 (PokéAPI call) must classify failures, not just retry/rethrow.**
   Using `WebClient` with an explicit timeout (`app.pokeapi.timeout-seconds`,
   default 5) and base URL (`app.pokeapi.base-url`) — both read from
   Spring properties (fixed in `application.yml`, not `.env` variables),
   never Java constants, so tests can target MockWebServer:
   - PokéAPI `404` → throw `PokemonNotFoundException`
   - Network failure, timeout, or PokéAPI `5xx`/other `4xx` → throw
     `PokeApiUnavailableException`
   Never let a generic `RuntimeException`/`WebClientException` propagate
   unclassified to the controller — the whole point is that the frontend
   can tell "not found" from "service down" apart (CLAUDE.md §6 table).

6. **Maintain `deleted` on every L3 result.** Load the existing row with
   the unfiltered `findByPokemonId`/`findByPokemonName` so it is updated
   in place. PokéAPI `404` → if the row exists, set `deleted = true`,
   save, evict both L1 keys, then throw `PokemonNotFoundException`.
   Success → set `deleted = false` as part of the upsert (restores rows
   wrongly marked by an earlier bogus 404). Unavailable → touch nothing
   and let `PokeApiUnavailableException` propagate (`502`) — even when a
   stale row exists, it is **not** returned as a fallback (CLAUDE.md
   §6.2: lenient reads, strict writes).

7. **On a successful L3 fetch, upsert then write through both L1 keys.**
   Map the DTO from the **saved row** with the same `toDto(PokemonCache)`
   used for L2 hits — never map the raw L3 JSON separately — so a search
   response has one shape whichever level answered.
   ```java
   PokemonCache saved = upsert(existingOrNew, freshJson); // sets data + cachedAt
   PokemonDetailDto dto = toDto(saved);
   l1Cache("pokemonById").put(dto.pokemonId(), dto);
   l1Cache("pokemonByName").put(dto.name(), dto);
   return dto;
   ```

8. **Log every level's outcome (CLAUDE.md §11).** SLF4J only, one line
   per event in the `event=NAME key=value` format:
   ```java
   log.debug("event=CACHE_L1_HIT by=name key={}", name);   // DEBUG only
   log.info("event=CACHE_L1_MISS by=name key={}", name);
   log.info("event=CACHE_L2_MISS by=name key={} reason=stale cachedAt={}", name, row.getCachedAt());
   log.info("event=POKEAPI_SUCCESS identifier={} status=200 durationMs={}", identifier, ms);
   log.warn("event=POKEAPI_FAILED identifier={} status={} durationMs={}", identifier, status, ms, ex);
   ```
   - `PokemonLookupService`: `CACHE_L1_HIT` (**DEBUG** — written only when
     DEBUG is enabled for this logger), `CACHE_L1_MISS` (INFO),
     `CACHE_L2_HIT`/`CACHE_L2_MISS` (`reason=absent|stale`),
     `POKEMON_CACHE_MARKED_DELETED` (WARN), `POKEMON_CACHE_RESTORED`.
   - `PokeApiClient`: exactly one result event per call —
     `POKEAPI_SUCCESS`, or `POKEAPI_FAILED` with `status=404` (INFO, no
     stack trace), an HTTP code like `500`/`503` (WARN), or
     `TIMEOUT`/`CONNECTION_ERROR` (WARN), always with `durationMs`.
   - Never log the PokéAPI JSON payload.

## Tests to write alongside any change here

Using JUnit 5 + Mockito (mock the repository, the `WebClient`/API client,
and the `CacheManager`/`Cache`), cover at minimum:

- L1 hit → repository and API client are never called.
- L1 miss, L2 hit and fresh → API client never called; both L1 keys get
  populated.
- L1 miss, L2 hit but stale (TTL boundary: test both "1 second before
  expiry" and "1 second after expiry" using a fixed `Instant`) → API
  client is called.
- L1 miss, L2 miss entirely → API client is called, result is persisted.
- API client throws not-found with no existing row →
  `PokemonNotFoundException` propagates, nothing is written to L1/L2.
- Stale existing row + API not-found → row saved with `deleted = true`,
  both L1 keys evicted, `PokemonNotFoundException` propagates.
- Previously `deleted` row + API success → row updated in place with
  `deleted = false` and fresh `cachedAt`.
- `getByName("  PIKACHU ")` looks up `"pikachu"` exactly; a partial name
  never resolves to a different Pokémon.
- API client throws unavailable (simulate via MockWebServer returning
  `500` or a connection that times out) → `PokeApiUnavailableException`
  propagates, nothing is written to L1/L2.

Logging (use Spring Boot's `OutputCaptureExtension`; the test
`src/test/resources/logback-test.xml` keeps root at INFO with a console
appender only):
- L1 hit at the default INFO level emits **no** `CACHE_L1_HIT` line (and
  no `CACHE_L2_*`/`POKEAPI_*` line); with the `PokemonLookupService`
  logger raised to DEBUG in the test, it emits `event=CACHE_L1_HIT`.
- L1 miss + stale L2 emits `CACHE_L1_MISS`, `CACHE_L2_MISS ... reason=stale`,
  then the PokéAPI result event.
- MockWebServer `404` → `event=POKEAPI_FAILED ... status=404`; `500` →
  `status=500`; delayed response past the timeout → `status=TIMEOUT`;
  `200` → `event=POKEAPI_SUCCESS`.

Use MockWebServer for the PokéAPI-facing tests (`PokeApiClient`) rather
than mocking `WebClient` itself — it exercises the real HTTP/timeout
handling instead of just the Java call shape.

## Common mistakes to avoid

- Adding `@Cacheable` "for simplicity" — breaks the dual-key write-through.
- Querying `data ->> 'name'` or similar jsonb path expressions instead of
  the dedicated `pokemon_name` column.
- Putting the TTL comparison in a repository method name/query.
- Returning a generic `500` for PokéAPI failures instead of `502`.
- Using `IgnoreCase`/`Containing`/`LIKE` for the name lookup instead of
  normalizing in Java and matching exactly.
- Forgetting `deleted = false` on a successful refresh, or querying L2
  without the `deleted` filter.
- Pushing collection-list/filter results into L1 (evicts hot search keys).
- Adding a stale-if-error fallback (returning the stale L2 row when
  PokéAPI times out or returns 5xx). It was deliberately rejected: new
  collection entries must only be created on data that is within the TTL
  or just confirmed by PokéAPI (CLAUDE.md §6.2). Ask the user before
  changing this.
- Logging `CACHE_L1_HIT` at INFO (floods the file on every repeat search).
- Logging a PokéAPI failure twice (client + service/handler at WARN), or
  logging a `404` as WARN/ERROR with a stack trace.
- Free-text log messages instead of the `event=NAME key=value` format.
- Adding per-trainer data (e.g. `inCollection`) to the cached
  `PokemonDetailDto` or to `pokemon_cache`. L1/L2 are shared across all
  trainers; per-trainer flags are added in the controller after the
  lookup (CLAUDE.md §7.1).
- Treating "not found" and "upstream down" as the same error code anywhere
  in the stack (backend exception, HTTP status, or frontend message).
