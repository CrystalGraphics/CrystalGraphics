# `CgVulkanDevice` — the self-owned Vulkan backend

**Status**: design, 2026-09-02. Nothing here is implemented.
**Preconditions**: `plan_cgdevice.md` (the seam this implements), `plan_harness_lwjgl3.md` (the
only place it can run before a 26.x adapter exists).
**Scope**: a `CgDevice` over raw Vulkan, owning its instance, device and swapchain, for the harness on
Windows, Linux and macOS (MoltenVK). Written so the same backend can later sit on a host-owned
`VkDevice` (diagnosis §5.2 option 2) with the instance/swapchain half removed.

---

## 0. What it is for, and what it is not

It is the proof that `core/` genuinely runs on one path over two APIs — the half of the parity scene
that is not OpenGL — and it is where "CrystalGraphics is an engine that isn't Minecraft's renderer"
becomes true and testable. It is **not** the 26.x backend; that is the Blaze3D adapter, and in-game
Vulkan through this backend is a later option, not a plan.

Its requirements are Mojang's, deliberately: **Vulkan 1.2, dynamic rendering, push descriptors**.
They ship those on every platform they support, MoltenVK included, so a device that cannot run this
backend cannot run 26.2's either — the hardware floor is somebody else's decision, already taken.

Sized against `com.mojang.blaze3d.vulkan`, which is the same list of components.

---

## 1. Components

| Component | Responsibility | Est. lines |
|---|---|---|
| `VkInstanceHolder` | instance creation, validation layers in debug, `VK_EXT_debug_utils`, portability enumeration on macOS | 250 |
| `VkPhysicalDeviceSelector` | prefer discrete, require the three features, pick the graphics+present queue family, read limits and format support into `CgDeviceInfo` | 250 |
| `VkDeviceHolder` | logical device, one graphics queue, VMA allocator (`org.lwjgl.util.vma`), `VK_KHR_portability_subset` when present | 250 |
| `VkSwapchainHolder` | surface via `GLFWVulkan.glfwCreateWindowSurface`, format and present-mode choice, recreate on resize, an owned depth image; implements `CgSurface` | 450 |
| `VkFrameRing` | N frames in flight: per frame a command pool + primary command buffer, a fence, acquire/present semaphores, the host-visible ring chunks, the deletion queue | 500 |
| `VkRingAllocator` | persistently mapped host-visible buffers, bump allocation with per-usage alignment, overflow chunks | 250 |
| `VkBufferImpl`, `VkTextureImpl`, `VkTextureViewImpl`, `VkSamplerCache` | the device objects over VMA allocations; per-image current-layout tracking; sampler cache by `CgSamplerDesc` | 700 |
| `VkShaderCompiler` | shaderc (`org.lwjgl.util.shaderc`) GLSL 450 → SPIR-V, target env Vulkan 1.2; error text mapped back to the `.shader` line | 250 |
| `VkPipelineLayoutCache`, `VkPipelineCache` | descriptor set layouts from `CgBindGroupLayoutDesc`, pipeline layouts from the set list, `VkPipeline` per `CgPipelineDesc` key, `VkPipelineCache` persisted to disk | 700 |
| `VkCommandEncoderImpl`, `VkRenderPassImpl` | the encoder: barriers, layout transitions, copies, blits, `vkCmdBeginRenderingKHR`; the pass: pipeline bind, push descriptors, vertex/index binds, viewport/scissor, draws | 900 |
| `VkQueryPool` (timestamps) | optional; `timestampPeriod` scaling | 150 |
| `VkDebug` | labels, markers, validation message routing to the logger | 150 |
| **Total** | | **≈ 4,800–6,500** |

---

## 2. Creation

1. **Instance** — application info, Vulkan 1.2. Extensions: what GLFW asks for
   (`glfwGetRequiredInstanceExtensions`), `VK_EXT_debug_utils` in debug, and on macOS
   `VK_KHR_portability_enumeration` with `VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR` set —
   without the flag MoltenVK's device is not enumerated at all. Layers: `VK_LAYER_KHRONOS_validation`
   when `CgDevice.debug()`.
