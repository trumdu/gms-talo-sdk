# Talo API Guide for Cursor (Project-Specific)

This file is a compact, implementation-focused reference for working with Talo in this project.
Source: PDF exports in `docs-main/http`.

## 1) Global HTTP Auth Requirements

- Every HTTP call must include `Authorization: Bearer <access_key>`.
- Missing/invalid auth produces common auth errors (see `common-errors.pdf`).

## 2) Player Authentication Headers

When calling endpoints on behalf of an authenticated player, send:

1. `x-talo-player` - player ID
2. `x-talo-alias` - identified alias ID
3. `x-talo-session` - session token from register/login

Notes:
- Some endpoints only require alias context; some require full player auth context.
- If endpoint expects player-auth context and `x-talo-session` is missing/invalid, request fails.

## 3) Leaderboard API Essentials

## GET entries

Endpoint shape:
- `GET /v1/leaderboards/:internalName/entries`

Key facts from docs:
- Returns up to 50 entries per page.
- Query filtering includes (at least): `page`, `player_id`, `alias_id`, `service`, date filters, `prop` filters, `withDeleted`.
- Use `player_id=<current_player_id>` when you need the current player's own entry.

## POST create/update entry

Endpoint shape:
- `POST /v1/leaderboards/:internalName/entries`

Required:
- Header: `x-talo-alias`
- Body: numeric `score`

Behavior:
- If leaderboard mode is `unique`, existing player entry is updated with new score.
- Response may include an `updated` flag when entry was updated.

## 4) Common Error/Rate-Limit Rules

- Missing required params/headers usually returns `400`.
- Rate limit: roughly `100 req/min` per player (fixed 1-minute window).
- Auth endpoints have stricter rate limit (`20 req/min` per player).
- Respect retry headers (`Retry-After` style behavior described in docs).

## 5b) Multiplayer leaderboard `mbest` (ImFriend)

- After an online multiplayer match, the project may call `TaloSDK_leaderboard_entries_add_score("mbest", delta)` (same client flow as `add_score` / `sbest`, different internal leaderboard name).
- Create the `mbest` leaderboard in the Talo dashboard if it is not present.

## 5) Rules for `add_score` in This Codebase

`add_score` is implemented as client-side sequence:
1) `GET entries?player_id=<current_player>`
2) read current player score
3) compute `newScore = oldScore + delta`
4) `POST entries` with `newScore`

Important constraints:
- This flow is not atomic (race-condition possible if parallel updates happen).
- Never use "first entry of page" as player score source.
- Always match entry to current player (by `playerId` or nested `playerAlias.player.id`).
- If player entry not found, treat previous score as `0` unless product logic specifies otherwise.

## 6) Parsing Responses Safely (Practical)

Talo responses can vary by endpoint/options. For robust parsing:
- check both root and `data` object;
- for leaderboard post result, support `entry`, `leaderboardEntry`, and `entries[0]` fallback paths;
- do not hard-crash on missing optional fields (`updated`, `position`, etc.);
- emit async failure with raw body when parse fails.

## 7) Async Contract Used by This Project

Android extension emits Social Async with:
- `id = 9829`
- `talo_op` identifying operation (e.g. `identify`, `leaderboard_entries_get`, `leaderboard_entries_post`, `leaderboard_entries_add_score`)
- `success`, `http_code`, `body`, `error`

When adding new API wrappers, keep this shape unchanged for compatibility with existing GML handlers.

