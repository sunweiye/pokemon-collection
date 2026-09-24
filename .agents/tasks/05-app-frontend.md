# Phase 5 — Application frontend

Depends on: Phase 2 (login shell + session check already in place),
Phase 3 (search API), Phase 4 (collection API).

Read `CLAUDE.md` §5 and §7 before starting.

## Goal

The `app` SPA (served at `/`) becomes a real application: search for a
Pokémon, see clear feedback for both error cases, add it to the
collection, view/remove entries — all scoped to the logged-in trainer via
the already-working session.

## Steps

1. **No client-side router** in either bundle (CLAUDE.md §5.1): the app
   is the single page at `/`, and search/filter/sort/pagination state
   stays in component state, not in the URL. Unknown URLs are answered by
   the backend's 404 page — don't build a client-side "not found" view.
   Adding routes is a scope change: ask first.
2. Data layer: React Query (`useQuery`/`useMutation`) wrapping the
   `/api/pokemon/search`, `/api/collection` (GET/POST/DELETE) calls via
   the shared `src/api/http.ts` Axios instance from Phase 2.
3. Search UI: a single input + submit (exact match, per CLAUDE.md scope —
   do not build autocomplete or fuzzy suggestions). On result, show the
   Pokémon with an "add to collection" action. If the result's
   `inCollection` is `true`, render the action disabled as "Already
   added" (CLAUDE.md §7.1).
4. Error rendering: implement the full table in CLAUDE.md §7.3 (every
   error code, 5xx, network failure, and non-Axios errors get a
   user-facing message). At minimum —
   `error: "NOT_FOUND"` → "not found, check spelling" style message,
   `error: "UPSTREAM_UNAVAILABLE"` → "service unavailable" message *with
   a retry action*, anything else → generic fallback. Do not merge these
   into one generic "no results" message — that's the exact mistake the
   architecture doc calls out.
5. Collection list view: fetch and render `GET /api/collection` page by
   page (CLAUDE.md §7.2) — page controls (first/previous/numbered/next/
   last), a page-size selector (6/18/30), a name filter, and a sort
   selector (added date, Pokémon id, name × asc/desc). Page/size/query/
   sort live in the React Query key; the UI is 1-based and sends
   `page - 1`; changing filter/sort/size resets to page 1; the current
   page is clamped when `totalPages` shrinks. Empty collection and "no
   filter matches" show different messages. Each card shows the
   Pokémon's `types` as tags, using the **same** type-tag component as
   the search result (CLAUDE.md §7.4). Each entry has a remove
   action calling `DELETE /api/collection/{entryId}` and invalidating the
   `['collection']` queries on success.
5a. Loading overlay and load-error toast per CLAUDE.md §7.5: overlay on
   the collection card driven by `isFetching` (previous rows stay visible
   via the last successfully loaded page kept in state; card content
   `inert` + `aria-busy`), `refetchOnWindowFocus: false` and `retry: false` on the
   collection query, a shared `Toast` for `GET /api/collection` failures,
   and recovery after a failed update (restore the last successful
   page/size/filter/sort; inline error + Retry when nothing ever loaded).
6. Add-to-collection: follow the button-state table in CLAUDE.md §7.1 —
   on click, disable the button and show a spinner + "Adding…" until the
   response arrives (no double submits). On success, invalidate `['collection']` and set the
   cached search result's `inCollection` to `true`; on remove, set it back
   to `false` if that Pokémon is the current search result. Handle the
   `409` duplicate case with an inline message ("already in your
   collection") and mark the result as added — not a generic error.
7. Logout: a visible action calling `POST /api/auth/logout`, then
   `window.location.href = '/login'` on success.
8. A global 401 handler (Axios response interceptor or equivalent):
   any `401` from an authenticated endpoint redirects to `/login` — this
   complements (does not replace) the Phase-2 on-mount check, covering
   the case where a session expires mid-use.

## Tests to write

Vitest + React Testing Library + MSW (mock the backend):
- Search: renders the not-found message on a `404` mock response, the
  unavailable+retry message on a `502` mock response, and the actual
  result on `200` — three distinct assertions, not one shared snapshot.
- Add to collection: success path updates the list; `409` shows the
  duplicate message without crashing.
- Add button is disabled with a loading indicator while the request is
  in flight; a non-409 failure re-enables it and shows the right message.
- Each §7.3 error case renders its own message: `BAD_REQUEST`, `5xx`/
  `INTERNAL_ERROR`, network failure (no response), and a thrown
  non-Axios error; `UPSTREAM_UNAVAILABLE` shows a working Retry button.
- `inCollection: true` in a search result renders a disabled "Already
  added" button and never calls `POST /api/collection`; a successful add
  flips the button to "Already added" without re-searching.
- Loading overlay: going to another page, changing sort, submitting the
  filter, and changing page size each show the overlay (`role="status"`)
  while the request is pending, with the previous rows still in the
  document and the card controls not clickable; it disappears when the
  new page renders.
- Load-error toast: a failed page switch shows the toast with the
  `collectionLoad` text, then the previous page's rows and pagination
  are shown again; closing the toast removes it; a later successful
  request clears it. An initial-load failure shows the inline error with
  a Retry button that refetches. A `401` shows no toast (redirects).
- Type tags: a collection entry with `types: ["grass","poison"]` renders
  both tags in order, `[]` renders none without crashing, and the search
  result uses the same tag component.
- Pagination: correct request params (`page` is 0-based on the wire),
  page indicator, first/previous/next/last disabled states, page-size
  change resets to page 1, and clamping after the last item on the last
  page is removed.
- Filter and sort: submitting the filter sends `query`, clearing it
  resets; changing sort sends the right `sort`/`direction`; empty vs
  no-match messages differ.
- Remove from collection: success removes the item from the rendered
  list.
- A `401` response from any authenticated call triggers the redirect
  behavior (assert `window.location.href`, using the same mocking
  approach as Phase 2's login test).

## Definition of done

- [ ] A trainer can search, see a real result or a correctly-distinguished
      error, add it, see it appear in their collection, and remove it —
      all without a page reload except where explicitly designed (login →
      app transition).
- [ ] NOT_FOUND and UPSTREAM_UNAVAILABLE are visibly different to the
      user (different text, retry button present only for the latter).
- [ ] No autocomplete/fuzzy search was added to the PokéAPI search (the
      collection name filter is the only substring match, and it is a
      submit-to-filter over the trainer's own rows, not suggestions).
- [ ] The collection is never fetched or rendered in full — only the
      current page.
- [ ] Logging out and pressing "back" does not show stale collection data
      (verify the 401 redirect handles this).