2. **Physical device** — score discrete over integrated (26.2 makes the same choice), require
   `VK_KHR_dynamic_rendering`, `VK_KHR_push_descriptor`, `VK_KHR_swapchain`; `VK_KHR_maintenance1` is
   core in 1.1. Read `VkPhysicalDeviceLimits` into `CgDeviceLimits`; probe format support with
   `vkGetPhysicalDeviceFormatProperties` for every `CgFormat` and record the answer for `DEPTH24_PLUS`
   (Apple GPUs have no D24 → `D32_SFLOAT`) and the 3-channel expansion.
3. **Logical device** — one queue family with graphics + present; `VK_KHR_portability_subset` enabled
   when the physical device reports it (mandatory on MoltenVK). Features enabled:
   `dynamicRendering`, `samplerAnisotropy` if present, `fillModeNonSolid` if present. VMA created with
   the same instance/device and Vulkan 1.2 API version.
4. **Swapchain** (harness only) — `B8G8R8A8_SRGB`/`UNORM` as available, `FIFO` (vsync) by default and
   `IMMEDIATE`/`MAILBOX` when the harness asks for uncapped frames; image count = frames in flight + 1;
   a depth image `DEPTH24_PLUS` per swapchain size. Recreated on `glfwSetFramebufferSizeCallback`
   after `vkDeviceWaitIdle`.

`CgDeviceInfo.features` on this backend: `storageBuffers = true`, `texelBuffers = true`,
`copyImage = true`, `clipControl = true` (native), `explicitBinding = true`, `persistentMapping = true`,
`timestamps` per queue family, `textureArrays = true`, `texture3D = true`, `stencil = true`.

---

## 3. Frames in flight

**N = 3.** Per frame: a command pool (reset per frame), one primary command buffer, a fence, an
acquire semaphore, a render-finished semaphore, the ring chunks for that frame, and a deletion queue.

```
beginFrame():
    wait fence[i]; reset fence; reset pool i
    drain deletionQueue[i]            -- resources closed N frames ago are now free
    reset ring chunks for frame i
    acquire swapchain image with acquireSemaphore[i]   (harness; a host-owned device skips this)
    begin command buffer i
end():
    end command buffer; submit with wait=acquire, signal=renderFinished, fence[i]
    present with wait=renderFinished                    (harness only)
    i = (i + 1) % N
```

`CgFrame.deferClose` enqueues onto the *current* frame's queue; the object is destroyed when that
frame's fence is next waited, i.e. exactly N frames later.

**The ring** (`VkRingAllocator`): one persistently mapped `HOST_VISIBLE | HOST_COHERENT` buffer per
usage class per frame, sized from `CgDeviceConfig`, bump-allocated with alignment from
`minUniformBufferOffsetAlignment` / `minStorageBufferOffsetAlignment` / `minTexelBufferOffsetAlignment`
(256 on most desktop drivers; MoltenVK reports its own). Usage bits on the ring buffers:
`UNIFORM | STORAGE | UNIFORM_TEXEL | VERTEX | INDEX | TRANSFER_SRC`. Overflow: allocate an extra chunk
for the rest of the frame, count it, grow next frame's chunk. Non-coherent memory is not used, so no
`vkFlushMappedMemoryRanges` — if a device only offers non-coherent host memory the allocator flushes
per frame; MoltenVK on Apple Silicon is unified and coherent.

---

## 4. Resources

- **Buffers**: `VkBuffer` + VMA allocation. Persistent buffers are `DEVICE_LOCAL`, written only by
  `vkCmdCopyBuffer` from a ring slice (§5.4.1 of the device plan); they are never mapped, which is the
  rule that makes frames-in-flight race-free.
- **Textures**: `VkImage` (+ VMA) with `VkImageView`s per `CgTextureViewDesc`, cached. **Layout is
  tracked per image** (and per mip range where a view narrows it): `UNDEFINED` → `TRANSFER_DST` for
  upload → `SHADER_READ_ONLY` for sampling → `COLOR_ATTACHMENT`/`DEPTH_STENCIL_ATTACHMENT` inside a
  pass → back. The encoder inserts the transition at the point of use; the pass records its
  attachments' layouts on begin and end.
