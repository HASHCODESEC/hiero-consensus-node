# CLPR Spec Follow-ups — 2026-05

This document consolidates spec-level work items discovered during the 2026-05 Hiero
drift review (see `DRIFT-REVIEW-2026-05.md`). Each item carries one of three tags:

- **RESOLVED** — the canonical spec (`clpr-spec`) already addresses this; no further
  action needed.
- **ALIGN-WITH-SC** — clpr-smart-contracts has made a concrete implementation choice
  that Hiero should mirror; no spec change is needed but a Hiero code change is.
- **OPEN-SPEC-ISSUE** — an unresolved gap that requires a PR against `clpr-spec` (and
  possibly a matching `clpr-smart-contracts` change). Proposed spec wording is
  included so the follow-up PR can be drafted directly.

References use the form `clpr-spec/<filename>:<line>` for the canonical spec repository and
`clpr-sc/<path>:<line>` for the smart-contracts repository.

---

## C?-12 — messageId 0 validity

**Drift-review citation:** C?-12 (`ClprRedactMessageHandler.pureChecks` line 46 —
`messageId > 0`).

**Current state of clpr-spec.**
`clpr-spec/clpr-service-spec.md:503–504` states:

> "When a Channel is created, `next_message_id` = 1, `acked_message_id` = 0,
>
>> `received_message_id` = 0"

`clpr-spec/clpr-service-spec.md:857` reinforces:

> "The first Response Message received MUST match the first Data Message ever sent on
>
>> this Channel (ID 1, since `next_message_id` is initialized to 1)."

The spec thus makes `next_message_id` start at 1, implying message ID 0 is never
assigned to any Data Message. However the spec's `redactMessage` pseudo-API
(`clpr-spec/clpr-service-spec.md:1237–1241`) has no explicit lower-bound constraint
on `message_id`.

**Current state of clpr-smart-contracts.**
`clpr-sc/src/logic/ChannelLogic.sol:167` initialises `conn.nextMessageId = 1`.
`clpr-sc/src/logic/MessagingLogic.sol:88` uses `messageId > conn.ackedMessageId &&
messageId < conn.nextMessageId` as the redact validity window — this naturally
excludes 0 because `ackedMessageId` is initialised to 0 and the check is strict
greater-than.

**Decision tag:** RESOLVED for the code model (ID 0 is never valid); OPEN-SPEC-ISSUE
for the missing explicit statement in the spec.

**Proposed spec wording** — add to `clpr-spec/clpr-service-spec.md` §4.4 (Message
Lifecycle and Redaction), immediately after the opening sentence of the Redaction
paragraph:

> **Message ID validity.** Message IDs are assigned starting from 1. ID 0 is never
> valid and MUST be rejected by `redactMessage` and any other operation that
> references a `message_id`. More precisely: a `message_id` is valid for `redactMessage`
> if and only if `acked_message_id < message_id < next_message_id`.

Also add to the `redactMessage` pseudo-API (`§6.4`):

> // MUST fail if message_id == 0, or if message_id <= acked_message_id (already
> // acknowledged), or if message_id >= next_message_id (never enqueued).

**Action for Hiero:** The existing `messageId > 0` guard is correct. Add a comment
explaining why (aligns with the proposed spec wording above).

---

## S-1 — PENDING Channel state

**Drift-review citation:** S-1 — design doc §3.1.3 says no PENDING state for
Channels; protocol spec §2.1.1 lists PENDING in the enum.

**Current state of clpr-spec.**
`clpr-spec/clpr-service-spec.md:484–486` (§2.1 Channel state model) now includes
`PENDING` in the status enum and `clpr-spec/clpr-service-spec.md:519–527` (§2.1.1
Status Transitions) defines the full PENDING -> ACTIVE and PENDING -> (deleted)
transitions. `clpr-spec/clpr-service-spec.md:967` explicitly states: "A PENDING
Channel cannot be used for messaging — no sendMessage, no bundle submission, no
syncing. It exists solely as a claimed reservation."

**Current state of clpr-smart-contracts.**
`clpr-sc/src/libraries/ClprTypes.sol:11–13` documents:

