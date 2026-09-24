---
name: add-flyway-migration
description: Use whenever a database schema change is needed — a new table, column, index, constraint, or seed data. This is the ONLY way schema changes happen in this project (ddl-auto=validate forbids Hibernate auto-DDL). Also use when adding/changing a JPA entity, to make sure the migration and the entity are written together and stay in sync.
---

# Add a Flyway migration

`spring.jpa.hibernate.ddl-auto=validate` is set project-wide (CLAUDE.md
§3). Hibernate will refuse to start if an entity doesn't match the actual
table structure. This means: **no schema change is complete until a
Flyway migration exists for it** — an entity annotation change alone does
nothing to the database and will make the app fail to boot.

## Rules

1. **Never edit an existing, already-applied migration file.** Flyway
   tracks applied migrations by checksum; editing one that's already run
   (locally or in any environment) breaks it for everyone. Always add a
   new file with the next version number.
2. **File naming**: `V{n}__{snake_case_description}.sql` under
   `backend/src/main/resources/db/migration/`, `n` strictly increasing
   from whatever the highest existing version is. Check the directory
   before picking a number — don't guess.
3. **One logical change per migration** (e.g. "add table X" and "seed
   table X" are two files). Current files: `V1__create_trainer_table.sql`
   (all three core tables), `V2__seed_trainers.sql`,
   `V3__add_deleted_to_pokemon_cache.sql` — the next one is `V4__...`.
4. **Every migration must be matched by an entity change in the same
   commit/task**, and vice versa — never let SQL and `@Entity` drift apart.
   After writing both, the expectation is that
   `mvn test` (which boots the context and validates the schema via
   `@DataJpaTest`/`@SpringBootTest`) passes.
5. **Seed data (passwords, fixtures) uses real BCrypt hashes**, generated
   ahead of time — do not store plaintext passwords in a migration, even
   for seed/test accounts. Document the corresponding plaintext in the
   README (CLAUDE.md §4), not in the SQL file itself.
6. **Naming and constraints must match CLAUDE.md §3 exactly** — table and
   column names (`cache_id`, `pokemon_id`, `pokemon_name`, `data`,
   `cached_at`, etc.) are treated as fixed unless the user explicitly asks
   to change them. Don't rename them "for clarity."

7. **Indexes follow the actual query shape.** If repository queries
   always filter a flag (like `deleted = FALSE`), use a partial index
   with the same `WHERE` so the index stays small and the planner can
   use it (see V3). For a list that is filtered by one column and sorted
   by another, use a composite index in that order with the matching
   direction (e.g. `(trainer_id, caught_at DESC)`). Adding a column to
   `pokemon_cache`/`pokemon_collection_entry` also means updating the DDL
   in CLAUDE.md §3.

## Checklist for a new migration

- [ ] Checked `db/migration/` for the current highest `V{n}` and picked
      the next one.
- [ ] SQL matches the table/column definitions in CLAUDE.md §3 (if this
      migration touches one of the three core tables) or is a clearly
      justified, user-approved addition.
- [ ] Corresponding `@Entity` class added/updated in the same change.
- [ ] Any new unique/foreign-key constraint that a repository query relies
      on is actually present in the SQL (e.g. don't assume a `UNIQUE`
      exists — check the file).
- [ ] Ran a local build (`./mvnw test` or at minimum a `@DataJpaTest`) to
      confirm Hibernate's `validate` mode accepts the new schema.
- [ ] If this migration adds seed data with a password, the plaintext is
      added to the README's test-account list, and the SQL only contains
      the BCrypt hash.

## Common mistakes to avoid

- Relying on `ddl-auto=update`/`create` to "just make it work" instead of
  writing the migration — this project has that explicitly disabled.
  Re-enabling it is not an acceptable workaround — including via an env
  var override (e.g. `SPRING_JPA_HIBERNATE_DDL_AUTO` in `.env` or
  `docker-compose.yml`); `ddl-auto` is deliberately not configurable
  (CLAUDE.md §10).
- Editing a migration file that has already been applied instead of
  adding a new one.
- Adding a column to the entity without an accompanying migration (will
  fail schema validation on next boot).
- Storing plaintext passwords or secrets in migration SQL.