- **Transient attachments** (`CgTextureUsage.TRANSIENT`, the MSAA colour target): allocated with
  `VMA_MEMORY_USAGE_GPU_LAZILY_ALLOCATED` when the device offers lazily allocated memory (tilers,
  Apple GPUs), plain device-local otherwise. `storeOp = DISCARD` on the pass keeps it lazy.
- **Samplers**: cached by `CgSamplerDesc`; `compareEnable` for `CgCompareFunc`; anisotropy clamped to
  the limit.
- **3-channel uploads**: `writeTexture` expands rows to RGBA in the staging copy.
- **Mipmaps**: `generateMipmaps` is the standard blit chain (`vkCmdBlitImage` level by level with
  transitions between), which is what every Vulkan engine writes; ~60 lines.
- **Readback**: `copyImageToBuffer` into a host-visible buffer, `vkQueueWaitIdle`, map, copy out. It
  costs a frame and is documented as such; only the harness's screenshots use it.

---

## 5. Barriers

v1 policy is **correct and coarse**, refined only where a profile says so:

- Before a copy into a resource: a barrier from whatever stage last used it to `TRANSFER`, with the
  layout transition if it is an image.
- At `beginPass`: attachments transition to their attachment layouts from whatever they were;
  every texture the pass will *sample* is already in `SHADER_READ_ONLY` because the copy that wrote
  it transitioned it there on completion (the encoder transitions back to read-only after every
  upload — one extra barrier per upload, none per draw).
- At pass end: attachments stay in attachment layout unless they have `SAMPLED` usage, in which case
  they transition to `SHADER_READ_ONLY` immediately, because the next thing that happens to a layer
  target is being sampled by a composite.
- Between two passes that write and then read the same target (layer → composite): the transition
  above is the barrier.
- Ring buffers need no barrier for host writes (coherent) and a `TRANSFER`→`VERTEX_INPUT`/`SHADER`
  barrier only where a ring slice is the *source* of a copy.

This is more barriers than a scheduler would emit and far fewer than a draw-level approach. Nothing
in the engine's frame — a few dozen passes, a few hundred uploads at worst — is within an order of
magnitude of where barrier count matters.

---

## 6. Passes and pipelines

**Passes** are `vkCmdBeginRenderingKHR` with one `VkRenderingAttachmentInfoKHR` per
`CgColorAttachment` (`loadOp`, `storeOp`, clear value, `resolveMode = AVERAGE` + `resolveImageView`
when a resolve target is set) and one for depth/stencil. The render area is the `CgRect` or the
attachment size. Nested layer passes are end-then-begin with `LOAD` on the outer; suspend/resume bits
are not needed. Viewport and scissor are **dynamic state** on every pipeline, so a `setScissor` is a
`vkCmdSetScissor` and the UI's scissor stack costs nothing per pipeline. The viewport is set with a
**negative height and `y = height`** (maintenance1) so NDC is y-up like the other backend (R6).
`CgDepthDirection.REVERSED` changes nothing here — it is a policy the pass owner applies to its
compares and clear values.

**Pipelines**: `VkGraphicsPipelineCreateInfo` from `CgPipelineDesc` — vertex input from the layouts
(binding per layout, `VK_VERTEX_INPUT_RATE_INSTANCE` for step-mode INSTANCE, attribute formats from
`CgAttribType` × components × normalized), input assembly from the topology, rasterization from
cull/front-face (`frontFace` is `CCW` in core; with the flipped viewport this stays CCW on screen),
multisample from `sampleCount`, depth-stencil and per-target blend from the state, dynamic
viewport/scissor, and `VkPipelineRenderingCreateInfoKHR` carrying the target formats (dynamic
rendering's replacement for a render-pass object). Layout from the pipeline-layout cache. All
compiled through a `VkPipelineCache` loaded from and saved to
`harness-output/vk-pipeline-cache.bin`, keyed by the device's `pipelineCacheUUID`.

**Descriptors**: **push descriptors only.** Sets 0–2 are declared
`VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR`; `setBindGroup` is a
`vkCmdPushDescriptorSetKHR` with the group's writes. No pools, no allocation, no per-frame descriptor
lifetime — the same simplification Mojang bought by requiring the extension. Dynamic offsets for
set 2 are expressed by pushing the slice's `VkDescriptorBufferInfo` with its offset directly.
Combined image-samplers are used for `COMBINED_TEXTURE_SAMPLER` entries; separate sampled image +
sampler for the others.

**Shaders**: the compiler's `VULKAN450` dialect text → shaderc (`shaderc_glsl_vertex_shader` /
`fragment`, target env `vulkan1_2`, optimisation performance in release, zero in debug with debug
info) → `VkShaderModule`. shaderc's error lines are rewritten through the same `#line`
bookkeeping `CgGraphCompiler` already keeps, so a failure names the `.shader` line. No reflection:
locations, sets and bindings are explicit in the emitted GLSL, which is what §11 of the device plan
decided.

