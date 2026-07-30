# `mc/shader` — Minecraft Shader Implementation

Concrete implementations of the `api/shader` contracts. All classes here depend on Minecraft/Forge APIs (`ResourceLocation`, `IResourceManager`, log4j). Not accessible from the standalone GL harness.

---

## Class Map

### `CgShaderImpl`
Package-private. The real `CgShader` implementation created by `CgShaderManagerImpl`. Supports two creation modes:

**Path-based** (created by `CgShaderManagerImpl.load()`):
- Constructed with `(vertexPath, fragmentPath, CgVertexFormat, defines)`.
- Sources loaded from Minecraft resources via `CgIO.loadSource()` on first `recompile()`.
- Has a non-null `CgShaderCacheKey` and is tracked in the manager cache.

**Inline-source** (created by `CgShaderManagerImpl.createFromSource()`):
- Constructed with `(vertexSource, fragmentSource, CgVertexFormat)`.
- No resource loading — GLSL strings compiled directly.
- `getCacheKey()` returns `null`; not tracked in the manager cache.
- `preprocess(pp)` applies the preprocessor to the stored original sources on next `recompile()`.

**Common lifecycle:**
- `markDirty()` sets a flag; recompile runs at the start of the next `bind()`.
- `delete()` frees the underlying `CgShaderProgram` and clears the uniform cache.

**Error tracking:**
- `getLastCompileError()` — returns the error message captured in `recompile()` on failure (source-load or compile/link exception message). Returns `null` on success.

**Uniform introspection:**
- `getActiveUniforms()` — delegates to `program.getActiveUniforms()` when compiled; returns empty list otherwise.

**Preprocessor priority:**
Two preprocessor fields exist:
1. `preprocessor` — built from `defines` passed at construction (legacy path). Always non-null.
2. `activePreprocessor` — attached post-construction via `preprocess(pp)`. When non-null, takes precedence.

`recompile()` picks `activePreprocessor` if set, otherwise falls back to `preprocessor`, then calls `pp.process(source, path)` passing the source file path so `#include` resolution works with relative paths.

**Binding application order per `bind()`:**
1. `CgSystemUniformRegistry.applyAll(this)` — system uniforms
2. `bindings.apply(this)` — persistent per-shader bindings
3. `ephemeralBindings.apply(this)` — one-shot from `applyBindings()`; auto-cleared after

**`bindScoped()`** captures the previously bound program via `CgGlState.saveProgram()` **before** binding its own, and restores it on scope close. **`bindScoped(CgGlSlot...)`** is the overload for saving additional domains; passing none delegates to the no-arg form.

> Earlier revisions described this as `GLStateMirror` + `GLContext.getCapabilities`, and named a `bindScoped(FULL_BINDINGS)` taking a constant. Neither is true: `GLStateMirror` is deleted, `CgStateBoundary` never survived, and the overload takes varargs slots.

### `CgShaderManagerImpl`
`CgShaderManager` backed by a `HashMap<CgShaderCacheKey, CgShader>`.

- `load(vert, frag, format, defines)` — builds a `CgShaderCacheKey`; returns cached entry if found, otherwise creates a new `CgShaderImpl` and calls `recompile()` immediately.
- `reloadAll()` — marks every cached shader dirty; actual recompile deferred to next `bind()`.
- `deleteAll()` — deletes all programs and clears the cache.
- Constructor registers `this` with `CgShaderReloadHook` so F3+T / resource pack changes trigger `reloadAll()` automatically.

The global singleton is `CgShaderFactory.SHADER_MANAGER`. Mod code should access it via `CgShaderFactory.load(...)`.

`createFromSource(vertSrc, fragSrc, format)` — public static bridge used by `CgShaderFactory.fromSource()`. Creates an inline-mode `CgShaderImpl`, calls `recompile()` eagerly, and returns it without caching.

