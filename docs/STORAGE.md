# Storage — the guide

CrystalGraphics gives your mod somewhere to keep its own files, a cache that is safe to lose, and a way to download
what it needs, the same on every version and loader and on a dedicated server. You name your product once; the engine
decides the folders, writes files so a crash never leaves half of one, verifies what it downloads, resumes what was
interrupted, and lets a dead link be repaired without a new release.

It comes in three parts:

- **Layout and config**: one folder per product, split into what must survive (preferences, sessions) and what may be
  deleted (derived output); values and text read and written by name, in the format the name's extension says. It is
  [`SERIALIZATION.md`](SERIALIZATION.md)'s codecs and formats underneath.
- **Cache files**: a file installed only once its bytes are verified, atomically, with a partial one kept to resume.
- **Downloads**: a described transfer — named, reported, cancellable, resumed, retried, verified — and a
  `download/locations.json` per product that says where each artifact lives.

Read sections 1 and 2 first; the rest is by need.

## If you have written this by hand before

**The usual way.** A path built from `FMLPaths` or `FabricLoader`, a `Files.writeString`, a `URL.openStream`:

```java
Path dir = FMLPaths.GAMEDIR.get().resolve("mymod");                  // a different call on every loader
Files.writeString(dir.resolve("settings.json"), json);               // a crash mid-write leaves half a file
try (InputStream in = new URL("https://example.org/engine.jar").openStream()) {
    Files.copy(in, target);                                          // no timeout, no resume, no check, no bar
}
// The host moves: every released jar is dead. The download is cut: the next start trusts the stub on disk.
```

**Here.**

```java
CgConfigStorage config = new CgLocalConfigStorage(LAYOUT.configIn(CgGameDirectory.game()));
config.write("settings.toml", Settings.CODEC, settings);             // a codec, a format by extension, whole or untouched

CgDownloads.located(LOCATIONS, "engine/jar").named("Engine").reporting(progress).into(target);
```

### What goes away

| The struggle | Usually | Here |
|---|---|---|
| **Finding the game folder** | a loader call per era | `CgGameDirectory.game()`, one call on every version |
| **A half-written file** | `Files.write` torn by a crash | writes go to a temp file and rename |
| **Saving a value** | a hand-written serializer per file | a codec, and the extension picks TOML, JSON, YAML or binary |
| **Telling config from cache** | one folder, so clearing cache loses settings | two roots; the cache one may be deleted at any moment |
| **Two apps on one folder** | each overwrites the other's `settings.json` | `scoped("editor")`, a folder of its own |
| **A truncated download** | trusted because the file exists | verified against a digest, installed only if it matches |
| **A flaky connection** | start over | the `.part` is kept; each retry resumes |
| **A dead link** | a new release | the locations file is re-read from master; its addresses repair every released jar |
| **A progress bar** | hand-counted bytes | `reporting(progress)`; the total is announced before the connect |

## Contents

