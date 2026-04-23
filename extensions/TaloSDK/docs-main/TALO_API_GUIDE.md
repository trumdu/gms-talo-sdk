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

## 8) Public HTTP routes ↔ `TaloSDK_*` (trytalo `GET /public/docs`)

Canonical source: [Talo public docs JSON](https://api.trytalo.com/public/docs) (`docs.services[].routes`). Each route below maps to one extension call (or `TaloSDK_http_async` for gaps). `talo_op` in async is shown in parentheses.

| Method | Path | `TaloSDK_*` (GML) |
|--------|------|-------------------|
| POST | `/v1/events` | `TaloSDK_events_track` (`events_track`) |
| GET | `/v1/game-channels` | `TaloSDK_game_channels_list` |
| POST | `/v1/game-channels` | `TaloSDK_game_channels_create` |
| GET | `/v1/game-channels/subscriptions` | `TaloSDK_game_channels_subscriptions` |
| POST | `/v1/game-channels/:id/join` | `TaloSDK_game_channels_join` |
| POST | `/v1/game-channels/:id/leave` | `TaloSDK_game_channels_leave` |
| POST | `/v1/game-channels/:id/invite` | `TaloSDK_game_channels_invite` |
| GET | `/v1/game-channels/:id/members` | `TaloSDK_game_channels_members` |
| GET | `/v1/game-channels/:id/storage/list` | `TaloSDK_game_channels_storage_list` |
| GET | `/v1/game-channels/:id/storage` | `TaloSDK_game_channels_storage_get` |
| PUT | `/v1/game-channels/:id/storage` | `TaloSDK_game_channels_storage_put` |
| GET | `/v1/game-channels/:id` | `TaloSDK_game_channels_get` |
| PUT | `/v1/game-channels/:id` | `TaloSDK_game_channels_update` |
| DELETE | `/v1/game-channels/:id` | `TaloSDK_game_channels_delete` |
| GET | `/v1/leaderboards/:internalName/entries` | `TaloSDK_leaderboard_entries_get` |
| POST | `/v1/leaderboards/:internalName/entries` | `TaloSDK_leaderboard_entries_post`; composite `TaloSDK_leaderboard_entries_add_score` (`leaderboard_entries_add_score`) |
| GET | `/v1/game-config` | `TaloSDK_game_config_get` |
| GET | `/v1/game-feedback/categories` | `TaloSDK_game_feedback_categories_get` |
| POST | `/v1/game-feedback/categories/:internalName` | `TaloSDK_game_feedback_create` |
| GET | `/v1/game-saves` | `TaloSDK_game_saves_list` |
| POST | `/v1/game-saves` | `TaloSDK_game_saves_create` |
| PATCH | `/v1/game-saves/:id` | `TaloSDK_game_saves_update` |
| DELETE | `/v1/game-saves/:id` | `TaloSDK_game_saves_delete` |
| GET | `/v1/game-stats` | `TaloSDK_game_stats_list` |
| GET | `/v1/game-stats/player-stats` | `TaloSDK_game_stats_player_stats` |
| GET | `/v1/game-stats/:internalName/history` | `TaloSDK_game_stats_history` |
| GET | `/v1/game-stats/:internalName/global-history` | `TaloSDK_game_stats_global_history` |
| GET | `/v1/game-stats/:internalName/player-stat` | `TaloSDK_game_stats_player_stat_get` |
| GET | `/v1/game-stats/:internalName` | `TaloSDK_game_stats_get` |
| PUT | `/v1/game-stats/:internalName` | `TaloSDK_game_stats_put` |
| GET | `/v1/players/identify` | `TaloSDK_players_identify` (`identify`) |
| GET | `/v1/players/search` | `TaloSDK_players_search` |
| POST | `/v1/players/merge` | `TaloSDK_players_merge` |
| POST | `/v1/players/socket-token` | `TaloSDK_players_create_socket_token` (`socket_token`) |
| GET | `/v1/players/:id` | `TaloSDK_players_get` (`get_player`) |
| PATCH | `/v1/players/:id` | `TaloSDK_players_patch_props` (`patch_player`) |
| POST | `/v1/players/auth/register` | `TaloSDK_players_auth_register` |
| POST | `/v1/players/auth/login` | `TaloSDK_players_auth_login` |
| POST | `/v1/players/auth/refresh` | `TaloSDK_players_auth_refresh` |
| POST | `/v1/players/auth/verify` | `TaloSDK_players_auth_verify` |
| POST | `/v1/players/auth/logout` | `TaloSDK_players_auth_logout` |
| POST | `/v1/players/auth/change_password` | `TaloSDK_players_auth_change_password` |
| POST | `/v1/players/auth/change_email` | `TaloSDK_players_auth_change_email` |
| POST | `/v1/players/auth/change_identifier` | `TaloSDK_players_auth_change_identifier` |
| POST | `/v1/players/auth/forgot_password` | `TaloSDK_players_auth_forgot_password` |
| POST | `/v1/players/auth/reset_password` | `TaloSDK_players_auth_reset_password` |
| PATCH | `/v1/players/auth/toggle_verification` | `TaloSDK_players_auth_toggle_verification` |
| DELETE | `/v1/players/auth` | `TaloSDK_players_auth_delete` |
| POST | `/v1/players/auth/migrate` | `TaloSDK_players_auth_migrate` |
| GET | `/v1/player-groups/:id` | `TaloSDK_player_groups_get` |
| GET | `/v1/players/presence/:id` | `TaloSDK_players_presence_get` |
| PUT | `/v1/players/presence` | `TaloSDK_players_presence_put` |
| POST | `/v1/players/relationships` | `TaloSDK_players_relationships_create` |
| PUT | `/v1/players/relationships/:id/confirm` | `TaloSDK_players_relationships_confirm` / `TaloSDK_players_relationships_confirm_str` |
| GET | `/v1/players/relationships/subscribers` | `TaloSDK_players_relationships_subscribers` |
| GET | `/v1/players/relationships/subscriptions` | `TaloSDK_players_relationships_subscriptions` |
| DELETE | `/v1/players/relationships/:id` | `TaloSDK_players_relationships_delete` / `TaloSDK_players_relationships_delete_str` |
| POST | `/v1/socket-tickets` | `TaloSDK_socket_tickets_create` |

Helpers (not single-route): `TaloSDK_init`, `TaloSDK_http_async`, `TaloSDK_set_player_context`, `TaloSDK_save_credentials`, `TaloSDK_get_last_player_id`, `TaloSDK_get_last_alias_id`, `TaloSDK_socket_disconnect`, `TaloSDK_is_initialized`, `TaloSDK_is_player_authorized`.

