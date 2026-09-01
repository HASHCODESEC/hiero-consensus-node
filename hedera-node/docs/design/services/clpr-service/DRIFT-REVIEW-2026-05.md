# Drift Review — 2026-05-01 (Hiero)

A comprehensive comparison of `hedera-node/docs/design/services/clpr-service/` and the
actual implementation under `hedera-node/hedera-clpr-service-impl/`,
`hedera-node/hedera-app/.../workflows/clpr/`,
`hedera-node/hedera-smart-contract-service-impl/.../clpr/`,
`hapi/.../proto/services/clpr*` against `clpr-spec/clpr-service.md`,
`clpr-spec/clpr-service-spec.md`, and `clpr-spec/clpr-test-spec.md`. Findings are grouped
by severity and each has a concrete proposed code, test, or doc change.

This review intentionally goes beyond doc-vs-spec drift to record
**implementation-vs-spec** drift discovered while validating the docs; docs that
*correctly describe the code* may still be misleading if the code itself diverges from
the spec, so both layers had to be checked together.

## Scope and pre-existing trackers

The Hiero CLPR docs under `docs/design/services/clpr-service/` (README.md,
state-and-protobufs.md, handlers.md, evm-integration.md, sync-workflow.md, verifier.md,
config-and-slashing.md, testing.md) are the documentation surface; this review
verifies them against code and canonical spec.

`hedera-node/hedera-clpr-service-impl/REVIEW_NOTES.md` is the impl team's working
scratchpad. Pre-existing items there are noted as `[REVIEW_NOTES]` where applicable.

The two canonical specs (`clpr-service.md`, the design doc, and `clpr-service-spec.md`,
the protocol spec) are not internally consistent — there are several places where they
describe different models. Where they disagree, this document does **not** pick a
winner; it flags as **S** (spec gap) and notes which spec Hiero matches.

Severity legend:

- **C** — correctness bug (verified by reading the code in this review)
- **C?** — likely correctness bug (high confidence; recommend test before fixing to
  confirm the failure mode)
- **I** — incomplete implementation vs spec
- **S** — spec gap (the spec under-specifies, or the two canonical specs disagree)
- **D** — documentation inaccuracy (in `docs/` or in code/proto comments)
- **E** — acceptable Hiero specialization that should be called out in a Hiero
  platform-spec but is not currently written down anywhere

---

## Section 1 — Confirmed correctness bugs

### C-1. Inbound `redactMessage` produces no `REDACTED` reply

- **Where:** `ClprSubmitBundleHandler.java` per-message dispatch (lines ~308–428).
  Branches handle `payload.hasMessage()`, `hasMessageReply()`, and `hasControl()`.
  A payload with all three unset (the on-the-wire shape of a redacted slot) falls
  through the `else` silently — `receivedMessageId` advances but no
  `ClprMessageReply` is enqueued.
- **Spec:** §4.4 + §4.5: when the destination dequeues a redacted slot it MUST emit a
  `ClprMessageReplyStatus.REDACTED` reply for that slot. Otherwise the response-
  ordering invariant is violated for the source (its outbound DATA never gets a reply).
- **Impact:** the moment any admin redacts an in-flight DATA, the next bundle from
  the source arrives expecting a reply at that slot; the missing reply trips
  response-ordering and PAUSEs the channel — exactly the failure mode redaction is
  supposed to avoid.
- **Fix (code):** add an `else` branch in the per-message dispatch loop that, when the
  payload variant is unset, enqueues a `ClprMessageReply{ status = REDACTED,
  message_id = receivedMessageId }`.
- **Test:** `ClprSubmitBundleHandlerTest` — `test_redactedSlot_generatesRedactedReply`.
  Build a bundle whose verifier returns one redacted DATA payload; assert a REDACTED
  reply is enqueued and the running hash advances.

Richard: Yes, we should fix this. But also check with the clpr-smart-contracts repo
because both this project and that one need to handle this in an identical manner.
And, this should be explicit in the spec, that a message with all three unset is
the same as "redacted". Although, TBH, this surprises me. I would have expected an
enum in the protobuf that said "DATA", "REPLY", "CONTROL", "REDACTED" or something
like that to make it very explicit and have only a single byte on the wire for
the type. But I guess we didn't do that we did a one-of. So if the one-of isn't set,
then it is redacted. OK, that's fine.

### C-2. Connector charged AFTER application dispatch

- **Where:** `ClprSubmitBundleHandler.java:343–425`. Pre-check:
  `connectorAccount.tinybarBalance() < worstCaseCharge` then dispatch then transfer.
- **Spec:** §3.3.3 (Ethereum reentrancy callout) — checks-effects-interactions; the
  receive-side connector must be debited before dispatch.
- **Impact:** the dispatched application contract can drain the connector's account
  during the call, leaving insufficient funds to satisfy the post-dispatch transfer.
  A `try/catch + slash` masks the race but doesn't prevent it: a connector that
  cooperates with a malicious application can extract the execution payment back to
  itself before the protocol can charge it.
- **Fix (code):** debit `worstCaseCharge` from the connector account **before**
  dispatch; on dispatch success refund overage if any; on dispatch failure the funds
  are already in escrow.
- **Test:** `ClprSubmitBundleHandlerTest` — application contract that re-enters and
  transfers all HBAR out of the connector; assert charge still completes with full
  amount and endpoint is paid.

Richard: Confirmed, this is a bug! Must fix.

### C-3. `sendMessage` validates payload size against LOCAL config

- **Where:** `ClprServiceApiImpl.java:78–97`. Reads `configStore.getConfiguration()` —
  the **local** singleton — and checks `messageData.length > throttles.maxMessagePayloadBytes()`.
- **Spec:** §4.3 step 4: `len(message_data) <= peer_config.throttles.max_message_payload_bytes`.
  The destination is authoritative for its own limits.
- **Impact:** outbound messages can exceed what the peer will accept, guaranteeing
  inbound rejection (and slashing of the source connector) on the destination side.
- **Blocker:** the peer's `ClprThrottles` is currently never persisted on the
  `ClprChannel` record (the verifier returns the full `ClprLedgerConfiguration` but
  Hiero stores only the `peerConfigTimestamp`). This must be fixed alongside C-3.
- **Fix (code):**
  1. Add `ClprThrottles peer_throttles` (or full `ClprLedgerConfiguration`) to the
     `ClprChannel` proto. Populate at `completeChannel` from
     `verifyConfig`'s return; refresh on inbound `ConfigUpdate` control messages.
  2. Change the size check at `ClprServiceApiImpl.java:97` to read the channel's
     stored peer throttles.
- **Test:** `ClprServiceApiImplTest` — register a channel where peer caps payload
  at 10 bytes but local caps at 1000; an 11-byte `sendMessage` must revert with
  `CLPR_PAYLOAD_TOO_LARGE`.

Richard: Confirmed. this is a bug. Must fix.

