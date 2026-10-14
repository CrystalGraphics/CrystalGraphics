# serialization — codecs, and where their values are stored

A value's shape is a `CgCodec`; where it goes is a `CgDynamicOps` (the wire's plain tree, JSON, Minecraft's tags) and,
for storage, a `CgFormat`. **Using it: `docs/SERIALIZATION.md`, the guide**, imported below.

| Package | Owns |
|---|---|
| `serialization` | The codec vocabulary: `CgCodec` (`encode`, `decode`, `decode(ops, in, target)` into a live object, `comment(text)`), `CgCodecs` (primitives, `listOf`, `mapOf`, `dispatch`, `xmap`, `record`, `versioned`, the map builder and reader, `object`), `CgRecordCodec` and `CgRecordField` (a record's keys and constructor), `CgObjectCodec` (a mutable class's fields declared once), `CgVersionedCodec` and `CgTreeEdit` (an old save migrated by steps), `CgDynamicOps`, `CgPlainOps` (the plain tree the wire and files use), `CgBinaryFormat` (that tree as bytes), `CgContentHash`, `CgStateMap`, `CgCodecException` |
| `serialization.format` | What a mod calls to store a value: `CgFormats` (`BINARY`, `JSON`, `JSONC`, `TOML`, `YAML`, `of(file)`, and the NBT bridge `toNbt`/`fromNbt`/`nbt()`), `CgFormat` (files), `CgTextFormat` (files and text), `CgJsonOps` (Gson's tree) |
| `serialization.format.file` | Each file format: `BinaryFileFormat` (gzipped `CgBinaryFormat`), `JsonFileFormat` (Gson), `JsoncFileFormat` (`JsoncReader`, `JsoncWriter`), `TomlFileFormat` (TOML 1.0: `TomlReader`, ported from Jackson's, and `TomlWriter`), `YamlFileFormat` (SnakeYAML), with `FileComments` (a person's comments by key path), `CommentedOps` (a codec's, per write) and `TextTreeOps`, over `FileFormat`, which writes beside and moves over, and reads whole before decoding |
| `serialization.format.nbt` | The NBT bridge's slot, `CgHostFormats` (each era's tag primitives: `HostFormatsModern`, `HostFormatsLegacy`, `HostFormats1710`), `HostTagOps`, NBT's rules over them, and `CgTestTags`, tags and SNBT for a unit test |

## Rules

- **Gson is Minecraft's**: compile against 2.2.4, 1.7.10's. No static `JsonParser.parseString` (2.8.6), no API newer.
- **SnakeYAML is ours, relocated** in the shipped jar (`com.crystalgraphics.shadow.org.yaml.snakeyaml`). Only
  `YamlFileFormat` names it; nothing outside `core/` may import `org.yaml`. A YAML file loads through
  `SafeConstructor` only: it never instantiates a class it names.
- **A codec that can update in place overrides `decode(ops, in, target)`** and answers the target. Every other codec
  inherits the default, a new value; never answer a half-new mix.
- **A new format** is a `FileFormat` (bytes) or `TextFileFormat` (text) in `format.file`, a constant on `CgFormats`,
  and a case in `CgFormats.of`.
- **Comments travel with the maps a codec makes**, through `ops.withComments(map, comments)`: a constant map per
  codec, which every format but TOML and YAML ignores. A codec that makes a new map out of another's (as `versioned`
  does) passes `ops.commentsOf(old)` on, or a TOML or YAML file loses them.
- **NBT's rules live in `HostTagOps`, once**; a host answers only the primitives, and decides nothing.

---

Also loaded with this folder:

@../../../../../../../docs/SERIALIZATION.md
