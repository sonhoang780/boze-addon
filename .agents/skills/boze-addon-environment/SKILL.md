---
name: boze-addon-environment
description: Use when setting up, configuring, or verifying a Boze addon project — enforces correct Maven repo, Minecraft Fabric 26.1.2+ mappings, JDK 25+, and Boze API 3.2.2+26.1.2. Invoke BEFORE touching build.gradle, gradle.properties, or any dependency config.
---

# Boze Addon Environment Constraints

Rigid skill. Follow exactly — no adaptation, no skipping sections.

## Why This Exists

Wrong mappings, wrong JDK, or wrong Maven repo produces builds that compile locally but crash at runtime, use stale API that no longer exists, or pull wrong artifact versions. These constraints are non-negotiable.

---

## 1. Maven Repository

**REQUIRED repo block in `build.gradle`:**

```groovy
repositories {
    maven {
        name = "Boze"
        url = "https://maven.boze.dev/releases"
    }
    // other repos (fabric, mojang, etc.) below
}

Verification checklist:
- [ ] URL is https://maven.boze.dev/releases — NOT /snapshots, NOT /repository, NOT any mirror
- [ ] Repo appears BEFORE any third-party mirror that might shadow it
- [ ] No hardcoded local path pointing to a cached jar instead of maven

If using settings.gradle pluginManagement: add the same URL there too if any Boze plugin is consumed.

---
2. Boze API Dependency

Target artifact: dev.boze:boze-api:3.2.2+26.1.2

dependencies {
    modImplementation "dev.boze:boze-api:3.2.2+26.1.2"
    // OR, if using version catalog:
    modImplementation libs.boze.api  // must resolve to 3.2.2+26.1.2
}

Canonical reference:
- Maven browser: https://maven.boze.dev/#/releases/dev/boze/boze-api/3.2.2+26.1.2
- API docs: https://docs.boze.dev

NEVER do:
- Use 3.2.1+1.21.10 or any pre-26.1.2 classifier — wrong MC version
- Use + wildcard version ranges — unpinned versions break reproducible builds
- Copy-paste from old projects without checking the classifier suffix (+26.1.2 required)

Version string anatomy: <api-semver>+<mc-version> — the +26.1.2 part IS the Minecraft version tag.

---
3. Minecraft Fabric Mappings

Target MC version: 26.1.2 (or higher — never lower)

Required in gradle.properties:

minecraft_version=26.1.2
yarn_mappings=26.1.2+build.1
loader_version=0.16.14

▎ Verify exact yarn_mappings build number at https://fabricmc.net/versions.html — always use the latest build for the target MC version.

Fabric API dependency must match:

modImplementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_version}"

with fabric_version in gradle.properties set to the version released FOR 26.1.2.

Checklist:
- [ ] minecraft_version is 26.1.2 or newer — never 1.21.x, 1.20.x or old snapshots
- [ ] Yarn build exists for the set minecraft_version (check fabricmc.net)
- [ ] loom.mappings in build.gradle uses yarn — NOT mojmap, NOT parchment (unless user explicitly requires)
- [ ] No mixin target class names from 1.21.x naming — 26.1.2 has renames; trace via sources jar

---
4. JDK Version

Minimum required: JDK 25

In build.gradle:

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

In gradle.properties:
jdk_version=25

Verification:
- Run java -version — must report 25.x.x or higher
- Run .\gradlew -version — check JVM line
- If lower: update JAVA_HOME or toolchain resolver before proceeding

NEVER:
- Set sourceCompatibility = JavaVersion.VERSION_21 or lower for new code
- Mix sourceCompatibility without a toolchain block — toolchain is authoritative

---
5. Full gradle.properties Baseline

# Minecraft + Fabric
minecraft_version=26.1.2
yarn_mappings=26.1.2+build.1
loader_version=0.16.14
fabric_version=<check fabricmc.net for 26.1.2>

# Boze
boze_api_version=3.2.2+26.1.2

# Java
jdk_version=25

# Mod metadata
mod_version=1.0.0
maven_group=com.example
archives_base_name=example-addon

---
6. API Lookup Protocol

When writing code that calls Boze or Minecraft API:

1. NEVER guess from memory — API changes between MC versions
2. Boze API sources: read entries from the sources jar at .gradle/loom-cache/remapped_mods/remapped/dev/boze/boze-api-*/3.2.2+26.1.2/boze-api-*-sources.jar via PowerShell System.IO.Compression.ZipFile
3. MC API: use minecraft-merged-*-sources.jar in loom cache, or check https://mcsrc.dev for diff between versions
4. After writing any API call: run .\gradlew build — LSP diagnostics may be stale; build output is truth

---
7. Verification Gate

Before declaring environment correct, run:

.\gradlew dependencies --configuration modImplementation | Select-String "boze"
.\gradlew build
java -version

Expected:
- First command shows dev.boze:boze-api:3.2.2+26.1.2
- Build exits 0
- JVM reports 25+

If any check fails: stop, fix, re-run. Do NOT proceed to feature work with a broken environment.

---
Red Flags — STOP If You See These

┌─────────────────────────────────────┬────────────────────────────────────────────────┐
│               Symptom               │                    Problem                     │
├─────────────────────────────────────┼────────────────────────────────────────────────┤
│ 3.2.1+1.21.10 in deps               │ Wrong Boze version / wrong MC tag              │
├─────────────────────────────────────┼────────────────────────────────────────────────┤
│ yarn_mappings=1.21.x+...            │ Wrong MC mappings — renames will break         │
├─────────────────────────────────────┼────────────────────────────────────────────────┤
│ sourceCompatibility = VERSION_21    │ JDK too old                                    │
├─────────────────────────────────────┼────────────────────────────────────────────────┤
│ Maven URL is /snapshots             │ Unstable artifacts                             │
├─────────────────────────────────────┼────────────────────────────────────────────────┤
│ Build resolves from local file path │ Bypassing maven, reproducibility broken        │
├─────────────────────────────────────┼────────────────────────────────────────────────┤
│ LSP shows no errors but build fails │ Stale diagnostics — trust .\gradlew build only │
└─────────────────────────────────────┴────────────────────────────────────────────────┘