1. [The words you need](#1-the-words-you-need)
2. [Quick start](#2-quick-start)
3. [The layout](#3-the-layout)
4. [Config storage](#4-config-storage)
5. [Cache files](#5-cache-files)
6. [Downloading](#6-downloading)
7. [The locations file](#7-the-locations-file)
8. [Setting up a product](#8-setting-up-a-product)
9. [Testing](#9-testing)
10. [Questions people ask](#10-questions-people-ask)

## 1. The words you need

- **Product**: your mod, the name every folder is made under. CrystalGraphics is one (`crystalgraphics`).
- **Layout** (`CgStorageLayout`): where a product's folders go under a base directory. It holds no state and names no host.
- **Config**: durable files, as named text. Preferences, an arrangement, one session per project.
- **Cache**: derived files that can be regenerated. Never the only copy of anything.
- **Digest**: what a download must hash to, tagged by algorithm: `sha1:…`, `md5:…`, `gitblob:…`, or a bare MD5.
- **Locations**: a JSON file mapping an artifact id (`"engine/jar"`) to where it can be fetched and its digest.

## 2. Quick start

```java
public final class MyStorage {
    public static final CgStorageLayout LAYOUT = CgStorageLayout.of("mymod");

    public static final CgDownloadLocations LOCATIONS = CgDownloadLocations.of(new CgDownloadLocations.Source(
            "mymod", MyMod.class, "/assets/mymod/download/locations.json",
            "mymod.download.remote", "mymod.download.locations", null));
}

// Settings that survive
CgConfigStorage config = new CgLocalConfigStorage(MyStorage.LAYOUT.configIn(CgGameDirectory.game()));
config.write("settings.toml", Settings.CODEC, settings);
Settings back = config.read("settings.toml", Settings.CODEC);   // null when never written

// A file fetched once, checked, kept
Path jar = MyStorage.LAYOUT.cacheIn(CgGameDirectory.game()).resolve("engine.jar");
CgDownloads.located(MyStorage.LOCATIONS, "engine/jar").named("Engine").reporting(progress).into(jar);
```

## 3. The layout

`CgStorageLayout.of("mymod")` answers three folders under whatever base you give it:

```java
CgStorageLayout layout = CgStorageLayout.of("mymod");
Path game = CgGameDirectory.game();

layout.rootIn(game);      // <game>/mymod
layout.configIn(game);    // <game>/mymod/config             durable
layout.cacheIn(game);     // <game>/mymod/cache              deletable
```

- Two constants only, `CgStorageLayout.CONFIG` (`config`) and `CACHE` (`cache`). Folders of your own (projects,
  backups) are your product's: add them beside, under `rootIn`, in a class of yours. A product that names its durable
  tree differently resolves it under `rootIn` itself, as CrystalGUI does with `workspace-config`.
- Hold the layout in a `static final`; it is cheap and stateless, and one place then says where everything is.
- On a dedicated server the same calls work; a player's preferences just do not belong there.

CrystalGraphics' own cache is `CgCacheDirectory`, on its own layout; `-Dcrystalgraphics.cache.dir` moves it.

## 4. Config storage

`CgConfigStorage` is named files: a value through a codec, or raw text. It is [`SERIALIZATION.md`](SERIALIZATION.md)
§6 underneath: the name's extension picks the format (`.toml`, `.json`, `.jsonc`, `.yml`/`.yaml`, anything else
binary), and a write is `CgFiles`' own atomic write.

```java
CgConfigStorage config = new CgLocalConfigStorage(layout.configIn(CgGameDirectory.game()));

config.write("shelf.toml", Shelf.CODEC, shelf);         // a codec; a person's comments in the file survive
Shelf s = config.read("shelf.toml", Shelf.CODEC);       // null when never written
config.read("shelf.toml", Shelf.CODEC, live);           // decoded into a live object

config.write("notes.txt", "text");                      // raw text, no codec
config.read("notes.txt");                               // the text, or null
config.list();                                          // every name stored
config.delete("notes.txt");
config.isWritable();                                    // false when the directory could not be created
```

**Scoped corners.** Two applications sharing a store each take a corner, so neither replaces the other's file:

```java
CgConfigStorage editor = config.scoped("editor");            // <config>/editor/settings.toml
CgConfigStorage mine   = config.scoped("mymod:editor");      // ':' and '/' become '.', legal on Windows
```

**Where it is stored.**

| Store | On | For |
|---|---|---|
| `CgLocalConfigStorage` | a directory | the real thing; a write is `CgFiles.writeAtomic`: a temp file, then a rename |
| `CgScopedConfigStorage` | a key prefix of another | what `scoped(...)` returns |
| `CgInMemoryConfigStorage` | a map | tests |

The primitive is bytes (`readBytes`, `writeBytes`); text and values are layered on it, which is why binary works.

**Store, or `CgFiles` on a path?** The same formats either way. Use a store for a product's own files: it is scoped,
sits on the layout, and tests with the in-memory one. Use `CgFiles.TOML.write(path, ...)` when you already hold a
`Path` (an export, a file the player picked) or a `CgSettings` file in Minecraft's `config/`.

- Absence is `null`. An unreadable or unwritable file is logged and the store carries on.
- **A file that is not its format, or holds what the codec cannot read, throws `CgCodecException`** naming the file.
  Catch it where you can fall back; do not write defaults over it, as it is somebody's hand edit.
- `write` on a store that is not writable throws `IllegalStateException`; check `isWritable()` first when it matters.
- `directory()` is for derived output that must be a real folder; settings go through `read`/`write`.

## 5. Cache files

`CgCacheFiles` installs a file only when its bytes are right, so a half-finished one is never mistaken for a good one.

```java
Path target = cache.resolve("engine.jar");

if (!CgCacheFiles.isValid(target, "sha1:20ba…")) {
    boolean ok = CgCacheFiles.install(target, stream, "sha1:20ba…");   // written to .part, checked, renamed
}

CgCacheFiles.sha1Of(file);          // hex
CgCacheFiles.digestOf(file);        // MD5, for a bare digest
CgCacheFiles.gitBlobSha1(file);     // what GitHub's contents API calls the sha
```

- `install` returns false, and leaves nothing at `target`, when the bytes do not match the digest.
- A `null` digest means any non-empty file will do, for something nobody can pin.
- `partOf(target)` and `partialSize(target)` expose the kept `.part`, which is how a download resumes.

**Reading an archive.** `CgTarArchive` walks a `.tar.gz` forward without unpacking it to disk:

```java
try (CgTarArchive tar = CgTarArchive.gzip(stream)) {
    for (CgTarArchive.Entry e; (e = tar.next()) != null; ) {
        if (e.isFile() && e.name().endsWith(".json")) use(e.name(), tar.read());
    }
}
```

It is forward-only: `read()` answers the current entry, and calling `next()` discards what is left of it.

## 6. Downloading

`CgDownloads` describes a transfer; nothing touches the network until you `open` or `into` it.

```java
// By id, from a locations file: every address it lists, checked against its pin
CgDownloads.located(LOCATIONS, "engine/jar").named("Engine").reporting(progress).into(target);

// A stream, to read through something else
try (CgDownload d = CgDownloads.located(LOCATIONS, "jdk-sources/17").reporting(progress).open()) {
    read(d.stream());
}

// An address known only at run time
CgDownloads.from(url).verifying("sha1:" + sha1).into(target);

// Many files under one bar
CgDownloads.batch(artifacts).named("Downloading engine").reporting(progress).into(dir);
```

A `Request` is immutable: every wither returns a new one, so a half-described transfer can be finished two ways.

| Wither | Does |
|---|---|
| `named(what)` | what the bar calls it |
| `reporting(progress)` | a `CgProgress`; the total is announced before the connect, so a hung host shows a bar |
| `verifying(digest)` | the digest the file must match |
| `cancelledWhen(supplier)` | polled as bytes pass; cooperative |

`into(target)` returns whether the file is now valid there. False means every answering host served other bytes than
the pin, and nothing was installed: report it, do not retry.

**What it does for you.**

- Each attempt resumes from the kept `.part` when the server agrees; three attempts, a second apart.
- An unknown host is not retried (offline answers the same every time); a timeout, reset, truncated body or 5xx is.
- A located transfer tries each address in order and re-reads master's locations once if all have failed.
- Built on `URLConnection`, because `java.net.http` does not exist on the Java 8 of older Minecraft.
- A batch sums the sizes first for one total, and is all-or-nothing in what it reports; what installed stays.

`CgDownloads.describe(failure)` turns an exception into words a user can act on.

## 7. The locations file

A URL compiled into a class cannot be repaired when its host moves. So an artifact has an **id**, and a
`download/locations.json` says where it is:

```json
{
  "format": 1,
  "repositories": { "fabric": ["https://maven.fabricmc.net/{path}"] },
  "files": {
    "mojang/version-manifest": { "urls": ["https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"] },
    "engine/jar": { "maven": "net.fabricmc:intermediary:1.20.1:v2", "from": ["fabric"], "digest": "sha1:20ba…" }
  }
}
```

Three copies are merged per id:

| Copy | Trusted for |
|---|---|
| **The jar's** | the ids it knows and their digests. The only copy that pins anything |
| **Master's**, fetched when the one on disk is a day old | extra addresses for ids the jar lists. Its digests and unknown ids are ignored |
| **An override file**, tried first | a pack's mirror, an offline machine |

So master can *move* an artifact, never change what your jar accepts: a compromised master cannot swap a file.

```java
CgDownloadLocations.Location where = LOCATIONS.find("engine/jar");   // null when the jar does not list it
where.urls();       // the override's, then master's, then the jar's own
where.digest();     // the jar's pin, or null
```

- A copy that is not JSON, or declares another `format`, is ignored whole.
- `useCacheRoot(path)` lets master's copy survive a restart; without it, it is fetched every session.
- Setting your `remoteProperty` to `false` never reads master. Your `overrideProperty` names the override file.
- CrystalGraphics' own is `CgDownloads.LOCATIONS`, from `assets/crystalgraphics/download/locations.json`.

## 8. Setting up a product

1. Put `assets/<product>/download/locations.json` in your jar (`{"format": 1, "repositories": {}, "files": {}}` to begin).
2. Make one class holding the `LAYOUT` and the `LOCATIONS` (section 2).
3. At startup, hand the locations a place to keep master's copy:

```java
MyStorage.LOCATIONS.useCacheRoot(MyStorage.LAYOUT.cacheIn(CgGameDirectory.game()));
```

4. Publish the same file on your repository's master, so a dead link is one edit away from repaired.

CrystalGUI is a product on exactly this: its layout adds projects, apps, extensions, backups and history beside
`CONFIG` and `CACHE`, and its locations file carries every address it downloads from.

## 9. Testing

No game, no GL, no network.

```java
CgInMemoryConfigStorage config = new CgInMemoryConfigStorage();
config.write("shelf.toml", Shelf.CODEC, shelf);
assertEquals(shelf, config.read("shelf.toml", Shelf.CODEC));

config.setWritable(false);                       // a store that refuses writes
assertThrows(IllegalStateException.class, () -> config.write("a", "b"));
```

For a download, point a request at a file URL or a local server and assert on `into(...)`. All of this runs on a
dedicated server's classpath (`core/src/headlessTest`).

## 10. Questions people ask

**Config or cache?** If losing it loses something the user did, config. If it can be rebuilt, cache.

**Why does a typed read throw on a bad file but return null for a missing one?** Missing is a normal first run. A
file that will not parse is somebody's edit half done; returning defaults would let the next save overwrite it.

**Why does `into` return a boolean instead of throwing on a bad digest?** A wrong digest is an answer, not a fault:
the host served something else. The caller decides whether to tell the user; a retry would fetch the same bytes.

**My download stalls at 0%.** The bar is announced before the connect, so 0% is a host that is not answering. The
timeout is 15 seconds, three attempts.

**Can I pin a digest in master's file?** No; it is ignored. Pins live in the jar, which is what makes a moved link safe.

**Where do I put my own folders?** Beside `CONFIG` and `CACHE`, under `layout.rootIn(base)`, named in your own class.
