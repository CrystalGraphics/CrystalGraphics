# The GL debug harness to LWJGL 3

**Status**: design, 2026-09-02. Nothing here is implemented.
**Position**: step 0 of `plan_cgdevice.md` §9 — done **before** the device seam, on the existing
`CgGLBackend`, and validated by the old scenes producing the same PNGs on LWJGL 3 as on LWJGL 2.
**Where**: `gl-debug-harness/`, a git submodule of CrystalGUI on its `crystalgui` branch.

---

## 0. Why, and why first

The harness is LWJGL **2.9.4** (`build.gradle.kts` lines 39–43), which `CrystalGraphics/AGENTS.md`'s
module table records as LWJGL3 — it is not. LWJGL 2 has no Vulkan, no shaderc, no VMA, no arm64
natives, and on macOS its `ContextAttribs(3, 0).withForwardCompatible(false)` is handed the legacy
2.1 context, so the harness does not run on a Mac at all. Every later step — the Vulkan backend, the
parity scene, the Mac gate — needs LWJGL 3.

It goes first because it is **independent of the seam**: an `Lwjgl3GLBackend` implementing today's
`CgGLBackend` over `GL33C` is the same shape as mc1201's `GL1201Backend` minus the Blaze3D routing,
and core does not change at all. That makes it the one step with a byte-for-byte acceptance test:
the existing scenes' PNGs.

---

## 1. Inventory

47 files under `src/main/java` import `org.lwjgl.*`. By what they touch:

