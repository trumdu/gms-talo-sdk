# Cursor Talo Checklist

Use this checklist before/after any change in `extensions/TaloSDK`.

## Before coding

- Confirm endpoint requirements in PDF docs (`http/*.pdf`).
- Confirm which headers are required:
  - always `Authorization: Bearer <access_key>`
  - if player context: `x-talo-player`, `x-talo-alias`, `x-talo-session` (when required)
- Confirm expected async `talo_op` name and response fields.

## During implementation

- Keep Social Async contract unchanged:
  - `id=9829`, `talo_op`, `success`, `http_code`, `body`, `error`, `talo_auto`
- Parse both root and `data` response objects when relevant.
- Handle missing optional fields safely (no crashes).
- Preserve existing operation names (`talo_op`) for backward compatibility.
- Do not use first leaderboard entry as "current player" score.
- For player score operations, match current player explicitly (`playerId` / `playerAlias.player.id`).

## add_score specific

- Flow must be: `GET player entry -> compute new score -> POST new score`.
- Use `player_id` filter in GET.
- If player entry is missing, fallback previous score to `0` (unless product rule differs).
- Remember this flow is non-atomic; avoid parallel updates for same player if possible.

## Error/rate-limit handling

- Surface HTTP failures through async (`success=0`, `http_code`, `error`, `body`).
- Expect `400` for missing required params/headers.
- Respect rate limits:
  - ~100 req/min per player for regular APIs
  - ~20 req/min per player for auth APIs

## After coding

- Verify exported function exists in `TaloSDK.yy` with correct `externalName`.
- Verify GML call sites still match function signature.
- Verify async handlers in GML still catch new/updated `talo_op`.
- Run lint on touched files.
- Smoke test:
  - init/identify
  - leaderboard get
  - leaderboard post / set_score / add_score
  - UI reaction (toast/pending message)