### C-4. `deregisterConnector` lacks an in-flight-messages guard

- **Where:** `ClprDeregisterConnectorHandler.java`. Deregister proceeds unconditionally
  (subject to `stake_recipient` signature). Code grep for "inflight"/"queue" returns
  no matches in the file.
- **Spec:** §6.3: "MUST NOT deregister if the Connector has unresolved in-flight
  messages."
- **Impact:** a connector can extract its locked stake while messages it authored are
  still queued for delivery. If those messages later fail with
  `CONNECTOR_NOT_FOUND`/`CONNECTOR_UNDERFUNDED`, source-side slashing has nothing to
  slash and the endpoint absorbs the cost.
- **Fix (code):** count messages with this `connector_id` between
  `acked_message_id + 1` and `next_message_id`; reject deregister if > 0. Reuse
  `ClprServiceApiImpl.countConnectorMessages` (which currently scans for the per-
  connector quota).
- **Test:** `ClprConnectorSuite.deregisterRejectsWithInFlightMessages` (covers
  spec test 5.3.3).
- **Doc updates:** `handlers.md` previously claimed deregister refuses banned
  connectors — already corrected to flag this gap.

Richard: Oh, I see. The problem is, if there are *outbound* unresolved messages
then the connector cannot be deregistered. OK, makes sense. Although checking this
is pretty brutal if we have to do a walk of the queue. It is better to maintain
a counter of in-flight (outbound) messages for each connector and only allow
deregister if this number is 0.

I think I got this wrong in the clpr-smart-contracts implementation. Review that
code as well when handling this issue.

### C-5. `IClprConnectorAuth.authorizeMessage` is never called

- **Where:** `SendMessageCall.java:64–88` in `…systemcontracts/clpr/sendmessage/`. The
  comment explicitly defers the per-message sub-call:

  > "Per-message authorization via `IClprConnectorAuth.authorizeMessage()` requires
  >
  >> EVM-native sub-calls from within the precompile, which will be added in a future
  >> ticket."
  >> `ClprServiceApiImpl.sendMessage` lines 90–91 falsely claim it is "handled upstream
  >>
  >>> by SendMessageCall."

- **Spec:** §3.2 + §4.3 step 3: every `sendMessage` MUST sub-call
  `authorizeMessage(sender, target_application, message_size, message_data) → bool`
  on the Connector's contract. A revert blocks the send.
- **Impact:** any caller may enqueue messages naming any registered connector_id —
  the Connector cannot enforce allow-lists, rate limits, or payment requirements.
  The trust model spec §3.2 describes is unimplemented.
- **Fix (code):** in `SendMessageCall`, sub-call `authorizeMessage(...)` on the
  connector contract before invoking `ClprServiceApi.sendMessage`. Static-call
  semantics are required (spec §3.2: "MUST NOT have side effects").
- **Test:** new BDD case in `ClprSendMessageSuite` where the connector contract
  reverts; assert `sendMessage` reverts and no message is enqueued (covers spec
  test 5.3.4). And a positive case where the contract returns true.
- **Doc updates:** `evm-integration.md` previously documented this as if implemented
  — already corrected to a KNOWN-GAP note, but the underlying gap remains.

Richard: This is obviously a P0 security issue and blocker. Must fix. We cannot have this gap.

### C-6. Endpoint signature is never produced or verified

- **Where:**
  - Outbound: `ClprSyncWorkflowImpl.buildResponsePayload` (line ~248) and
    `ClprChannelManager.buildOutboundPayload` (line 439) set
    `endpoint_signature: Bytes.EMPTY`. Both have a `// TODO (CLPR-4.3)` comment.
  - Inbound: `ClprSubmitBundleHandler` never reads the `endpoint_signature` field.
    `preHandle` has the admin-key requirement commented out (lines ~103–107) due to
    self-deadlock with `ClprBundleSubmitter`.
  - HAPI body: `clpr_submit_bundle.proto` carries `endpoint_node_id` but **no
    `endpoint_signature` field** — the wire-level signature would be dropped at the
    HAPI boundary even if it were produced.
- **Spec:** §1.5 / §6.4: `endpoint_signature` MUST be ECDSA secp256k1 over
  `keccak256(channel_id ‖ bundle_payload)`. Receiver MUST recover and validate.
- **Impact:** Hiero is conformant only when talking to itself — the consensus
  event-level signature on the HAPI tx authenticates the *node* that submitted, but
  not the peer endpoint that constructed the bundle. A non-Hiero peer cannot
  authenticate Hiero's outbound bundles, and Hiero cannot authenticate inbound
  bundles cryptographically.
- **Coupled spec gap (S-6):** signature recovery requires an on-chain peer-key
  registry to match against. Spec §1.5's "match against on-chain endpoint roster"
  language does not pin where that state lives; spec §2 defines no such state. The
  fix below picks one model.
- **Fix (multi-step):**
  1. Add `bytes endpoint_signature` to `clpr_submit_bundle.proto`.
  2. Persist peer endpoint signing keys per Channel. Interim shape: store the
     `ecdsa_signing_key` of every `endpoints[]` entry from the verified peer
     config, on the `ClprChannel` record. Refresh on inbound `ConfigUpdate`.
  3. In `ClprSyncWorkflowImpl.buildResponsePayload` /
     `ClprChannelManager.buildOutboundPayload`, sign the bundle payload using the
     node's ECDSA signing key.
  4. In `ClprSubmitBundleHandler.doHandle`, recover the pubkey from
     `endpoint_signature` over `keccak256(channel_id ‖ bundle_payload)` and assert
     it matches one of the persisted peer signing keys for this Channel.
- **Test:** `ClprSubmitBundleHandlerTest` — bundle with random/zero/wrong-key
  signature must be rejected with `CLPR_INVALID_ENDPOINT_SIGNATURE`. Multi-network
  E2E covering bidirectional signed delivery.

Richard: OK, this whole thing is quite a mess. We do NOT want to require only ECDSA secp256k1
because it is not quantum resistant. So I think we need to include some kind of additional
information on the bundle metadata and in the config to say "these are the signature schemes
I can read" and then in the bundle metadata we say "and this is the signature scheme I used".

That will require a fairly complex update across multiple repos. So for now, let's just write
this down into clpr-spec's open issues, and just use ECDSA secp256k1 consistently in clpr-hiero.
In other words, for now, we use secp256k1 and fix this bug so we verify and sign; and then
write an open issue to the spec repo, and in the future we'll come back and add support for
additional signature schemes.

(Incidentally, you're going to have some real trouble with this one because consensus nodes
DO NOT currently use secp256k1 keys for signing. We want to change this for event signing
as well to use secp256k1 instead of RSA, but we haven't done it yet. So each consensus node
will need a *NEW* key and none of that has been plumbed in yet. We do not need to list this
key on the address book, just in the endpoint config, but you will need to load and manage
a new public/private keypair and you will need to update the tests and such to initialize
all this properly).

