# Debug and JVM flags

Every `-Dcrystalgraphics.*` switch, by area. Moved from [`AGENTS.md`](../AGENTS.md).

## Debug / JVM Flags

```bash
# Hotswap (dev only) — re-applies the LaunchWrapper transformer chain, Mixins included,
# to classes HotswapAgent redefines. The redirector.* flags are gone with the coremod.
-Dcrystalgraphics.hotswap.verbose=true               # log each class transformed

# GL state manager (see gl/state/CLAUDE.md)
-Dcrystalgraphics.state.verify=true                  # verify the shadow against the driver before
                                                     # eliminating any call; logs the offending domain
                                                     # with tracked-vs-actual. Very slow — diagnosis only.
-Dcrystalgraphics.state.noDedup=true                 # never eliminate a call; distinguishes "the shadow
                                                     # is lying" from a semantic regression in one run
-Dcrystalgraphics.state.rereadEachScope=true         # every outermost scope re-reads what it declares,
                                                     # inside a host section too -- the rule before section
                                                     # trust; glState.adopt (zone, crystalgraphics.gl) times
                                                     # the reads either way
-Dcrystalgraphics.state.roundTrip=true               # read every scope's domains on open and after it
                                                     # restores, name any that differ; on 1.7.10 with
                                                     # Angelica also against the raw driver; and report
                                                     # every write no open scope declares (a leak, with its
                                                     # stack; CgGlState.handOver declares one meant to
                                                     # stay). Totals every 1000 scopes, via log4j; any
                                                     # count but 0 is a bug. Fits prodSmoke's 120 s run
                                                     # alone; four at a time, a client can miss it

# The GL state a host hands us, per entry point, once (every host)
-Dcrystalgraphics.host.census=true                   # opaque, transparent, gui, frame: ~90 values each on
                                                     # the 120th visit (.at=N); singlejar-logic/census_diff.py
                                                     # lays two clients side by side where they differ

# Minecraft's own GL state cache (modern nodes)
-Dcrystalgraphics.host.verify=true                   # after each pass, compare the driver against the host's
                                                     # GlStateManager and name the domain that disagrees

# GL errors (LWJGL3 hosts, needs a debug context -- every dev client has one)
-Dcrystalgraphics.gl.debugStacks=true                # log the Java stack of the first 5 GL errors, so a
                                                     # debug message names the call; .limit=N for more
-Dcrystalgraphics.gl.debugPerf=true                  # each distinct driver performance message once, with
                                                     # the stack of the call it came on, and counts: names a
                                                     # CPU stall ("pixel transfer is synchronized"). The
                                                     # harness asks for a debug context under it

# GL issued where none may be (any host)
-Dcrystalgraphics.gl.threadCheck=true                # log each CgGL call site inside a GL-free section
                                                     # (CgGL.enterGlFree -- a UI paint context recording) or
                                                     # off the context's thread, once, with its stack; every
                                                     # site and its count to stderr at exit

# Meshes
-Dcrystalgraphics.mesh.frameRing=false               # FRAME meshes take slab ranges like the others, not the
                                                     # frame ring: one build, both paths, for comparing them
-Dcrystalgraphics.mesh.editStacks=true               # each mesh edit records its stack, which the [cg-mesh]
                                                     # report of a mesh edited every frame prints
-Dcrystalgraphics.mesh.multiDraw=false               # a call a draw: no run of draws sharing pipeline, bindings and a
                                                     # slab joined into one multi-draw (render/graph/CLAUDE.md,
                                                     # Multi-draw); the picture must not change

# Frame graph
-Dcrystalgraphics.graph.barriers=false               # keep every access, issue no barrier: what synchronization
                                                     # validation must catch on --mode=compute-graph
-Dcrystalgraphics.graph.asyncAll=true                # every compute pass that can go async does, as if marked
                                                     # async(): a check of the waits, which the compute scenes must
                                                     # pass under synchronization validation

# Post stack (render/post/CLAUDE.md)
-Dcrystalgraphics.post.bloom.linear=true             # bloom composited in linear light (the copy form) from the start,
                                                     # to compare with the default blend form; L cycles blend,
                                                     # linear and off in any harness scene with a 3D camera
-Dcrystalgraphics.post.debug=emission               # the emission target over the frame; =level<N> a level of
                                                     # bloom's chain, 0 the glow the composite reads; =overdraw
                                                     # transparent fragments per pixel as a heat ramp (blue 1,
                                                     # green 4, red 16, white 32), CgWorldRenderer.overdraw;
                                                     # =distortion the distortion target, |offset| x 50 in red
                                                     # and green, the split in blue

-Dcrystalgraphics.world.halfResolution=false        # draws marked halfResolution() at full size, in the transparent
                                                     # pass (H in the harness)

# VFX (vfx/CLAUDE.md)
-Dcrystalgraphics.vfx.sim=gpu                        # cpu|gpu: where effects' particles simulate, cpu by default; V
                                                     # switches it in any harness scene with a 3D camera. gpu is not
                                                     # built yet and runs cpu, logged once
-Dcrystalgraphics.vfx.threads=1                      # threads the CPU path runs emitters on, the render thread one of
                                                     # them; one per core by default, 1 for all on the render thread
-Dcrystalgraphics.vfx.skip=body_light,haze           # layers whose shader path contains any of these draw nothing:
                                                     # what a frame's GPU time is spent on, one profile per group;
                                                     # CgVfxSystem.skip live
-Dcrystalgraphics.vfx.coarseVolumes=true            # measurement: volume layers on a 12x24 sphere, not 48x96
-Dcrystalgraphics.vfx.sharedDistortion=true         # measurement: distortion layers after every effect, one copy
-Dcrystalgraphics.vfx.particleStep=1                 # ticks a particle step spans: 2 by default (particles at 60 Hz,
                                                     # effects at 120), 1 steps them every tick
-Dcrystalgraphics.vfx.simBudgetMs=12                 # wall ms an update may spend catching up ticks before it drops
                                                     # the rest and effects slow down

# Compute tiers (compute/CLAUDE.md § Three forms)
-Dcrystalgraphics.compute.tier=G40                   # V|G43|G40|G33|CPU: run kernels as that tier would, where the
                                                     # context has what it needs; refused, naming it, where not
-Dcrystalgraphics.shaderBuffer.tier=TBO              # SSBO_GL43|SSBO_ARB|TBO: engine buffers read as buffer textures,
                                                     # GLSL 3.30, as a 3.3 context runs them; with G33, that context
-Dcrystalgraphics.compute.selfTest=true              # at the first frame, run a kernel of every shape on this context
                                                     # and check each result against Java; logs `[crystalgraphics]
                                                     # compute self-test <tier>: PASS|FAIL`, which prodSmoke gathers
                                                     # (-PcgSmokeProps=crystalgraphics.compute.selfTest=true)
