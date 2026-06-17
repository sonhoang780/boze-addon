# Core — example-addon-master

Boze Client Minecraft addon (mod ID: `boze-addon`). Client-side Fabric mod targeting MC 1.21.11.

## Source Layout

```
src/main/java/com/example/addon/
  ExampleAddon.java          # Addon entry point — registers all modules/commands/extensions
  ExampleExtension.java      # Example ClientModuleExtension (extends AutoCrystal)
  commands/                  # AddonCommand implementations (singletons)
  modules/                   # AddonModule implementations (singletons)
    betterrekit/             # Sub-package for grouped modules
  mixin/                     # Fabric mixins (MixinMinecraftClient only)
src/main/resources/
  fabric.mod.json            # Mod manifest
  example-addon.mixins.json  # Mixin config (client: MixinMinecraftClient)
```

## Project-Wide Invariants

- All modules/commands are singletons: `public static final Foo INSTANCE = new Foo()`
- Every module must be registered in `ExampleAddon.initialize()` via `modules.add(X.INSTANCE)`
- Every command must be registered via `dispatcher.registerCommand(X.INSTANCE)`
- Event bus package registered once: `BozeInstance.INSTANCE.registerPackage("com.example.addon")`
- Mixins must be listed in `example-addon.mixins.json`; client-side only go in `"client"` array
- Output jar name is always `boze-addon.jar` (hardcoded in `build.gradle`)

See `mem:tech_stack`, `mem:conventions`, `mem:suggested_commands`, `mem:task_completion`.