### C-7. Genesis `ClprLedgerConfiguration` missing fields

- **Where:** `V0650ClprSchema.migrate` (lines 130–147) seeds only `chainId`,
  `protocolVersion`, and the throttle defaults. **Not seeded:** `service_address`,
  `timestamp`. Within throttles, `max_gas_per_message` is left at default 0.
- **Spec:** §1.1 — `ClprLedgerConfiguration` carries `chain_id`, `protocol_version`,
  `service_address`, `timestamp`, `throttles`, `endpoints`. `max_gas_per_message`
  is a required throttle.
- **Impact:**
  - **`service_address` empty:** Connector signature verification per spec §6.3
    callout uses `keccak256(connector_id ‖ service_address)`. Until an admin runs
    `updateLedgerConfiguration`, no peer can verify a Hiero connector signature.
  - **`max_gas_per_message = 0`:** every inbound `ClprSubmitBundleHandler` app
    dispatch has 0 gas budget — every bundle fails `APPLICATION_ERROR`.
  - **`timestamp = 0`:** lazy ConfigUpdate propagation triggers off staleness; with
    a zero genesis timestamp every first `sendMessage` enqueues a bogus initial
    ConfigUpdate.
- **Fix (code):** in `migrate`, set
  - `serviceAddress` to `0x16e` zero-padded to 20 bytes;
  - `timestamp` to genesis consensus time;
  - `maxGasPerMessage` to a sane default (e.g. 15M) — match the consensus gas budget
    or `appDispatchGasLimit * channel-side overhead`.
- **Test:** `V0650ClprSchemaTest` asserting the seeded singleton has `service_address.length() == 20`, `timestamp > 0`, `throttles.max_gas_per_message > 0`.

Richard: Agreed, this needs fixing.

### C-8. `pureChecks` rejects empty bundle payloads

- **Where:** `ClprSubmitBundleHandler.pureChecks` line 94 —
  `validateTruePreCheck(op.bundlePayload().length() > 0, INVALID_TRANSACTION_BODY)`.
- **Spec:** §4.2 — a pure-ack empty bundle is legitimate; the receiver still updates
  `received_message_id` to acknowledge prior delivery.
- **Impact:** today this path is dead because `ClprSyncWorkflowImpl.handleSync`
  filters empty inbound bundles before submission. But the assumption is non-
  conformant — a peer that legitimately ack-only-syncs would have its body rejected
  at HAPI ingest if the wrapping logic ever changes.
- **Fix (code):** relax the check; allow empty payloads to flow through. The bundle
  handler must still apply the queue-metadata advance from the empty bundle.
- **Test:** `ClprSubmitBundleHandlerTest.acceptsEmptyBundlePayload`.

Richard: Confirmed, this is a bug.

---

## Section 2 — Likely correctness bugs (recommend test first)

### C?-9. Bundle dispatch does not enforce control-messages-first

- **Where:** `ClprSubmitBundleHandler.java:304–498` iterates messages in given order;
  `peerConfigTimestamp` updates mid-iteration during inbound `ConfigUpdate`
  application.
- **Spec:** §4.2 — control messages MUST precede data/replies in a bundle so config
  updates land before any data message that depends on the new config.
- **Impact:** a malicious source could place a `ConfigUpdate` after a data message in
  the same bundle; the data message dispatches against the *old* peer config.
  Currently low-impact (`ConfigUpdate` only carries timestamp/chainId/protocol
  version), but it grows as control-message variants expand.
- **Fix (code):** before the dispatch loop, scan the bundle once: assert any payload
  with `hasControl()` precedes the first non-Control entry. Reject the whole bundle
  with `CLPR_INVALID_BUNDLE_ORDER` if the rule is violated.
- **Test:** craft a bundle with `[Data, Control, Data]`; expect rejection.

Richard: I think you misunderstand. If a bundle as M1->C2->M3 then M1 will be based
on the then-active config information, C2 will update the config, and M3 will be
subject to the new config from C2. If that is what the code is doing, then it is
right, and you should add a test to make sure the new config is used. There should
be a test where M1->C2->M2 works (in such a way that M2 would have failed if C2
was not applied), and we should also do the negative test where M1->C2->M2 fails if
M2 violates C2 in some way.

### C?-10. Cross-bundle response ordering may not be enforced

- **Where:** `ClprSubmitBundleHandler` has a "read-only outbound prescan" (lines
  ~213–258) that walks acknowledged outbound DATA messages and asserts in-order
  matching of replies.
- **Spec:** §4.5 — the incoming response's `message_id` MUST match the oldest
  unresponded Data Message's ID, regardless of which bundle the DATA was originally
  delivered in.
