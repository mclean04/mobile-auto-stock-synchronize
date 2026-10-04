# Debug HTTP logging

Explicit debug policy: full raw URL/query, header values and request/response JSON
are logged without redaction. This includes authentication headers and financial
values. Do not paste captured production credentials into issues or shared logs.
App diagnostics: DEBUG / PlanningApp. API diagnostics: WARN / OkHttp.

Filter: `package:com.example.finance_planning tag:OkHttp level:WARN`
Layout: --> METHOD URL, headers, body, --> END METHOD; then <-- STATUS URL (ms),
response headers, raw body, <-- END HTTP (byte count).

DNSE uses Retrofit + OkHttp DebugBodyLoggingInterceptor. It is attached only when
BuildConfig.DEBUG is true and also checks that flag internally. Backend Transport
and HttpLogFormat likewise disable logging in release builds. No raw log files
are created. JSON is not reformatted, filtered or limited to sample array entries.
Long text is split into Logcat-sized chunks. The existing 4 MiB application
response limit remains; logging does not consume the response supplied to callers.
Streaming/one-shot request bodies are not replayed for logging; current DNSE calls
are GET-only, and backend POST/PUT bodies are ordinary JSON.