| Category | Files | What changes |
|---|---|---|
| **The platform bundle** | `platform/PlatformServiceHarness`, `platform/gl/Lwjgl2GLBackend`, `platform/gl/Lwjgl2GLContext`, `platform/input/InputAdapter`, `harness/util/Lwjgl2CursorService` | Rewritten as `Lwjgl3GLBackend` (over `GL33C`, core profile, no waterfalls — `plan_cgdevice.md` §0.1), `Lwjgl3GLContext` (over `GL.getCapabilities()`, like `GL1201Context`), a GLFW `InputAdapter`, and `GlfwCursorService` (a copy of `CursorService1201`, which was written as exactly this) |
| **Window and loop** | `harness/config/HarnessContext` (`Display.create` → GLFW window), `harness/InteractiveSceneRunner` (`Display.update/sync/isCloseRequested` → `glfwSwapBuffers`/`glfwPollEvents`/`glfwWindowShouldClose`), `harness/runtime/ResizeHandler` (→ framebuffer-size callback), `harness/runtime/InputPauseHandler`, `harness/camera/Camera3D` (`Keyboard`/`Mouse` polling → `glfwGetKey`/cursor-pos callback) | GLFW. Window hints: `CONTEXT_VERSION 3.3`, `OPENGL_PROFILE CORE`, `OPENGL_FORWARD_COMPAT` (required on macOS to be handed 4.1), `SRGB_CAPABLE`. Frame pacing: `glfwSwapInterval(1)` for vsync, a sleep-based cap when the config asks for a fixed rate (today's `Display.sync(TARGET_FPS)`) |
| **Raw-GL utilities** | `harness/util/ScreenshotUtil` (`glReadPixels`), `harness/util/GlStateResetHelper`, `harness/util/HarnessFboHelper`, `harness/util/HarnessShaderUtil`, `harness/util/HarnessTextureUtil`, `harness/util/HarnessProjectionUtil`, `harness/util/RenderPassState`, `harness/util/ValidationCubeHelper`, `harness/tool/FboInspector`, `harness/tool/GlErrorChecker`, `harness/tool/GlStateDumper` | `GL11`→`GL11C`/`GL33C` mechanically for this step (they stay GL until the device seam, after which `ScreenshotUtil` becomes `encoder.readback` and `GlStateResetHelper` has nothing to reset) |
| **Fixed-function overlays** — `harness/camera/FloorRenderer`, `WorldAxisRenderer`, `HUDRenderer`, `PauseScreenRenderer`, `harness/object/QuadRenderer`, `VertexBinding` | **Audit for immediate mode.** A core profile has no `glBegin`, no matrix stack, no `GL_QUADS`. Anything using them is rewritten over `CgVectorRenderer`/`CgQuadRenderer`/a material — which is the harness's own rule ("never call raw GL") finally applied to its own chrome |
| **Scenes using raw GL or the raw shader API** — `AtlasDumpScene`, `CgTextStressScene`, `MeshTestScene`, `TextScene2D`, `TriangleScene2D`, `test/CgAttachedBufferStressScene`, `test/CgForwardRendererScene`, `test/CgMaterialDualPathScene`, `test/ImageScene`, `test/InstancingTestScene`, `test/LightScene`, `test/MultiMeshInstancingDemoRenderer`, `test/ReviewScene`, `test/ShaderLibTestScene` | This step: `GL11`→`GL33C` where they only clear or set a viewport; scenes that use `glBegin` or the matrix stack are rewritten. Later step (device seam): the ones built on `CgShaderFactory`, `CgBatchRenderer`, `CgInstanceRenderer` or `CgDebugBlit` go with those APIs and are replaced by material scenes in the parity suite |
| **UI scenes** (17 `harness/scene/ui/*`) | They touch `Keyboard`/`Mouse` for scene-specific input and nothing else | `glfwGetKey`/the adapter; no rendering change |
| `src/main/java/com/crystalgraphics/CrystalGraphicsVersion.java` | a harness-local copy of a CG class that imports LWJGL | delete; use the composite's |

---

## 2. The platform bundle, rewritten

`PlatformServiceHarness` keeps its shape (eager public fields, scenes reach in to swap `soundImpl`)
and its contents change:

| Service | Implementation | Source to copy |
|---|---|---|
| `gl()` | `Lwjgl3GLBackend extends CgGLBackend` — every method one `GL33C`/`GL30C`/`GL32C` call; `glCopyImageSubData` via `GL43C` behind the capability, `glBindSampler` `GL33C`, sync `GL32C`; the ARB/EXT methods of `CgGLBackend` are dropped from the interface in this step or throw until §0.1 lands | `GL1201Backend` minus `RenderSystem`/`GlStateManager` routing, minus the alpha-test/matrix-stack throws (a harness has no host to coexist with; `PoseStack`'s three calls become no-ops here until they are deleted) |
| `capabilities()` | `Lwjgl3GLContext` over `GL.getCapabilities()` | `GL1201Context` verbatim |
| `input()` | GLFW: `glfwGetKey`/`glfwGetMouseButton` state, modifier mask from the GLFW mods, a **GLFW→LWJGL2 keycode table** because `CgKeyCodes` is LWJGL2-shaped — the table `CgInputService`'s own javadoc sketches; clipboard via `glfwGetClipboardString`/`glfwSetClipboardString` (replacing the AWT path) | `CgInputService` javadoc |
| `cursor()` | `GlfwCursorService` | `CursorService1201`, which needs only its `Minecraft.getInstance().getWindow()` line replaced by the harness window handle |
| `resources()`, `rendering()`, `lifecycle()`, `reload()`, `sound()` | unchanged | — |

`CgSystemInput` events: LWJGL 2 polled `Keyboard.next()`/`Mouse.next()` in the runner; GLFW delivers
callbacks, so `InputAdapter` registers key, char, cursor-pos, mouse-button and scroll callbacks and
forwards them to the same `consumeKeyboardEvent`/`consumeMouseEvent` calls, translating key codes
through the table. Char input arrives separately (`glfwSetCharCallback`), which LWJGL 2 folded into
the key event — the adapter pairs them the way MC 1.20's `KeyboardHandler` does.

---

## 3. Build

```kotlin
val lwjglVersion = "3.3.6"           // whatever LWJGL 3 release is current at the time
val lwjglNatives = /* per os.name/os.arch: natives-windows, natives-windows-arm64, natives-linux,
                      natives-linux-arm64, natives-macos, natives-macos-arm64 */
dependencies {
    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-glfw")
    implementation("org.lwjgl:lwjgl-opengl")
    implementation("org.lwjgl:lwjgl-vulkan")       // for plan_cgvulkan.md; MoltenVK is inside natives-macos*
    implementation("org.lwjgl:lwjgl-shaderc")
    implementation("org.lwjgl:lwjgl-vma")
    runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-glfw::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-opengl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-shaderc::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-vma::$lwjglNatives")
    // lwjgl-vulkan has natives only on macOS (MoltenVK); elsewhere the loader is the system's
}
```

- The `extractLwjglNatives` copy task and `org.lwjgl.librarypath` go: LWJGL 3 extracts its own natives.
- `runHarness` adds `-XstartOnFirstThread` when `os.name` contains `Mac` (GLFW's requirement).
- A `--device=gl|vulkan` argument beside the existing `--engine=`, read by `PlatformServiceHarness`
  to choose the bundle. Only `gl` exists after this step.
- The composite build substitution for `com.crystalgraphics:*` is unchanged.

---

## 4. Acceptance

**The old scenes, byte for byte.** Before touching anything, capture every managed scene's PNG on
LWJGL 2 on the machine that will run the comparison and commit them under
`harness-output/lwjgl2-baseline/`. After the move, the same scenes on LWJGL 3 GL must produce
identical PNGs on the same GPU and driver — same context flags aside, it is the same driver drawing
the same calls, so the tolerance is **zero**. Any difference is a bug in the port (a wrong keycode,
a viewport off by the framebuffer-vs-window scale on a HiDPI Mac, a flipped readback), not a
rendering question.

Second gate: the interactive scenes drive through GLFW input identically — `cgui-gallery`,
`cgui-desktop`, `cgui-textfield` (clipboard), `cgui-button` (the sound counter).

Third gate: **it runs on a Mac.** That is new; there is no baseline. Apple GL 4.1 with the forward-compat
core context, and the scenes that survive the fixed-function audit render.

---

## 5. Order

1. Build file and the bundle (`Lwjgl3GLBackend`, `Lwjgl3GLContext`, `InputAdapter`,
   `GlfwCursorService`) — compile with nothing else changed; nothing runs yet.
2. `HarnessContext` + `InteractiveSceneRunner` + `ResizeHandler` + `InputPauseHandler` + `Camera3D`
   on GLFW — the managed scenes run; capture and compare.
3. The fixed-function audit of the overlays and scenes — the interactive scenes run.
4. HiDPI: `glfwGetFramebufferSize` vs window size, and `ScreenshotUtil` reading the framebuffer size.
5. The Mac run.

Sized at roughly 2–3k lines changed, most of it mechanical, and it is the only step in the whole
programme whose correctness is checkable to the byte.