- **Risk:** verify the prescan iterates **all retained outbound DATA between
  `last_acked_with_reply` and `received_message_id`**, not just the DATA acknowledged
  within this single bundle. If only this-bundle DATA is iterated, a REPLY for
  previously-acked DATA can arrive out of order without triggering PAUSE. (Same shape
  as the EVM project's C?-7.)
- **Fix (code, after test confirms):** broaden iteration to walk outbound DATA back
  to `last_acked_with_reply`; or maintain a `next_expected_reply_id` counter on
  `ClprChannel`.
- **Test:** bundle 1 acks DATA #1 + #2 with no replies; bundle 2 carries replies for

  # 2 then #1. Expect channel → PAUSED.

Richard: I'm not sure I understand, but it must be the case that if
ledger A sends data messages A1, A2, A3, with any arbitrary control or
reponses messages sprinked in between, that the remote ledger will send
back responses for A1, A2, and A3 in order, with any ledger B data or
control messages sprinkled between.

In other words, for any response messages that come in, they must line
up with data messages that went out, in the same order in which they
went out.

### C?-11. Per-message dispatch may not isolate proto-decode reverts

- **Where:** `ClprSubmitBundleHandler` per-message dispatch (~lines 304–428). Need to
  verify whether protobuf decode of `ClprMessagePayload` and downstream payload
  manipulation (e.g. `Bytes`-to-address conversions) are wrapped in try/catch.
- **Spec interpretation (per design discussions):** a malformed payload inside a
  bundle that the verifier has already accepted indicates the *verifier* attested to
  garbage — this should PAUSE the Channel (the state proof signed off on
  nonsense). It should NOT silently skip the message (which would diverge state) and
  it should NOT revert and unwind the bundle (which would block all valid messages
  in the same bundle).
- **Risk:** if the handler reverts the entire bundle on a single bad-payload decode,
  one malformed message poisons every other message in the bundle. If it silently
  skips, state diverges from the source. PAUSE is the correct middle ground.
- **Fix (code, after test confirms):** wrap each per-message decode in `try/catch`;
  on catch, transition the Channel to PAUSED with a specific
  `CLPR_BUNDLE_DECODE_FAILED` reason and stop processing remaining messages in the
  bundle (no further state mutation).
- **Test:** bundle with one well-formed DATA followed by a malformed one; assert
  state → PAUSED, first DATA's reply is **not** enqueued (since processing stopped),
  and subsequent submitBundle on the same channel rejects until admin
  intervention.

Richard: The inability to parse a specific message is
a bad bundle and should throw us into a PAUSED state (assuming the state
proof checked out, since it means the state proved something that was
nonsense). The statement in the spec was intended only to indicate that
when we went to the application, or connector, to try to handle the message,
if it then fails, it does not impact the bundle. Only a bad bundle impacts
the bundle. A bad handling of a specific message is not a bundle problem but
a connector or application problem.

### C?-12. `redactMessage` rejects `messageId == 0`

- **Where:** `ClprRedactMessageHandler.pureChecks` line 46 —
  `validateTruePreCheck(messageId > 0, ...)`.
- **Spec:** §4.4 has no such restriction.
- **Risk:** depends on enqueue indexing. If `ClprServiceApiImpl.sendMessage` ever
  assigns `messageId = 0` to the first DATA on a Channel, that message can never
  be redacted. Verify with a unit test.
- **Fix (code, after test confirms):** if 0 is a valid message id, drop the check.
  If 0 is reserved (e.g., the schema initializes `next_message_id = 1`), document
  why and tighten the proto comment.

Richard: I believe message ID 0 is NOT valid. Check with clpr-smart-contracts. If
message ID 0 is not valid, then we should update the spec to be explicit about this.
TBH, I'm not sure if 0 should be the first message ID or if 1 should be. I thought
I saw some logic that required 1 to be the first ID for some other math to work
out cleanly. You will need to check the code here and in clpr-smart-contracts
to see how to handle this case, and then make sure the spec in clpr-spec defines
this scenario (as it impacts all implementations of CLPR on all systems).

### C?-13. `received_running_hash` initialized inconsistently

- **Where:** `ClprSubmitBundleHandler.java:171–179` treats `length() == 0` as the
  "first bundle" sentinel, prepending 32 zero bytes. The schema seeds the field on
  Channel creation.
- **Spec:** §4.1 — initial running hash is exactly 32 zero bytes.
- **Risk:** if the schema initializes to empty bytes (length 0) and the handler also
  treats that as "use 32 zeros", the persisted state mixes representations of the
  same logical value. Equality comparisons on the field elsewhere in the code may
  silently disagree.
- **Fix (code, after test confirms):** schema initializes to `Bytes.wrap(new byte[32])`
  on Channel creation; remove the length-0 special case in the handler.

Richard: I don't understand the issue here, I'm missing something subtle. Double
check and make sure you understand this one correctly, and take the appropriate action.

---

## Section 3 — Incomplete vs spec

### I-1. Missing pseudo-API ops

- **Missing:** `getQueueDepth(channel_id)` (spec §6.2). Information is reachable
  via `ReadableChannelStore.getChannel(...).nextMessageId() -
  ackedMessageId()`, but no named HAPI query exposes it. Hiero docs originally
  claimed §6.2 was covered — already corrected.
  - **Decision (Richard):** low priority. We know the depth internally; an explicit
    API isn't critical. Either add a thin query handler or document the absence.
- **Deliberately missing:** `topUpConnector(amount)` /
  `withdrawConnectorBalance(amount)` (spec §6.3). The Hiero `ClprConnector` record
  has `locked_stake` but no `balance` field; execution payments draw from the
  connector's *contract account* tinybar balance. Topping up = sending HBAR to the
  contract address via `CryptoTransfer`; withdraw = ordinary contract logic.
  - **Decision (Richard):** these ops shouldn't exist; the spec should be updated.
    Tracked as S-7 (spec gap) below.
- **Missing:** abandoned-PENDING cleanup. `closeChannel` rejects everything that
  isn't ACTIVE/PAUSED, so commitments in `PENDING_COMMITMENTS` accumulate.
  - **Decision (Richard):** `closeChannel` should sweep both `CHANNELS` and
    `PENDING_COMMITMENTS`. See I-3 below.
- **Missing in part:** `submitBundle` does not accept `endpoint_signature`. See C-6.

Richard: In this case, DO NOT add a query to HAPI for getQueueDepth. Just, don't.

### I-2. Endpoint roster is consensus-roster-derived (no on-ledger ClprEndpointRoster)

- **Spec doc §3.1.2:** describes an on-ledger roster keyed by `channel_id`.
- **Spec doc §6.5:** says "On platforms where endpoints are derived automatically
  from the consensus roster (e.g., Hiero), [registerEndpoint/deregisterEndpoint]
  operations are not needed."
- **Hiero:** uses `ReadableNodeStore` directly for the local roster. No persisted
  `ClprEndpointRoster` state. There is an in-memory `peerEndpointCache` in
  `ClprChannelManager`, seeded from `endpoints`, restart-wiped, with the
  spec's cap-of-10 unenforced on consumption.
- **Decision (Richard):** the local roster is rightly not per-channel — "my
  endpoints" are global within the CLPR Service. **Peer** endpoints, however, MUST
  be tracked per-channel (they're the signature-recovery match target — see C-6).
- **Fix (code):** persist a per-channel peer signing-key set on
  `ClprChannel`. Initial population from the verified peer config's
  `endpoints` (cap 10). See C-6 for the full plan.
- **Fix (peer cache):** enforce `Math.min(10, peerConfig.seedEndpoints.size())` in
  `ClprChannelManager.seedEndpointsFromConfig`. A malicious peer publishing >10
  seed endpoints should not bloat Hiero's cache.

Richard: right.

### I-3. `closeChannel` cannot close PENDING / abandoned commitments

- **Where:** `ClprCloseChannelHandler.doHandle` calls
  `requireActiveOrPausedChannel`, which rejects every other status with
  `CLPR_INVALID_CHANNEL_STATUS`. Abandoned entries in `PENDING_COMMITMENTS` have
  no admin sweep path.
- **Spec doc §3.1.3 / §5.1.4:** admin can `closeChannel` on a PENDING channel
  to delete an abandoned commitment.
- **Decision (Richard):** `closeChannel` should visit both `CHANNELS` and
  `PENDING_COMMITMENTS` as needed — single API, no commitment leak.
- **Fix (code):** add a branch in `ClprCloseChannelHandler.doHandle`: if the
  Channel record doesn't exist for `channelId`, look up the matching
  commitment in `PENDING_COMMITMENTS` (by reverse-lookup or pass commitment
  explicitly) and remove it; admin-only. Whether the body needs an extra parameter
  to identify the commitment depends on whether `channelId` is known at this
  stage — for the Hiero "PENDING-as-commitment-only" model the tx body may need to
  carry `ownership_commitment` for this admin-cleanup case.
- **Test:** `ClprCloseChannelSuite.adminClearsAbandonedCommitment`.

Richard: correct.

### I-4. Endpoint deregister has no in-flight gate

- **Spec:** §6.5: "MUST NOT deregister if the endpoint has in-flight sync submissions."
- **Hiero:** consensus nodes are endpoints by virtue of the address book; there is no
  separate Hiero `deregisterEndpoint` op. A node "deregisters" by leaving the
  network via the standard address-book mechanism.
- **Decision (Richard):** the spec rule is questionable for Hiero. A consensus node
  cannot submit a bundle and then unilaterally vanish — the bundle either pays the
  payer's account or doesn't make it onto a block. Slashing is decided at handle-
  time on the bundle in question, not on a separate later deregister event.
- **Recommendation:** mark this rule platform-specific; on Hiero it's enforced
  implicitly by the consensus-roster lifecycle. Document as E-3 (Hiero
  specialization).

### I-5. Resolved: `clpr.enabled` defaults to `false`

- **Resolution:** `ClprConfig` and the checked-in node configuration now default
  `clpr.enabled` to `false`.
- **Spec §7:** `clprEnabled = false` is the recommended default.
- **Decision (Richard):** lower priority than originally framed; the master
  kill-switch is mostly there in case something goes wrong post-launch. For staged
  rollout the spec's safer default still applies.
- **Note:** the kill-switch itself is wired (`AbstractClprHandler` checks
  `clpr.enabled`; `ClprSystemContract` halts with `CLPR_NOT_ENABLED`).

Richard: That is right, this flag must be false by default until we are ready to go live
in production. Under no circumstance do we want this to go live prematurely.

### I-6. Sync timer hardcoded to 1 second

- **Where:** `ClprChannelManager.start` (~line 108) ticks every 1 s.
- **Spec §7:** sending endpoints SHOULD self-pace using
  `sync_interval_ms = 1000 * num_local_endpoints / max_syncs_per_sec`.
- **Impact:** Hiero ignores `maxSyncsPerSec` from the peer config; small networks
  hammer the peer, large networks under-sync.
- **Fix (code):** derive the tick interval from peer config and local node count.
- **Test:** unit test on the orchestrator with seeded peer config = 10 syncs/sec
  and 5 local endpoints — assert tick interval = 500ms.

Richard: You are right, this should not be hard coded. Fix.

### I-7. Inbound throttle is per-Channel wall-clock, not per-peer-endpoint sync-rounds

- **Where:** `InboundSyncThrottle` (sliding-window wall-clock, threshold from local
  `clpr.maxInboundSyncsPerSec`); peer key is derived from
  `endpointSignature.toHex()` — and signatures are always empty (C-6) — so throttle
  collapses to per-Channel.
- **Spec §1.6:** receiver enforces fair share `max_syncs_per_sec / num_peer_endpoints`
  per peer endpoint, ~10% tolerance, measured in sync rounds (not wall-clock).
- **Impact:** the per-endpoint shun mechanism doesn't exist; one misbehaving peer
  endpoint cannot be shunned without also penalizing well-behaved peers on the same
  Channel.
- **Fix (code, after C-6 lands):** derive peer key from recovered ECDSA pubkey;
  threshold from `peer_throttles.max_syncs_per_sec / num_peer_endpoints`; replace
  wall-clock with consensus-round counters.

Richard: OK, let's fix this. But also, we shouldn't assume that the signature will be
recovered from ECDSA. If we need the signature, we should probably add the necessary
structures to the Protobuf an spec so we don't have to rely on recovery.

### I-8. `connectorContract` parameter naming throughout the API surface

- **Where:** `ClprServiceApi.sendMessage` (line 39) and `ClprServiceApiImpl.sendMessage`
  parameter is named `connectorContract`. The actual value carried at every call
  site is the 32-byte derived `connector_id` (`SendMessageCall` → `Bytes.wrap(connectorId)`
  → `ClprServiceApiImpl` → `new ClprConnectorKey(channelId, connectorContract)`).
  The proto field is correctly named `connector_id`.
- **Impact:** future maintainers misread the API as accepting an EVM address.
  Already caused doc drift (D-4 below).
- **Fix (code):** rename `connectorContract` → `connectorId` in the SPI and impl;
  no behavior change.

Richard: OK fix.

### I-9. `endpointPenaltyTinybars` config is dual-purpose

- **Where:** `ClprConfig.endpointPenaltyTinybars` is used both as an **endpoint
  payout cap** (in `ClprSlashingUtils.reimburseEndpoint`) and as the **penalty
  charged for endpoint misbehavior** (in `ClprSubmitBundleHandler` validation
  failure paths).
- **Impact:** confusing knob; tuning one purpose tunes the other.
- **Fix (config):** split into `endpointPayoutCapTinybars` and
  `endpointMisbehaviorPenaltyTinybars`.

Richard: Before making the fix, or settling on what the fix is, please look at
what clpr-smart-contract is doing. I have already reviewed what it is doing, and
I think it make sense. In any event we need to have the same behavior on both
implementations, and I think this should also be called out in the spec (and maybe
it is now, since the other agent may have updated it).

---

## Section 4 — Spec gaps (need spec updates)

### S-1. Canonical specs disagree on PENDING Channel state

- **Spec doc §3.1.3:** "no PENDING state for Channels themselves; temporary
  ownership commitments are tracked separately until revealed."
- **Protocol spec §2.1.1:** lists `PENDING` in the `ClprChannelStatus` enum.
- **Hiero:** matches the design doc — no `ClprChannel` row is created at
  register; commitment lives in `PENDING_COMMITMENTS` until reveal. The proto
  enum carries PENDING as `0` to match the wire enum, but no row ever uses it.
- **Recommendation:** raise with spec authors. The design doc model is more
  storage-efficient; the protocol spec model is more uniform. Either is workable
  but the two specs must agree.

Richard: I am the spec author. And you helped write it ;-). Anyway, check the
spec again to see if it has been clarified, otherwise look at clpr-smart-contract
and we should follow its lead and spec it if it has not already done so.

### S-2. Connector identity model diverges between specs

- **Design doc §3.3.1:** Connector ID is a 32-byte derived value
  `keccak256(channel_id ‖ public_key ‖ salt)`; registration is commit-reveal.
- **Protocol spec §2.2 / §6.3:** raw `source_connector_address`; no derivation, no
  commit-reveal.
- **Hiero:** matches the design doc exactly (`ClprCompleteConnectorHandler.deriveConnectorId`
  lines 154–160; PENDING_CONNECTOR_COMMITMENTS state).
- **Recommendation:** spec authors should reconcile in favor of the derived-ID
  model — it provides anti-squatting and stable cross-ledger identity by
  construction. The protocol spec needs updating.

Richard: Look at clpr-smart-contract, it is right. And I think the spec has been
updated in this regard. Reread it and check. But we should be doing the design doc
approach not the protocol spec source_connector_address thing.

### S-3. `verifier_contract` placement diverges between specs

- **Design doc §3.1.3:** `verifier_contract` and `config_proof` are revealed in
  Phase 2 (`completeChannel`).
- **Protocol spec §6.2:** lists them on `registerChannel`.
- **Hiero:** matches the design doc — `clpr_register_channel.proto` carries only
  `ownership_commitment`; `clpr_complete_channel.proto` carries
  `verifier_contract` and `config_proof_bytes`.
- **Recommendation:** spec authors should reconcile in favor of the design doc —
  putting verifier setup in Phase 2 keeps Phase 1 as a pure commitment.

Richard: I believe the design doc is right. Double check the spec, it might have
been fixed. If it is not in agreement, check with clpr-smart-contract to see what
it is doing, as it is a correct implementation to the best of my knowledge.

### S-4. `max_syncs_per_sec` advisory vs enforced

- **Design doc §3.1.6:** locally enforced; receivers MUST shun peers exceeding fair
  share.
- **Protocol spec §1.1 / §7:** comment labels it "Advisory."
- **Hiero proto:** `clpr_ledger_configuration.proto:61` says "Advisory" (matches
  protocol spec). Hiero's `InboundSyncThrottle` does enforce something
  rate-limit-like but at coarser granularity (see I-7).
- **Recommendation:** spec authors should adopt the design doc's enforcement model;
  misbehavior detection requires real enforcement. Once resolved, Hiero proto
  comment must be updated and per-peer enforcement implemented (I-7).

Richard: OK, technically the spec is right, it is advisory. But any endpoint that
doesn't do this will be taken advantage of by griefers. So they should all do it.
But there is no way to mandate it. So MUST is right in concept, but unenforceable.
Update the design or spec as appropriate to clarify this.

### S-5. `max_bundles_per_sec` field placement

- **Design doc §3.1.2 + §7 table:** describes a per-endpoint enforced parameter.
- **Protocol spec §1.1:** `ClprThrottles` ends at field 6; no `max_bundles_per_sec`.
- **Hiero:** matches the protocol spec proto (no field). Per-endpoint bundle
  throttling is therefore not enforceable at the protocol level on either side.
- **Recommendation:** if per-endpoint throttling is wanted, the field must be added
  to `ClprThrottles` (per design doc) and `ClprSubmitBundleHandler` must enforce
  per-endpoint share. If not, drop from the §7 table.

Richard: Yes, must be fixed. Please check with the spec and design doc to see
if this has been fixed. Also check with clpr-smart-contract project to see what
it believes.

### S-6. On-chain peer signing-key registry is implicit but not defined

- **Protocol spec §1.5:** `endpoint_signature` recovery "matches against on-chain
  endpoint roster." But spec §2 defines no such roster (this is the same shape as
  S-2 and S-3 — design doc has it, protocol spec doesn't).
- **Impact:** signature recovery (C-6) requires SOME on-chain peer-key state.
  Without spec resolution, every implementation invents its own.
- **Recommendation:** pin a single model. Hiero's interim direction (per-Channel
  peer signing-key set, populated from `endpoints`, capped at the seed
  endpoint count) is consistent with the spec doc's `endpoints` mechanism but
  caps the peer roster at 10. A richer "peer roster Control Message" mechanism may
  be needed for production-scale.

