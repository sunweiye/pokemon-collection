# Pokémon Collection

A Pokémon collection application built with Spring Boot, PostgreSQL, React, and
Vite, implemented according to `ARCHITECTURE.md`, `AGENTS.md`, and the phase tasks in `.agents/tasks/`.

## Full container startup

Requirements: Docker Desktop or any Docker environment with Docker Compose.

```bash
cp .env.example .env
# Fill the five required POSTGRES_* values in .env before starting.
docker compose up --build -d
```

This is the full container / production mode: the only published port is 8080.
PostgreSQL is not exposed to the host; the backend connects to it over the
Compose network as `postgres:5432`. Only the development mode (with
`docker-compose.dev.yml` added on top) exposes PostgreSQL as `localhost:65432`
(for host tools such as psql or an IDE) and opens port 5173 for Vite to serve
frontend modules.

Open the Spring Boot page shell:

- http://localhost:8080/

## Development startup

Development must use the Docker Compose override. A plain
`docker compose up --build -d` does not start the Vite dev server, so frontend
source changes will not show up after a reload.

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up --build -d
```

Development mode is still accessed through the Spring Boot entry point on port
8080, while frontend changes take effect immediately on reload.

In this mode Spring Boot still renders `http://localhost:8080/login` and the
application page, and the frontend modules are served by the Vite dev server.
After you change code under `frontend/src`, Vite updates the modules
automatically; reload the page on 8080 to see the result — no backend image
rebuild is needed. Port 5173 only serves frontend modules and hot reload for
the pages on 8080. It is not an entry point and returns 404 when opened
directly; in every mode, the application is accessed only through
`http://localhost:8080`.

The backend reloads automatically in this mode as well: the `backend/` source
is mounted into the container, a watch script inside the container recompiles
when `src/main/java` or `src/main/resources` changes, and
`spring-boot-devtools` then restarts the application context. Adding,
changing, or deleting Java classes usually takes effect within a few seconds,
without a manual restart.

Recreate the backend container manually in these cases (in development mode
`up --build` does not recompile the source, because the image only contains
the startup script):

- dependencies in `backend/pom.xml` changed;
- the behaviour is still wrong after the automatic restart (e.g. it did not
  recover from a compilation failure).

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --force-recreate backend
```

`spring-boot-devtools` is for development only: it is declared as `runtime` +
`optional`, excluded from the packaged artifact, and switched off
automatically when the packaged jar runs with `java -jar`, so the production
image is not affected.

If only the base Compose file is started, the backend uses the frontend assets
bundled into its image; after frontend changes, run
`docker compose up --build -d backend` again.

Stop the services but keep the database data:

```bash
docker compose down
```

## Local accounts

Flyway creates three demo accounts, all with the password `password`:

| Username | Password |
| --- | --- |
| `apple` | `password` |
| `banana` | `password` |
| `cherry` | `password` |

`POSTGRES_USER` / `POSTGRES_PASSWORD` are the PostgreSQL database credentials,
not the trainer accounts above.

## Configuration

`.env.example` is the only committed configuration template; the real `.env`
is not committed. The supported variables are:

- `POSTGRES_HOST` (local default `localhost`; use `postgres` with Docker Compose)
- `POSTGRES_PORT` (local default `5432`; use `5432` with Docker Compose)
- `POSTGRES_DB` (default `pokemon_collection`)
- `POSTGRES_USER` (default `app`)
- `POSTGRES_PASSWORD` (default `app`; must be overridden for deployments)
- `APP_POKEMON_CACHE_TTL_DAYS`
- `LOG_PATH` (backend log directory, default `logs`, relative to the backend's working directory)
- `SESSION_COOKIE_SECURE` (whether `JSESSIONID` is only sent over HTTPS, default `false`; set to `true` for production over HTTPS)
- `THYMELEAF_CACHE` (whether Thymeleaf templates are cached; `true` in the main configuration, `false` in the dev profile)

The database variables in `.env.example` are intentionally empty: Compose
requires the deployment/container connection values to be provided explicitly
in `.env`. The fallbacks in `application.yml` contain no real credentials and
must not be used as production configuration. PostgreSQL is exposed as
`localhost:65432` only in development mode.

Login rate limiting is not implemented yet. CSRF protection only prevents
cross-site request forgery; it is not protection against password brute
forcing. Login rate limiting is a planned security hardening item.

The application page currently has no client-side routes. Search, filtering,
sorting, and pagination of the collection list are kept in React component
state and are not written to the URL; a routing library can be introduced if
the project grows and needs deep links.

## Docker development and verification

Start the development environment:

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up --build -d
```