> "PENDING is the proto3 default; on-chain we don't store Channels in PENDING
>
>> state (registration uses a separate commitment map)"

The EVM implementation uses the same separate-commitment model as Hiero: commitments
live in `_pendingCommitments` mapping, not in a Channel record. `closeChannel`
handles abandoned commitments via reverse index
(`clpr-sc/src/logic/ChannelLogic.sol:87–95`).

**Decision tag:** RESOLVED. The spec has been updated to describe the commit-reveal
model explicitly. The PENDING enum value is retained for wire compatibility (proto3
default = 0) but neither Hiero nor the EVM project creates a Channel record in
PENDING state. No spec change needed.

---

## S-2 — Connector identity model

**Drift-review citation:** S-2 — design doc uses derived `keccak256(channel_id ||
public_key || salt)` ID; protocol spec §2.2/§6.3 previously referenced raw
`source_connector_address`.

**Current state of clpr-spec.**
`clpr-spec/clpr-service-spec.md:549–559` (§2.2 Connector) now states the derived-ID
model in full:

> "connector_id = keccak256(channel_id || public_key || salt)"

`clpr-spec/clpr-service-spec.md:1125–1154` (§6.3 completeConnector) matches: the
pseudo-API re-derives `connector_id = keccak256(channel_id || public_key || salt)`
and uses `keccak256(connector_id || service_address)` as the ownership proof.

**Current state of clpr-smart-contracts.**
`clpr-sc/src/logic/ChannelLogic.sol:158–159` derives and validates the
`channel_id` itself; the connector derivation is in
`clpr-sc/src/logic/ConnectorLogic.sol` and matches the spec formula.

**Decision tag:** RESOLVED. The spec has been updated to the derived-ID model.
No further action needed.

---

## S-3 — `verifier_contract` placement

**Drift-review citation:** S-3 — design doc places `verifier_contract` in Phase 2
(`completeChannel`); protocol spec §6.2 previously put it on `registerChannel`.

**Current state of clpr-spec.**
`clpr-spec/clpr-service-spec.md:1054–1088` (§6.2): `registerChannel` takes only
`ownership_commitment`; `completeChannel` carries `verifier_contract` and
`config_proof_bytes`. This matches the design doc and Hiero's proto layout.

**Current state of clpr-smart-contracts.**
`clpr-sc/src/logic/ChannelLogic.sol:54–67` (`completeChannel`) receives
`verifier` as a parameter, not `registerChannel`. Consistent.

**Decision tag:** RESOLVED. The spec places `verifier_contract` in Phase 2.
No further action needed.

---

## S-4 — `max_syncs_per_sec` advisory vs enforced

**Drift-review citation:** S-4 — design doc says locally enforced (MUST); protocol
spec §1.1/§7 labelled it "Advisory". Richard's direction: receiving side MUST enforce
(shun); sending side SHOULD self-pace (unenforced). Clarify wording to reflect this.

**Current state of clpr-spec.**
`clpr-spec/clpr-service-spec.md:81–85` (§1.1, `ClprThrottles.max_syncs_per_sec`):

> "This limit is locally enforced: receiving endpoints track inbound sync frequency
>
>> per remote endpoint and MUST shun peers that persistently exceed their fair share."

`clpr-spec/clpr-service-spec.md:1002–1004` (§5.2):

> "Sending endpoints SHOULD self-pace to stay within their share ... Self-pacing is a
>
>> best-effort hint; the enforcement is on the receiving side."

`clpr-spec/clpr-service-spec.md:1306` (§7 table): the `maxSyncsPerSec` row already
says "enforced by the receiving side" and "Sending endpoints SHOULD self-pace."

The spec has been updated to use MUST on the receiving side and SHOULD on the sending
side, which exactly matches Richard's intent. The Hiero proto comment
(`clpr_ledger_configuration.proto:61`) still says "Advisory" and is now stale.

**Decision tag:** RESOLVED in the canonical spec. One residual action for Hiero only:
update the proto field comment to replace "Advisory: suggested maximum sync frequency"
with language consistent with the spec (MUST enforce on receive, SHOULD self-pace on
send). This is a Hiero doc-only change (D-6 from the drift review); no spec PR needed.

