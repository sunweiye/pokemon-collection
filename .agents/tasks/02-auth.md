# Phase 2 — Authentication

Depends on: Phase 1. Use skill `add-rest-endpoint` for the controller work.

Read `CLAUDE.md` §4 and §5 before starting — this phase is the one most
likely to accidentally drift toward JWT or a full Spring Security filter
chain. It should not.

## Goal

A working login/logout/me flow, backed by in-memory `HttpSession`, plus
the Thymeleaf-rendered page shells that carry the CSRF token and locale,
plus the login page SPA.

## Steps

### Backend

1. `AuthSessionService` interface + `HttpSessionAuthService` impl, exactly
   as specified in CLAUDE.md §4 (including invalidating any pre-existing
   session on login).
2. `AuthInterceptor` (`HandlerInterceptor`) + `WebConfig` registering it
   on `/api/collection/**`, `/api/auth/me`, and `/api/auth/logout`.
3. `SecurityConfig`: configure `PasswordEncoder` (BCrypt bean) and CSRF
   with a `HttpSessionCsrfTokenRepository` bean (CLAUDE.md §4):
   - The token lives in the `HttpSession`, bound to it. There is **no CSRF
     cookie** (no `CookieCsrfTokenRepository`, no `XSRF-TOKEN`).
   - Delivery is only via the page shell: `PageController` receives the
     `CsrfToken` argument and the layout renders
     `<meta name="_csrf" th:content="${csrfToken.token}">` and
     `<meta name="_csrf_header" th:content="${csrfToken.headerName}">`
     (header name `X-CSRF-TOKEN`). The token is generated lazily when the
     shell renders it — so only `GET /` and `GET /login` create a session.
   - Do **not** add a filter that eagerly loads/generates the token on
     every request (e.g. a `CsrfTokenResponseFilter`): with a
     session-backed repository it would create a session for API calls,
     404s, and asset requests (CLAUDE.md §5.1).
   - Rotation needs no code: `HttpSessionAuthService.login` invalidates the
     old session and logout invalidates the current one, so the old token
     dies with it; the frontend then does a full navigation and reads the
     new token from the next page shell. Don't add manual token rotation
     to `AuthController`.
   - A rejected token → `403` JSON `{"error":"CSRF_FORBIDDEN",...}` via a
     custom `AccessDeniedHandler`.
   Explicitly do **not** add `.formLogin()`/`.httpBasic()`/an
   `AuthenticationManager`.
4. `AuthController`: `POST /api/auth/login` (verify username + BCrypt
   password match, call `authSessionService.login`, return `200`
   trainer DTO or `401`), `POST /api/auth/logout` (call
   `authSessionService.logout`, `204`), `GET /api/auth/me` (`200` trainer
   DTO or `401`). Log `AUTH_LOGIN_SUCCESS` / `AUTH_LOGIN_FAILED` (WARN) /
   `AUTH_LOGOUT` per CLAUDE.md §11.3 — username and trainerId only,
   never the password.
5. `ViteManifestService`: loads `classpath:vite/manifest.json` at startup
   (you'll need to configure the frontend build or a copy step so this
   file lands on the backend's classpath — a Maven resource copy or a
   documented manual step is fine for now; Phase 6 finalizes the full
   build wiring).
6. `PageController`: exactly `GET /login` and `GET /` — **no catch-all
   route** (CLAUDE.md §5.1) — each resolving the correct manifest entry
   and rendering `templates/login.html` / `templates/index.html` with CSRF
   token + locale attributes. The shared layout links the favicon at
   `/assets/favicon.ico`.
6a. Static resources and not-found handling per CLAUDE.md §5.1:
   `spring.mvc.static-path-pattern: /assets/**` +
   `spring.web.resources.static-locations: classpath:/static/assets/`;
   a `NotFoundHandler` (`@ControllerAdvice`, highest precedence) that is
   the only handler for `NoHandlerFoundException`/`NoResourceFoundException`
   and dispatches by path — JSON `NOT_FOUND` for `/api/**`, a bare `404`
   for `/assets/**`, `ModelAndView("error/404")` with status `404` for
   everything else (remove these two exceptions from
   `GlobalExceptionHandler`); `templates/error/404.html` as the static
   error page kind from CLAUDE.md §5 (fixed copy, favicon link, link back
   to `/`, no React bundle, no CSRF token, no login check). Because the
   token is only generated when a page shell renders it (step 3), 404s
   and asset requests never create an `HttpSession`.