### `CgShaderBindingsImpl`
Ordered `List<BindingOp>` of deferred uniform/texture operations. Operations are appended via the fluent `CgShaderBindings` API and replayed in insertion order when `apply(CgShader)` is called.

**Missing uniform handling:** A `warnedProgramId` + `Set<String> warnedNames` pair tracks which names have already been logged as missing for the current program. When the program ID changes (recompile), both are reset so warnings surface again for the new program.

**Texture binding:** `sampler2D(name, unit, CgTexture)` calls `texture.bind(unit)` then sets the sampler uniform to `unit`. `sampler2DRaw(name, unit, target, texId)` does the same via raw `glActiveTexture` + `glBindTexture`.

### `CgShaderReloadHook`
Static utility. Bridges Minecraft's `IResourceManagerReloadListener` to CrystalGraphics shader management.

- `trackManager(CgShaderManager)` — registers a manager to be notified on reload; identity-based, thread-safe.
- `register()` — registers the singleton `LISTENER` with `IReloadableResourceManager`. Idempotent.
- `reload()` — snapshots tracked managers and calls `reloadAll()` on each, isolated per-manager (one failure won't block others).

Called automatically: `CgShaderManagerImpl` calls `trackManager(this)` in its constructor. `register()` must be called once at mod init (e.g. during `FMLInitializationEvent`).

### `CgSystemUniformRegistry`
Singleton (`getInstance()`) registry of `CgUniformInjector` callbacks keyed by uniform name.

**Default injectors (provided out of the box):**
| Uniform name(s) | Value |
|---|---|
| `u_time`, `time` | Seconds since game start (float) |
| `u_viewportResolution`, `u_resolution` | Current GL viewport size (vec2, pixels) |

**Registration API:**
```java
CgSystemUniformRegistry reg = CgSystemUniformRegistry.getInstance();

// Simple registration
reg.register(CgUniformName.of("u_myUniform"), (shader, name) -> {
    int loc = shader.getUniformLocation(name);
    if (loc >= 0) shader.getProgram().setUniform1f(loc, myValue());
});

// With priority (lower = earlier; default 0)
reg.register(CgUniformName.of("u_time", "time"), -10, myTimeInjector);

// Unregister
reg.unregister("u_myUniform");
```

Injectors run in ascending priority order before per-shader bindings on every `bind()`.

---

## Call Flow: `CgShaderFactory.load()` → first `bind()`

```
CgShaderFactory.load(vert, frag)
  └─ CgShaderManagerImpl.load(vert, frag, null, {})
       ├─ build CgShaderCacheKey(vert, frag, {})
       ├─ cache miss → new CgShaderImpl(vert, frag, null, {})
       │     ├─ preprocessor = new CgShaderPreprocessor({})  [legacy slot]
       │     ├─ activePreprocessor = null
       │     └─ dirty = true
       └─ CgShaderImpl.recompile()
             ├─ CgIO.loadSource(vertexPath)  → raw GLSL string
             ├─ CgIO.loadSource(fragmentPath)
             ├─ pick pp = activePreprocessor ?? preprocessor
             ├─ pp.process(vertSrc, vertexPath)   → expands #include
             ├─ pp.process(fragSrc, fragmentPath) → expands #include
             └─ CgShaderFactory.compile(expandedVert, expandedFrag, format)
                  └─ CgCoreShaderProgram.compile(...)  (or CgArbShaderProgram)

shader.bind()
  ├─ dirty? → recompile() (above)
  ├─ program.bind()  (glUseProgram)
  ├─ CgSystemUniformRegistry.applyAll(this)
  ├─ bindings.apply(this)
  └─ ephemeralBindings.apply(this) + clear()
```

---

## Do Not

- Do not call `CgShaderImpl` directly — always go through `CgShaderFactory.load()` or `CgShaderManager.load()`.
- Do not add Minecraft imports to `api/shader/` — that package must stay MC-free for the standalone harness.
- Do not pass `#include` paths that start with `assets/harness/` to the lib preprocessor from production code — those paths only resolve in the harness environment.
