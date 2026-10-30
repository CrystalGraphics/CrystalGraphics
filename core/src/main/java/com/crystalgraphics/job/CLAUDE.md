# job — work off the caller's thread

CPU work that runs beside the game or a UI and hands its result back to the thread that started it, and work split
across threads within a tick. **Using it: `docs/JOBS.md`, the guide.** GPU work off the render thread is `gpu/` and `render/graph`, not this. Design: private
plan `crystalgraphics/collision.md` §4.14; the policy (keys, debounce, lanes, replies, progress) is CrystalGUI's
`core.async`, moved here so both projects share one pool.

```java
CgCollisionWorld.of(level).snapshot(x, y, z, 24)                           // the level's thread: a reply, frozen
        .flatMap(area -> CgJob.run(() -> simulate(area, copy)))            // a worker
        .then(next -> pieces = next);                                      // back here
CgJob.keyed(doc, "reparse").debounce(300).run(ctx -> parse(text, ctx)).then(doc::setTree);
CgJob.forEach(shards, s -> s.step(area));                                   // split now, returns when done
```

| File | Owns |
|---|---|
| `CgJob` | The API: `run` (`Supplier`, `Runnable`, or `Work` taking a `CgJobContext`), `keyed`/`lane`/`on` builders with `debounce`, the reply (`then`, `onError`, `always`, `map`, `failed`, `cancel`), `progress`, `isRunning`; `repeat(read).work(fn)` → `Repeating`; static `forEach`, `executor(lane)` (the pool for work that reports nothing back), `drain`, `isWorkerThread` |
| `CgJobBase` | The state a job moves through, shared by `CgJob` and `Repeating`: the worker's run, the drain's step, cancel and supersede |
| `CgJobLoop` | Where jobs report back: pending jobs stepped in start order at `drain`, each key's latest job. Every thread has its own (ended with the game session); `create` makes one for a sequence or a test, `enter` makes it current. Hooks Minecraft's server and client ticks when first loaded |
| `CgJobContext` | Handed to `Work`: `isCancelled`, `throwIfCancelled`, `progress` |
| `CgJobKey`, `CgJobLane` | Single-flight identity (owner by identity, plus a kind); urgency |
| `CgJobQueue` | Which queued job a free worker takes: starved first, then lane, then first come; background never the last thread |
| `CgJobPool` | Jobs' work for every loop: `crystalgraphics-job-*`, below normal priority, `crystalgraphics.job.threads` (half the cores past two) |
| `CgWorkers` | `forEach`: the caller and `crystalgraphics-each-*` take items together, `crystalgraphics.job.forEachThreads` (two fewer than the cores); a job worker's on `crystalgraphics-each-bg-*`, below normal. Items are claimed by a CAS tagged with the run, and the caller waits only for claimed items, never a helper that claimed none (`forEach.join`, `forEach.idle-helpers`). One runner per thread; a run nested in an item runs inline |
| `CgWorkerThread` | Marks both pools' threads, for `isWorkerThread` |
| `reply/` | `CgReply` (one shape for an answer not here yet: `both`, `all`, `of`, `failed`), `CgPendingReply` (the producer's half), `CgStream`/`CgPendingStream` (in pieces), `CgReplyError` |
| `progress/` | `CgProgress` (the writer), `CgProgressState` (one reading), `CgProgressTracker` (keeps the latest, with an ETA), `CgRateEstimator` (Chromium port, BSD-3, `THIRD-PARTY.md`) |

## Rules

- **Two pools, never one.** `forEach` joins every helper it queued, so a helper waiting behind a long job would hold
  the caller's frame.
- **Every decision about a job is made on its loop**: debounce against the loop's clock, promotion, delivery. Only the
  work runs elsewhere, so a loop with `Runnable::run` and a manual clock is the real code path, step by step.
- **A job starts on the thread it reports to** and its listeners run there; starting one on a worker throws. Inside
  work, split with `forEach`.
- **A worker never touches the host.** `CgCollisionWorld.of(level)` and `client()` throw on a worker, and a level's
  world asked from another thread throws: workers query a snapshot (`collision`'s `CgBlockSnapshot` and
  `CgEntitySnapshot`, with its hittable hitboxes frozen), every thread at once.
- **One in flight per key.** A newer start replaces a waiting job and supersedes a running one: its context is
  cancelled, its result dropped whether it polled or not, and its reply never settles. The newer one waits until the
  old one's work has returned.
- **A failure is never swallowed**: `onError`/`failed`, else logged as `[cg-job] <name> failed`. A repeating job stops
  at one, including one thrown by its `then`. A cancel settles with `CgReplyError.CANCELLED` and is not a failure.
- **Leaving a world cancels every job a thread's own loop holds**: `CgNetwork.onSessionEnd` (client disconnect, server
  stop). A loop from `create` ignores the session and ends its jobs at `close`.
- **`drain` and `forEach` allocate nothing.** `run` allocates its job and reply, so a per-tick job is a `repeat`.
- **Replies are single-threaded**: settle a `CgPendingReply` on the thread that owns it, never a worker.

---

Also loaded with this folder:

@../../../../../../../docs/JOBS.md
