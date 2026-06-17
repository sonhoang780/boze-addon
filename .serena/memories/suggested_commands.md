# Suggested Commands (Windows)

Use `.\gradlew` (PowerShell) or `./gradlew` (Bash) — Gradle wrapper is present.

| Purpose | Command |
|---|---|
| Build & remap jar | `.\gradlew build` |
| Remap only (skip tests) | `.\gradlew remapJar` |
| Run dev client (Fabric dev env) | `.\gradlew runClient` |
| Run production client (Boze loader) | `.\gradlew runBoze` |
| Clean build output | `.\gradlew clean` |

- Output jar: `build/libs/boze-addon.jar`
- `runBoze` uses `ClientProductionRunTask` — loads `modRuntimeOnly` (boze-loader-beta)
