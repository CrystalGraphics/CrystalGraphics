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

# Frame graph
-Dcrystalgraphics.graph.barriers=false               # keep every access, issue no barrier: what synchronization
                                                     # validation must catch on --mode=compute-graph

# Post stack (render/post/CLAUDE.md)
-Dcrystalgraphics.post.bloom.linear=true             # bloom composited in linear light (the copy form) from the start,
                                                     # to compare with the default blend form

# Compute tiers (compute/CLAUDE.md § Three forms)
-Dcrystalgraphics.compute.tier=G40                   # V|G43|G40|G33|CPU: run kernels as that tier would, where the
                                                     # context has what it needs; refused, naming it, where not
-Dcrystalgraphics.shaderBuffer.tier=TBO              # SSBO_GL43|SSBO_ARB|TBO: engine buffers read as buffer textures,
                                                     # GLSL 3.30, as a 3.3 context runs them; with G33, that context
-Dcrystalgraphics.compute.selfTest=true              # at the first frame, run a kernel of every shape on this context
                                                     # and check each result against Java; logs `[crystalgraphics]
                                                     # compute self-test <tier>: PASS|FAIL`, which prodSmoke gathers
                                                     # (-PcgSmokeProps=crystalgraphics.compute.selfTest=true)

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
