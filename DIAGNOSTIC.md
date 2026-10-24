# Full-App Diagnostic — 2026-10-24

Scope: all 50 Kotlin files (~13.7k lines) under `app/src`, including the uncommitted artist-blacklist work.
Build: `compileDebugKotlin` ✅ · unit tests 32/32 ✅ (only deprecation warnings).
Paths are relative to `app/src/main/java/com/kiki/spotifymixer/`. ✔ = re-verified by hand after the audit.

---

## 🔴 Critical

| # | Issue | Where | Effect |
|---|---|---|---|
| C1 ✔ | `@Insert(REPLACE)` on tracks + `ON DELETE CASCADE` on `playlist_tracks` | `TrackDao.kt:13,16`, `PlaylistTrackCrossRef.kt:16-22` | SQLite REPLACE = delete+insert → cascade wipes the track from **every playlist incl. Liked Songs**. Fires on every song played (`PlayerViewModel.kt:622,703`), queue load (`QueueViewModel.kt:173,191`), liking (`SpotifyMixerRepository.kt:33`), sync. Also resets `addedAt`/`popularity`/`isHiddenGem`. **Fix: `@Upsert`.** |
| C2 ✔ | Same pattern on playlists | `PlaylistDao.kt:13,16` | Every sync (`SpotifyCloudService.kt:288`) wipes all cached playlist tracks, including local reorders. |
| C3 ✔ | Android Auto service crashes on bind | `auto/KikiMediaBrowserService.kt:59,63` | `setSessionToken` is called twice (inside `apply{}` and again after) → `IllegalStateException`. |
| C4 | Android Auto never plays audio | `KikiMediaBrowserService.kt:236-256` | `playCurrentTrack()` only sets metadata/state; no Web API call. Dashboard shows "Playing" in silence. "Your Playlists" always empty. |

## 🟠 High

**Playback / service**
- H1 — Headless auto-advance can crash with `ForegroundServiceDidNotStartInTimeException`: `startForeground` only runs after a successful network call (`KikiPlaybackService.kt:168-175, 290-335`).
- H2 ✔ — Swiping the app away / Stop doesn't cancel the track-end alarm (`KikiPlaybackService.kt:483-492`, no `am.cancel` anywhere in the service) → music restarts and chains indefinitely.
- H3 — Static callbacks (`TrackTransitionReceiver.onTrackEndTriggered`, `KikiPlaybackService.onSkipNextAction` etc.) never cleared → point at dead ViewModels, fallback never runs.
- H4 — Background playback uses the raw stored token, never refreshed (`KikiPlaybackService.kt:296,313,522`; `playTracks`/`pause`/`resume`/`seek` skip `getValidToken`) → auto-advance dies after ~1h.
- H5 ✔ — `playTracks` maps 401 → `Error(401)` not `Unauthorized` (`SpotifyCloudService.kt:457-478`), so the refresh-and-retry in `PlayerViewModel.kt:175` never fires for play.

**App shell**
- H6 ✔ — ViewModels are constructed manually in `MainActivity.onCreate` (`:55-59`), no `ViewModelProvider`. Each rotation creates a new set; old ones never cleared → duplicate 2s pollers, double track advances, Activity leaks, queue reset to Liked Songs.
- H7 — Battery-optimization prompt fires on every `onCreate` (`MainActivity.kt:62`).
- H8 — Queue heart **unlikes on Spotify with no confirmation** (`AppScaffold.kt:331`, `PlayerViewModel.kt:435-451`), while `UnlikeConfirmationDialog` exists but is never used. Like behavior differs per screen (toggle vs save-only).
- H9 — "Backup ready" dialog shows even when the backup failed (`LibraryViewModel.kt:255-276`); Share then silently does nothing.
- H10 ✔ — `assets/initial_library.json` (715 KB, 1735 tracks, 14 playlists) seeds a personal library into every install (`MainActivity.kt:216-333`).
- H11 — Logout incomplete (`AppScaffold.kt:454-457`): Discover/Queue keep in-memory tokens; DB library, queue, code verifier stay.

**Data / backup**
- H12 ✔ — Safety backup still uses deprecated `GET /playlists/{id}/tracks` (`LibraryBackupManager.kt:330`) → 403 → playlists backed up empty but reported success. Errors mid-pagination swallowed; file is only written once and never refreshed.
- H13 — Queue reorder overwrites `liked_songs`/`all_tracks` cross-refs locally (`QueueViewModel.kt:257-263, 278-284`) despite comment saying "custom playlists only".

