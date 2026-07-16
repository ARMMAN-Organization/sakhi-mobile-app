# Test Cases — Login API Integration (br/login-api-integration)

**Scope:** replace `StaticAuthRepository` with a real `auth-service` client (`POST /api/v1/auth/login`),
add persisted "stay logged in" session, JWT claims decode for profile/role, and offline
re-login via a locally cached credential hash. **Token refresh (item 7) is deferred** —
this branch stores the refresh token but does not yet call a refresh endpoint; that
lands in a follow-up once the endpoint contract is confirmed.

**Confirmed from a real response/token (2026-07-16), decisions locked in:**
- Response is `{success, message, data:{accessToken, refreshToken}}` — **no `expiresIn` field**; expiry comes from the access token's own `exp` claim.
- JWT payload is `{sub, roles:[...], projectId, geographyUnitId, iat, exp}` — **no username or display-name claim**. Display name = the username typed at login, carried forward locally; no `/me` call for now.
- `roles` is a plural array. This app **enforces `"SAKHI"` must be present** in `roles`, rejecting login otherwise (`WRONG_ROLE`) — matches the SRS's strict per-app role separation (Sakhi/Supervisor/Manager/Admin/Analyst are different apps).

**Cross-checked against the QA tracker's "Login Test Cases" tab (59 cases, 2026-07-16) — explicitly out of scope for this branch:**
- **Account lockout (TC_12, TC_40–46)** — not in the API, not in the SRS. Not built here; revisit as its own CR if/when the backend adds it.
- **Username/password max-length and special-character restrictions (TC_20–23)** — no defined rule anywhere; not invented here. The app relies on the server's 400 validation response (`RA-3`) rather than duplicating business rules client-side.
- Most UI/visual cases (TC_01–06, 34, 35, 47–51) and general robustness cases (TC_08, 25, 36, 48–58) are either already implemented on the existing Login screen or are inherent to the platform (rotation/config-change survival via ViewModel, Android battery-saver behavior) — not re-tested here.
- **Every new string resource this branch adds** (network error, wrong-role, offline-no-cache messages) ships in both English and Marathi, per the existing app-wide convention — not itemized as separate test cases, just a delivery checklist item.
- **TC_28 (access after logout via deep link)** — flagged as a manual/nav-integration check outside JVM unit-test scope (this repo has no nav-integration test harness, matching the existing `beneficiary-profile.md` NAV-* section precedent); verify manually before merge that the nav graph blocks protected routes once logged out.

**Files under test:**
`data/auth/AuthApi.kt` (new) · `data/auth/RemoteAuthRepository.kt` (new) ·
`data/auth/JwtClaimsDecoder.kt` (new) · `data/auth/session/SessionStore.kt` (new) ·
`data/auth/session/OfflineCredentialCache.kt` (new) · `ui/login/LoginViewModel.kt` (updated) ·
`data/auth/AuthModels.kt` (updated: `userId` → `username`).

**Type:** JVM unit tests only (matches current repo convention — no `androidTest` Compose UI setup yet).

Status legend: ☐ not yet implemented · ☑ implemented & passing.

---

## 1. `RemoteAuthRepositoryTest` — API call + response/error mapping