---

## S-5 — `max_bundles_per_sec` field placement

**Drift-review citation:** S-5 — design doc described `max_bundles_per_sec` as a
per-endpoint enforced parameter; protocol spec §1.1 had no such field.

**Current state of clpr-spec.**
`clpr-spec/clpr-service-spec.md:109–116` (§1.1, `ClprThrottles`): field 7
`max_bundles_per_sec` is now present. `clpr-spec/clpr-service-spec.md:1301` (§7
table) documents it. `clpr-spec/clpr-service-spec.md:993–996` (§5.2) specifies the
per-endpoint fair-share enforcement.

**Current state of clpr-smart-contracts.**
`clpr-sc/src/libraries/ClprTypes.sol:110` — `Throttles` struct contains
`uint64 maxBundlesPerSec`. Consistent with spec.

**Decision tag:** RESOLVED. The field is in the spec and in both implementations.
Hiero's proto already lacks the field (`ClprThrottles` in proto only has fields
1–6). The Hiero proto needs a field 7 `uint32 max_bundles_per_sec` added to
`ClprThrottles` to match the spec and EVM. This is a Hiero code change, not a
spec issue.

**Action for Hiero (code):** Add `uint32 max_bundles_per_sec = 7;` to
`clpr_ledger_configuration.proto`'s `ClprThrottles` message and wire it into
`ClprSubmitBundleHandler` for per-endpoint rate enforcement.

---

## S-6 — On-chain peer signing-key registry

**Drift-review citation:** S-6 — spec §1.5 says endpoint_signature recovery "matches
against on-chain endpoint roster" but §2 defined no peer roster. Richard's direction:
spec should say peer endpoints are listed per channel with maximum 10, and an
open-questions doc should discuss uncapping via control messages.

**Current state of clpr-spec.**
`clpr-spec/clpr-service-spec.md:629–631` (§2.4, Peer roster note):

> "The peer's endpoint roster is not stored on-ledger on this side — peer endpoints
>
>> are discovered off-chain via gossip (§5.2), seeded by the `endpoints`
>> published in the peer's `ClprLedgerConfiguration`."

`clpr-spec/clpr-service-spec.md:606–611` (§2.4, EndpointRosterEntry):

> "Endpoint signature verification on `submitBundle` — the recovered ECDSA public key
>
>> MUST match an `ecdsa_signing_key` in the Channel's roster."

The spec currently says the peer roster is off-ledger (gossip-only) and that
signature verification matches against the local Channel roster. This is silent on
how a receiving implementation knows which peer signing keys to match against.
There is no explicit cap or storage model for peer keys. No open-questions doc
currently exists in clpr-spec.

**Current state of clpr-smart-contracts.**
The EVM implementation does not store peer signing keys on-ledger. Signature
verification on `submitBundle` matches `msg.sender` against `RegisteredEndpoint`
(`clpr-sc/src/libraries/ClprTypes.sol:134–138`), i.e., the local registered-endpoint
roster, not a per-channel peer set. The `ecdsa_signing_key` field is stored on
`RegisteredEndpoint` but is informational only (the contract's own comment at line 128
says "the EVM contract itself does not verify against it").

**Decision tag:** OPEN-SPEC-ISSUE

**Proposed spec wording.**

In `clpr-spec/clpr-service-spec.md` §2.4, replace the current "Peer roster" note with:

> **Peer endpoint keys (per-Channel, on-ledger).** To verify the
> `endpoint_signature` on incoming `submitBundle` calls, each Channel stores a
> capped set of peer endpoint ECDSA signing keys. These keys are populated from the
> `ecdsa_signing_key` fields of the `endpoints` published in the peer's verified
> `ClprLedgerConfiguration` at `completeChannel` time, and refreshed on inbound
> `ConfigUpdate` control messages. **A Channel MUST NOT store more than 10 peer
> endpoint signing keys.** Peer keys beyond the 10-entry cap are silently ignored at
> population time. Incoming `submitBundle` calls whose recovered signing key does not
> match any stored peer key for the Channel MUST be rejected with a misbehavior
> penalty charged to the submitter.
>
> This 10-entry cap bounds on-ledger storage cost. Production-scale deployments may
> need more than 10 peer endpoints; the path forward (adding a `PeerRosterUpdate`
> control message variant to allow the peer to publish its full endpoint set on-chain)
> is tracked as an open question — see `clpr-options-to-consider.md`.

