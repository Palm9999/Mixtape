# Mixtape handoff

Personal-use Android app (one user, sideloaded, never published) that merges Spotify and YouTube Music playlists into one library and plays merged playlists ("mixes") across both apps.

**Design doc (source of truth):** https://claude.ai/artifact/He12hYjrF7zyNbEvnHzDGi. Read its Architecture, Data model, Screens and Auth sections before coding.

## Status

Milestone 1 code is written and builds (`./gradlew testDebugUnitTest assembleDebug` green, 4 mapper tests pass). **Not yet run on a phone**: needs the user's Spotify client ID and Google OAuth client first (see below). Next: user registers both clients, installs, checks the milestone-1 "done when"; then milestone 2.

Debug keystore SHA-1 (register with Spotify and Google): `77:94:58:84:0D:39:AE:EF:08:3C:29:6E:41:58:17:87:2C:CC:60:A8`

## Where things live (`app/src/main/java/dev/mixtape/`)

- `Db.kt`: Room entities `playlist`, `track`, `playlist_track`, DAO, database. IDs are `"<service>:<remote_id>"` strings.
- `Mappers.kt`: pure JSON → rows (`SpotifyJson`, `YouTubeJson`), tested in `src/test` against fixtures in `src/test/resources`.
- `Auth.kt`: `SpotifyAuth` (Auth lib 3.1.0, PKCE, tokens in EncryptedSharedPreferences, refresh via `accounts.spotify.com/api/token`), `YouTubeAuth` (Identity Authorization API, silent re-auth every sync).
- `Sync.kt`: paged fetch with `HttpURLConnection` + `org.json`, skip on unchanged `snapshot_id`/`etag`, rebuild `playlist_track`, delete playlists gone remotely.
- `MainActivity.kt`: Compose screens Connect / Library / Playlist, state-based navigation (no nav lib).

## Decisions made in milestone 1

- HTTP is `HttpURLConnection` + Android's built-in `org.json`, not Ktor/Retrofit: two GET endpoints don't need a client lib. `org.json` is a test dependency only, because android.jar's copy is a stub on the JVM.
- DataStore and WorkManager not added yet (queue state is milestone 4, matching backlog is milestone 3).
- Credential Manager skipped: the Authorization API alone shows the account picker and returns the token.
- Spotify client ID comes from Gradle property `spotifyClientId` (`gradle.properties` or `~/.gradle/gradle.properties`) into `BuildConfig`. Not a secret.
- AGP 8.13.2 + Gradle 8.14.3 + compileSdk 36. Newest AndroidX (Compose BOM 2026.x, activity 1.13) needs AGP 9.1 and compileSdk 37, so versions are pinned one step back. Upgrading means moving to AGP 9 (built-in Kotlin, drop the `kotlin.android` plugin).
- No playlist art shown yet (`art_url` stored); add an image loader when the UI needs it.

## Gotchas

- Spotify Feb 2026 API: endpoint used is `/playlists/{id}/items`; the mapper accepts both `item` and `track` keys per row because the migration guide could not be fetched from this environment to confirm. If Spotify returns 403 on a playlist's items (Dev Mode may restrict to owned/collaborative playlists), sync stops with that error: check this first on the phone.
- Test fixtures are hand-written in the documented response shapes, not recorded from live calls. Replace with real recorded responses once sign-in works.
- Cloud sessions: Android SDK comes from `dl.google.com`; a personal `mixtape` cloud environment allows it. SDK installed at `/opt/android-sdk` with `local.properties` pointing there (not committed).

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
- Google Cloud project with YouTube Data API v3, OAuth consent screen in Testing with the user as test user, Android OAuth client (package + SHA-1). SHA-1 above.

## Rulings that bind

- Personal use only: no store listing, no extended quota, no backend, no client secrets in the APK.
- Spotify plays through App Remote; YouTube plays in the YouTube Music app via its media session (milestone 5). No embedded YouTube player.
- Mixes are local only; nothing is written back to either service in v1.
- Keep it to one module and the fewest files that work.
