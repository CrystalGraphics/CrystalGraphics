# storage — a product's own files, caches and downloads

The plumbing every consumer shares; each product (CrystalGraphics, a mod on it) has its own tree and its own
locations file. Layout of the tree is `platform`'s `CgStorageLayout`.

```java
CgConfigStorage config = new CgLocalConfigStorage(CgStorageLayout.of("mymod").configIn(CgGameDirectory.game()));
config.scoped("editor").write("settings.toml", Settings.CODEC, settings);

CgDownloads.located(MY_LOCATIONS, "jdk-sources/17").named("JDK sources").reporting(progress).into(target);
```

| Package | Owns |
|---|---|
| `config/` | `CgConfigStorage` (named bytes, text and codec values by extension through `CgFiles`; `scoped` corners) over a directory (`CgLocalConfigStorage`), a key prefix (`CgScopedConfigStorage`) or a map (`CgInMemoryConfigStorage`). Atomic writes are `CgFiles.writeAtomic`, shared with every format |
| `cache/` | `CgCacheFiles` (verified, atomic installs; digests `sha1:`, `md5:`, `gitblob:`), `CgTarArchive` (forward-only `.tar.gz`) |
| `download/` | `CgDownloads` (a described transfer: named, reported, cancellable, resumed, retried, verified), `CgDownload`, `CgDownloadLocations` (a `download/locations.json` per product: the jar's pins, master's addresses, an override) |

- CrystalGraphics' own locations file is `assets/crystalgraphics/download/locations.json`, `CgDownloads.LOCATIONS`.
- A consumer registers its own with `CgDownloadLocations.of(Source)`; nothing here names a product.
- Master's copy can move an artifact, never change what a jar accepts: digests come from the jar's copy only.

Also loaded with this folder:

@../../../../../../../docs/STORAGE.md