Also add a new entry to `clpr-spec/clpr-options-to-consider.md` (or a new
`open-questions.md`):

> **S-6: Uncapping the peer endpoint roster.** The current spec caps per-Channel
> peer signing keys at 10 (the seed endpoint count). For large validator sets this
> is insufficient. Options:
> 1. Add a `PeerRosterUpdate` control message variant carrying a signed roster
> update. The receiver replaces its stored peer key set with the new roster.
> Threshold: allow up to N keys (N TBD per platform).
> 2. Use the off-chain gossip protocol (`discoverEndpoints` RPC) to maintain
> ephemeral peer key state; the ledger only stores a Merkle root, and bundles
> carry a Merkle proof of the signing key.
> 3. Accept the 10-key cap as the v1 constraint and revisit at v1.1.
> Decision deferred pending production-scale deployment experience.

---

## S-7 — registrant-chosen vs derived `channel_id`

**Drift-review citation:** S-7 — spec §2.1/§6.2 says channel_id is
registrant-chosen (arbitrary 32 bytes); clpr-smart-contracts moved to a derived model;
Hiero proto comment says `keccak256(uncompressed_public_key)` (wrong).

**Current state of clpr-spec.**
`clpr-spec/clpr-service-spec.md:456–459` (§2.1):

> "Each Channel is keyed by its Channel ID — exactly 32 bytes, chosen by the
>
>> registrant (e.g., randomly generated). The same Channel ID is used on both
>> ledgers..."

`clpr-spec/clpr-service-spec.md:1081` (`completeChannel`):

> `channel_id: bytes(32),  // arbitrary 32-byte identifier, same on both ledgers.`

The canonical spec still says registrant-chosen.

**Current state of clpr-smart-contracts.**
`clpr-sc/src/logic/ChannelLogic.sol:197–204` — `_deriveChannelId` computes:

```
keccak256(sorted(local_chain_id, peer_chain_id) || pubKey || salt)
```

The EVM implementation has diverged: `channel_id` is derived, not caller-supplied.
The sort step (`_sortChains`) ensures the same ID is produced regardless of which
ledger registers first.

**Decision tag:** OPEN-SPEC-ISSUE

**Proposed spec wording.** Replace the §2.1 "chosen by the registrant" language and
the `completeChannel` comment with:

> **Channel ID derivation.** The Channel ID is a 32-byte value derived
> deterministically so that both ledgers produce the same ID without prior
> coordination:
>
>         channel_id = keccak256(chain_id_lo || chain_id_hi || public_key || salt)
>
> where `chain_id_lo` and `chain_id_hi` are the two ledgers' CAIP-2 chain identifiers
> sorted lexicographically (so the result is independent of which side computes it),
> `public_key` is the registrant's public key in platform-specific encoding, and
> `salt` is an optional 32-byte label (defaults to 32 zero bytes). Different public
> keys may be used on each ledger; the registrant uses the key they provide to
> `completeChannel` on each side, and the sort ensures identical IDs.
>
> At `completeChannel` time the CLPR Service re-derives the expected `channel_id`
> from the caller's submitted `public_key`, `salt`, the local `chain_id`, and the peer
> `chain_id` obtained from `verifyConfig`; it MUST reject the call if the submitted
> `channel_id` does not match.

Update the `completeChannel` pseudo-API parameter comment to:

> `channel_id: bytes(32),  // derived as keccak256(chain_id_lo || chain_id_hi || public_key || salt); same on both ledgers.`

**Action for Hiero:** The Hiero code treats `channel_id` as opaque caller input and
does not re-derive it, which diverges from the EVM implementation. The Hiero proto
comment is also wrong. Until a spec resolution is agreed:
1. Fix the proto comment (D-5 in drift review) to "registrant-provided 32-byte
identifier" — stop claiming a derivation that doesn't exist in the code.
2. Track alignment with the EVM derived model as a separate code ticket once the spec
is updated.