| # | Name | Given / When / Then |
|---|---|---|
| RA-1 | ☐ `login success maps token payload` | Given API returns 200 `{success:true, message:"OK", data:{accessToken, refreshToken}}` (no `expiresIn` field — confirmed via real response); When `login()`; Then `LoginResult.Success` with matching token fields, expiry derived from the access token's `exp` claim. |
| RA-2 | ☐ `login sends username and password fields` | When `login(LoginRequest(username, password))`; Then request body has keys exactly `username`/`password` (no `userId`). |
| RA-3 | ☐ `400 validation error maps to VALIDATION_ERROR` | Given API returns 400 `{success:false, errorCode:"VALIDATION_ERROR", ...}`; Then `LoginResult.Failure(VALIDATION_ERROR)`. |
| RA-4 | ☐ `401 invalid credentials maps to INVALID_CREDENTIALS` | Given API returns 401; Then `LoginResult.Failure(INVALID_CREDENTIALS)`, regardless of the (currently generic) `errorCode` value in the body. |
| RA-5 | ☐ `network/timeout failure maps to NETWORK_ERROR` | Given the call throws `IOException`/`SocketTimeoutException`; Then `LoginResult.Failure(NETWORK_ERROR)` (new enum value), never an unhandled exception. |
| RA-6 | ☐ `unexpected 5xx or malformed body maps to UNKNOWN` | Given 500 or unparseable JSON; Then `LoginResult.Failure(UNKNOWN)`. |
| RA-7 | ☐ `successful login writes offline credential hash` | Given RA-1 success; Then `OfflineCredentialCache.store(username, password)` was invoked exactly once (verify via fake/mock). |
| RA-8 | ☐ `successful login persists session via SessionStore` | Given RA-1 success; Then `SessionStore.save(...)` invoked with the returned token pair + expiry (from `exp`) + decoded claims. |
| RA-9 | ☐ `login rejected when roles does not contain SAKHI` | Given a decoded token with `roles:["SUPERVISOR"]` (or any set not containing `"SAKHI"`); Then `LoginResult.Failure(WRONG_ROLE)` (new enum value) — this app enforces SAKHI-only per the SRS's per-app role separation, even though the HTTP call itself succeeded. |
| RA-10 | ☐ `login accepted when roles contains SAKHI among others` | Given `roles:["SAKHI","SUPERVISOR"]`; Then success — presence of SAKHI is sufficient, other roles present don't block this app. |

## 2. `JwtClaimsDecoderTest` — access-token claims decode

Real decoded payload shape (confirmed from a live token): `{sub: "<uuid>", roles: ["SAKHI"], projectId: string|null, geographyUnitId: string|null, iat, exp}`. **No `username` or display-name claim exists** — the app must carry the username entered at login forward itself rather than expect it from the token.