Frontend tests and build:

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml run --rm frontend sh -c 'npm install && npm test -- --run'
docker compose -f docker-compose.yml -f docker-compose.dev.yml run --rm frontend sh -c 'npm install && npm run build'
```

Backend tests: the full suite includes Testcontainers. The test container
needs the host Docker socket mounted for that one run; this does not give the
normally running backend service any Docker access.

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml run --rm --build \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  --entrypoint mvn backend test
```

If the environment cannot provide the Docker socket, run the tests that do not
depend on Testcontainers:

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml run --rm --build --entrypoint mvn backend -Dtest='!DataLayerTest,!CollectionIsolationIntegrationTest' test
```

## Logging

The backend uses SLF4J with Spring Boot's default Logback. Logs go to the
console and to `${LOG_PATH}/pokemon-collection.log`, rolled daily or at 10 MB,
compressed, and kept for 30 days.

```bash
tail -f logs/pokemon-collection.log
docker compose logs -f backend
```

The default INFO log contains cache, PokéAPI, collection, authentication, and
request-failure events. To see the high-frequency `CACHE_L1_HIT` event, enable
DEBUG for the corresponding logger through a startup argument:

```bash
docker compose run --rm backend \
  --logging.level.com.example.pokemoncollection.pokemon.PokemonLookupService=DEBUG
```

## Feature scope

- Session-based trainer login/logout
- Exact-match Pokémon search: numeric queries look up by id, anything else by
  name; names are normalized (trim + lowercase) and matched by equality, never
  `LIKE` (`pika` does not find `pikachu`)
- L1 Caffeine + L2 PostgreSQL + L3 PokéAPI lookup chain, used only by search
- `pokemon_cache.deleted`: a stale row whose PokéAPI refresh returns 404 is
  marked deleted (and evicted from L1); a later successful refresh restores it.
  L2 lookups use partial indexes on non-deleted rows
- Add/list/remove the current trainer's collection entries
- Search results include `inCollection`, so Pokémon already in the current
  trainer's collection are shown as "Already added" and cannot be added twice
- Server-side paginated collection (6/18/30 per page, dropdown) with a name
  filter and a sort dropdown (added date, Pokémon id, name; ascending or
  descending; default: newest added first, backed by the
  `(trainer_id, caught_at DESC)` index)
- The collection list and its filter read PostgreSQL directly: they skip the L1
  memory cache, ignore the cache TTL, and never call PokéAPI
- The add button is disabled with a loading indicator while the request runs,
  then shows "Already added"; duplicate (409), not-found, upstream-unavailable
  (with retry), bad-request, server, and network errors each show their own
  message
- Thymeleaf static page shell with independent React login/app bundles
- Tailwind CSS utilities with local Shadcn-style UI primitives and Lucide icons
- Flyway-owned schema; Hibernate uses `ddl-auto=validate`

Registration, password reset, fuzzy search or autocomplete against PokéAPI,
social sharing, collection limits are out of scope.
The collection's name filter is a case-insensitive substring match over the
current trainer's own collected entries only; it does not call PokéAPI and
offers no input suggestions, so it is not the "fuzzy search" excluded above.
