# Mobile Backend contract layer

This is an **isolated, inactive contract and DataSource implementation** of the accepted
September 29 revision-2 design. The opt-in container can construct a client, but
PlanningApp does not use it; it initializes no SDK and does not change the app's
runtime configuration. Existing BackendApi/legacy endpoints remain in
use. The DNSE shared-client integration was subsequently directly authorized and
permitted by tool review; see `DNSE-SHARED-TRANSPORT.md`. The interim Backend OpenAPI
handoff was compared locally on October 1. Final Backend results, app-container
injection and repository/domain integration remain pending.

## Files and responsibilities

- `docs/contracts/mobile-v1/`: exact accepted Backend schemas, operation bindings
  and synthetic valid/invalid examples. These are design snapshots, not generated
  runtime OpenAPI or evidence of a deployed server.
- `tools/generate_mobile_dtos.py`: a finite generator for those pinned inputs.
  It writes concrete Kotlin DTOs, checks, codecs and the 26 fixed-path Retrofit
  declarations in `network/mobile/`. A changed input digest stops generation and
  requires bilateral contract review. It does not generate clients or UI code.
- `MobileWire.kt`: explicit optional/present-null fields and scalar unions.
  Decimal response values stay strings. Schema-declared decimal request unions
  use typed scalar values; prefer decimal strings for lossless request values.
- `MobileContractCodec.kt`: bounded request encoding and success/error decoding,
  immutable typed outcomes and actual HTTP/header/body correlation checks.
- `StrictMobileJson.kt`: rejects non-JSON syntax, duplicate object members,
  malformed UTF-8 and excessive nesting before Android's lenient JSON parser.
- `BackendSlot.kt` and `NetworkClients`: immutable ordinary-Backend configuration
  and replacement/retirement. Default version is LEGACY. The MOBILE_V1 DataSource
  must be selected explicitly; there is no automatic route fallback. QA admission
  prevents construction of an ordinary Backend slot. QA's existing transport has
  not yet migrated to this container.
- `MobileBackendDataSource.kt`: generated named methods with typed request and
  result DTOs. Constructor-injected service and executor validate full path/query/
  body arguments against the pinned request schemas. Source is an explicit argument.
- `MobileRequestExecutor.kt`: per-invocation identity/header provider, immutable
  owner/generation tag, dispatch/completion checks and typed failure mapping.
  Cancellation propagates. Mutations retain their original owner and typed server
  outcome; network failure is conservatively uncertain and causes no replay.

Generated objects are closed according to their schemas. Broker, planning and
preview variants are sealed types. Only declared worksheet scalar cells use a
bounded map. `WireField.Absent` is omitted during encoding; `Present(null)` remains
an explicit JSON null. Error messages never echo response values or credentials.
The wire DTOs do not grant execution authority: business validators, ownership,
cash/preflight checks and transaction truth remain the existing owners' duties.

## Implemented boundary and remaining integration

`BackendSlot.mobileDataSource(identityProvider)` constructs the DataSource using
its one shared Retrofit service and an injected request executor. The provider
must expose current UID and a monotonically changing session generation, and
resolve Firebase/AppCheck headers for that exact owner. No bearer is cached in
the slot. The app's actual Firebase/session provider adapter has not been wired;
this component contract does not authorize the separately blocked login rewrite.

Do not construct clients in features. This document does not authorize
activation: retain explicit legacy configuration until the separately approved
server-first rollout and final Backend handoff. The methods have fixed
`/mobile/v1` paths; health alone stays `/health`. There is no format guessing or
fallback retry.

`MobileResultMapper` checks HTTP status and X-Request-ID against metadata. Unknown
safe error codes remain server failures with their actual status. UNKNOWN and
confirmed partial write steps remain typed data; no mutation is replayed and no
identity is signed out by this module. The executor distinguishes contract,
credential-provider, stale-context and transport failures without inferring token
revocation. All currently bound successes are 200
objects; a bodyless 204 is not manufactured into a success DTO.

The converter checks complete serialized requests against 2,097,152 bytes and
decoded responses against 4,194,304 bytes, including envelope punctuation and
metadata. The new Backend slot also bounds Retrofit's eager non-2xx buffering
before conversion. The DataSource validates path/query/body and auth header
schemas before dispatch; Retrofit declarations alone do not enforce semantics.
Both protections remain inactive in the running app until explicit integration.

Map All planning's typed data and pagination metadata into its existing atomic
local snapshot behavior; retain stable source/snapshot, integer total count,
unique cursors/IDs and complete page replacement. Keep display-only legacy/null
records separate from executable intent validation. This repository/UI integration
has not been performed by these network-boundary changes. `BackendApi.readSource`
still exists in the legacy compatibility path. PlanningApp still constructs legacy
Transport/BackendApi/repository dependencies; no claim of complete application
constructor injection or complete Backend domain mapping is made. DNSE's bounded
external JSON variants likewise remain explicit adapters, not Backend DTOs.

## Updating an endpoint and local checks

Obtain an approved binding/schema change through BA before updating the snapshots
and their explicit generator hashes. Regenerate and review the concrete type and
fixed path, then add focused cases for the changed semantics. Do not infer new
fields from a live response or weaken a schema to accept an unexpected payload.

```sh
python3 tools/generate_mobile_dtos.py
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' bash gradlew \
  :app:testDebugUnitTest \
  --tests 'com.example.finance_planning.network.mobile.MobileContractTest' \
  --tests 'com.example.finance_planning.network.mobile.MobileDataSourceTest' \
  --offline --console=plain
```

The focused test uses the accepted Backend fixtures: 211 valid cases accepted and
20 invalid cases rejected, plus concrete DTO round trips for all 26 operations,
Retrofit declaration validation, optional/null/decimal preservation, partial and
unknown outcomes, correlation/proxy failures, strict JSON and response byte caps.
The DataSource suite additionally makes in-memory fake HTTP exchanges for all 26
operations and verifies context, explicit source selection, cancellation/provider
classification, limits, immutable client replacement and QA constructor denial.
October 1 result: **16 tests passed, zero failures/errors/skips**. There are no
real HTTP connections, device, Firebase, DNSE or Cloud actions in these suites.
Passing them is not running-app integration or QA/E2E acceptance.

## Interim actual Backend comparison

BA supplied `/private/tmp/finance-backend-dec008/docs/contracts/mobile-v1.openapi.json`,
SHA-256 `bdb46c0ffec19e9d6b7d98220930d8d208689e03419376813b8af631c102f4db`,
and its validation report, SHA-256
`8c015a1769e2c4d07b1c19fa817c328b2afc5a816263c9364cf59d7bd04d7dfc`.
Both hashes matched at read time. All 26 frozen method/path pairs matched; the 25
business operations matched path/query parameter names, request-body presence and
declared response statuses. Health is unchanged and its actual OpenAPI response
schema remains unspecified. Backend's report records 195 applicable example checks
against its generated schemas; that is Backend evidence, not an Android rerun.

These artifacts were generated from uncommitted Backend work based on `6a1bf216`.
The comparison is interim and finite, not schema-equivalence proof or final wire
acceptance. Recheck changed artifacts through BA before actual rollout. No frozen
schema or binding hashes were changed for this comparison.