**Auth / API**
- H14 ✔ — `/me/player/recently-played` needs `user-read-recently-played`, missing from `SpotifyPkceAuthManager.SCOPES` → always 403, "recently heard" filter never works.

**Discover**
- H15 — Catalog Search ignores the blacklist entirely (`DiscoverViewModel.kt:336-452`, plus all autocomplete paths). The new Block button there removes nothing and shows no feedback (banner only renders in Surprise Me).
- H16 ✔ — Hidden Gems is non-functional: no cloud parser reads `popularity` → everything defaults to 50 → filter fails → fallback badges **every** track as a gem (`DiscoverViewModel.kt:1075-1092`). Badge threshold (`<=42`, `DiscoverScreen.kt:1377`) also disagrees with filter (45/48).

## 🟡 Medium

**Auth / network**
- Six independent token-refresh implementations, no mutex (`MainActivity:147`, `SpotifyCloudService:714/736`, each VM's `getValidAccessToken`). Parallel Discover searches race refreshes; `expires_in` hard-coded 3600; on refresh failure stale token is returned forever.
- Login CSRF: exported BROWSABLE `kikispotifymixer://callback#access_token=…` is accepted with no `state` (`MainActivity.kt:121-125`) — dead legacy branch, delete it.
- Loopback auth server binds `0.0.0.0` not loopback (`SpotifyAuthServer.kt:31`) → LAN DoS of login; error param echoed unescaped (`:251`).
- `onDestroy` always calls `SpotifyPkceAuthManager.cancel()` (`MainActivity.kt:178`) → login dies on rotation / process reclaim while in browser.
- Gson `items.get(i)?.asJsonObject` throws on `JsonNull` → partial results; Spotify playlist search routinely returns nulls (`SpotifyCloudService.kt:1175` + ~12 sites).
- Pagination: partial page failure overwrites local playlist with partial list (`:324-388`); `syncLibrary` truncates silently and never removes deleted playlists; `replacePlaylistTracks` can leave remote playlist truncated; episode URIs get double-prefixed (`:993`).
- No 429 / `Retry-After` handling anywhere; Discover fans out up to ~60 concurrent searches.
- `syncLibrary` still uses `GET /me/tracks` (`:115`) — inconsistent with the `/me/library` migration.
- Full library sync runs on most cold starts (refresh → `saveAndApplyToken` → `syncLibrary`).

**Data**
- Like race: `saveTrackToLiked` check-then-insert outside a transaction → duplicates; `total_tracks` drifts from `COUNT(*)` (`SpotifyMixerRepository.kt:32-57,120-158`).
- Empty Liked Songs falls back to the *entire library* (`SpotifyMixerRepository.kt:65-73`, duplicated in `QueueViewModel.kt:131-142`) — combined with C1, Auto/service play everything as "Liked".
- `fallbackToDestructiveMigration()` + `exportSchema=false` (`AppDatabase.kt:81`): any missing migration silently wipes tokens, blacklist, custom playlists. (The new 2→3 blacklist migration itself is correct.)
- Duplicate-count query groups by title+artist but matches on title only (`TrackDao.kt:50-57`).
- `stateIn(viewModelScope).value` inside suspend funcs leaks a collector per call (`LibraryViewModel.kt:169,201,266,287`) → use `.first()`.

**Playback**
- Track end can trigger twice in one poll (`PlayerViewModel.kt:600-603` and `:648-650`).
- After Spotify autoplay / external track change, `indexOfFirst == -1` → queue restarts at track 1 (`AppScaffold.kt:106-107`); duplicate IDs loop.
- Headless fallback ignores `EXTRA_TRACK_ID`, uses liked songs instead of queue after process death.
- Pause on exit probably lost (VM already cleared; service scope cancelled right after launch).
- Volume slider does nothing (`PlayerViewModel.setVolume` is local only; poll overwrites it).
- Expanded-player Shuffle / Anti-clump toggles only change icon tint; three separate anti-clump flags.
- Four different "next track" implementations; `onPlayNext` uses the *search-filtered* list.
- `KikiMediaBrowserService` exported with no caller check → any app can browse liked songs.

**Discover**
- `getPrimaryArtist` splits band names ("Simon & Garfunkel" → "Simon", "Tyler, The Creator" → "Tyler") → blacklist mismatches between library and cloud rows (`SearchUtils.kt:226-232`).
- Block during in-flight generation is lost (blacklist snapshotted at `DiscoverViewModel.kt:739`, mix overwritten at `:1148`).
- Fuzzy exclude over-prunes: excluding "Drake" removes "James Blake"; "One" removes "Money" (`:783-793`).
- Genre include/exclude is matched against **album name** (`:788,832,1064`).
- Three dedup rules (id / normalized / raw title-artist); same id from two sources can crash `LazyColumn` (`key = { it.id }`, `DiscoverScreen.kt:1329`).
- Per-artist cap re-implements primary-artist logic locally and ignores `track.primaryArtist` (`:1100-1131`).

**Library / UI**
- Language selector does nothing (local `remember`, never read; `Strings.kt` is Spanish-only).
- "TUS PLAYLISTS" shows system rows `liked_songs`/`all_tracks`, count +2 (`LibraryScreen.kt:223,240`).
- "Append to Queue" and "Duplicates" card are wired to empty lambdas.

## ⚪ Low
- Blacklist unique index is case-sensitive ("Drake" + "drake" both stored); block toast shown even when insert ignored.
- `allowBackup="true"` with no rules → tokens in Drive/adb backups; token response body logged on failure (`SpotifyPkceAuthManager.kt:220`).
- Login screen flashes on every launch (`isLoggedIn` default false).
- Overwrite-playlist skips confirmation for ≤3 tracks; non-owned playlists offered as targets.
- JSON bodies built by string concat (`createPlaylist` escapes only `"`).
- `POST_NOTIFICATIONS` never requested at runtime; both `SCHEDULE_EXACT_ALARM` and `USE_EXACT_ALARM` declared (Play policy).
- Shuffle ignores the queue lock; anti-clump look-ahead is 8 not 10 as commented.
- Catalog search catches `CancellationException` and logs false errors.
- Status poll hits the API every 2s forever, including background.
- Hard-coded EN/ES strings mixed across screens despite `Strings.kt`.
- `isMinifyEnabled=false`; proguard rules target the wrong Spotify package.
- Two dialog packages (`ui/dialogs`, `ui/screens/dialogs`).

---

## 🧪 Tests that test copies, not the app
`DiscoveryFilterTest.kt` never calls `DiscoverViewModel` — every test re-implements the logic inline. Several assert behavior production no longer has (depth-2 recursion at `:215`, uncapped orbit harvest at `:128`), and fixture IDs (`"d1"`, `"ts1"`) are <15 chars so production `isAllowedCandidate` would reject them. Passing tests here give false confidence. `SearchUtilsTest` and `ShuffleEngineTest` do test real code.

---

## 🧹 Stale / dead code

**Auth:** `SpotifyAuthHelper` (whole object except `REQUEST_CODE`), `MainActivity.onActivityResult`, implicit-token branch of `handleAuthIntent`, `exchangeDirectCode` + deep-link redirect path. Only the PKCE-loopback flow is live.

**SpotifyCloudService:** `skipToNext`, `skipToPrevious`, `setRepeatMode`, `setShuffle`, `setVolume`, `fetchAudioFeatures` (deprecated endpoint), `searchArtists`, `fetchRelatedArtists` (last caller removed in 480b47c — the "genre-validated related artists" feature is unused), `allExistedOnPage`, `activeAccessToken`. ~7 copies of track-JSON→`TrackEntity` parsing.

**Repository / DAOs:** `getTracksForPlaylist` (Flow), `searchTracks`, `renamePlaylist`, `deletePlaylist`, `addTrackToPlaylist`, `removeTrackFromPlaylist`, `getOrderedTrackIds`, `clearArtistBlacklist`, `clearAllData`; `TrackDao.upsertTrack/getTrackById/deleteTrack`; `SettingDao.deleteSetting/getAllSettings`; `PlaybackHistoryDao.getRecentlyPlayedTracks` (history never pruned); `ArtistBlacklistDao.getAllBlacklisted/getBlacklistCount`; `ArtistBlacklistEntity.spotifyId` never set; `TrackEntity.addedAt` never read. `LibraryBackupManager` ctor params `cloudService`/`repository` unused.

**ViewModels:** `LibraryViewModel.removeArtistFromBlacklist(String)` / `removeArtistFromBlacklistById`; `DiscoverViewModel.saveTrackToLiked`, the four `remove*Modifier(String)` overloads, `toggleGenreModifier`, `selectedGenreCategory`/`setSelectedGenreCategory`, private `normalize()`, `blockArtist(onBlocked)`; `QueueViewModel.onQueueOrderChanged` (never assigned), `getNextTrack`, `getPrevTrack`, `removeTrack`, `reorderTracks(Int,Int)`, selection-mode state, `isShuffled`; `PlayerViewModel.playQueue`, `seekRelative`, `setProgress`.

**UI / misc:** `UnlikeConfirmationDialog`; `GenreCatalog.CATEGORIES`; 9 unused `Strings` keys (`BtnPlay`, `QueueEmpty`, `NoActivePlayback`, `ToastLikedSuccess`, `ToastLikedAlready`, `SearchPlaceholder`, `CategoryTabsLabel`, `BtnBlockArtist`, `ToastArtistUnblocked`); 4 unused colors; `Theme(darkTheme)` param unused; unused deps: `navigation-compose`, `okhttp-logging`, `lifecycle-viewmodel-compose`, `lifecycle-runtime-compose`, `fileTree("libs")`.

---

## Fix plan (agreed criteria: your priorities → Spotify-data safety → small/easy → complex → new features)

Decisions so far: instant unlike is intended (delete the dialog); blacklist applies to Surprise Me only; backup is top priority; the bundled library is replaced by restore-from-backup (the asset stays until restore ships).

The only writes this app makes to Spotify: like/unlike, create playlist, replace playlist tracks (Save list → overwrite). Nothing deletes playlists. So the danger to Spotify data is **overwriting a playlist with an incomplete queue**.

**Phase 0 — Blacklist cleanup (uncommitted work)**
- Case-insensitive unique name (`COLLATE NOCASE`); the 2→3 migration hasn't shipped yet, so it can still change freely.
- Re-filter the mix after generation so a block made mid-generation isn't lost.
- Use `track.primaryArtist` (from Spotify) wherever it exists; stop splitting band names on `&` and `,`.
- Catalog Search: no filtering. Optionally sink blacklisted artists to the bottom with a stable sort. The Block button there gets visible feedback.
- Remove the unused blacklist helpers.

**Phase 1 — Backup (your priority)**
- Use the `/items` endpoint and read `item ?: track`.
- Any failed page fails the whole backup; retry on 401 and 429.
- Write a new timestamped file on every run.
- Show the "backup ready" dialog only on success.
- Include local-only data (blacklist, custom playlists, non-secret settings) so the file can later be restored.

**Phase 2 — Protect Spotify playlists from bad overwrites**
- `REPLACE` → `@Upsert` (C1/C2): the cascade corrupts the local playlist cache that the queue loads from.
- `fetchPlaylistTracks` must fail on a partial page instead of returning a partial list (it currently overwrites the cache and feeds the queue).
- Remove the fallback that loads the whole library when Liked Songs is empty.
- Stop queue reorders from rewriting `liked_songs`/`all_tracks` locally.
- `replacePlaylistTracks`: don't leave the playlist truncated when a later append fails; fix the episode-URI double prefix.
- Overwrite: always confirm; show a before → after track count; refuse playlists you don't own (check `owner.id` at overwrite time).

**Phase 3 — Small, easy fixes**
- **Auth and tokens:** map 401 → `Unauthorized` in `playTracks`; add the `user-read-recently-played` scope (one re-login); bind the auth server to loopback; delete the implicit-token / CSRF branch and the dead `SpotifyAuthHelper`.
- **App and services:** cancel the track-end alarm on swipe-away/Stop; ask for battery optimization only once; fix the double `setSessionToken` (or hide Android Auto).
- **Data and parsing:** handle `JsonNull` in the parsers; fix the duplicate-count query; replace `stateIn().value` with `first()`; make logout clear the Discover/Queue tokens.
- **UI:** hide system rows from "Tus playlists"; hide the Append/Duplicates/Language controls that do nothing; align the Hidden Gems badge threshold; delete `UnlikeConfirmationDialog`; dead-code sweep.

**Phase 4 — Complex fixes**
- **ViewModels:** let the system own them (`ViewModelProvider`; `rememberSaveable` tab state).
- **Tokens:** one lock-guarded `TokenManager`, replacing the 6 refresh copies; service playback refreshes tokens.
- **Playback:** fix the foreground-service crash in auto-advance; clear stale static callbacks; guard against a double track-end trigger; one "next track" implementation.
- **Network:** 429 / `Retry-After` handling, and a limit on parallel Discover calls.
- **Discover:** read popularity (Hidden Gems), match genres on artist genres instead of album titles, tighten the fuzzy exclude.
- **Database:** `exportSchema = true` plus migration tests; drop `fallbackToDestructiveMigration`.
- **Tests:** rewrite `DiscoveryFilterTest` so it runs the real ViewModel logic.

**Phase 5 — New features**
- Restore from backup file → then remove `initial_library.json` and the seeding code; Auto Backup rules that exclude the tokens.
- Working volume slider, expanded-player shuffle toggles, Append to Queue, Duplicates view, EN language.
- Android Auto with real playback.
