# Nyachat — Deep Audit Working Notes (r1.7.3)

Scope (planned coverage):
- [x] firestore.rules (334 lines) — full read
- [x] functions/index.js (543 lines) — full read
- [x] functions/package.json, app/google-services.json
- [x] data/crypto/WorkspaceCrypto.kt (170)
- [x] data/remote/E2eeSyncManager.kt (360)
- [x] data/local/SecureStorage.kt
- [ ] app/src/main/AndroidManifest.xml (exported/backup/cleartext)
- [ ] firebase.json + storage.rules (Storage access for family photos)
- [ ] build.gradle.kts (minify/shrink, debug flags), proguard-rules.pro
- [ ] data/remote/GitHubUpdateChecker.kt (unsigned APK update vector)
- [ ] data/remote/FirestoreSyncManager.kt (primary data path, encrypt/decrypt, PendingOp replay)
- [ ] data/remote/MembershipManager.kt (PIN gen, join/approve, member limits)
- [ ] data/remote/GeminiService.kt, FinanceAiService.kt (BYOK key handling)
- [ ] data/backup/* (BackupCrypto, DataExporter, DriveBackupManager)
- [ ] Constants.kt, PinAttemptLimiter (client-side PIN throttling)
- [ ] app/src/debug/AndroidManifest.xml

## Candidate findings (need verification)

C1 [HIGH, E2EE] Any member may overwrite `families/{fid}/e2eeKeys/{uid}` for ANY uid
(rules: `allow create, update` with only type/size validation, no uid binding, no key-version
binding, no integrity tag). Enables wrapped-group-key Substitution: attacker (any member, or
a member whose device is compromised) writes a wrap of THEIR OWN group key for victim uid →
victim encrypts with wrong key; attacker reads victim's messages/transactions while everyone
else cannot (decryption failures), i.e. key-substitution MITM + selective DoS.
Fix direction: bind write to `uid == request.auth.uid` OR require the member doc pubkey hash
match + monotonic `e2eeKeyVersion`, and detect version regression at read time.

C2 [HIGH, E2EE] No group-key rotation on member removal / no ex-member forward secrecy.
`E2eeSyncManager` never rotates group key; leaving member keeps key material for all future
messages they can no longer fetch (mitigated by rules) — but on re-add with same device the
old key is still valid, and there is no mechanism to invalidate it.

C3 [MED, rules] `messages/allow update` lets ANY member rewrite/overwrite any existing message
(no author or edit-window binding) — history tampering (also covers transactions: `update` and
`delete` on `transactions` open to any member → financial records mutable/deletable by anyone).

C4 [MED, rules] `receipts` create/update only requires caller uid ∈ deliveredBy but lets the
caller insert ARBITRARY uids into `deliveredBy`/`readBy` → spoofed ✓✓ / rainbow read state,
and unbounded array growth (no size/type validation on elements, no `hasOnly` uid check).

C5 [MED, join] PIN workspace enumeration: `joinRequests` create only checks
`exists(family)` + timestamp window, with no per-uid attempt cap server-side (client
PinAttemptLimiter is bypassable). 8-digit PIN space probed at will; also spams owners' FCM.

C6 [MED, e2ee rules] `families/{fid}/e2ee/{markerDoc}` is a wildcard `{markerDoc}` but
semantics assume single doc `e2ee`; any member can create extra marker docs (harmless but
does not match the documented single-marker model). Verify client reads only `e2ee`.

C7 [MED, functions] `aiComplete` rate limit is per-instance in-memory Map (documented) —
cold-start/multi-instance fan-out trivially multiplies quota; no per-uid daily cap, no global
cap; `imageBase64` accepted up to 3 MB with no cost guard on OpenRouter fan-out across
FREE_MODELS × images.

C8 [LOW, functions] `notifyChatMessage` sends `sender` name in FCM data payload while the
entire content is E2EE — metadata leak (sender display name + cloudId + senderUid) to FCM;
the "generic notification" claim in comments is only partially true.

C9 [LOW, crypto] `WorkspaceCrypto.hkdf` has no AAD/context binding on the AES-GCM wrap
(wrap is not bound to familyId/recipient uid) → a valid wrap may be replayed across
workspace slots. Also `generateKeyPair()` uses provider `EC` without disabling
`AndroidKeyStore`-supplied EC — private key is software-held then wrapped by Keystore,
acceptable, but no hardware-backed key for E2EE.

C10 [LOW, SecureStorage] Single master key alias `money_chat_master_key` shared app-wide,
`setUserAuthenticationRequired(false)`; `getSecret` uses `String(plaintext)` (default
charset) and does not zero the intermediate byte arrays.

C11 [LOW, firestore.rules] `families/{fid}` create allows ANY signed-in Google user to claim
any PIN (squatting) — documented, but combined with C5 gives a cheap takeover prompt:
squatter registers PIN first, then becomes the only "owner".
