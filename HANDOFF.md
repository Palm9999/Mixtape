# Mixtape handoff

Personal-use Android app (one user, sideloaded, never published) that merges Spotify and YouTube Music playlists into one library and plays merged playlists ("mixes") across both apps.

**Design doc (source of truth):** https://claude.ai/artifact/He12hYjrF7zyNbEvnHzDGi

## Status

All six milestones of the build plan are written: library sync, mixes, matching, Spotify playback, YouTube Music playback, polish. `./gradlew testDebugUnitTest lintDebug assembleDebug` is green (10 JVM tests, 0 lint errors). GitHub Actions runs the same on every push and uploads the debug APK as the `mixtape-debug-apk` artifact.

**Nothing has been run on a phone.** Sign-in, sync and above all playback are untested against the real apps. The next step is a phone test, in this order:

1. Install the APK. Accounts screen: sign in to Spotify and YouTube, grant notification access and "Display over other apps".
2. Library: playlists from both services appear; open one.
3. Play a Spotify playlist: tracks advance on their own, with the screen off too.
4. Play a YouTube playlist: YouTube Music opens, Mixtape comes back to the front, the next track starts when one ends.
5. Build a mix from one playlist of each service and play it through a Spotify to YouTube handoff and back.
6. Review screen: accept or reject a few matches; a linked YouTube track should then play from Spotify.

The Spotify client ID is in `gradle.properties`. Debug keystore SHA-1 (given to Spotify; the Google Android OAuth client needs the same one): `77:94:58:84:0D:39:AE:EF:08:3C:29:6E:41:58:17:87:2C:CC:60:A8`. Whether the user finished the Google Cloud setup is not confirmed.

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
- Cloud sessions: the Android SDK needs `dl.google.com` (the personal `mixtape` cloud environment allows it); SDK at `/opt/android-sdk`, `local.properties` not committed.

## Rulings that bind

- Personal use only: no store listing, no extended quota, no backend, no client secrets in the APK.
- Spotify plays through App Remote; YouTube plays in the YouTube Music app via its media session. No embedded YouTube player.
- Mixes are local only; nothing is written back to either service in v1.
- Keep it to one module and the fewest files that work.
