# Phase 1 — Data layer

Depends on: Phase 0. Use skill `add-flyway-migration` for every step here.

Read `CLAUDE.md` §3 before starting.

## Goal

The three core tables exist via Flyway migrations, matching JPA entities
exist, and repository tests prove the schema (especially the `jsonb`
column and the unique constraints) actually works — before any business
logic is built on top of it.

## Steps

1. Write migrations, in order (the actual layout in this repo):
   - `V1__create_trainer_table.sql` — creates all three core tables
     (`trainer`, `pokemon_cache`, `pokemon_collection_entry`).
   - `V3__add_deleted_to_pokemon_cache.sql` (after the seed below) —
     adds `pokemon_cache.deleted BOOLEAN NOT NULL DEFAULT FALSE`, the
     partial indexes `WHERE deleted = FALSE` on `pokemon_id` and
     `pokemon_name`, and
     `idx_pokemon_collection_entry_trainer_caught_at`
     `(trainer_id, caught_at DESC)`.
   Use the exact DDL in CLAUDE.md §3 — do not improvise column
   names/types.
2. `V2__seed_trainers.sql`: insert at least two trainer accounts with
   BCrypt-hashed passwords. Generate the hashes ahead of time (e.g. a
   throwaway `PasswordEncoderTest`/small script using
   `BCryptPasswordEncoder`) — do not hand-write a fake-looking hash.
   Record the plaintext username/password pairs somewhere you'll carry
   into the README later (Phase 6).
3. Create entities: `Trainer`, `PokemonCache`, `PokemonCollectionEntry`,
   with the annotations shown in CLAUDE.md §3 (note: `PokemonCache.data`
   uses `@JdbcTypeCode(SqlTypes.JSON)` with `columnDefinition = "jsonb"`,
   backed by a Jackson `JsonNode`).
4. Create repositories:
   - `TrainerRepository`: `findByUsername`, `existsByUsername`.
   - `PokemonCacheRepository`: `findByPokemonIdAndDeletedFalse`,
     `findByPokemonNameAndDeletedFalse` (exact equality on the
     lowercased name — no `IgnoreCase`/`Containing`), `findByPokemonId` /
     `findByPokemonName` (unfiltered, used only to update an existing
     row in place on refresh), and a
     scalar `findActiveCacheIdByPokemonId(Integer)` →
     `Optional<Long>` (`select c.cacheId …`, no `jsonb` load) for the add
     flow.
   - `PokemonCollectionRepository`: the `CollectionEntryView` interface
     projection and its two native queries from CLAUDE.md §7.4 —
     `findEntryViews(trainerId, nameQuery, Pageable)` (list + name
     filter, with a `jsonb`-free `countQuery`) and
     `findEntryView(entryId, trainerId)` — plus
     `existsByTrainerIdAndPokemonCache_CacheId` (duplicate check),
     `existsByTrainerIdAndPokemonCache_PokemonId` (the `inCollection`
     flag, CLAUDE.md §7.1), `findByIdAndTrainerId` (needed later for the
     "404 not 403" delete rule).
5. Set `spring.jpa.hibernate.ddl-auto=validate` and `spring.flyway.enabled=
   true` in `application.yml` (both hard-coded — never make `ddl-auto`
   an env var). Confirm the app still boots against a real Postgres (via
   `docker compose up postgres`, connection values taken from the required
   `.env` values per CLAUDE.md §10) with no schema mismatch.

## Tests to write

- `@DataJpaTest` (Testcontainers-Postgres, not H2 — H2's jsonb emulation
  is not reliable enough for this) covering:
  - Saving and reading back a `PokemonCache` row round-trips the `jsonb`
    payload correctly (assert on a nested field, not just non-null).
  - `pokemon_id` and `pokemon_name` uniqueness is enforced at the DB level
    (attempt a duplicate insert, expect a constraint violation).
  - `pokemon_collection_entry`'s `(trainer_id, pokemon_cache_id)` unique
    constraint is enforced.
  - `findEntryViews`/`findEntryView` return `spriteUrl` from
    `sprites.front_default` (and `null` when it is JSON `null`), `types`
    in PokéAPI slot order (`["grass","poison"]` for bulbasaur) and `[]`
    when `types` is missing; the name filter is case-insensitive
    substring and treats `%`/`_` in the input literally; each sort option
    and direction orders the rows; another trainer's rows never appear.
  - `findByPokemonNameAndDeletedFalse("pikachu")` finds the row, a
    partial name (`"pika"`) finds nothing (exact match, not `LIKE`).
  - A row with `deleted = true` is invisible to
    `findByPokemonIdAndDeletedFalse` / `findByPokemonNameAndDeletedFalse`,
    and a new row defaults to
    `deleted = false`.

## Definition of done

- [ ] All migrations (V1–V4) applied cleanly against a fresh database
      (`docker compose down -v && docker compose up postgres` then boot
      the app).
- [ ] `ddl-auto=validate` passes — no entity/schema drift.
- [ ] `@DataJpaTest` suite green, including the jsonb round-trip and
      uniqueness tests.
- [ ] Seed trainer plaintext credentials captured for later README use.
