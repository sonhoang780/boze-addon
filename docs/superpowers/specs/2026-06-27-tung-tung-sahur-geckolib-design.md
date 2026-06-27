# TungTungSahur — Geckolib OBJ Replacement Design

> **STATUS: PARTIAL — brainstorm stopped mid-session. Resume from Section 2.**
>
> **For agentic workers:** Resume brainstorm from Section 2 (build.gradle + render design).
> Manual prerequisite must be completed by user BEFORE coding.

**Goal:** Replace hand-built `TungTungModel.java` (cuboid definitions) with OBJ model from zip, rendered via Geckolib. Delete TungTungModel.java entirely.

**Source zip:** `C:\Users\conng\Downloads\tung-tung-tung-sahur.zip`
- `source/model.zip` → `base.obj` (5.7 MB), `base.mtl`, PBR textures
- `textures/texture_diffuse.png` — use this as the in-game texture

**Decision log:**
- Chose Geckolib (Option B) over custom OBJ renderer (Option A) or baked vertex data (Option C)
- Model is **static** (no animation needed) — demo on internet shows stationary pose only
- User has Blockbench installed, can do OBJ → .geo.json conversion manually

---

## Global Constraints

- MC 26.1.2 (Boze snapshot), Boze API 3.2.2, Fabric Loader 0.19.3
- Java 25, Fabric Loom 1.16-SNAPSHOT
- No animation required — static model only
- Geckolib version must compile with MC 26.1.2 (risk: no official release for this snapshot — try latest 4.x, document fallback to Option A if build fails)
- Texture: diffuse only (`texture_diffuse.png`), no PBR in MC pipeline

---

## Manual Prerequisite (user does BEFORE coding)

1. Open Blockbench
2. File → Import → OBJ → select `base.obj` (extract from `source/model.zip` first)
3. Adjust scale/orientation to match TungTungSahur companion size (~1.5 blocks tall)
4. File → Export → GeckoLib Model → save as `tung_tung.geo.json`
5. Copy `texture_diffuse.png` (from outer zip `textures/` folder) → rename to `tung_tung.png`
6. Place files:
   - `src/main/resources/assets/example-addon/geo/tung_tung.geo.json`
   - `src/main/resources/assets/example-addon/textures/entity/tung_tung.png`

---

## Section 1: Architecture (APPROVED)

Pipeline:
```
Blockbench (manual) → tung_tung.geo.json + tung_tung.png (in resources)
                            ↓
                    Geckolib GeoModel (load from resources)
                            ↓
                    BakedGeoModel → VertexConsumer (LevelRenderEvents hook, same as current)
                            ↓
                    TungTungSahur.java (follow logic unchanged, swap model field)
```

Files:
- DELETE  `src/main/java/com/example/addon/render/TungTungModel.java`
- MODIFY  `build.gradle` — Geckolib repo + dep
- MODIFY  `TungTungSahur.java` — swap TungTungModel → Geckolib BakedGeoModel
- ADD     `src/main/resources/assets/example-addon/geo/tung_tung.geo.json`
- ADD     `src/main/resources/assets/example-addon/textures/entity/tung_tung.png`

---

## Section 2: build.gradle (TO BE DESIGNED)

Need to determine:
- Geckolib maven URL
- Artifact ID for Fabric + closest MC version to 26.1.2
- Whether `implementation` + `include` or just `compileOnly`
- Risk: if no 26.1.2 artifact exists, try latest 4.x and see if render APIs match

---

## Section 3: TungTungSahur render rewrite (TO BE DESIGNED)

Current: `model.render(matrices, consumer, light, overlay)` via `TungTungModel`
New: Geckolib low-level API — `GeoModel.getBakedModel()` → traverse `GeoBone` tree → push to VertexConsumer

No entity system needed — keep existing LevelRenderEvents hook.
No AnimationState needed — static model, no bone transforms.

---

## Risks

1. Geckolib has no MC 26.1.2 release → try latest 4.x, fallback to custom OBJ renderer (Option A) if compile fails
2. OBJ → .geo.json scale/orientation might need Blockbench tuning — multiple iterations possible
3. `base.obj` is 5.7MB (high-poly Sketchfab mesh) — may need decimation in Blender before Blockbench import for acceptable perf
