# Tech Stack

- **Language**: Java 21 (source + target compatibility = 21)
- **Build**: Gradle with Fabric Loom `1.16-SNAPSHOT`
- **Minecraft**: `1.21.11`, Yarn mappings `1.21.11+build.4`
- **Fabric Loader**: `0.18.4`
- **Fabric API**: `0.140.2+1.21.11`
- **Boze API**: `3.2.2+1.21.11` (compile-only; runtime provided by `boze-loader-beta:1.0.0`)
- **Event bus**: `meteordevelopment:orbit:0.2.5` (annotation-driven, `@EventHandler`)
- **Audio**: LavaPlayer `2.2.6` + lava-common + lavaplayer-natives + lavalink youtube source
- **Rendering**: HumbleUI Skija `0.119.5` (Windows x64 native bundled — not cross-platform)
- **JSON**: Jackson `2.15.2`, nanojson `1.8`
- **Scripting**: Mozilla Rhino `1.7.14`
- **HTTP**: Apache HttpClient `4.5.14` / HttpCore `4.4.16`
- **Version strategy**: Git short hash in dev; `GITHUB_REF_NAME` env var on CI → `boze-addon-<tag>`
- **Baritone**: compile-only dependency `com.github.cabaletta:baritone:1.2.14`