---

## S-8 — Connector `balance` field

**Drift-review citation:** S-8 — spec §2.2 has a `balance` field; Hiero uses the
connector contract account balance instead. Richard's direction: spec should permit or
mandate the contract-account model; remove `balance` from spec §2.2.

**Current state of clpr-spec.**
`clpr-spec/clpr-service-spec.md:567` (§2.2 Connector state model):

```
balance : uint  // available funds for message execution (native tokens)
```

`clpr-spec/clpr-service-spec.md:1152–1171` (§6.3): `completeConnector` takes
`initial_balance`, and `topUpConnector` / `withdrawConnectorBalance` are specified as
pseudo-APIs.

**Current state of clpr-smart-contracts.**
`clpr-sc/src/libraries/ClprTypes.sol:74–77`:

> "Operating funds (used to pay for inbound message execution) live as native balance
>
>> on the `connectorContract` itself — the protocol pulls payment via
>> `IClprConnector.payForExecution`. `lockedStake` is the slashable bond and is held
>> by ClprService."

The EVM implementation has no `balance` field on the `Connector` struct; operating
funds are held on the connector contract's own account. `topUpConnectorStake`
(`clpr-sc/src/logic/ConnectorLogic.sol:86`) tops up stake, not a separate balance
field. There is no `topUpConnectorBalance` or `withdrawConnectorBalance` in the EVM
implementation.

**Decision tag:** OPEN-SPEC-ISSUE

**Proposed spec wording.** In §2.2 replace the `balance` field with:

> `connector_contract : bytes   // address of the Connector's authorization contract`
>
> Operating funds for inbound message execution are held by the `connector_contract`
> account itself (as native token balance). The CLPR Service charges the connector by
> calling the connector contract's execution-payment interface (see §3.2). On
> platforms where this model is unavailable, an explicit `balance` field MAY be
> maintained by the CLPR Service instead; platform-specific specifications MUST
> define which model is used.

In §6.3 mark `topUpConnector` and `withdrawConnectorBalance` as optional:

> These operations apply only on platforms that maintain an explicit `balance` field
> in the on-ledger Connector record. On platforms using the contract-account model,
> operators top up and withdraw by sending native tokens directly to/from the
> `connector_contract` address.

---

## S-9 — Initial trust anchor

**Drift-review citation:** S-9 — EVM `completeChannel` returns an `initialTrustAnchor`
from `verifyConfig`; Hiero's proto has no separate trust anchor field; spec is silent.
Richard's direction: spec must add a verifier-specific anchor parameter.

**Current state of clpr-spec.**
`clpr-spec/clpr-service-spec.md:962–963` (§5.1.3, Phase 2):

> "3. Calls `verifyConfig(config_proof_bytes)` on `verifier_contract` to obtain the
>
>> peer's verified `ClprLedgerConfiguration`."

The verifier spec (`clpr-spec/clpr-service-spec.md:667`):

```
function verifyConfig(bytes proof_bytes) returns (ClprLedgerConfiguration)
```

No trust anchor is returned. The `Channel` state model (§2.1) has no `trust_anchor`
field.

**Current state of clpr-smart-contracts.**
`clpr-sc/src/interfaces/IClprVerifier.sol:29–39` — `verifyConfig` returns a 5-tuple
including `initialTrustAnchor`. `clpr-sc/src/logic/ChannelLogic.sol:174`:
`conn.trustAnchor = initialTrustAnchor`. `clpr-sc/src/libraries/ClprTypes.sol:70–71`
documents the field: "Opaque trust anchor bytes managed by the verifier ... EVM
verifiers store the current signing-key state here."

**Decision tag:** OPEN-SPEC-ISSUE

**Proposed spec wording.** In §2.1 (Channel state model) add:

> `trust_anchor : bytes       // opaque verifier state, updated by verifyBundle (optional; stateless verifiers leave empty)`

Update §3.1 (Verifier Contract Interface) `verifyConfig` signature:

```
function verifyConfig(bytes proof_bytes)
    returns (ClprLedgerConfiguration, bytes initial_trust_anchor)
```