Richard: Double check the spec, it may have been updated to resolve this question.
The spec should just say the peer endpoints are listed per channel with a
maximum of 10 peers per channel and in the 'open questions' doc in the spec
repo we should talk about the problem and how we want to fix this by uncapping
and allowing all registered endpoints to be listed and using some control messages
to this effect.

### S-7. `channel_id` is "registrant-chosen" in spec but possibly derived in code

- **Spec:** the `channel_id` is an arbitrary 32-byte value chosen by the
  registrant (same on both ledgers) — see spec §2.1 / §6.2. Hiero handler
  implementation (`ClprCompleteChannelHandler` lines 88, 100–101) treats it as
  opaque input.
- **However:** `clpr_channel.proto:58–60` proto comment claims
  `keccak256(uncompressed_public_key)`. The comment is wrong; the code is right.
  The EVM project has actually moved to a derived `channel_id` model, which is
  *also* not what the canonical spec says.
- **Decision (Richard):** the spec is wrong / out of date here. A derivation needs
  to be defined and made consistent across `clpr-evm`, `clpr-hiero`, and any
  relays; the sort/binding rule must be canonical.
- **Recommendation:** raise as a spec issue. Until then, fix the Hiero proto
  comment (D-6) to reflect the spec's "registrant-chosen" model, since that's what
  the code actually does.

