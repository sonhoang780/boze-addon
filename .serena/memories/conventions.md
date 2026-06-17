# Conventions

## Module Pattern

```java
public class MyModule extends AddonModule {
    public static final MyModule INSTANCE = new MyModule();

    public final ToggleOption foo = new ToggleOption(this, "Foo", "Description");
    public final SliderOption bar = new SliderOption(this, "Bar", "Desc", 1.0, 0.0, 10.0, 0.1);

    public MyModule() { super("MyModule", "Description"); }

    @EventHandler
    private void onTick(EventTick.Pre event) { ... }
}
```

## Key Rules

- Options are `public final` fields on the module class; parent arg is always `this`
- Option visibility/dependency: pass a parent `Option<?>` as last arg (e.g., `toggleOption` as guard)
- Event handlers: `private`, annotated `@EventHandler`, parameter type = specific event class
- Sub-packages allowed for grouped modules (e.g., `modules/betterrekit/`)
- Extensions extend `ClientModuleExtension`, call `super(ModuleManager.getClientModule("Name"))`
- Mixins: package is `com.example.addon.mixin`, class name prefix `Mixin`; client mixins go in `"client"` array in `example-addon.mixins.json`
- Commands: extend Boze command class, singleton pattern same as modules

## Utility APIs (Boze)

- `InvHelper.find(Item)` — hotbar slot search
- `InvHelper.swapToSlot(slot, SwapType.Silent)` / `InvHelper.swapBack()`
- `PlaceHelper.place(InteractionMode, BlockHitResult, Hand)`
- `MathHelper.calculateRotation(eyePos, target)` → `float[]{yaw, pitch}`
- `MathHelper.getBestAimPoint(BoundingBox)` → `Vec3d`