And `verifyBundle` signature:

```
function verifyBundle(bytes bundle_payload, bytes trust_anchor)
    returns (ClprQueueMetadata, ClprMessagePayload[], bytes new_trust_anchor)
```

Add explanatory note:

> **Trust anchor.** Some verifiers maintain mutable proof state across bundle
> verifications — for example, a sync-committee verifier must track committee rotation
> state, and an IBFT verifier must track the current validator set. The `trust_anchor`
> field is an opaque byte blob stored on the `Channel` record and passed to
> `verifyBundle` on each call. The verifier returns a `new_trust_anchor` which
> replaces the stored value (if non-empty). Stateless verifiers (e.g., a Hiero
> verifier that only checks TSS signatures against a fixed public key) return empty
> bytes and the stored anchor remains unchanged. The CLPR Service MUST store the
> returned anchor atomically with the bundle processing outcome.

**Action for Hiero:** Hiero currently passes `config_proof_bytes` but ignores any
trust anchor. Once the spec lands, add `trust_anchor` to the `ClprChannel` proto
and pass it through the verifier call path. For Hiero's own stateless TSS verifier,
the anchor will always be empty.

---

## S-10 — `ClprBundleContent` absent from canonical spec

**Drift-review citation:** S-10 — both Hiero and clpr-smart-contracts implement a
`ClprBundleContent` (queue metadata + repeated message payloads) as the verifier
return type, but neither canonical spec defines this message.

**Current state of clpr-spec.**
`clpr-spec/clpr-service-spec.md:686`:

```
function verifyBundle(bytes bundle_payload) returns (ClprQueueMetadata, ClprMessagePayload[])
```

The spec returns a tuple inline; there is no named `ClprBundleContent` message
defined anywhere in the spec.

**Current state of clpr-smart-contracts.**
`clpr-sc/src/interfaces/IClprVerifier.sol:18–21` — `verifyBundle` returns
`(ClprTypes.QueueMetadata, bytes[], bytes)`. The return is an ABI-decoded tuple, not
a named struct in the protobuf sense. The EVM ABI does not need a named wire type
because Solidity ABI encoding handles the tuple directly.

Hiero's `clpr_bundle_content.proto` defines `ClprBundleContent` as the
PBJ-encoded message that the verifier contract's return bytes must parse into. This
Hiero-specific protobuf wrapper is needed because the EVM call return value must be
decoded from raw bytes into a structured type that PBJ can work with.

**Decision tag:** OPEN-SPEC-ISSUE

**Proposed spec wording.** In §3.1 (Verifier Contract Interface), after the
`verifyBundle` signature, add:

> **`ClprBundleContent` wire type.** On platforms where the verifier returns a single
> opaque byte blob (rather than a multi-return-value ABI call), implementations MUST
> encode the verifier return as a `ClprBundleContent` message:
>
> ```protobuf
> message ClprBundleContent {
>   ClprQueueMetadata metadata = 1;
>   repeated ClprMessagePayload messages = 2;
>   bytes new_trust_anchor = 3;  // empty for stateless verifiers
> }
> ```
>
> On platforms that natively support multiple return values (e.g., Solidity ABI
> tuple returns), implementations MAY use platform-native multi-value return
> conventions instead. The logical fields are identical.

---

## S-11 — PQ extensibility / signature scheme

**Drift-review citation:** S-11 — spec §6.2 has no `signatureScheme` parameter;
Hiero adds `ClprSignatureScheme` enum to `clpr_complete_channel.proto`. Richard's
direction: document as ledger-specific; no PQ for v1; open question.

**Current state of clpr-spec.**
`clpr-spec/clpr-service-spec.md:458–460` (§2.1):

> "The protocol does not mandate a particular signature scheme, allowing platforms to
>
>> adopt post-quantum signature algorithms as they become available."

`clpr-spec/clpr-service-spec.md:933–934` (§5.1.2):

> "Generates a keypair on the local ledger using a signature scheme supported by that
>
>> platform (e.g., ECDSA secp256k1 on Ethereum, Ed25519 on other platforms)."

`clpr-spec/clpr-service-spec.md:955` (§5.1.3):