### S-8. Connector `balance` field

- **Spec §2.2:** declares `balance` as a Connector field for execution payments.
- **Hiero:** `ClprConnector` proto has only `locked_stake`; execution payments draw
  from the connector contract's account tinybar balance.
- **Decision (Richard):** prefer the contract-account model uniformly; the spec
  should be updated. There's no need for a separate `balance` field if the
  contract account always holds operating funds.
- **Recommendation:** update spec §2.2 to permit/mandate the contract-account model.
  No Hiero code change needed.

### S-9. Initial trust anchor

- **Where:** the EVM project's `completeChannel(... bytes initialTrustAnchor)`
  takes a per-verifier configuration blob (e.g., validator public-key list, ledger
  ID set). Hiero's `clpr_complete_channel.proto` carries `config_proof_bytes`,
  not a separate trust anchor.
- **Decision (Richard):** the trust anchor concept is missing from the design doc
  and protocol spec. Some verifiers need a list of validators / public keys; some
  need only a ledger ID. The spec must add a verifier-specific anchor parameter,
  and Hiero will need to add it too.
- **Recommendation:** raise as a spec issue. Until resolved, Hiero is consistent
  with the current spec but will need an additive field once the spec lands.

### S-10. `ClprBundleContent` is implemented but absent from the canonical spec

- **Where:** Hiero `clpr_bundle_content.proto` defines the message returned by
  `ClprVerifier.verifyBundle` (queue metadata + repeated `ClprMessagePayload`).
  Same shape as the EVM project's `ClprBundleContent` (per the EVM drift review
  S-2). Neither canonical spec defines this message.
- **Decision (Richard):** check what the parallel Hiero/EVM projects do; align.
- **Recommendation:** if both clpr-hiero and clpr-evm independently arrived at the
  same `ClprBundleContent` shape, promote it to the canonical spec §1 (alongside
  `ClprSyncPayload`). Keeps cross-implementation verifiers aligned.

### S-11. PQ extensibility / signature scheme

- **Spec §6.2:** lists no `signatureScheme` parameter to `completeChannel`, but
  §5.1.3 hints at PQ extensibility.
- **Hiero:** adds a `ClprSignatureScheme` enum to `clpr_complete_channel.proto`
  (`ECDSA_SECP256K1` and `ED25519`).
- **Decision (Richard):** signature scheme is ledger-specific. We're not PQ for
  v1. The spec should document "ledger-specific; consult your platform docs."
  Hiero uses both ECDSA and ED25519; EVM uses ECDSA.
