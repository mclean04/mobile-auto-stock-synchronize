# Planning → backend → FCM TEST, 17/09/2026

- Sheet: https://docs.google.com/spreadsheets/d/1JubHTNMehkqqhPP_sm8CEV6988dM-UNymjjcxnads_w/edit#gid=9151517
- Committed input: `NOTIFICATION_OUTBOX!A5:Q5`; backend owns `R5:V5`.
- Plan: `test-dnse-daily-20260917-2315`, version 1, TEST / PLACE_ORDER.
- DEMO only: `DEMO-ACCOUNT`, symbol `DEMO`, 100 × 10,000 VND (1,000,000 VND simulated).
- Window: 2026-09-17 23:15–23:45 Asia/Ho_Chi_Minh.
- Calendar confirmed: `1gjfgau6ebct9h4jc2bavu1puo` in the planning owner's calendar.
- Event ID: `e59cb320-6c69-50b0-ae7d-b4b5c4aa4d83`.
- Cloud Run job: `planning-fcm-test-20260917-2315`, region `asia-southeast1`.
- Execution: `planning-fcm-test-20260917-2315-zf72k` (diagnostic retry; earlier execution was cancelled).

The existing exact-plan `planning_backend.test_notification_worker` waits until due,
reads only this TEST plan, and sends actual data-only FCM to registered admin devices.
Its polling delay is overridden from 20 to 120 seconds for this execution. The worker
retains the 30-minute deadline and exits early after an OPENED receipt. It records
accepted delivery per token and therefore does not resend an accepted notification
on each poll. No broker action is executed. LIVE notifications remain disabled and
the regular daily Sheet scheduler is unchanged.

The worker uses the previously deployed TEST image
`sha256:ca34a3491a08c6bc825df327d45a79cdc1011039cceaa08c853f8b28d99f2791`.
The initial attempt with the current service image failed because that image does
not package `test_notification_worker`; no notification was sent by that attempt.

Verification: Calendar and Sheet input were read back; Safari displayed the new
TEST row. The corrected Cloud Run execution reached Started with one running task.
`ACCEPTED` means FCM accepted the message; `RECEIVED` / `OPENED` requires an Android
receipt and is stronger evidence of actual delivery. At 23:15:15 the backend ingested the TEST row without errors. FCM returned HTTP 403 / IAM_PERMISSION_DENIED; no accepted delivery or Android receipt exists yet. The backend FCM role has a condition ending 2026-09-16T09:50:00Z, which is expired. A proposed replacement conditional grant ending 2026-09-17T16:45:00Z was rejected by automatic approval review and was not applied. The user subsequently explicitly approved the exact role, service account and 23:45 cutoff. Conditional IAM binding `planning-fcm-test-20260917-until-2345` was successfully applied at approximately 23:19:30. Delivery retry is pending IAM propagation. The diagnostic worker retains the 2-minute interval and 23:45 cutoff.

## Actual result

After the time-limited IAM grant propagated, FCM accepted the current app token.
Android reported RECEIVED at 2026-09-17 23:22:04.512812 +07:00 and OPENED at
23:22:21.371078 +07:00. The test succeeded with a delay caused by the expired
backend IAM grant. Three historical admin device tokens returned UNREGISTERED;
they were not deleted. This makes the aggregate sender state RETRY even though
the current device received and opened the notification. The subsequent 2-minute
worker poll acknowledged OPENED in NOTIFICATION_OUTBOX!U5 and exited early. No real order was placed.
