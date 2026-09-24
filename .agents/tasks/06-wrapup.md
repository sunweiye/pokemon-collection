# Phase 6 — Wrap-up: build wiring, README, end-to-end verification

Depends on: all previous phases. This phase produces the actual
submission artifact — treat it as seriously as any feature phase, not as
an afterthought.

Read `CLAUDE.md` §9 and the full document once more before starting, as a
final consistency check against everything built so far.

## Steps

1. **Finalize the frontend→backend build wiring**: the frontend build
   (`npm run build` in `frontend/`) must produce `frontend/dist/` with
   `manifest.json`, and that `manifest.json` plus the built assets must
   end up on the backend's classpath / static resources such that
   `ViteManifestService` (Phase 2) resolves real paths, not the
   placeholder wiring from earlier phases. Prefer wiring this as a Maven
   build step (e.g. `frontend-maven-plugin` or an `exec-maven-plugin` npm
   call bound to `generate-resources`, copying `dist/assets/` (hashed
   bundles **and** the files from `frontend/public/assets/`, e.g. the
   favicon) into `src/main/resources/static/assets/` and the manifest
   into
   `src/main/resources/vite/manifest.json`) so `./mvnw clean package`
   alone produces a runnable jar — don't leave this as a manual
   copy-paste step for the evaluator.
2. **Dockerfile for `backend`**: multi-stage — a Node stage building the
   frontend, a Maven stage building the backend jar (consuming the
   frontend stage's `dist/` output as above), a final slim JRE runtime
   stage. Update `docker-compose.yml` if the Phase-0 placeholder needs
   adjusting.
3. **README.md** at the repo root, covering at minimum:
   - Prerequisites (Docker/Docker Compose version).
   - Two Docker startup paths: (a) `docker compose up --build` for the
     full containerized run, (b) `docker compose -f docker-compose.yml -f
     docker-compose.dev.yml up --build` for the containerized development
     loop with frontend and backend hot reload.
   - **Test account credentials** (plaintext username/password, from
     Phase 1's seed migration) — this is not optional, the evaluator needs
     it to log in.
   - How to run tests inside Docker Compose containers: `mvn test` (backend), `npm test -- --run` (frontend).
   - Environment variables (CLAUDE.md §10): the `cp .env.example .env`
     step, that all five `POSTGRES_*` connection values are required with no
     repository fallback, and every variable with meaning and default where
     applicable, especially `POSTGRES_USER` /
     `POSTGRES_PASSWORD` and `APP_POKEMON_CACHE_TTL_DAYS`. State clearly
     that the `POSTGRES_*` credentials are the database account, not the
     trainer login accounts listed above.
   - Where logs go (`$LOG_PATH/pokemon-collection.log`, `LOG_PATH`
     default `logs` relative to the backend's working directory, rotation
     policy), how to follow them (`tail -f`, `docker compose logs -f
     backend`), and how to enable DEBUG to see `CACHE_L1_HIT`.
   - A feature list that includes collection pagination/filter/sort and
     the `inCollection` "already added" marking (CLAUDE.md §7.1/§7.2).
   - A short "known limitations / non-goals" section mirroring CLAUDE.md
     §1, so the evaluator understands e.g. "no self-registration" and "no
     fuzzy search" are deliberate, not missed requirements (and that the
     collection name filter is not the PokéAPI fuzzy search).
4. **Full test suite run**: `./mvnw -f backend clean verify` and
   `npm --prefix frontend test` both green, from a clean checkout (no
   leftover local state).
5. **Manual end-to-end pass** (do this yourself, don't just claim it):
   - `docker compose up --build` from a clean state (`docker compose down
     -v` first).
   - Open `/login`, log in with a seeded account, confirm redirect to `/`.
   - Search a valid Pokémon, add it; search it again and confirm the
     button shows "Already added" and is disabled. Confirm its
     collection card shows the sprite and the same type tags as the
     search result.
   - With more than 6 entries, page through the collection, change the
     page size, filter by part of a name, and switch sort orders.
   - Search a nonsense query, confirm the not-found message (not a
     generic error). Search a partial name (e.g. `pika`) and confirm it
     is also not found (exact match only).
   - Check the log file for the search trail: `CACHE_L1_MISS` →
     `CACHE_L2_MISS` → `POKEAPI_SUCCESS` on a first search; on a repeat
     search no line by default, and `CACHE_L1_HIT` once DEBUG is enabled
     for `PokemonLookupService`; `POKEAPI_FAILED ... status=404` for the nonsense query.
   - Open an unknown URL (e.g. `/does-not-exist`) → the custom 404 page
     with status `404` (check in the browser dev tools); `/api/does-not-exist`
     → JSON `404`; the favicon loads from `/assets/favicon.ico`.
   - Log out, confirm redirect to `/login` and that `/` now bounces back
     to `/login` if visited directly.
   - Log in as a **second** seeded trainer, confirm their collection is
     empty (does not show the first trainer's Pokémon).
   - Add a different Pokémon as trainer 2, log back in as trainer 1,
     confirm trainer 1 still only sees their own single entry.

## Definition of done

- [ ] `docker compose up --build` from a clean checkout fails early with a
      clear missing-variable message when `.env` is absent, and reproduces
      the whole app after required `.env` values are supplied.
- [ ] Both test suites pass from a clean checkout.
- [ ] The log file contains the CLAUDE.md §11.3 events and no passwords,
      session ids, CSRF tokens, or PokéAPI payloads.
- [ ] The manual end-to-end pass above was actually performed, including
      the two-trainer isolation check, not assumed from earlier unit
      tests.
- [ ] README's test credentials actually work against the seeded
      migration.
- [ ] The variable list is identical across `.env.example`,
      `application.yml`, `docker-compose.yml`, and the README; required
      database values have no fallback; `.env` is not committed.
- [ ] Nothing in CLAUDE.md's "explicitly out of scope" list (§1) was
      accidentally built — do a final scan for scope creep (e.g. an
      autocomplete dropdown, a registration form) before calling this
      done.
