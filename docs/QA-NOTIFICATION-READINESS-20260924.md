# Android QA notification readiness — 2026-09-24

## Evidence timestamp

- UTC: `2026-09-23T17:09:56Z`
- Asia/Ho_Chi_Minh: `2026-09-24T00:09:56+0700`
- Source branch: `codex/mobile-api-integration`
- Source commit: `f2fb291`
- Working tree at capture: clean and synchronized with `origin/codex/mobile-api-integration`

## Target context

- Android package: `com.example.finance_planning`
- Android test package: `com.example.finance_planning.test`
- Android user: `0`
- Device model: `SM-X730`
- ADB transport at capture: `189` (ephemeral; do not reuse as device identity)

The package and test package were both installed for Android user 0. The metadata helper
ran against this exact target context. No account switch, data clear, uninstall, app APK
replacement, QA registration, proxy call, FCM send, cloud mutation, or System QA run was
performed during this capture.

## Safe metadata result

```json
{
  "approved": true,
  "fcm_token_included": false,
  "firebase_configured": true,
  "firebase_user_present": true,
  "model": "SM-X730",
  "notification_permission": true,
  "target_device_id_present": true,
  "target_uid_present": true
}
```

The helper returned exit code 0. It emitted only the allowlisted structured status through
`Instrumentation.sendStatus`; it did not emit email, FCM token, API key, bearer, DNSE
credential, or raw logcat/instrumentation output. The exact UID and device UUID were
verified during the local capture but are intentionally excluded from this repository
artifact.

## Installed artifact baseline

| Artifact | Installed SHA-256 | Local SHA-256 | Result |
| --- | --- | --- | --- |
| App APK | `02eb55aa3710d2c6c06fff30ce020692359ba38660d3d4a9ea54f29ec212b62c` | `02eb55aa3710d2c6c06fff30ce020692359ba38660d3d4a9ea54f29ec212b62c` | Match |
| Android test APK | `df0135e9d56872e9bc7c34283ee19d05603476f31f83fe4dd5c0b2081299c63f` | `df0135e9d56872e9bc7c34283ee19d05603476f31f83fe4dd5c0b2081299c63f` | Match |

The prior installed app hash `05c88c49…` is no longer the device baseline and is not a
readiness blocker. The current installed app and test APK match the locally verified
artifacts for the recorded source state.

## Readiness conclusion

Android local notification readiness is **READY** for the bounded next QA step:

- Firebase build configuration is present.
- A Firebase current user exists in the installed package context.
- Backend mobile approval is present for that session.
- Android notification permission is enabled.
- Exact target UID and device UUID are available.
- Installed app/test artifacts match the owner build baseline.

This conclusion covers Android local readiness only. QA device registration, local proxy,
real FCM delivery, receipt readback, Cloud Run/Scheduler, and System QA were intentionally
not executed in this step.