- **Recommendation:** raise as a spec issue (open question, document path forward).
  No Hiero change needed beyond E-2 below.

---

## Section 5 — Acceptable Hiero specializations missing from any platform-spec

These are valid Hiero implementation choices that diverge from spec because the spec
is platform-agnostic, but they are not currently documented anywhere as the Hiero
platform's binding. Recommend a new doc `docs/design/services/clpr-service/hiero-platform-spec.md`
(or annexed section in the impl docs) recording:

### E-1. CLPR Service address is `0x16e`

- The Hiero CLPR Service is the system contract at EVM address
  `0x000000000000000000000000000000000000016e`. This is the value bound into
  `keccak256(connector_id ‖ service_address)` for connector registration signature
  verification (spec §6.3 callout — already there).

Richard: This is fine, and is a hiero specific detail. It should not be in the clpr-spec
repo, but should be described in a hiero-spec document here in this repo.

### E-2. Both ECDSA-secp256k1 and ED25519 supported on `completeChannel`

- The `ClprSignatureScheme` enum in `clpr_complete_channel.proto` allows both
  schemes; signature verification dispatches via `CryptographyProvider.verifySync`.
  Hedera accounts commonly use Ed25519; ECDSA-secp256k1 is also standard.
- Pubkey lengths: 64 bytes for ECDSA (uncompressed `x ‖ y`, no `0x04` prefix),
  32 bytes for Ed25519. Signature length is 64 bytes for both schemes (no recovery
  byte for ECDSA — sufficient for verification, insufficient for address recovery).
- See related discussion under S-11.

Richard: Fine, list in the hiero-spec document in this repo (maybe we call it clpr-hiero-spec.md)

### E-3. Local endpoints derived from consensus roster (no on-ledger ClprEndpointRoster)

- Per spec §6.5 platform-managed clause. `ClprSubmitBundleHandler` reads endpoint
  identity via `ReadableNodeStore.get(endpointNodeId)`. There is no separate
  `registerEndpoint` / `deregisterEndpoint` HAPI tx; consensus roster changes
  propagate naturally.
- I-4 (deregister with in-flight messages) is implicitly handled by the consensus
  lifecycle.

Richard: Good. Make sure the generic spec doesn't mandate such an API to exist, it should be
a specific choice by hiero vs. EVM or other ledger. Different ledgers can decide how endpoints
are listed.

### E-4. Bundle submission via empty-SignatureMap node tx

- `ClprBundleSubmitter` wraps inbound bundles in a `ClprSubmitBundleTransactionBody`
  with an empty `SignatureMap`; identity comes from the platform event-level
  signature (same path as TSS / Hints submissions). Requires `ClprSubmitBundle` in
  `networkAdmin.nodeTransactionsAllowList` (default).
- This is **not yet sufficient for cross-ledger trust** — see C-6 for the missing
  cryptographic endpoint signature.

Richard: OK, C-6 is separate. It is good we document this in this repo, but not elsewhere.

### E-5. PrivilegesVerifier-based admin gating

- Admin-gated CLPR ops (`closeChannel`, `redactMessage`, `updateLedgerConfiguration`)
  are gated by `PrivilegesVerifier.checkClprAdmin` — payer must be **treasury or
  systemAdmin**. There is no separate "CLPR admin key" stored in state or config.
  This matches the standard Hedera platform admin model.

Richard: OK, we should make sure the general spec doesn't require an admin key on the ClPR Service,
since this implementation of CLPR won't have one, and we should document who is the admin
(the 0.0.2 key or whatever) in the hiero level docs.

### E-6. Per-connector queue quota (`connectorQueueQuotaPct`)

- A Hiero invention not in either canonical spec. Default 50% — a single connector
  cannot occupy more than half the channel's `max_queue_depth`. Defensible
  anti-griefing against a misbehaving connector that floods the queue.
- Trade-off: O(queueDepth) scan in `ClprServiceApiImpl.countConnectorMessages` on
  every `sendMessage`. Consider an aggregated counter on the `ClprConnector` record.

Richard: I think this may be something we can put in the general spec and make
consistent. How does clpr-smart-contract handle this? The right way to do this on hiero
would be to say that the maximum number of slots any given connector can use is paid
for by the connector when registered (and in the future, when computing rent). Basically,
we want to make sure connectors who use more space pay for it. And maybe we do the
same in the EVM space.

### E-7. Geometric slashing escalation

- Code: `penalty = basePenalty * multiplier^slashCount`, capped at `lockedStake`,
  with auto-ban at `slashBanThreshold`. Defaults: base 10M tinybars, multiplier 2,
  threshold 5 → slashes are 10M, 20M, 40M, 80M, then ban-and-forfeit.
- **Cross-impl note (Richard):** the EVM project also implements geometric
  escalation. Recommendation: promote the formula to the canonical spec §4.6 so
  all implementations share the same escalation curve. (See the EVM project's
  E-7.)

### E-8. `ClprBundleContent` PBJ wire format for verifier returns

- Verifier contracts on Hiero must emit a PBJ-encoded `ClprBundleContent`
  (queue metadata + repeated `ClprMessagePayload`) as the return of
  `verifyBundle`. `EvmClprVerifier` parses this format. See S-10 for the
  cross-impl spec gap; until S-10 resolves, this is the de-facto Hiero ABI.

### E-9. Lazy ConfigUpdate prepended at send

- Hiero implements spec §1.3 + §4.3 step 1a in `ClprServiceApiImpl.sendMessage`
  (lines 124–141): when the channel's `lastConfigTimestamp` is older than the
  singleton `ClprLedgerConfiguration.timestamp`, a `ClprControlMessage.configUpdate`
  is prepended to the queue at `nextMessageId` before the data message. Reserves
  an extra queue slot so the prepend can't push past `maxQueueDepth`.
- Mirrored on inbound in `ClprSubmitBundleHandler` (lines 280–293).

### E-10. Native `messageExecutionCost` (currently flat)

- **Where:** `ClprConfig.messageExecutionCost = 1_000_000` tinybars (flat, regardless
  of actual gas). `worstCaseCharge = messageExecutionCost + margin` is charged on
  every successful inbound dispatch.
- **Decision (Richard):** *intent* is "submitter is reimbursed for actual gas + a
  margin to incentivize honest submission." Today's flat fee under-recovers
  endpoint costs at high gas usage. The model should be:
  - Charge connector `gasUsed * gasPriceTinybars * (1 + margin)`.
  - Pay the entire amount to the submitter (there is no separate treasury
    account on Hiero — submitter receives the full charge).
- **Fix (code):** measure actual gas in the dispatch, charge connector with margin,
  pay submitter the full amount.
- **Doc updates:** `config-and-slashing.md` should add a "Success-path economics"
  subsection making the flow explicit (charge → endpoint, no central treasury).

---

## Section 6 — Documentation accuracy issues in `docs/`

### D-1. `handlers.md` — fictional CLPR admin key (FIXED)