> "The signature scheme and key encoding are platform-specific."

The spec correctly defers signature scheme selection to platform specs but does not
discuss negotiation across platform boundaries.

`clpr-spec/clpr-service-spec.md:150–154` (§1.2, `ClprEndpoint.ecdsa_signing_key`):

> "Future: ECDSA secp256k1 is not quantum-safe. The protocol anticipates migrating
>
>> endpoint_signature to a post-quantum scheme ... This migration will use the existing
>> ConfigUpdate mechanism to rotate endpoint keys across all Channels."

**Current state of clpr-smart-contracts.**
No `signatureScheme` field exists in the EVM implementation. The EVM uses
secp256k1 throughout.

**Decision tag:** OPEN-SPEC-ISSUE

**Proposed spec wording.** Add a new open-question entry (to `clpr-options-to-consider.md`
or a new `open-questions.md`):

> **S-11 / C-6: Signature scheme negotiation (endpoint_signature).** The current spec
> fixes `endpoint_signature` as ECDSA secp256k1 (§1.2, §1.5) and notes a future PQ
> migration path via ConfigUpdate. No negotiation mechanism is defined yet.
>
> The following work is deferred to a post-v1 spec revision:
> 1. Add a `signature_scheme` field to `ClprEndpoint` (§1.2) identifying the signing
> algorithm used for `endpoint_signature` on that endpoint.
> 2. Add a `signature_scheme` field to `ClprSyncPayload` (§1.5) identifying the
> scheme used for the `endpoint_signature` in this specific payload.
> 3. Add a `supported_signature_schemes` list to `ClprLedgerConfiguration` (§1.1)
> so that peers know which schemes the ledger can verify.
> 4. Define the migration procedure: a ledger that adds PQ support publishes the new
> scheme in its configuration; the ConfigUpdate control message propagates this to
> all peer Channels; after a grace period the old scheme is retired.
>
> **v1 behaviour (all implementations):** use ECDSA secp256k1 exclusively.
> Hiero-specific note: consensus nodes currently use RSA and Ed25519 keys for
> consensus signing; they MUST generate a dedicated ECDSA secp256k1 keypair per node
> for CLPR endpoint signing. This key is published in `ClprEndpoint.ecdsa_signing_key`
> in the ledger configuration and is not part of the address book.

---

## C-6 — PQ extensibility for endpoint signature (spec dimension)

**Drift-review citation:** C-6 — `endpoint_signature` is never produced or verified;
Richard says add signature-scheme negotiation to bundle metadata and config for the
future, but use ECDSA secp256k1 consistently for v1. Add as open spec issue.

**Current state of clpr-spec.**
`clpr-spec/clpr-service-spec.md:323–341` (§1.5, `ClprSyncPayload.endpoint_signature`)
specifies ECDSA secp256k1 only, with no scheme field. The spec says the signer's
public key is recovered via ecrecover and matched against the Channel's endpoint
roster. No `signature_scheme` field exists in `ClprSyncPayload` or `ClprEndpoint`.

**Current state of clpr-smart-contracts.**
Consistent with the spec: secp256k1 only, no scheme negotiation field.

**Decision tag:** OPEN-SPEC-ISSUE (same root cause as S-11; the two items should be
addressed together in a single spec PR).

**Proposed spec wording.** This item is subsumed by S-11 above. The S-11 open-question
entry covers both the bundle metadata scheme field (`ClprSyncPayload.signature_scheme`)
and the endpoint configuration scheme field (`ClprEndpoint.signature_scheme`).

**v1 action for clpr-spec (immediate):** clarify §1.5 to read:

> **v1 constraint.** All v1 implementations MUST use ECDSA secp256k1 for
> `endpoint_signature`. A `signature_scheme` field will be added in a future
> revision (see open question S-11/C-6); until then, secp256k1 is the implicit and
> only permitted scheme.

**Action for Hiero (code, not spec):** implement signing with a per-node ECDSA
secp256k1 key and verify on inbound bundles as described in C-6 of the drift review.
This is a Hiero implementation task, not a spec task. The spec already says MUST
sign/verify; the code gap is on the Hiero side.