7. `LocaleResolver`/`LocaleChangeInterceptor` beans per CLAUDE.md §5.
8. `GlobalExceptionHandler`: add the `UnauthorizedException` → `401`
   mapping now (other exception types come in later phases).

### Frontend

9. `templates/login.html` and `templates/index.html` (Thymeleaf) per the
   shape in CLAUDE.md §5 — shell only, no business content.
10. `src/login/`: a minimal login form (username/password), posting to
    `/api/auth/login` with `axios`. On `200`, `window.location.href = '/'`.
    On `401`, show the error message inline — do not navigate.
11. `src/app/`: on mount, call `GET /api/auth/me`; on failure redirect to
    `/login`. This is the only thing this phase needs from the app shell —
    actual collection/search UI comes in Phase 5.
12. Shared `src/api/http.ts`: reads the `_csrf`/`_csrf_header` meta tags
    at module load and sends the token in that header (`X-CSRF-TOKEN`) on
    every `POST`/`PUT`/`PATCH`/`DELETE`; sets `withCredentials: true`.
    Never read the token from a cookie and never hard-code the header
    name. The meta tags are the only token source: no code that fetches
    `/login` to extract a token or refreshes it; a write request without
    a token is rejected before it is sent, and on `403 CSRF_FORBIDDEN`
    the login page asks the user to reload.

## Tests to write

- Backend: `@WebMvcTest` for `AuthController` (login success/failure,
  logout, me with/without session). A small unit test for
  `HttpSessionAuthService` confirming session invalidation on login and
  correct behavior with no session on logout.
- Backend (`@SpringBootTest` + MockMvc, filters on): `POST
  /api/collection`, `DELETE /api/collection/{id}`, and `POST
  /api/auth/logout` without a token → `403` `CSRF_FORBIDDEN` and no side
  effect; a pre-login token is rejected after login; `GET /` and
  `GET /login` render a non-empty `_csrf` meta tag and create a session,
  while `/api/**` calls and 404s create none.
- Backend: a failed login emits `event=AUTH_LOGIN_FAILED` with the
  username, and the captured output never contains the submitted
  password.
- Backend (`@WebMvcTest` or `@SpringBootTest` + MockMvc): `GET /nope`,
  `GET /favicon.ico`, and `GET /login/extra` → `404` + `text/html`
  containing the 404 page marker; `GET /api/nope` → `404` +
  `application/json` `{"error":"NOT_FOUND"}`; `GET /assets/missing.js`
  → `404` with an empty body; an existing test file under
  `src/test/resources/static/assets/` → `200`; `/` and `/login` still
  `200`. None of the 404 responses and no `/assets/**` response sets a
  `JSESSIONID` cookie.
- Frontend: component test for the login form covering both the success
  navigation path and the inline-error path (mock the API call, assert
  `window.location.href` was/was not set).

## Definition of done

- [ ] Logging in with a seeded trainer (Phase 1) returns `200` and sets a
      session cookie; `/api/auth/me` then returns `200`.
- [ ] Wrong password returns `401` with an `ErrorResponse` body, no
      session created.
- [ ] Logout invalidates the session; a subsequent `/api/auth/me` is
      `401`.
- [ ] Visiting `/` without a session redirects (client-side) to `/login`.
- [ ] `/login` and `/` render via Thymeleaf with correct hashed asset
      paths and a non-empty CSRF meta tag.
- [ ] Unknown page URLs return the Thymeleaf 404 page with status `404`
      (no soft 404), unknown `/api/**` returns JSON `404`, missing
      `/assets/**` returns a bare `404`.
- [ ] No JWT anywhere. No `.formLogin()`/full Security filter chain.