The doc previously described a per-handler "configured CLPR admin key" check that
doesn't exist; admin gating is via `PrivilegesVerifier.checkClprAdmin` (payer =
treasury/systemAdmin). Already corrected.

### D-2. `handlers.md` — fictional banned-connector check on deregister (FIXED)

The doc claimed `ClprDeregisterConnectorHandler` refuses banned connectors. The code
does not implement this, and the spec's actual rule is "no in-flight messages."
Already corrected to flag the gap (see C-4).

### D-3. `evm-integration.md` — false claim that `authorizeMessage` is invoked (FIXED)

The doc described per-message authorization as implemented via sub-call. The code
explicitly defers it. Already corrected to a KNOWN-GAP note pointing to C-5.

### D-4. `state-and-protobufs.md` — `connectorContract` instead of `connector_id` (FIXED)

`ClprConnectorKey`'s second field is `connector_id`. Doc previously said
`connectorContract`. Already corrected.

### D-5. `clpr_channel.proto:58–60` — `channel_id` derivation comment is wrong

- **Where:** comment says `"32-byte primary key, computed as keccak256(uncompressed_public_key)"`.
- **Reality:** code treats `channel_id` as opaque registrant input.
- **Action:** rewrite the proto comment to "arbitrary 32-byte value chosen by the
  registrant; same on both ledgers" (per spec §6.2). Tracked as part of S-7.

### D-6. `clpr_ledger_configuration.proto:61` — `max_syncs_per_sec` "Advisory"

- **Where:** field comment says "Advisory: suggested maximum sync frequency."
- **Reality:** matches the protocol spec wording but disagrees with the design
  doc's enforcement model (S-4). Hiero's `InboundSyncThrottle` does perform
  rate-limiting (per I-7).
- **Action:** rewrite once S-4 resolves. Until then, the comment is accurate to
  the protocol spec — leave as-is and reference S-4 in a follow-up.

### D-7. `verifier.md` — doesn't document the `ClprBundleContent` ABI requirement

- **Reality:** `EvmClprVerifier` parses verifier-call return bytes as a PBJ-encoded
  `ClprBundleContent`. Verifier-contract authors need to know the wire format.
- **Action:** add a "Verifier ABI: returned PBJ wire format" section to
  `verifier.md` documenting the expected return shape.

### D-8. `config-and-slashing.md` — under-describes success-path economics

- **Reality:** every successful inbound dispatch pays the submitting endpoint
  `messageExecutionCost + margin` directly from the connector account. The doc
  emphasizes slashing payouts but never walks through the steady-state success-
  path revenue flow — readers reasonably (but incorrectly) infer that endpoints
  earn only from slashing.
- **Action:** add a "Success-path economics" subsection. Tie into the E-10 fix
  when measured-gas charging lands.

### D-9. `ClprServiceApi.java` / `ClprServiceApiImpl.java` parameter naming

- **Where:** the `connectorContract` parameter (line 39 in `ClprServiceApi`,
  similar in impl).
- **Action:** rename to `connectorId`. See I-8.

### D-10. `testing.md` — `ClprHieroToHieroSuite` only polls counters

- **Reality:** `oneWayDelivery` / `fullRoundTrip` only assert `receivedMessageId`
  and `ackedMessageId` advance. Spec test 4.1.1 demands assertions on response
  status SUCCESS, application-side receipt, and queue-metadata consistency.
  Counters increase on `APPLICATION_ERROR` / `CONNECTOR_NOT_FOUND` too — false
  positives are possible.
- **Action:** `testing.md` should explicitly call this out as a known-weak
  assertion. Test code itself (in §7 below) should grow proper application-side
  assertions.

### D-11. `handlers.md` doesn't mention the missing REDACTED reply path

- See C-1. The handler doc should note that the inbound dispatch loop has a known
  gap on redacted slots until C-1 is fixed.

---

## Section 7 — Test coverage gaps vs `clpr-test-spec.md`

Of the spec's ~80 numbered test cases, Hiero has strong coverage in §3.1–§3.5
(single-ledger lifecycle and bundle handling) and partial coverage in §3.7
(redaction). Most §4 (multi-ledger), §5 (adversarial), §6 (apps), §7 (perf), §8
(recovery) are uncovered.

### Highest-priority test additions (ordered by safety/criticality)

1. **3.6.2 / 5.4.1** — automatic ACTIVE→PAUSED transition on response-ordering
   violation. Single-ledger seedable.
2. **3.8.2 / 5.3.1 / 5.3.2** — source-side slashing on `CONNECTOR_NOT_FOUND` /
   `CONNECTOR_UNDERFUNDED`. Math is unit-tested but the response→stake-mutation
   pipeline isn't.
3. **3.10.1 / 3.10.2 / 3.5.4 / 5.2.6** — bundle-level enforcement of
   `max_messages_per_bundle`, `max_sync_bytes`, per-message payload size, non-
   contiguous IDs.
4. **3.12.1 / 3.12.3** — mixed-message-type bundle (Data + Reply + Control) and
   partial bundle failure independence (per C?-11 semantics).
5. **3.13.1 / 3.13.2** — redaction × bundle verification (depends on C-1 fix).
6. **5.3.4** — `authorizeMessage` rejection on send (depends on C-5 fix).
7. **3.3.3 / 5.3.3** — deregister-with-in-flight-messages rejection (C-4 fix).
8. **3.2.1b** — admin closes a PENDING / abandoned commitment (I-3 fix).
9. **4.1.3** — multi-network coverage of full PAUSED/CLOSING/DRAINED/CLOSED
   lifecycle under traffic.
10. **3.3.2** — `topUpConnector` / `withdrawConnectorBalance` (or document
    deliberate omission per S-7).

### Drift in existing tests

- **`ClprConnectorSuite.canRegisterConnector`** — exercises commitment-only
  registration without first establishing an ACTIVE Channel. Spec test 3.3.1
  requires the prerequisite; the newer
  `ClprCompleteConnectorHandlerTest.rejectsWhenChannelNotFound` does enforce
  it. Retire the legacy entry-point assertion.
- **`ClprHieroToHieroSuite.oneWayDelivery / fullRoundTrip`** — D-10 above.
  Polls counters only; missing SUCCESS-status / app-receipt / queue-metadata
  consistency assertions.
- **`ClprCloseChannelSuite.nonAdminCannotCloseChannel`** — accepts both
  `AUTHORIZATION_FAILED` and `NOT_SUPPORTED`. The latter reflects an unfinished
  throttle-layer wiring rather than a spec response. Tighten when throttle
  finishes.
- **`ClprSubmitBundleSuite.rejectsMalformedPayload`** — asserts
  `CLPR_BUNDLE_VERIFICATION_FAILED` but does not assert that the Channel
  remains ACTIVE post-rejection. Spec 5.1.4: malformed payload from a malicious
  verifier MUST not poison Channel state on Hiero — should be tested per
  C?-11.
