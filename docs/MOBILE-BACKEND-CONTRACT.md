# Mobile Backend contract layer

This is an **isolated, inactive contract implementation** of the accepted September
29 revision-2 design. It creates no client, initializes no SDK and does not change
the app's runtime configuration. Existing BackendApi/legacy endpoints remain in
use. The shared-client/DNSE integration is separately blocked by tool review; this
module does not integrate or bypass that operation. Repository/DataSource wiring
also awaits the actual Backend contract/OpenAPI handoff through BA.

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

Generated objects are closed according to their schemas. Broker, planning and
preview variants are sealed types. Only declared worksheet scalar cells use a
bounded map. `WireField.Absent` is omitted during encoding; `Present(null)` remains
an explicit JSON null. Error messages never echo response values or credentials.
The wire DTOs do not grant execution authority: business validators, ownership,
cash/preflight checks and transaction truth remain the existing owners' duties.

## Later integration requirements

Use the approved application-owned Retrofit instance with
`MobileContractConverter`, and inject `MobileBackendService` into a typed
DataSource. Do not construct clients in features. This document does not authorize
activation: retain explicit legacy configuration until the separately approved
server-first rollout and actual Backend handoff. The methods have fixed
`/mobile/v1` paths; health alone stays `/health`. There is no format guessing or
fallback retry.

`MobileResultMapper` checks HTTP status and X-Request-ID against metadata. Unknown
safe error codes remain server failures with their actual status. UNKNOWN and
confirmed partial write steps remain typed data; no mutation is replayed and no
identity is signed out by this module. Transport exceptions/cancellation remain
the future DataSource's responsibility. All currently bound successes are 200
objects; a bodyless 204 is not manufactured into a success DTO.

The converter checks complete serialized requests against 2,097,152 bytes and
decoded responses against 4,194,304 bytes, including envelope punctuation and
metadata. **The future client must also bound Retrofit's eager non-2xx buffering
before conversion.** This isolated converter cannot establish transport-wide
buffering, cancellation, origin isolation or server overflow enforcement. Request
path/query/header schemas are generated checks for the future DataSource to apply;
Retrofit method declarations alone do not enforce their semantic constraints.

Map All planning's typed data and pagination metadata into its existing atomic
local snapshot behavior; retain stable source/snapshot, integer total count,
unique cursors/IDs and complete page replacement. Keep display-only legacy/null
records separate from executable intent validation. This repository/UI integration
has not been performed by the contract-only change.

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
  --offline --console=plain
```

The focused test uses the accepted Backend fixtures: 211 valid cases accepted and
20 invalid cases rejected, plus concrete DTO round trips for all 26 operations,
Retrofit declaration validation, optional/null/decimal preservation, partial and
unknown outcomes, correlation/proxy failures, strict JSON and response byte caps.
It performs no HTTP exchange, device, Firebase, DNSE or Cloud action. Passing it is
not application integration, shared-client verification or QA/E2E acceptance.
