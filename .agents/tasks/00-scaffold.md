# Phase 0 — Scaffold

Read `CLAUDE.md` in full before starting. This phase creates the project
skeleton only — no business logic yet.

## Goal

A repository that builds and starts (even though it does nothing useful
yet), with the directory layout from CLAUDE.md's architecture, ready for
the following phases to fill in.

## Steps

1. **Backend**: initialize a Spring Boot 3.x / Java 21 Maven project
   (`backend/`) with dependencies: `spring-boot-starter-web`,
   `spring-boot-starter-data-jpa`, `spring-boot-starter-thymeleaf`,
   `spring-boot-starter-security`, `spring-boot-starter-cache`,
   `spring-boot-starter-webflux` (for `WebClient` only — do not build a
   reactive stack, this is just for the HTTP client), `flyway-core`,
   `flyway-database-postgresql`, `postgresql` driver, `caffeine`,
   `spring-boot-starter-test`, `mockwebserver` (test scope),
   `testcontainers` + `testcontainers-postgresql` (test scope).
2. Create the package layout exactly as in CLAUDE.md's directory
   structure: `auth/`, `trainer/`, `pokemon/`, `collection/`, `common/`,
   `config/`, plus `PageController` at the root package (no SPA catch-all
   controller — see CLAUDE.md §5.1). Empty/stub classes are fine at this
   stage.
3. **Frontend**: initialize `frontend/` with Vite + React + TypeScript.
   Set up the two entry points as `rollupOptions.input` pointing directly
   at `src/login/main.tsx` and `src/app/main.tsx` per CLAUDE.md §5, with
   `build.manifest: true` in `vite.config.ts`. **No HTML files in
   `frontend/`** and **no Vite proxy**: pages are only ever rendered by
   Spring Boot on port 8080 (CLAUDE.md §2); the dev server on 5173 only
   serves modules (`strictPort`, `server.origin:
   'http://localhost:5173'`, `server.cors` limited to
   `http://localhost:8080`). Each entry should render a trivial
   placeholder component for now. Put hand-placed static files in
   `frontend/public/assets/` (start with `favicon.ico`); the Thymeleaf
   layout references it (CLAUDE.md §5.1).
4. `docker-compose.yml` at the repo root with a `postgres` service and a
   `backend` service (no frontend service — port 8080 is the only entry
   point; the Vite dev server belongs in `docker-compose.dev.yml`) — it's fine if `backend`'s Dockerfile is a minimal
   placeholder at this stage as long as `docker compose up postgres`
   works. Database values use required `${VAR:?message}` interpolation (no
   hard-coded credentials or connection fallbacks); non-sensitive operational
   values may use safe defaults. The command must fail before starting when
   a required database variable is missing.
5. Environment configuration per CLAUDE.md §10:
   - `.env.example` at the repo root listing **every** variable from the
     §10 table with an English comment. Required database values are blank so
     users must provide them in the ignored `.env` file.
   - `.gitignore` at the repo root ignoring `.env`.
   - `backend/src/main/resources/application.yml` binding database variables
     explicitly without fallbacks and non-sensitive variables with safe
     explicit defaults (datasource URL/user/password,
     `app.pokemon-cache.ttl-days`), plus fixed-value
     `app.pokeapi.base-url` / `app.pokeapi.timeout-seconds`, plus
     `spring.config.import: optional:file:../.env[.properties]`.
     `spring.jpa.hibernate.ddl-auto: validate` stays hard-coded.
6. Logging per CLAUDE.md §11.1 — keep Spring Boot's default Logback (no
   Log4j2, no `logback-spring.xml`): in `application.yml` set
   `logging.file.name: ${LOG_PATH:logs}/pokemon-collection.log`, the
   `logging.logback.rollingpolicy.*` values (daily + 10 MB, `.log.gz`, 30
   days) and `logging.level.*`; add
   `backend/src/test/resources/logback-test.xml` (console only, root
   INFO); `LOG_PATH` in `.env.example` and passed through
   `docker-compose.yml`; `logs/` in `.gitignore`.
7. Confirm `./mvnw -f backend clean verify` and `npm --prefix frontend
   run build` both succeed before moving on.

## Definition of done

- [ ] `backend/` builds with `./mvnw clean verify` (no tests yet, that's
      fine — just must compile and the (empty) test suite must pass).
- [ ] `frontend/` builds with `npm run build`, producing two HTML/JS
      bundles under `frontend/dist/`.
- [ ] `docker compose up postgres` starts a working Postgres container.
- [ ] The only logging backend is Logback (`./mvnw -f backend
      dependency:tree` shows no `log4j-core`/`spring-boot-starter-log4j2`);
      starting the backend writes to both the console and
      `logs/pokemon-collection.log`, and setting `LOG_PATH` in `.env` moves
      the file to that directory.
- [ ] `.env.example` matches the CLAUDE.md §10 table exactly; `.env` is
      git-ignored.
- [ ] The stack fails fast with a clear missing-variable message without a
      `.env` file, and starts with a filled `.env` (e.g. a changed
      `POSTGRES_PASSWORD` is picked up by both the Postgres container and the
      backend).
- [ ] No credential or environment-specific value is hard-coded in
      `application.yml`, `docker-compose.yml`, or Java code.
- [ ] Directory layout matches CLAUDE.md.
- [ ] Nothing in this phase implements auth, PokéAPI, or collection logic
      — resist the urge to get ahead of the plan.
