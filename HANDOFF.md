# Mixtape handoff

Personal-use Android app (one user, sideloaded, never published) that merges Spotify and YouTube Music playlists into one library and plays merged playlists ("mixes") across both apps.

**Design doc (source of truth):** https://claude.ai/artifact/He12hYjrF7zyNbEvnHzDGi

## Status

All six milestones of the build plan are built and merged to `main` (PR #1). `./gradlew testDebugUnitTest lintDebug assembleDebug` is green (13 JVM tests, 0 lint errors). GitHub Actions runs the same on every push to `main` and on every PR, and uploads the debug APK as the `mixtape-debug-apk` artifact.

**The user installed the APK and reported "the app works" (2026-11-13).** They gave no detail on which flows they exercised, so do not assume every item below was checked. Ask before relying on any one of them:

- screen-off advance for Spotify and for YouTube Music
- Spotify to YouTube handoff and back inside a mix
- match review, and a linked YouTube track playing from Spotify
- whether Spotify refused the tracks of any playlist (Development Mode limits)

## Feature plan (agreed with the user 2026-11-13, one PR per step, in this order)

1. **Mix backup/restore**: built, see below.
2. Shuffle, repeat, sleep timer (in `Player`, order saved in the existing SharedPreferences queue entry).
3. Unified search over both libraries (one `LIKE` query, results reuse the add-to-mix dialog).
4. Matching backlog under WorkManager.
5. Play history and stats. Steps 5 to 7 share **one** Room migration v2 to v3: a `play_event` table, `rules` on `mix`, `added_at` on `playlist_track`.
6. Smart mixes (rules as JSON on the mix; "never played" needs step 5).
7. Library cleanup screen (duplicates across services, YouTube tracks with no Spotify match).
8. Mixtape's own media session. Reverses a decision below; build as a spike and drop it if headset buttons misbehave on the phone. The user has not yet confirmed this.
9. Home-screen widget (plain `AppWidgetProvider`, reusing the service intents).
10. Export a mix to Spotify / YouTube. Needs the "nothing is written back" ruling lifted by the user, and write scopes. Not yet confirmed.

Backup (step 1): Accounts screen, "Back up mixes" / "Restore", through the system file picker. The file (`BackupJson` in `Mappers.kt`, version 1) holds mixes, their items, the tracks they use, and `manual`/`reviewed` links. `LibraryDao.restore` only adds: a mix with the same name and creation time is skipped, existing track rows and the user's own link decisions are kept. Not yet tried on the phone.

Other candidates, not requested: tune the matching thresholds (0.85 / 0.6 in `Matcher.kt`) on the real library; replace the hand-written mapper fixtures with recorded API responses; move to AGP 9.1 / compileSdk 37 to unpin AndroidX.

Workflow: branch from `main`, open a PR, and merge only when the user asks.

The Spotify client ID is in `gradle.properties`. Debug keystore SHA-1 (registered with Spotify and with the Google Android OAuth client): `77:94:58:84:0D:39:AE:EF:08:3C:29:6E:41:58:17:87:2C:CC:60:A8`.

## Where things live (`app/src/main/java/dev/mixtape/`)

- `Db.kt`: Room schema v2: `playlist`, `track`, `playlist_track`, `track_link`, `mix`, `mix_item`, and the DAO. IDs are `"<service>:<remote_id>"`.
- `Mappers.kt`: JSON to rows for both APIs (pure, tested).
- `Matcher.kt`: title cleanup, scoring, mix dedupe and interleave (pure, tested in `MatcherTest`).
- `Sync.kt`: paged read-only sync of both libraries, Spotify search, the matching backlog.
- `Auth.kt`: `SpotifyAuth` (PKCE, tokens in EncryptedSharedPreferences), `YouTubeAuth` (Identity Authorization API).
- `Player.kt`: the queue engine. The only code that touches playback.
- `PlaybackService.kt`: `MixtapeApp` (holds the long-lived objects), the foreground service and its notification, the empty `NotificationListener`.
- `MainActivity.kt`: scaffold, route stack, mini player, add-to-mix dialogs. `Screens.kt`: Connect, Library, Playlist, Mix builder, Now Playing, Match review.

## How playback decides things (read before touching `Player.kt`)

- Each queue item resolves to Spotify (a Spotify track, or a YouTube track with a playable `track_link`) or to YouTube Music. The other app is paused first.
- Spotify: App Remote `play(uri)`, then player-state events. YouTube Music: `ACTION_VIEW` on `https://music.youtube.com/watch?v=<id>` in its package, then its `MediaController`, found through `MediaSessionManager.getActiveSessions` (this is why notification access is needed).
- "Ours has started" = the app reports our track playing. Spotify: the URI we asked for (after 5 s with no sign of it, whatever is playing is adopted, for relinked tracks). YouTube: metadata whose media ID, duration (within 2.5 s) or title matches the track (after 15 s, whatever is playing is adopted).
- "Ours has ended" = the app moved to a different track, or it paused at the end (Spotify also: paused at position 0) without Mixtape or the user asking. Mixtape then pauses that app and starts the next item.
- `gen` is bumped on every start; every callback checks it, so late events from an earlier item do nothing.
- A linked track that Spotify cannot play falls back to its YouTube audio.

## Decisions

- HTTP is `HttpURLConnection` + `org.json`; no Ktor/Retrofit. Coil loads artwork; `sh.calvin.reorderable` does drag-to-reorder.
- The App Remote AAR is committed in `app/libs/` (it is not on Maven; from the github.com/spotify/android-sdk release `v0.8.0-appremote_v2.1.0-auth`).
- Matching runs in the app's own coroutine scope after each sync, about one Spotify search per second, and resumes on the next sync. WorkManager was not added. Tracks already in the Spotify library are matched locally with no search call.
- Score = 0.5 title + 0.25 artist + 0.25 duration. `track_link.source`: `auto` (0.85 and up), `pending` (0.6 to 0.85, shown on the review screen, not used for playback), `reviewed`, `manual` (never re-matched; null Spotify ID pins the track to YouTube), `none`.
- Queue state is a SharedPreferences entry (track IDs and index), not DataStore.
- Sync keeps going when Spotify answers 403/404 for one playlist's items: the playlist is listed empty and its name is reported.
- Icons are a dozen inline path strings (`Glyphs`), not the Material icons artifact.
- AGP 8.13.2, Gradle 8.14.3, compileSdk 36. Newer AndroidX needs AGP 9.1 and compileSdk 37, so versions are pinned one step back.
- Not built, on purpose: export of a mix to a Spotify playlist (the ruling below forbids writing back in v1), and Mixtape's own media session for lock-screen controls (the playing app shows its own).

## Gotchas

- Schema: v1 is dropped on upgrade (it held only re-syncable data). Mixes exist only on the phone, so **any further schema change needs a real Room `Migration`**.
- The Spotify Feb 2026 API could not be checked from the build environment (developer.spotify.com is not reachable). The mapper accepts `item` and `track`; ISRC is read when present, and dedupe and matching work without it.
- Sideloaded APKs on Android 13+ may show notification access greyed out: App info, ⋮, "Allow restricted settings". Installing with `adb` or `./gradlew installDebug` avoids this.
- The screen-off launch of YouTube Music depends on "Display over other apps". If Android still blocks it, playback stops with an error after 15 s. This is the doc's main open risk; test it early.
- A Spotify sign-in made before playback existed lacks the `app-remote-control` scope; Spotify asks for it on first playback.
- Mapper fixtures are hand-written to the documented shapes, not recorded responses.
- The playback rules above were written from documented behavior. The user reports the app works, but which edge cases (relinked Spotify tracks, autoplay taking over, the 15 s YouTube fallback) were hit on the phone is unknown.
- Cloud sessions: the Android SDK needs `dl.google.com` (the personal `mixtape` cloud environment allows it); `local.properties` is not committed. If `/opt/android-sdk` is missing, install the command-line tools from dl.google.com, then `platforms;android-36`, `build-tools;36.0.0`, and write `sdk.dir`. Maven Central sometimes answers 429; re-run the build.

## Rulings that bind

- Personal use only: no store listing, no extended quota, no backend, no client secrets in the APK.
- Spotify plays through App Remote; YouTube plays in the YouTube Music app via its media session. No embedded YouTube player.
- Mixes are local only; nothing is written back to either service in v1.
- Keep it to one module and the fewest files that work.
