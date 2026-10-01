# Android-owned tools

Only Android product generators belong in this directory. The QA-owned Python suites,
runners and device/evidence helpers live in:

```text
/Users/tuanh/finance root/Test/TestProject/tools
```

`generate_mobile_dtos.py` remains here because it generates Kotlin product sources
from pinned mobile contracts. Run the ownership guard with:

```bash
scripts/check-no-qa-python-in-android.sh
```
