# Task Completion

After any coding change, verify with:

1. **Compile check**: `.\gradlew compileJava` — catches syntax/type errors fast
2. **Full build**: `.\gradlew build` — produces `build/libs/boze-addon.jar`

No automated test suite present. Manual testing requires running the client:
- Dev: `.\gradlew runClient`
- Production (with Boze loader): `.\gradlew runBoze`

There is no linter or formatter configured. Code style is enforced by review only.
