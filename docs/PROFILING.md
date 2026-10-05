# CrystalGraphics — profiling

The one document for measuring anything built on CrystalGraphics: how to instrument it with the trace
engine, how to record it, how to read what comes back — and how to do all of that **once**. CrystalGUI
adds its own channels, frame phases, window and harness scenes in
[`docs/CGUI_PROFILING.md`](../../docs/CGUI_PROFILING.md); read this first, then that. The invocable
checklist is the `profiling` skill.

---

## The rule: one run answers the question

The expensive mistake is not a slow profile; it is the **second** one. An agent instruments what it
thinks is slow, runs the harness or the game, finds the time is somewhere it did not zone, adds a zone,
runs again, finds a worker thread it did not know about, runs again. Every one of those gaps was findable
before the first run, from the code. So:

1. **Write the question down** — which frames (steady state, the first open, a hitch, a resize), which
   operation, and which number would answer it (CPU per frame, GPU per frame, calls per frame, a count).
2. **Map the whole path before touching code.** From the host's frame loop down to every leaf the question
   touches, reading the code, and write the list:
   - every **phase** on the frame thread, *including the callers you did not think were involved*: follow
     each call site of the thing you suspect, not just the one you were looking at;
   - every **thread** — worker pools, async generation, uploads, a parse on an executor. A zone on the
     frame thread cannot see work handed off, and a frame that waits for it shows only the wait;
   - **first use versus steady state** — shader compiles, atlas growth, VAO creation, cache misses, class
     loading. They run once and are often the whole of a hitch;
   - the **GPU** as well as the CPU: a frame can be GPU-bound with an idle CPU;
   - the **counts** behind every loop and cache: how many items, how many hits and misses. Time says a
     phase is slow; the count says whether it scales with something;
   - **one-off events** — a resize, a reload, a window opening — as markers, so a spike has a cause beside
     it;
   - work that **crosses frames** (a file open, a search, a load) as spans, since no frame contains it.