---

## 7. MoltenVK specifics

Named so they are not discovered on a Mac at the end:

- The instance flag and the portability-subset device extension (§2).
- **No triangle fans** (`triangleFans = false`): `CgPrimitiveTopology` has none, by design.
- **No wide lines** (`wideLines = false`): `CgGL.glLineWidth` has no device equivalent; strokes are
  SDF quads through `CgVectorRenderer` already.
- `separateStencilMaskRef = false`: front and back stencil masks must match — `CgDepthStencilState`
  is validated for it when the backend is MoltenVK.
- `imageViewFormatSwizzle` may be false: no swizzled views; `R8` sampled as `.r` in the shader, as
  the text shader already does.
- Depth: `D24S8` absent on Apple GPUs → `DEPTH24_PLUS` resolves to `D32_SFLOAT` and
  `DEPTH24_PLUS_STENCIL8` to `D32_SFLOAT_S8_UINT`.
- Timestamps: supported, with a coarser `timestampPeriod`; `CgGpuProfiler` reads it from the limits.
- Unified memory: host-visible and device-local are the same heap, so the ring is as fast as a
  device-local buffer and staging copies are cheap.
- Alignment: MoltenVK reports its own `minUniformBufferOffsetAlignment` (Metal's 16 on Apple GPUs,
  256 on AMD/Intel Macs); the ring reads the limit rather than assuming 256.

---

## 8. Bring-up order and tests

Each step is a harness scene that renders and is captured:

1. Clear to a colour — instance, device, swapchain, frame ring, present.
2. A triangle from a ring vertex slice — pipeline, shaderc, dynamic viewport.
3. A textured quad — staging upload, layout transitions, push descriptors, samplers.
4. `CgQuadRenderer` — texel/storage instance buffer, set 0, the frame block.
5. Text — `CgTextRenderer`, atlas array texture, deferred glyph uploads (§5.10).
6. A layer pass with MSAA and resolve, nested inside the main pass — the UI's shape.
7. `cgui-gallery` — the whole widget set.
8. **The parity scene** against the GL backend, at §9.1's tolerance, on Windows and on a Mac.

Validation layers are on for every harness run of this backend; a validation error fails the scene.
`CgDeviceStats` counters (draws, pipeline binds, push-descriptor writes, barriers, ring bytes) are
printed per scene and compared against the GL backend's for the same scene — a barrier count that
scales with draws is the bug §5 is written to avoid.

---

## 9. Risks

| Risk | Mitigation |
|---|---|
| MoltenVK behaviour differing from desktop drivers (it translates, it does not emulate) | the Mac parity run is in the gate; §7 lists the known divergences |
| shaderc/SPIR-V rejecting GLSL that GL drivers accepted (the `fwidth`-in-vertex class) | `ShippedShaderStagePurityTest` compiles every shipped `.shader` in the Vulkan dialect headlessly, with shaderc, before any GPU sees it |
| pipeline compile hitches on first use | the warm-up in `initContext` (§5.5) and the persisted `VkPipelineCache` |
| ring overflow on a pathological frame | overflow chunks and a counter; never a stall or a crash |
| a host-owned `VkDevice` later (in-game) | the instance/swapchain half is one class each and is the only part that assumes ownership; everything from `VkFrameRing` down takes a device and a queue |
