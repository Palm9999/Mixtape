# Mixtape handoff

Personal-use Android app (one user, sideloaded, never published) that merges Spotify and YouTube Music playlists into one library and plays merged playlists ("mixes") across both apps.

**Design doc (source of truth):** https://claude.ai/artifact/He12hYjrF7zyNbEvnHzDGi. Read its Architecture, Data model, Screens and Auth sections before coding.

## Status

Repo is empty. Nothing built yet. Next: milestone 1.

## Milestone 1: read both libraries

Done when, on the phone: sign in to both services, pull to refresh, and see every playlist from both in one Library list with a service badge, and open one to see its tracks.

Tasks:

1. Project skeleton: one `:app` module, Kotlin, Jetpack Compose, Room, DataStore, Ktor client (or Retrofit), minSdk 26. Package `dev.mixtape`. Debug keystore committed (personal app) so the SHA-1 is stable.
2. Room tables `playlist`, `track`, `playlist_track` (columns in the doc's Data model). Skip `track_link`, `mix`, `mix_item` until milestones 2 and 3.
3. Spotify sign-in: Spotify Auth library, PKCE code flow, scopes `playlist-read-private playlist-read-collaborative`. Refresh token in EncryptedSharedPreferences. Redirect `mixtape://callback`.
4. YouTube sign-in: Credential Manager + Google Identity Authorization API, scope `youtube.readonly`.
5. Sync: Spotify `GET /me/playlists` + `/playlists/{id}/items` (paged); YouTube `playlists.list?mine=true` + `playlistItems.list` (50 per page, then `videos.list` for durations). Skip a playlist when `snapshot_id` / `etag` is unchanged. Rebuild `playlist_track` whole per playlist.
6. Screens: Connect (two cards, sign in/out) and Library (all playlists, filter chips All / Spotify / YouTube, pull to refresh) and Playlist (track list).
7. Tests: one JVM test per mapper (Spotify JSON → rows, YouTube JSON → rows) using recorded responses in `src/test/resources`.

## Needs from the user

- Spotify client ID (Developer Dashboard, Development Mode, redirect `mixtape://callback`, package `dev.mixtape` + the debug keystore SHA-1).
- Google Cloud project with YouTube Data API v3, OAuth consent screen in Testing with the user as test user, Android OAuth client (package + SHA-1). Give them the SHA-1 after task 1.

## Rulings that bind

- Personal use only: no store listing, no extended quota, no backend, no client secrets in the APK.
- Spotify plays through App Remote; YouTube plays in the YouTube Music app via its media session (milestone 5). No embedded YouTube player.
- Mixes are local only; nothing is written back to either service in v1.
- Keep it to one module and the fewest files that work.
