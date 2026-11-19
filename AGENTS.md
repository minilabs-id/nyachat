# AGENTS.md

Petunjuk untuk agent yang bekerja di repo ini.

## Struktur (repo-map)
```
functions/index.js:
  function readSecret(...)
  function callOpenRouter(...)
  function callGemini(...)
  function allowCall(...)
  function tokenOf(...)
  function sendMulticastAndCleanup(...)
```

## Konvensi
- Ikuti gaya kode existing.
- Jalankan typecheck/test sebelum selesai.
