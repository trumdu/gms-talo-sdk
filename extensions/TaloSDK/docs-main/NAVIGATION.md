# Cursor Navigation Index for Talo Docs

This file is a quick map for Cursor to find the right PDF fast.

Use this with:
- `ReadFile` for direct reading of a known PDF
- `WebFetch` not needed (local docs only)

## Fast Route (What to open first)

- HTTP route → GameMaker function index -> `TALO_API_GUIDE.md` §8 (synced with https://api.trytalo.com/public/docs)
- Полный прогон всех 58 HTTP-маршрутов с живого сервера -> `../tools/test_routes.mjs` (`node extensions/TaloSDK/tools/test_routes.mjs`)
- Auth/header issues -> `http/authentication.pdf`, then `http/common-errors.pdf`
- Leaderboard/add_score logic -> `http/leaderboard-api.pdf`
- Player identify/context -> `http/player-api.pdf`
- Register/login/session -> `http/player-auth-api.pdf`
- Socket flow/realtime -> `sockets/intro.pdf` then `sockets/requests.pdf` and `sockets/responses.pdf`
- ImFriend multiplayer (this project) -> `TALO_API_GUIDE.md` (HTTP game-channels + storage; socket events via Social async `talo_op` = `socket`)

## HTTP API PDFs

- `http/authentication.pdf` - global auth model and required headers
- `http/common-errors.pdf` - status codes, auth errors, missing params, rate-limits
- `http/dev-data.pdf` - developer/test data conventions
- `http/event-api.pdf` - event tracking endpoints
- `http/game-channel-api.pdf` - channels, membership, channel storage
- `http/game-config-api.pdf` - remote game config
- `http/game-feedback-api.pdf` - feedback categories and create feedback
- `http/game-save-api.pdf` - game saves CRUD
- `http/game-stat-api.pdf` - stats endpoints and player/global stat history
- `http/leaderboard-api.pdf` - leaderboard entries GET/POST, filters, pagination
- `http/player-api.pdf` - identify/search/get/merge/player props
- `http/player-auth-api.pdf` - register/login/refresh/verify/password/email/identifier flows
- `http/player-group-api.pdf` - player groups endpoints
- `http/player-presence-api.pdf` - online/custom presence
- `http/player-relationships-api.pdf` - subscriptions/subscribers/confirm/delete
- `http/socket-ticket-api.pdf` - socket ticket creation

## Sockets PDFs

- `sockets/intro.pdf` - socket model and lifecycle
- `sockets/requests.pdf` - client request messages
- `sockets/responses.pdf` - server response messages/events
- `sockets/custom-ping-pongs.pdf` - ping/pong behavior
- `sockets/common-errors.pdf` - socket-side error handling

## Task-to-Docs Mapping

- "Why identify/auth fails?" -> `http/authentication.pdf`, `http/player-auth-api.pdf`, `http/common-errors.pdf`
- "How to post leaderboard score?" -> `http/leaderboard-api.pdf`
- "How to query current player leaderboard entry?" -> `http/leaderboard-api.pdf` + `http/player-api.pdf`
- "Which headers are mandatory for this endpoint?" -> `http/authentication.pdf` + endpoint PDF
- "Rate limit/retry behavior?" -> `http/common-errors.pdf`
- "How to keep online presence/socket alive?" -> `http/player-presence-api.pdf`, `sockets/intro.pdf`

## Notes for Cursor Edits

- Keep SDK async contract stable: `id=9829`, `talo_op`, `success`, `http_code`, `body`, `error`.
- For leaderboard score increment logic, always verify docs in `http/leaderboard-api.pdf` before changing behavior.
- If docs and current implementation conflict, prefer docs and update both Java and GML handlers consistently.