3. **Check what is already instrumented** ([§ Instrumented today](#instrumented-today), and
   `grep -rn 'CgTrace\.' <package>`), and which **channel** each zone is on — a zone on a channel you do
   not record is not there.
4. **Instrument every gap from the list in one pass**, down to a leaf you could act on: one operation, one
   call you could make cheaper or skip. A zone you would have to split after reading it is a zone too big.
5. **Set up the run so it cannot come back partial** ([§ Before the run](#before-the-run)).
6. **Run once. Read the coverage before the numbers** ([§ Reading a report](#reading-a-report)): every
   frame states its `unzoned` cpu, its `unexplained` idle, the longest stretch of each with the zones either
   side, and its `GAP`s; the breakdown ranks zones by self time. If any of those is as large as the effect
   you are looking for, the map in step 2 was wrong — **fix everything the report names, together, before
   the next run.**

The report is built so a missing zone shows in the run that lacks it. An agent that reruns to discover a
gap has skipped either the map or the coverage.

**Unknown territory?** When you cannot say where the time goes at all, *sample first*, instrument second:
a JFR recording names the hot methods in one run, and those are what step 2 maps.

```bash
./gradlew :gl-debug-harness:runHarness --args="--mode=<scene>" \
    -Pharness.jvmArgs="-XX:StartFlightRecording=duration=60s,filename=build/scene.jfr"
jfr view hot-methods build/scene.jfr          # JDK 21+; `jfr print --events jdk.ExecutionSample` for stacks
```

Sampling finds *where*; only instrumentation says *which frame* and *how many times* — the two are
complements, and a hitch that happens once in 600 frames is invisible to a sampler.

---

## The engine in five minutes

`com.crystalgraphics.trace`, in the **platform** module — so a dedicated server and a headless test can
record too. It records an event stream into per-thread arenas and a ring of frames; everything else is a
view over that.

```java
private static final CgTraceChannel CH = CgTrace.channel("mymod.render");   // once, owner-first
private static final int DRAW = CgTrace.name("draw");                       // interned once

try (CgTrace.Zone z = CgTrace.zone(CH, DRAW)) {                             // a zone
    draw();
}
CgTrace.add(CH, "drawcalls", 1);                                            // a per-frame count
```

| Primitive | Call | Use for |
|---|---|---|
| **Zone** | `try (CgTrace.Zone z = CgTrace.zone(ch, name))` | a phase inside a frame, on one thread. Nests |
| Split zone | `long t = CgTrace.stamp(ch); ...; CgTrace.zoneDone(ch, name, t)` | a phase whose start and end are in different places, or a bucket that never nested. A 0 stamp (channel off) records nothing |
| Open/close | `long k = CgTrace.begin(ch, id); ...; CgTrace.end(k)` | the same as a zone without a handle |
| **Count** | `CgTrace.add(ch, name, delta)` | an event that fires many times a frame — summed, written once per frame per thread |
| **Reading** | `CgTrace.counter(ch, name, value)` | a value sampled (queue depth, bytes in use); several per frame make a distribution |
| Late count | `CgTrace.counterAt(ch, id, frameIndex, value)` | a total that lands after its frame moved on |
| **Marker** | `CgTrace.marker(ch, name[, detail])` | an instant: a resize, a reload, a compile — with an attribution in `detail` |
| **Span** | `long s = CgTrace.spanBegin(ch, name); ...; CgTrace.spanEnd(s)`, `spanDone(ch, name, t)` | a chain that crosses frames or happens outside one; nests per thread |
| **GPU zone** | `CgGpuTrace.begin(name); ...; CgGpuTrace.end()` | GPU time, GL thread only; lands in the frame 1–4 frames later as `gpuNanos` and a `gpu:<name>` counter |
| **GPU group** | `CgGpuTrace.mark(label)` inside a GPU zone, `markEnd()` to stop; a draw's `gpuGroup(label)` (world renderer, chunk builder) | a zone's time split by label without pausing it: `gpu:<zone>/<label>` counters, which `gpuNanos` leaves out. The report's GPU GROUPS section lists each zone's groups by cost. A run is completion to completion, so a zone's first run takes in work still in flight from before it |
| **Wait** | `static final int SYNC = CgTrace.waitName("frame.sync")`, then an ordinary zone | a zone that is waiting, not working — a sleep holding the rate, a fence, a swap. Reports list it under WAITS and leave it out of every cost table |
| **Frame** | host only: `CgTrace.frameBegin()` / `frameEnd()` | the boundary. A frame closes at the NEXT `frameBegin`; `frameEnd` is the CPU mark |

**A zone or a count?** A zone per item of a loop that runs thousands of times a frame (a glyph, a quad) is
thousands of zones: it floods the arena and costs ~50 ns each. Zone the loop, count the items.

**Where the zone goes.** In the **callee**, so every caller is measured — a zone at one call site misses
the other three. At the call site only when the question is about *that* caller.

### Channels

A channel is what somebody switches on; everything on a channel nobody enabled costs one mask test
(~1 ns). Names are dotted and owner-first; `enable("x")` takes `x` and everything beneath it, including
channels that register later — except a **detail channel**, which only its full name switches on.

| Channel | What is on it | Density |
|---|---|---|
| `crystalgraphics.text` | shaping, line breaking, fonts, glyph generation and placement, one zone per text draw (`glyph.resolveGlyphs`) and per text batch. Counters `placementCache.hit`/`.miss`, a miss by reason (`.miss.absent` a key never cached, `.evicted`, `.targetPx`, `.content` an entry refreshed because the atlas gained glyphs), and `atlas.added.bitmap`/`.msdf`/`.empty` per glyph the atlases took | hundreds a frame on a busy screen |
| `crystalgraphics.gl` | one zone per material bind (`material.doBind`) and per quad and curve flush, the frame ring | about 1,400 a frame on the CrystalGUI desktop |
| `crystalgraphics.gl.detail` | **detail** — the steps inside each: `doBind.*`, `quadRenderer.upload/bindBuffer/drawInstanced`, `curveRenderer.*`, stream-buffer `map`/`write`/`commit` | about 9,000 a frame on the desktop |
| `crystalgraphics.text.detail` | **detail** — the steps inside each text draw: `placementCache.*`, `glyph.flatten/resolvePlacements/resolveDecorations`, `draw.sortKeys/syncProjection/submitSortedQuads/quadLoop/planShadows` | about 1,400 a frame |
| `crystalgraphics.world` | the 3D world passes and the pipeline's phases | a handful a frame |
| `crystalgraphics.shadergraph` | the shader graph's emitters and preview renderers | a few dozen a frame with a graph open |
| `crystalgraphics.async` | background workers | varies |
| `crystalgraphics.misc` | everything else; the harness's own frame phases | light |
| `gpu` | `CgGpuTrace` timer queries | one per GPU zone |
| `crystalgraphics.gpu.groups` | **detail** — a timed raster pass split by material, `gpu:<zone>/<shader path>`: a timestamp wherever the material changes. Needs `gpu` too. `--mode=gpu-groups` is its gate | one per material change |
| `images` | a small picture of the frame every 30 frames (`CgFrameImages`) | one readback per 30 frames |
| `trace`, `trace.*` | the engine's own events; a viewer's own work | — |

A mod declares its own (`CgTrace.channel("mymod.worldgen")`) — never borrow `misc` for a subsystem. 64
channels at most; a 65th is inert rather than aliased. Steps inside an operation that runs hundreds of
times a frame go on a detail channel (`CgTrace.detailChannel("mymod.worldgen.detail")`), leaving one zone
per operation on the ordinary one; a run that needs them names them, as
`-Dcrystalgraphics.trace.channels=crystalgraphics,crystalgraphics.gl.detail`.

### Cost — why instrumentation stays in

| | |
|---|---|
| A zone on a channel that is off | 0.87–1.1 ns (a volatile read and an AND) |
| A zone on a channel that is on | ~50 ns (two clock reads, four array writes, no allocation) |
| `cgui-desktop` with every channel off | ~0.7 µs a frame, 0.012% — at most 9.3 µs (0.15%) (measured 2026-09-27) |

So a zone added to answer a question is **left in**: the next question is usually about the same code.

---

## Before the run

A run that comes back partial is a second run. Check each of these first:

| Check | How |
|---|---|
| **Every channel the path touches is on** | `-Dcrystalgraphics.trace.channels=crystalgraphics,gpu,<yours>` — applied at launch on any host, and taking channels that register later. The report's header lists what was NOT recording |
| **The ring holds the frames you want** | `-Dcrystalgraphics.trace.frames=<n>` (newest, 600); `-Dcrystalgraphics.trace.firstFrames=<n>` keeps a run's FIRST frames for good — startup, a first open, a cold cache; or `CgTrace.configure(first, newest, zonesPerFrame)` |
| **The arena holds the zones** | `-Dcrystalgraphics.trace.zones=<per thread>` (65,536 — **a dozen frames** with `crystalgraphics` on, which writes ~5,000 zones a frame). Size it as frames × zones per frame, or `CgTrace.configure(first, newest, zonesPerFrame)`; the harness's profile mode does it for you. The report warns on dropped zones and on frames whose zones were overwritten |
| **A rare hitch is kept** | `CgTrace.stopAfterHitch(ns, framesAfter)`: stops recording N frames after a slow one, so the ring does not roll past it |
| **The workload repeats** | a scene or script that does the same thing every run, a fixed delta where the harness offers one, warm-up excluded (the harness drops 30 frames) |
| **The frame is not capped** when the question is headroom | `-Dcrystalgraphics.harness.fps=0` — under the 120 cap every fast frame's wall time is the cap |
| **The run ends by itself** | `-Dcrystalgraphics.harness.profile=<frames>` stops the harness; a scene's own exit flag; a game's autotest |
| **GPU numbers are possible** | `CgGpuTrace.support()` — a context without timer queries records nothing and says so |

---

## Noise — know what you measured

A frame time is the code *plus* everything else on the machine *plus* whatever the process was still doing
to itself. Three kinds, each with a control and a tell:

| Kind | Tells you it is there | Control |
|---|---|---|
| **The machine** — another harness, a Gradle build (other agents share this machine), IDE indexing, a game client, a browser, power saving | the same run twice gives medians further apart than the effect; `unexplained` idle high in the slow frames **once the host's loop is zoned**; a flat, uniform slowdown across every zone | nothing heavy beside the run (`tasklist \| findstr java`); the performance power plan; **run twice** and state both |
| **The scene still starting** — JIT, class loading, glyph generation, language engines, shader compiles, caches filling | the slowest frames are the first profiled ones; `msdfgen.*`, `registry.*`, `material.recompile` or a `FIRST-DRAW` hint inside the window; a falling trend across frames | a warm-up long enough (`-Dcrystalgraphics.harness.profile.warmup=<frames>`; the desktop needs hundreds); **check the first profiled frames**, and if they are the slow ones the warm-up was too short |
| **The measurement** — the frame cap, vsync, GC, `crystalgui.blame`, `images`, a viewer open | wall pinned at the cap; a `GC` column on the worst frame; a `COLLECTED` hint | `-Dcrystalgraphics.harness.fps=0` for headroom; blame and images off while timing; read the GC column before blaming code |

Rules that follow:

- **Medians and the spread, never one frame or a mean** — unless the question is the hitch itself.
- **Compare inside one process**: two ranges of one ring, or blocks alternating A/B/A/B with the switch
  flipped between them (`TraceCostProbe` in `harness-scenes` is the pattern) — drift cancels.
- Across runs: back to back, on a quiet machine, each at least twice. **A difference smaller than twice the
  run-to-run spread is not a finding** — say so rather than report it.
- **CPU per call across backends wants the JIT done**: the tracked backend runs far more Java per call than GL's
  few JNI calls, and C2 compiles it only after thousands of calls. `gpu-ops-cost` after 10 frames put Vulkan's
  bounds at 0.48 ms of CPU against GL's 0.04, and after 600 at 0.08 against 0.01
  (`-Dcrystalgraphics.harness.opsCost.warmup=600`).
- **A spike no zone explains is the machine until a bare window says otherwise.** A stall of tens of
  milliseconds to seconds lands in whatever first waits on the driver -- a `glGet` (`glState.adopt`,
  `stage.parkSamplers`), the swap, even `glfwPollEvents` -- so where it shows names no cause. Before any
  engine theory, run a bare GLFW window (clear and swap, nothing of ours) for two minutes with `nvidia-smi`
  sampling beside it: if it stalls too, the machine is part of it -- and readbacks make those stalls worse
  (below, *A readback's time*). The recipe, the script and what it found
  (on 2026-10-01 a background utility froze every OpenGL window on this machine for seconds) are in
  `plan/gl-gpu-stalls-notes.md`, from its line *If the freezes come back, start here*.

---

## Where it runs

### The GL debug harness — the default

Any interactive scene, profiled over a frame count, one folder, no code:

```bash
./gradlew :gl-debug-harness:runHarness --args="--mode=<scene>" \
    -Dcrystalgraphics.harness.profile=300 \
    -Dcrystalgraphics.trace.channels=crystalgraphics,gpu
```

- Drops the warm-up (30 frames, `-Dcrystalgraphics.harness.profile.warmup=<n>`), records the profiled
  frames, writes and stops. `crystalgraphics` is always on in this mode; the property adds the rest.
- **Sizes the ring for the run**: every profiled frame keeps its zones (16,384 a thread a frame;
  `-Dcrystalgraphics.harness.profile.zonesPerFrame=<n>`), and a viewer's saved stop-after-hitch is turned
  off. Without that, dense channels wrap a thread's arena within a dozen frames.
- Output in `gl-debug-harness/harness-output/<scene>/profile-harness-<n>f/` — the same path every run, so
  a loop is "run, read `report.txt`, edit, run, diff against `profile-harness-<n>f.prev/`":

| File | Is |
|---|---|
| `report.txt` | **read this first**: `CgTraceReport`'s breakdown — see [Reading a report](#reading-a-report) |
| `tree.txt` | every thread's call tree, workers included: ms and calls per frame, the longest call, the source |
| `trace.json` | only with `-Dcrystalgraphics.harness.profile.json=true`: the frames for `ui.perfetto.dev` (drag the file in) |

  Sources print repository-relative (`core/src/main/java/.../BoxTree.java:333`), found from the outermost
  directory with a `settings.gradle(.kts)`, or `-Dcrystalgraphics.harness.repoRoot`.

- Every `-Dcrystalgraphics.*` on the Gradle line is forwarded into the harness JVM; JVM flags go through
  `-Pharness.jvmArgs="..."`.
- The runner frames each loop itself unless the scene does (a CrystalGUI document does).
- A scene with its own measurement (`text-3d`, `CgTextStressScene`) uses `TraceReport` / `TraceDump` in
  `com.crystalgraphics.harness.trace` — per-frame or since-a-mark readings of the ring.

### Minecraft

Every dev run — modern, legacy and 1.7.10 — forwards `-Dcrystalgraphics.*` and `-Dcrystalgui.*` into
the game:

```bash
./gradlew :runtime:mc:modern:forge:1.20.1:runClient \
    -Dcrystalgraphics.trace.channels=crystalgraphics.world,crystalgraphics.gl,gpu
```

On an installed client, put the same `-D` in the instance's JVM arguments. A host that gives the trace a
run directory (CrystalGUI's desktop does) writes `report.txt` and `meta.json` into
`<game>/crystalgui/cache/trace/latest/` on exit; CrystalGraphics alone writes nothing to disk, and a mod on
it reads `CgTraceReport.of(CgTrace.snapshot())` itself. **Profile world rendering in the game**, not the
harness: the world passes only exist there.

### Tests

The engine runs headless. Drive frames on a synthetic clock so a distribution can be asserted:

```java
CgTrace.resetForTesting();
CgTrace.enable("mymod");
CgTrace.frameBegin(clock);
CgTrace.zoneDone(CH, "work", clock, clock + 5_000_000L);
CgTrace.frameEnd(clock + 6_000_000L);
CgTrace.frameBegin(clock + 8_000_000L);          // commits the frame above
CgTraceReport.of(CgTrace.snapshot()).frame(0);
```

---

## Reading a report

`CgTraceReport` is the surface built for an agent: tiered, deterministic (two runs `diff`), every figure
per frame rather than summed, and every zone line ends in the file and line its name was first used from.

Three times, and the report keeps them apart:

| | Is | Its section |
|---|---|---|
| **cpu** | the frame's `frameBegin` to its `frameEnd` — the work | PHASES, SELF TIME, the frame trees |
| **idle** | wall − cpu: from the cpu mark to the next frame | WAITS; a frame tree's `[after cpu]` rows |
| **unexplained** | idle no zone covers | PER FRAME, every frame headline |

A breakdown (`report.txt`), in order, each section a question:

```
PROFILE   299 frames, #601-#899 over 2.92s, frame thread 'main'
channels  recording: ... |  NOT recording: crystalgui.blame, images
data      zones held for 299/299 frames, counters for 299/299, 0 zones dropped

VERDICT   120 fps median. Over the 16.7ms budget: 9/299 frames by wall, 4 by cpu.
          Slowest by cpu #612 (56.83ms cpu); slowest by wall #612 (56.96ms wall, 56.83ms cpu).
          Over by wall alone: 5 frames. Their idle: scene:workspacePump (after the cpu mark, 95%, max 45.45ms).

PER FRAME   median / p90 / max / the frame of the max, for wall, cpu, idle, unexplained, gpu, unzoned
PHASES      the frame thread's top-level zones: mean, p90, max, calls per frame, source
WAITS       the wait zones, the same columns
SELF TIME   every zone by its own body, all threads, with a kind: GAP, leaf or -
COUNTERS    per counter: median and max of the frame's sum, how many frames wrote it, writes per frame
MARKERS     instants, with the details they carried
HINTS       each rule: how many frames it fired in, and the first
SLOWEST BY CPU / SLOWEST BY WALL   five frame headlines each
FRAME #612 ... -- slowest by cpu     then the slowest by wall and a typical frame, each:
    paint:tree                               54.01  95%   core/src/main/java/com/crystalgui/ui/box/BoxTree.java:333
      quadRenderer.flush x142                35.61  63%   CrystalGraphics/core/.../CgQuadRenderer.java:591
    scene:workspacePump  [after cpu]         45.74 idle   harness-scenes/.../CgUiDesktopScene.java:420
    frame.sync  [wait]                        8.34 wait   gl-debug-harness/.../InteractiveSceneRunner.java:111
  unzoned   0.31ms of 56.83ms cpu (1%) -- frame-thread time no zone covers
            longest stretch 0.10ms: after scene:readout, before glbegin:frameClear
  GAP       frame:layout: 0.84ms of 0.89ms is in none of its children   core/.../UIDocument.java:478
  counters  those off their median or written in few frames; the rest are at the median
```

A frame tree merges siblings of one name (`x142` is 142 calls, their times summed) and folds rows under 1%
of the frame into a `(+N smaller)` line that says how much they hold.

Read in this order:

1. **The header.** `NOT recording` lists channels that were off — a gap there is not "no work". A
   `WARNING … zones dropped` means the arena overflowed; `WARNING … frames hold no zones` means a
   thread's arena wrapped and older frames lost theirs — those frames print `zones none held` and their
   time is unknown; `WARNING … frames hold no counters` is the same for counters. Any warning means
   **resize and rerun before concluding anything** about the frames it names.
2. **Coverage.** `unzoned` is frame-thread cpu no zone covers, `unexplained` the same for idle, and each
   frame prints the **longest stretch** of either with the zones on both sides — that is where the next
   zone goes. A `GAP` is time inside a zone none of its children name; SELF TIME marks each zone `GAP`
   (instrument inside it), `leaf` (split it if it is large) or `-` (its children hold it). **If any of these
   is as large as the thing you are chasing, instrumentation is missing** — go back to the map, fix all of
   them, then run.
3. **The verdict.** Over budget by cpu is work; over by wall alone is idle, and the verdict names what
   filled it — a zone after the cpu mark (host work outside the frame), a wait, or nothing named.
4. **The frames.** The slowest by cpu is the work question; the slowest by wall is the stall question; the
   typical frame is the steady state. A frame's counters list only what sets it apart from the median.
5. **Counters and hints.** A hint is a rule over counter names, with a link to what explains it.
6. **Wall, cpu and GPU are three questions.** Wall is the frame rate; cpu is headroom; GPU (`gpu:`
   counters, printed in ms) is the other half — a frame can be GPU-bound with an idle cpu. **Absent is not
   zero**: a missing cpu mark or an unlanded GPU figure prints as absent.

The queries, on any snapshot:

```java
CgTraceReport r = CgTraceReport.of(snapshot).budget(1000d / 60d);
r.verdict();                       // ~20 lines
r.breakdown();                     // ~120: slowest frames, zone table, self time
r.render(CgTraceReport.Tier.FULL); // every frame
r.frame(412);  r.worst(5);  r.zone("paint:tree");
r.compare(0, 299, 300, 599);  r.regressions(0, 299, 300, 599);   // two ranges of ONE ring
```

**Comparing a change**: two ranges of one ring are the honest comparison (same process, same JIT). Across
runs, run each build back to back on one machine, twice; differences smaller than run-to-run spread are
not findings.

---

## Conventions

- **Names are constants.** Interned once with their source location; a name built per call re-interns
  and points the report at the wrong line. Hot paths hold the `int` from `CgTrace.name`. Detail that
  varies goes in a counter or a marker's `detail`, never the zone name.
- **CrystalGraphics names are `subsystem.phase`** (`batch.flush`, `glyph.resolvePlacements`,
  `pipeline.forward`); counts are `subsystem.thing` (`gl.flush.count`, `atlas.evictPage.count`).
- **One channel per owner**, split by *price* when one part is far denser than the rest (that is why
  `text` and `gl` are separate from `world`).
- **Never time with `System.nanoTime()` and a log line.** It is invisible to every reader, and a log call
  on the frame thread changes what it measures. Use a zone; the log is `CgTraceLog`, off-thread.
- **Absent, never zero.** A reading that was not taken is not recorded; a viewer draws a gap.
- **Leave it in** unless it is on a path so hot that even the mask test matters (none is today).
- **A zone must close on every path** — try-with-resources, or `zoneDone`. A leaked zone is force-closed
  at the next frame boundary and counted (`NOTE … force-closed`).
- **GPU zones do not nest**: an inner one pauses the outer, which is timed in pieces
  (`gpu.nestedFlattened`). Put them around whole passes.

---

## Instrumented today

CrystalGraphics' zones, by package — **before adding one, look here and in the file**:

| Area | Channel | Zones and counts | Where |
|---|---|---|---|
| Text layout | text | line breaking, shaping, layout cache | `text/layout/CgLineBreaker`, `CgTextShaper`, `CgTextLayoutEngine`, `CgTextLayoutCache` |
| Glyph supply | text, async | `registry.*`, generation, atlas growth and eviction, packing | `text/cache/CgFontRegistry`, `CgWorkerFontContext`, `text/atlas/*`, `text/msdf/CgMsdfGenerator` |
| Text draw | text, gl; text.detail | `glyph.resolveGlyphs`, `draw.materialTransition`, `gl.flush`; on text.detail `placementCache.*`, `glyph.flatten/resolvePlacements/resolveDecorations`, `draw.sortKeys/syncProjection/submitSortedQuads/quadLoop/planShadows` | `text/render/CgTextRenderer`, `CgResolvedGlyphs` |
| Materials | gl; gl.detail | `material.*`; `doBind.*` on gl.detail; a compile split into `material.parse`, `.codegen`, `.preprocess`, `.glCompile` (or `.glSubmit` when deferred), `.depthAutoGen`, `.shadowAutoGen`; a deferred one under `material.submitRecompile`, then `.commit`/`.awaitPending`, and `.lateAutoGen` when a forward-only compile's depth or shadow pass is first asked for; `material.generated.hit/miss` counts | `api/material/CgMaterial`, `gl/material/CgMaterialShader`, `CgMaterialShaderRegistry` |
| Kernels | gl | `compute.compile` (a kernel's program: its GLSL written and compiled) and `compute.compileLowered`, counted by `compute.compiles`; `compute.prepare` (a `prepare()`: written and submitted) and `compute.compileWait` (a dispatch waiting for a prepared link), counted by `compute.compile-waits`; on a device, inside it, `shader.spirv` (shaderc and reflection) and `shader.computePipeline`; `shader.spirv` covers a graphics program's link too, on the `crystalgraphics-shaderc` thread where the backend compiles in the background, and `shader.spirvWait` is the owner thread waiting for it | `compute/CgCompute`, `platform/gl/tracked/gl/TrackedPrograms` |
| Shader graph | shadergraph, gpu | `shadergraph.emit`, `.previewEmit`; `preview.renderPending/render/draw`, `mainPreview.render/draw` — recording only: the passes execute inside the frame that records them, so their GPU time is that frame's; counters for what drew, was unchanged, is animated or still compiling, and `mainPreview.fallback` for a frame the main preview drew flat white | `shadergraph/CgShaderEmitter`, `CgPreviewEmitter`, `CgPreviewRenderer`, `CgMainPreviewRenderer` |
| Batching | gl; gl.detail | `batch.*`, `quadRenderer.flush`, `curveRenderer.flush`, `frameRing.wait`; on gl.detail their `upload`/`bindBuffer`/`drawInstanced` and stream buffer `map`/`write`/`commit` | `gl/render/*`, `gl/buffer/*` |
| Texture arrays | gl | uploads, growth | `gl/texture/CgTexture2DArray` |
| World | world, gpu; gl | a stage's whole firing as its path (`world.opaque`, `world.transparent`), the world renderer's recording inside it (`world.recordOpaque/recordTransparent`), `world.opaqueDraws/transparentDraws/emissiveDraws` counts; `gpu:world.opaque/transparent`, and bloom's passes on their own (`gpu:world.emission`, `post.bloom.chain`, `post.composite`, through `CgRasterPass.timed`); `post.record` the post stack's recording; on gl `stage.parkSamplers/unparkSamplers`, the host's sampler bindings read and put back around every firing | `render/stage/CgRenderStage`, `CgStageFrame`, `render/world/CgWorldRenderer` |
| VFX | vfx; world | `vfx.sim` (world) with a `vfx.tick` per tick, `vfx.emitters` inside it (the render thread's share of the emitters, and its wait for the `crystalgraphics-vfx-*` workers, whose time counters land on their own threads); `vfx.submit` › `vfx.warm`, `vfx.effect.submit` per effect (`vfx.particles.meshes` inside it, a mesh draw per particle), `vfx.paths.upload`, `vfx.particles.write` (records filled on the workers, then each record's `CgWorldLight.at` on the render thread where there is a level); `showcase.spheres`. The per-particle loops are time counters, not zones (`CgVfxTrace`): `vfx.sim.spawn/solve/age-ns`, `vfx.module.<kind>-ns` per module kind, `vfx.wave.stream/ground-fill/path-ns`; counts `vfx.ticks`, `vfx.ticks.capped` (a frame that stopped short of the clock, at 12 ticks or `vfx.simBudgetMs`: effects slowed down), `vfx.effects`, `vfx.particles.spawned/ticked/written`, `vfx.wave.path-rings`, `vfx.draws.<kind>`; a `vfx.blast` marker at each blast | `vfx/CgVfxSystem`, `CgVfxFrame`, `particle/CgVfxEmitterInstance`, `effect/beam/CgEnergyWave`, `render/CgVfxTube`, `demo/CgVfxShowcase` |
| GL state | gl | `glState.open` and `glState.restore` per scope, `glState.open.all`/`.restore.all` for a scope of every slot (`saveAll`); `glState.adopt` per domain read from the provider, inside an open; counters `glState.scopes`, `.scopes.all`, `.adopt.count`, `.adopt.units`; `glState.glGet.<slot>`, the reads a host-cache provider sent to the driver (a domain it cannot answer, or a startup check) | `platform/gl/CgGlStateManager`, `gl/state/CgCheckedProvider` |
| Culling | gl | frustum tests | `render/CgViewFrustum` |
| Frame graph | gl; gl.detail | `graph.build`, `graph.execute`, and inside it `graph.deferrals` (deferred texture work, the frame's pool), `graph.placeMeshes` and a zone per compute pass named by the pass (`recording.compute(name)`), with its GPU zone (`gpu:<name>`) on gl.detail, since an enclosing GPU zone is timed around it, not through it; counters `graph.dispatches`, `.dispatches.lowered`, `.dispatches.cpu` (the form each ran as); counters `graph.passes`, `.batches`, `.draws`, `.snapshots`, `.instances` per build; `graph.batches.skipped` (a pipeline with no program: its draws are missing that frame) and `graph.requested.made` (a requested texture's storage made -- at first use, or again after its picture was lost) per execution; `graph.passes.undamaged` and `graph.damage-kpx` (passes cut to their damage); `graph.again.requested-kept`/`-drawn` (a frame executed again: passes into kept textures skipped, or drawn whole); `graph.multi-draw.binding-breaks` (a run of draws ended only by the next draw's bindings: what bindless would join), `graph.indirect-commands` | `render/graph/CgFrameBuilder`, `CgExecutor` |
| Meshes | gl | `mesh.commitRing` (the frame ring's pages unmapped before the first pass; FRAME meshes are written at placement, inside `graph.placeMeshes`); counters `mesh.placed`, `.uploads`, `.upload-bytes`, `.ring-bytes`, `.slab-kb`, `.drawn-vertices`, and `mesh.edited-every-frame` (meshes not FRAME edited 60 frames running); `mesh.multi-draws` and `mesh.multi-draw-commands` (joined calls, and the draws in them) | `render/mesh/CgMeshStore` |

**Not instrumented** — zone these before any question that touches them: the executor's raster passes one by one
(`render/graph/CgExecutor`), mesh loading (`api/mesh/CgMeshLoader`), framebuffer creation and blits beyond the
depth snapshot, texture loading (`CgTextureManager`, `CgTextureIO`), raw `CgShader` compiles outside a
material, hot reload, and every host's own hooks (`runtime/mc/**`).

**A readback's time is not always its own either.** A `glGet` waits for the driver's own thread to drain what was
queued, so the first readback of a stage (`stage.parkSamplers`, `glState.adopt`) takes a stall from anywhere: another
process on the GPU, a present still pending. **And the reads make it worse**: a bare GLFW window making the reads a
frame here makes (two sampler parks, 60 more `glGet`s) had three times the frames over 20 ms of the same window
without them, 28-33 against 9-10 in two minutes, up to 96 ms against 41, with the time inside the reads
(`BareGets.java`, `plan/gl-gpu-stalls-notes.md`). So a spike in `stage.parkSamplers` or `glState.adopt` is the
machine's hiccup paid synchronously, at a price these reads set. Compare spike counts over interleaved runs, never
one run.

**A GPU zone's time is not always its own.** The first GPU zone of a frame absorbs whatever the GPU was still
finishing: in the shader graph, the main preview's GPU zone once read 9 ms, and with that draw switched off the
same 9 ms moved to the thumbnails'. Switch the suspect off and see where the time goes before believing it.

---

## Flags

| Flag | Does |
|---|---|
| `-Dcrystalgraphics.trace.channels=a,b` | records those channels (and everything beneath each, detail channels apart) from launch, on any host |
| `-Dcrystalgraphics.trace.frames=<n>` | newest frames kept (600) |
| `-Dcrystalgraphics.trace.firstFrames=<n>` | first frames kept for good (0) |
| `-Dcrystalgraphics.trace.zones=<n>` | zones per thread at most (65,536) |
| `-Dcrystalgraphics.trace.countersPerFrame=<n>` | ceiling on counter values a frame may hold before the store wraps (1,024) |
| `-Dcrystalgraphics.trace.report=verdict\|breakdown\|full` | the tier CrystalGUI's run directory writes on exit (breakdown) |
| `-Dcrystalgraphics.harness.profile=<frames>` | harness: warm up, record that many, write `profile-harness-<n>f/`, stop |
| `-Dcrystalgraphics.harness.profile.json=true` | also write `trace.json` for ui.perfetto.dev |
| `-Dcrystalgraphics.harness.repoRoot=<dir>` | where report sources are made relative to (the outermost Gradle build) |
| `-Dcrystalgraphics.harness.profile.warmup=<n>` | frames dropped first (30); a scene that keeps loading needs hundreds |
| `-Dcrystalgraphics.harness.profile.zonesPerFrame=<n>` | zones a profiled frame may hold per thread (16,384) |
| `-Dcrystalgraphics.harness.profile.splitAt=<marker>` | also write `-before` and `-after` reports, split at the frame of that marker's first appearance: a scene's two phases from one run (`vfx.blast` in `vfx-spheres-stress`) |
| `-Dcrystalgraphics.harness.profile.keepOpen=true` | write the report and keep the scene running, for a profile someone pilots |
| `-Dcrystalgraphics.harness.fps=<n>` | harness frame cap (120); `0` takes it off — a headroom measurement wants it off, since a capped frame's wall time is the cap |
| `-Pharness.jvmArgs="..."` | JVM flags for the harness — JFR, GC logging |
| `-Dcrystalgraphics.state.verify=true` / `.noDedup=true` | GL state manager diagnosis — not profiling, and very slow |
