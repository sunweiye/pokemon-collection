# Phase 4 — Collection management

Depends on: Phase 2 (auth), Phase 3 (PokéAPI lookup). Use skill
`add-rest-endpoint`.

Read `CLAUDE.md` §7 before starting — the "trainer only sees their own
collection" requirement is entirely enforced here, so this is the most
security-sensitive phase in the backend.

## Goal

Authenticated CRUD (add/list/remove — no update) on a trainer's own
collection, with the ownership check implemented once, correctly, and
tested adversarially (not just "does it work for the owner").

## Steps

1. `CollectionService`:
   - `list(Long trainerId, String nameQuery, Pageable pageable)` →
     paginated, filtered, sorted in the database (CLAUDE.md §7.2) via
     `findEntryViews` — the `jsonb` projection from CLAUDE.md §7.4
     (lowercase + escape `\`/`%`/`_` in `nameQuery` before binding).
     Map each `CollectionEntryView` to `CollectionEntryDto` (now with
     `types`) and wrap in `CollectionPageDto` (`page`, `size`,
     `totalElements`, `totalPages`) — never return Spring's raw `Page`,
     never load all rows and slice in Java, never load the
     `PokemonCache` entity or parse `JsonNode`.
     This path reads `pokemon_cache` via the join only: **no L1, no TTL
     check, no PokéAPI call** (CLAUDE.md §6.1) — stale rows are listed as
     stored. Default sort `caughtAt desc` uses
     `idx_pokemon_collection_entry_trainer_caught_at`.
   - `contains(Long trainerId, Integer pokemonId)` → backs the
     `inCollection` flag (CLAUDE.md §7.1).
   - `add(Long trainerId, Integer pokemonId)`: call
     `PokemonLookupService.getById` only to make sure the row exists and
     is fresh (don't duplicate the lookup logic; it may be served by L1,
     L2, or PokéAPI — its return value is **not** used for the
     response). Resolve `cache_id` with the scalar
     `findActiveCacheIdByPokemonId`; if the `(trainer_id,
     pokemon_cache_id)` pair already exists, throw
     `DuplicateCollectionEntryException`; otherwise save the entry with
     `getReferenceById(cacheId)`, flush, and return
     `findEntryView(newEntryId, trainerId)` mapped to
     `CollectionEntryDto` — the same projection as the list (CLAUDE.md
     §7.4).
   - `remove(Long trainerId, Long entryId)`: use
     `findByIdAndTrainerId` (from Phase 1) — if it returns empty (either
     because the entry doesn't exist, or it exists but belongs to another
     trainer), throw a not-found exception. **Do not** first fetch by
     `entryId` alone and then separately check ownership to decide
     between `404` and `403` — the point of `findByIdAndTrainerId` is
     that "not yours" and "doesn't exist" are indistinguishable from the
     caller's perspective. Never touch `entry.getPokemonCache()` here —
     the association is `LAZY`, so ownership check + delete never load
     `data`.
   - Remove the now-unused derived list methods
     (`findByTrainerId(Long)`, `findByTrainerId(Long, Pageable)`,
     `findByTrainerIdOrderByCaughtAtDesc`,
     `findByTrainerIdAndPokemonCache_PokemonNameContainingIgnoreCase`)
     and the `JsonNode`-parsing `toDto` in `CollectionService`.
2. `CollectionController`: `GET /api/collection`, `POST /api/collection`,
   `DELETE /api/collection/{entryId}`, all behind `AuthInterceptor`
   (already configured in Phase 2), all resolving `trainerId` via
   `AuthSessionService` — never from the request. `GET` takes
   `page`/`size`/`query`/`sort`/`direction` with the defaults and
   whitelists from CLAUDE.md §7.2: `size ∈ {6,18,30}`, `page ≥ 0`,
   `sort` resolved through `CollectionSortOption` to the SQL columns
   `e.caught_at` / `c.pokemon_id` / `c.pokemon_name` plus `e.id` as
   tie-breaker (never pass the raw string to `Sort.by`), `direction ∈ {asc,desc}`; anything else throws
   `IllegalArgumentException`.
3. Extend `GlobalExceptionHandler`:
   `DuplicateCollectionEntryException` → `409`,
   `IllegalArgumentException` → `400 BAD_REQUEST`.
4. `PokemonController`: return `PokemonDetailResponseDto` (=
   `PokemonDetailDto` + `inCollection`), computed per request via
   `AuthSessionService.getCurrentTrainerId` + `CollectionService.contains`;
   `false` when there is no session (the endpoints stay public). Never
   put `inCollection` into L1/L2.
5. Logging (CLAUDE.md §11.3): `COLLECTION_LIST` (with paging/sort/filter
   params and result counts), `COLLECTION_ADD` /
   `COLLECTION_ADD_DUPLICATE`, `COLLECTION_REMOVE` /
   `COLLECTION_REMOVE_NOT_FOUND`, all keyed by `trainerId`.

## Tests to write

- Service-level (Mockito): adding a duplicate pokemon for the same
  trainer throws; adding the same pokemon for two *different* trainers
  succeeds for both (proves the uniqueness is per-trainer, not global).
- Service-level: `remove` with a valid `entryId` belonging to a different
  trainer behaves identically (same exception) to a nonexistent
  `entryId` — write this as an explicit test, not just an inference from
  the code.
- Controller-level (`@WebMvcTest`): unauthenticated requests to all three
  endpoints return `401` without reaching the service mock.
- Controller-level: `GET /api/collection` defaults (`page=0,size=6,
  sort=caughtAt,direction=desc`) reach the service as the expected
  `Pageable`; `size=7`, `page=-1`, `sort=password`, `direction=up` each
  return `400 BAD_REQUEST` without reaching the service; the response is
  the `CollectionPageDto` shape.
- Repository/integration-level: pagination metadata (`totalElements`,
  `totalPages`) is correct, the name filter is case-insensitive
  substring, each sort option orders correctly, and a filter never
  returns another trainer's rows.
- The `POST /api/collection` `201` body is identical in shape and values
  (`spriteUrl`, `types` …) to the same entry in `GET /api/collection`,
  whether `getById` was served by L1, L2, or PokéAPI (test all three).
- `CollectionService` never calls `PokemonCache.getData()` or reads the
  `PokemonCache` entity on list/add/remove (verify with mocks, and in an
  integration test via Hibernate statistics or SQL logging that the
  list/add SQL contains no bare `c.data` column in the select).
- Listing a collection whose cached rows are past the TTL returns them
  without calling `PokemonLookupService`/PokéAPI and without touching the
  L1 caches (verify with mocks: zero interactions), and emits
  `event=COLLECTION_LIST` but no `CACHE_*`/`POKEAPI_*` log lines.
- `inCollection`: `true` for a Pokémon the logged-in trainer owns,
  `false` for one they don't, `false` without a session, and trainer B
  searching a Pokémon owned only by A gets `false` (proves the flag is
  not served from a shared cache).
- **Integration-level** (`@SpringBootTest` + Testcontainers-Postgres):
  log in as trainer A, add a Pokémon, log in as trainer B in a separate
  session, confirm `GET /api/collection` for B does not include A's
  entry, and `DELETE` by B on A's `entryId` returns `404` and leaves A's
  entry intact. This is the test that actually proves the core product
  requirement ("trainers only see their own collection") end to end —
  treat it as mandatory, not optional polish.

## Definition of done

- [ ] All three endpoints work for the owning trainer.
- [ ] `GET /api/collection` is paginated/filtered/sorted in SQL and
      rejects out-of-whitelist params with `400`.
- [ ] Both `GET` and `POST` collection responses come from the single
      `CollectionEntryView` projection and include `types`; no collection
      path loads the full `jsonb` document.
- [ ] Search responses carry a correct per-trainer `inCollection`.
- [ ] Cross-trainer isolation is proven by an integration test, not just
      assumed from the query shape.
- [ ] Duplicate-add returns `409`.
- [ ] Delete of someone else's entry (or a nonexistent one) returns `404`,
      never `403`, and never a `200`/`204` that silently no-ops.