| # | Name | Given / When / Then |
|---|---|---|
| JW-1 | ☐ `decodes sub roles project and geography from valid JWT` | Given a hand-built JWT with the real payload shape; When decoded; Then `subjectId`, `roles` (list), `projectId`, `geographyUnitId` all match, computed `expiresAtEpochSeconds == exp`. |
| JW-2 | ☐ `null projectId and geographyUnitId decode as null, not crash` | Given payload has `projectId: null, geographyUnitId: null` (the real test-account shape); Then both decode to null cleanly — this is the expected common case for a not-yet-assigned Sakhi, not an error state. |
| JW-3 | ☐ `roles missing entirely yields empty list, no crash` | Given payload has no `roles` key at all (defensive — contract says it's always present, but decode must not throw if it's absent); Then empty list, caller's RA-9 check then correctly fails as WRONG_ROLE. |
| JW-4 | ☐ `malformed token (not 3 segments) throws a typed decode error` | Given a garbage string; Then a specific `JwtDecodeException`, not a generic crash — caller maps this to `LoginFailureReason.UNKNOWN`. |
| JW-5 | ☐ `decoder never needs network` | Sanity: decoder is a pure function of the token string (no repository/network dependency in its constructor). |
| JW-6 | ☐ `decoder does not verify RS256 signature` | Documents a deliberate scope limit: this decoder only reads claims for UI/session purposes; it does not (and cannot, without the server's public key) verify the signature. Server-side validation is the security boundary; note this explicitly in code comments so it's never mistaken for auth verification. |

## 3. `SessionStoreTest` — encrypted session persistence

| # | Name | Given / When / Then |
|---|---|---|
| SS-1 | ☐ `save then read returns identical session` | Given a `UserSession` saved; When read back (new store instance, same backing file); Then all fields match. |
| SS-2 | ☐ `no session saved returns null` | Given a fresh install (no prior save); When read; Then null, no exception. |
| SS-3 | ☐ `logout clears session but not offline credential cache` | Given a saved session; When `logout()`; Then session read returns null, but `OfflineCredentialCache` for the same user is untouched (cross-check with §4). |
| SS-4 | ☐ `explicit remove-account clears both` | Given a saved session + cached credential hash; When `removeAccount()` (distinct from `logout()`); Then both session and offline cache are cleared. |
| SS-5 | ☐ `corrupted store read fails safe` | Given the underlying encrypted file is unreadable/corrupted; When read; Then null returned, not a crash. |

## 4. `OfflineCredentialCacheTest` — offline re-login hash

| # | Name | Given / When / Then |
|---|---|---|
| OC-1 | ☐ `store never persists plaintext password` | After `store(username, password)`; Then the underlying storage value for that entry does not equal `password` or contain it as a substring (sanity check against accidental plaintext leak). |
| OC-2 | ☐ `verify matches correct username and password` | Given stored via OC-1; When `verify(sameUsername, samePassword)`; Then `true`. |
| OC-3 | ☐ `verify rejects wrong password` | Given stored credential for user A; When `verify(userA, wrongPassword)`; Then `false`. |
| OC-4 | ☐ `verify rejects different username` | Given stored credential for user A; When `verify(userB, anyPassword)`; Then `false` (same-user-only policy). |
| OC-5 | ☐ `verify with no cache present returns false, not a crash` | Given nothing ever stored; Then `false`. |
| OC-6 | ☐ `a new successful online login overwrites the previous cached hash` | Given user A cached; When user A logs in online again with a changed password; Then `verify(userA, newPassword)` true, `verify(userA, oldPassword)` false. |

## 5. `LoginViewModelTest` — updated + new cases

| # | Name | Given / When / Then |
|---|---|---|
| VM-1 | ☑→ update | Existing "blank field" validation cases updated: `userIdError`/`onUserIdChanged` → `usernameError`/`onUsernameChanged` (rename only, behavior unchanged). |
| VM-2 | ☐ `online login success updates state and calls repository once` | Given repository returns Success; When `onLoginClicked()`; Then `isSubmitting` false, `loginSucceeded` true, repo called once with trimmed username. |
| VM-3 | ☐ `invalid credentials shows localized error, does not succeed` | Given repository returns `Failure(INVALID_CREDENTIALS)`; Then `loginError` = the matching string resource, `loginSucceeded` false. |
| VM-4 | ☐ `network error shows distinct message from invalid credentials` | Given repository returns `Failure(NETWORK_ERROR)`; Then `loginError` resolves to a network-specific string resource, distinct from R.string.login_error_invalid_credentials. |
| VM-5 | ☐ `no connectivity attempts offline verification path` | Given a `ConnectivityChecker` fake reporting offline; When `onLoginClicked()`; Then `OfflineCredentialCache.verify` is invoked instead of (or the repository short-circuits before) a real network call. |
| VM-6 | ☐ `offline verification success restores last session and succeeds` | Given offline + `verify()` true + a previously cached token pair exists; Then `loginSucceeded` true, session restored via `SessionStore`, no exception even though no live token was issued this attempt. |
| VM-7 | ☐ `offline verification failure shows invalid-credentials error` | Given offline + `verify()` false; Then same user-facing error as online invalid credentials (don't leak online/offline distinction to the user). |
| VM-8 | ☐ `offline with no cache at all shows a distinct "connect once first" message` | Given offline + no cached credential exists for any user; Then a distinct error string guiding the user to log in online at least once. |
| VM-9 | ☑→ update | Existing double-tap-ignored-while-submitting case retained, field names updated. |
| VM-10 | ☐ `wrong role shows app-mismatch error, not invalid-credentials` | Given repository returns `Failure(WRONG_ROLE)`; Then `loginError` resolves to a distinct "this account isn't a Sakhi account" string — never conflated with the invalid-credentials message. |

---

**Coverage mapping:** §1–2 = online login wiring (issues 3–4). §3–4 = issues 6 & 8 (stay-logged-in + offline cache).
§5 = issue 2/5 wiring into the ViewModel plus the new offline/network branches. Issue 7 (silent refresh)
and issue 9's Compose-level UI states are **not** covered here — refresh has no test class yet (blocked
on the endpoint contract); UI-level states are exercised indirectly via `LoginUiState` assertions in §5
since this repo has no Compose UI test harness yet.
