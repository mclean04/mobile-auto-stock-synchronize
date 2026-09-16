# Android verification — 2026-09-16

The original Compose template built successfully before implementation.

The integration build completed:
- assembleDebug: APK generated.
- testDebugUnitTest: 9 passed, 0 failed (8 new contract tests + 1 template test).
- lintDebug: no errors; non-blocking dependency/style/unused-resource warnings remain.
- assembleDebugAndroidTest: instrumented test APK generated.

Contract tests verify the DNSE HMAC vector independently calculated with the existing
Python backend's fixture, reject queries in signing paths, preserve decimal price units
and source timestamps, reject unsupported sides/overfills/missing timestamps, enforce
batch size and notification validity flags.

OutboxPersistenceTest verifies queue persistence across database reopen and owner isolation.
It was compiled but NOT executed: no Android device/emulator was connected.

Not tested or claimed:
- Actual UI interaction on a phone/emulator.
- Firebase sign-in, Play Integrity attestation, backend mobile authorization.
- FCM delivery/receipt on a real device.
- Real DNSE API connectivity and broker-to-backend-to-Sheet reconciliation.

The owner confirmed Firebase Android has not yet been configured. Production backend
currently lacks the app's Firebase/Auth App Check verification. No real DNSE credentials,
automation tokens or fabricated financial records were used in the tests.
