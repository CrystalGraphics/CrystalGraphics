# job — work off the game's thread

CPU work that runs beside the game and hands its result back to the thread that started it, and work split across
threads within a tick. GPU work off the render thread is `gpu/` and `render/graph`, not this. Design: private plan
`crystalgraphics/collision.md` §4.14.

```java
CgCollisionWorld area = CgCollisionWorld.of(level).snapshot(x, y, z, 24);   // the level's thread: frozen
CgJob.run(() -> simulate(area)).then(hits -> hits.forEach(this::hurt));     // a worker, then back here
CgJob.forEach(shards, s -> s.step(area));                                   // split now, returns when done
```

| File | Owns |
|---|---|
| `CgJob` | The API: `run`, `repeat(read).work(fn)`, `then`, `failed`, `named`, `cancel`; static `forEach`, `drain`, `isWorkerThread` |
| `CgJobLoop` | A thread's pending jobs, stepped in start order at its drain; the session counter `CgNetwork.onSessionEnd` bumps. Hooks Minecraft's server and client ticks when first loaded |
| `CgJobPool` | Jobs' work: `crystalgraphics-job-*`, below normal priority, `crystalgraphics.job.threads` (half the cores past two) |
| `CgWorkers` | `forEach`: the caller and `crystalgraphics-each-*` take items together, `crystalgraphics.job.forEachThreads` (two fewer than the cores). One runner per thread; a run nested in an item runs inline |
| `CgWorkerThread` | Marks both pools' threads, for `isWorkerThread` |

## Rules

- **Two pools, never one.** `forEach` joins every helper it queued, so a helper waiting behind a long job would hold
  the caller's frame.
- **A job starts on the game's thread** and `then` runs there; starting one on a worker throws. Inside work, split
  with `forEach`.
- **A worker never touches the host.** `CgCollisionWorld.of(level)` and `client()` throw on a worker, and a level's
  world asked from another thread throws: workers query a snapshot (`collision.query`'s `CgBlockSnapshot`,
  `CgEntitySnapshot`, and a frozen `CgShapeWorld`), each thread on a lane of its own.
- **A failure is never swallowed**: `failed`, else logged as `[cg-job] <name> failed`. A repeating job stops at one,
  including one thrown by its `then`.
- **Leaving a world cancels every job started in it**: `CgNetwork.onSessionEnd` (client disconnect, server stop).
- **`drain` and `forEach` allocate nothing.** `run` allocates its job, so a per-tick job is a `repeat`.
