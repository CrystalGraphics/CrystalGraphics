# Native libraries

FreeType, HarfBuzz and msdfgen are compiled into **one** JNI library per platform,
`freetype_msdfgen_harfbuzz_jni`, by the `freetype-msdfgen-harfbuzz-bindings` module — cross-compiled for
every target with Zig, from one machine. The built libraries are committed under that module's
`src/main/resources/natives/`, so nobody needs a C or C++ toolchain to build or run the mod, and the
shipped jar carries every platform's library.

**Rebuilding them — after changing the C++ or a dependency — is that module's
[`AGENTS.md`](../freetype-msdfgen-harfbuzz-bindings/AGENTS.md)**: the Gradle properties, the tasks
(`downloadZig`, `downloadNativeDeps`, `buildNatives`), the target table, and troubleshooting.

```bash
cd freetype-msdfgen-harfbuzz-bindings          # a build of its own, with its own wrapper
./gradlew buildNatives                          # every target
./gradlew buildNatives -PnativeBuild.targets=x86_64-windows
```

The Java side of each API: [`FREETYPE_API.md`](../freetype-msdfgen-harfbuzz-bindings/docs/FREETYPE_API.md),
[`HARFBUZZ_API.md`](../freetype-msdfgen-harfbuzz-bindings/docs/HARFBUZZ_API.md),
[`MSDFGEN_INTEGRATION_GUIDE.md`](../freetype-msdfgen-harfbuzz-bindings/docs/MSDFGEN_INTEGRATION_GUIDE.md).