-Dcrystalgraphics.compute.checked=true               # kernels run as compute bounds-check every buffer and image
                                                     # access: one out of range is skipped, and the first of each
                                                     # dispatch logged once with its .compute line (SHADERS.md,
                                                     # Debugging and testing). Every access tests its index

# The Vulkan device (--device=vulkan)
-Dcrystalgraphics.vulkan.syncValidation=true         # with the validation layer on, its synchronization checks too
-Dcrystalgraphics.vulkan.asyncCompute=false          # false|graphics: async() compute passes run in order on the
                                                     # frame's queue; graphics puts them on a second queue of its
                                                     # family rather than a compute-only one

# Caches kept across launches (CgCacheDirectory)
-Dcrystalgraphics.cache=false                        # keep nothing: SPIR-V and the Vulkan pipeline cache rebuilt
                                                     # each launch
-Dcrystalgraphics.cache.dir=path                     # the cache root, a folder per kind (spirv, vulkan); by default
                                                     # <game directory>/crystalgraphics/cache

# Extensions
-Dcrystalgraphics.gl.disableExtensions=GL_ARB_buffer_storage,GL_ARB_compute_shader
                                                     # treat these as absent (CgDisabledExtensions): a driver whose
                                                     # implementation misbehaves, and the harness's downlevel contexts.
                                                     # A feature core in the context's version stays; a forced tier
                                                     # turns that off

# Batching
-Dcrystalgraphics.recorder.lookback=false            # a recorder's passes join neighbouring draws only, in
                                                     # submission order: rules lookback out of a wrong picture

# Shader
-Dcrystalgraphics.shader.devmode=true                # emit #line directives in preprocessed output
-Dcrystalgraphics.shader.resourceOverrideDir=path    # filesystem override dir for shader sources
```
