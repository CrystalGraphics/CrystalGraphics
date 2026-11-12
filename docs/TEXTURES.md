# Textures

By the end of this page you will load an image and draw with it, tell the engine how a file loads, make empty
textures of your own and fill them, draw into textures, and read them back.

Everything here goes through one class, `CgTextures`. Every call works on any thread unless the page says otherwise.

```java
import com.crystalgraphics.api.texture.CgTextures;
import com.crystalgraphics.gpu.resource.CgTexture2D;

static final CgTexture2D ICON = CgTextures.of("mymod:textures/gui/icon.png");
```

That is a whole texture. Nothing is read from disk yet: the file loads the first time something uses it. So a constant
like this costs a dedicated server nothing.

---

## 1. Your first texture

Load it, then hand it to a material.

```java
static final CgTexture2D NOISE = CgTextures.of("mymod:textures/fx/noise.png");

CgMaterialDefinition glow = CgMaterialDefinition.of("mymod:glow", "mymod:shaders/glow.shader")
        .texture("_Noise", NOISE)          // the shader's sampler2D _Noise
        .build();
```

In the shader, `_Noise` is an ordinary sampler:

```glsl
vec4 n = texture(_Noise, uv);
```

A material file names it by path instead:

```yaml
textures:
  _Noise:
    texture: mymod:textures/fx/noise.png
```

That is all most textures need. The rest of this page is the variations.

---

## 2. What a file loads as

You never pass a format, a size or a mip count for a file. The file decides.

| File | Format | Levels |
|---|---|---|
| `.png` and other 8-bit images, **no settings file** | `RGBA8_UNORM` (linear) | one |
| `.png` and other 8-bit images, **with a settings file** | `RGBA8_SRGB`, or `RGBA8_UNORM` with `colour: linear` | a full chain, or one with `mips: false` |
| `.hdr`, `.exr` | `RGBA16_FLOAT`, always linear | as for an 8-bit image |
| `.cgtex` (cooked, § 8) | BC7, sRGB or linear as it was cooked | the file's own |

Its size is the image's. Asking for it loads the file on the asking thread:

```java
float aspect = ICON.getWidth() / (float) ICON.getHeight();   // decodes icon.png here
```

---

## 3. The settings file

A settings file sits beside the image, with the same name and `.yaml` in place of the extension. It says how the image
loads. These are Unreal's texture settings, in the same words.

```
assets/mymod/textures/fx/
  noise.png
  noise.yaml
```

```yaml
# noise.yaml
colour: linear                               # data, not colour: masks, noise, normals
mips: false                                  # one level
sampler: { filter: nearest, wrap: clamp }    # how it is sampled where a binding names nothing
```

Every key is optional.

| Key | Values | Left out |
|---|---|---|
| `colour` | `srgb`, `linear` | `srgb` |
| `mips` | `true`, `false` | `true`: a full chain, down to 1x1 |
| `sampler` | a block: `filter`, `wrap` | trilinear and repeated with mips, bilinear and repeated without |
| `cube` | `true` | `false`. `true`: six faces stacked top to bottom, `w x 6w` (§ 6) |
| `compression` | `none` | read by the cooker only (§ 8): `none` keeps the PNG uncooked |

**The `sampler` block:**

| Key | Values | Left out |
|---|---|---|
| `filter` | `nearest`, `bilinear`, `trilinear` | `trilinear` |
| `wrap` | `repeat`, `clamp`, `mirror`, `border`; or a list per axis: `[repeat, clamp]` | `repeat` |

```yaml
sampler: { filter: bilinear, wrap: [repeat, clamp] }   # repeats across, clamps down: a scrolling strip
```

**No file, or a file with nothing in it, are different things.** No file is plain: linear, one level, bilinear and
clamped, as a GUI image wants. An empty file is a texture asset: sRGB, mips, trilinear and repeated, as a material's
image wants. One key is enough to switch an image to asset defaults.

```yaml
# rock.yaml: a texture asset with Unreal's defaults
{}
```

### Settings from code

Code that wants an image its own way passes the settings instead of reading the file's. It is shared by the path and
those settings.

```java
import com.crystalgraphics.gpu.resource.CgTextureSettings;

CgTexture2D logo = CgTexture2D.create("mymod:textures/gui/logo.png",
        CgTextureSettings.PLAIN.withMips(true).withSampler(CgSampler.TRILINEAR_CLAMP));
```

`CgTextureSettings.PLAIN` is a file with no settings. `CgTextureSettings.ASSET` is an empty settings file.

---

## 4. How it is sampled

A sampler is filter plus wrap: what you set with `glTexParameteri` in GL. A texture never takes one where it is made.
It is sampled by, first to last:

1. **The binding's sampler**, where the binding names one.
2. **The texture's own**: its settings file's for a file (§ 3), its default or `.sampler(...)` for one of yours (§ 5).

```java
// The binding wins, for this material only. Everyone else still samples NOISE as noise.yaml says.
CgMaterialDefinition pixelated = CgMaterialDefinition.of("mymod:pixelated", "mymod:shaders/glow.shader")
        .texture("_Noise", NOISE, CgSampler.Filter.NEAREST, CgSampler.Address.CLAMP)
        .build();

// The same on a live material.
material.applyProperties(b -> b.sampler("_Noise", 0, NOISE, CgSampler.NEAREST_CLAMP));
```

```yaml
# The same in a material file. Anisotropy is a binding's only.
textures:
  _Noise:
    texture: mymod:textures/fx/noise.png
    sampler: { filter: trilinear, wrap: repeat, anisotropy: 8 }
```

**Samplers** are values. Use a preset, or build one:

| Preset | Filter | Wrap |
|---|---|---|
| `NEAREST_CLAMP`, `NEAREST_WRAP`, `NEAREST_MIRROR`, `NEAREST_BORDER` | nearest | as named |
| `BILINEAR_CLAMP`, `BILINEAR_WRAP`, `BILINEAR_MIRROR`, `BILINEAR_BORDER` | bilinear | as named |
| `TRILINEAR_CLAMP`, `TRILINEAR_WRAP` | trilinear | as named |
| `SHADOW` | bilinear, compared `LESS_EQUAL` | clamp |

```java
CgSampler sharp = CgSampler.builder()
        .filter(CgSampler.Filter.TRILINEAR)
        .address(CgSampler.Address.WRAP, CgSampler.Address.CLAMP)   // u, v
        .anisotropy(8)
        .build();
CgSampler shifted = CgSampler.BILINEAR_CLAMP.derive().address(CgSampler.Address.MIRROR).build();
```

---

## 5. Textures of your own

Pass a name and a description instead of a path. You get an empty texture, and it is yours: you fill it and you
release it.

```java
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.platform.rhi.format.CgFormat;

CgTexture2D lut = CgTextures.of("mymod.lut", CgTextureDesc.of(256, 256, CgFormat.RGBA8_UNORM));
lut.write(0, 0, 0, 256, 256, CgFormat.RGBA8_UNORM, pixels);   // level, x, y, width, height, format, texels
// ...
lut.release();
```

- **The first argument is a name**, never a path. It is what the logs and the GPU debugger call the texture.
- **`write` works on any thread.** It copies your buffer and lands before the next frame. Rows run bottom first,
  tightly packed. Texels in another format than the texture's are converted.
- **`release` works on any thread.** The texture goes once the frames still reading it are done.

**Its default sampler** follows its format and levels, clamped: nearest for an integer format, trilinear for several
levels, bilinear otherwise. Pick another with `.sampler(...)`, which returns the texture so it chains:

```java
CgTexture2D tiles = CgTextures.of("mymod.tiles", CgTextureDesc.of(64, 64, CgFormat.RGBA8_UNORM))
        .sampler(CgSampler.NEAREST_CLAMP);
```

### The four kinds

Each call takes the description of its own kind.

```java
CgTexture2D       flat   = CgTextures.of("mymod.flat", CgTextureDesc.of(512, 512, CgFormat.RGBA16_FLOAT));
CgTexture2DArray  glyphs = CgTextures.frames("mymod.glyphs", CgTextureDesc.array(1024, 1024, 4, CgFormat.R8_UNORM));
CgTexture3D       field  = CgTextures.volume("mymod.field", CgTextureDesc.volume(32, 32, 32, CgFormat.RGBA16_FLOAT));
CgTextureCubemap  sky    = CgTextures.cube("mymod.sky", CgTextureDesc.cube(512, CgFormat.RGBA16_FLOAT));
```

Only a 2D texture has `write` with a `CgFormat`. The other three are filled with a GL format and type, as
`glTexSubImage` takes them:

```java
glyphs.uploadLayerRegion(2, x, y, w, h, GL_RED, GL_UNSIGNED_BYTE, glyph);   // layer 2
glyphs.growLayers(8);                                                       // deeper, layers kept: an atlas that filled
field.uploadRegion(0, 0, 0, 0, 32, 32, 32, texels, GL_RGBA, GL_HALF_FLOAT);  // level, x, y, z, w, h, d
sky.uploadFace(CgTextureCubemap.POSITIVE_X, 0, 0, 0, 512, 512, face, GL_RGBA, GL_HALF_FLOAT);
```

### Levels

```java
CgTextureDesc chain = CgTextureDesc.of(w, h, CgFormat.RGBA16_FLOAT).withMips();    // every level, down to 1x1
CgTextureDesc five  = CgTextureDesc.of(w, h, CgFormat.RGBA16_FLOAT).withMips(5);   // five levels
```

You fill every level yourself: `write(level, ...)` for each. A volume can fill its own with `generateMipmaps()`:

```java
CgTexture3D noise = CgTextures.volume("mymod.noise", CgTextureDesc.volume(64, 64, 64, CgFormat.R8_UNORM).withMips())
        .sampler(CgSampler.TRILINEAR_WRAP);
noise.uploadRegion(0, 0, 0, 0, 64, 64, 64, texels, GL_RED, GL_UNSIGNED_BYTE);
noise.generateMipmaps();
```

---

## 6. The description

A `CgTextureDesc` is one texture's kind, size, format, levels, layers and samples. It is a record: two equal
descriptions describe the same texture.

| Make it with | Kind | Notes |
|---|---|---|
| `CgTextureDesc.of(w, h, format)` | 2D | one level |
| `new CgTextureDesc(w, h, format, levels)` | 2D | `levels` levels |
| `CgTextureDesc.array(w, h, layers, format)` | 2D array | one level, always |
| `CgTextureDesc.volume(w, h, depth, format)` | 3D | may take levels |
| `CgTextureDesc.cube(size, format)` | cube | six square faces, one level |

| Change it with | Gives |
|---|---|
| `.withMips()` | every level down to 1x1 |
| `.withMips(n)` | `n` levels |
| `.withSamples(n)` | multisampled: for a frame graph's textures only (§ 9) |

| Ask it | |
|---|---|
| `is2D()`, `isArray()`, `isVolume()`, `isCube()` | its kind |
| `isDepth()` | a depth, stencil or depth-stencil format |
| `isMultisampled()` | more than one sample |
| `width()`, `height()`, `depth()`, `format()`, `levels()`, `layers()`, `samples()`, `kind()` | the fields |

It refuses a description that cannot exist, where you make it:

- more levels than the size holds;
- a compressed format;
- an array with levels;
- a cube whose faces are not square;
- a depth format that is not one 2D level;
- a multisampled texture that is not one 2D level.

---

## 7. More than one image

### Frames: a 2D array

A flipbook: one layer per image, sampled as a `sampler2DArray`.

```java
// flame_0.png, flame_1.png, flame_2.png -> layers 0, 1, 2
static final CgTexture2DArray FLAMES = CgTextures.frames("mymod:textures/vfx/flame_%d.png", 3);
```

```glsl
vec4 c = texture(_Flames, vec3(uv, frame));
```

- The pattern is `String.format` with one int: `%02d` names `flame_00.png`.
- Frames that are not numbered are listed: `CgTextures.frames(pathA, pathB, pathC)`.
- Images of different sizes are each centred on a layer the size of the largest.
- The first frame's settings file says how all of them load.

### Volume: a 3D texture

Slices, one per image, sampled as a `sampler3D`. Unlike an array, it blends between slices.

```java
// lut_0.png .. lut_15.png -> slices 0 to 15, bottom to top
static final CgTexture3D LUT = CgTextures.volume("mymod:textures/lut/lut_%d.png", 16);
```

```glsl
vec3 graded = texture(_Lut, colour.rgb).rgb;
```

Every slice must be the same size.

### Cube

Six faces in one image, stacked top to bottom (`w x 6w`), in the order +X, -X, +Y, -Y, +Z, -Z:

```java
static final CgTextureCubemap SKY = CgTextures.cube("mymod:textures/sky.png");
```

Or six files, in the same order:

```java
static final CgTextureCubemap SKY = CgTextures.cube(
        "mymod:textures/sky/px.png", "mymod:textures/sky/nx.png",
        "mymod:textures/sky/py.png", "mymod:textures/sky/ny.png",
        "mymod:textures/sky/pz.png", "mymod:textures/sky/nz.png");
```

```glsl
vec3 c = texture(_Sky, direction).rgb;
```

A material file names a stacked cube by its path; `cube: true` in the image's settings file makes it one.

---

## 8. Float and cooked images

**Float.** A `.hdr` or `.exr` loads as `RGBA16_FLOAT`, linear, with values past 1 kept. An `.exr`'s negative values are
kept too: a vertex animation's offsets.

```java
static final CgTexture2D RAMP = CgTextures.of("mymod:textures/fx/fire_ramp.hdr");
```

**Cooked.** A `.cgtex` is a PNG compressed to BC7 with all its levels, ahead of time: smaller on disk and on the GPU.
Load it like any image:

```java
static final CgTexture2D SMOKE = CgTextures.of("mymod:cooked/fx/smoke.cgtex");
```

Make them with the `cookTextures` task (Windows; it runs Microsoft's `texconv`):

```bash
./gradlew cookTextures -Pcook.from=src/main/resources/assets/mymod/textures -Pcook.to=src/main/resources/assets/mymod/cooked
```

- Each PNG's settings file decides its cook: `colour: linear` cooks linear, `mips: false` one level,
  `compression: none` leaves it uncooked.
- The settings file is copied beside the `.cgtex`, so the cooked texture samples as the PNG did.
- An image whose `.cgtex` is newer than it and its settings file is skipped.
- A device without BC7 gets it decoded to RGBA8 as it loads.

---

## 9. Drawing into textures

A target is your textures as somewhere a pass draws: a colour, a depth, or both, of one size. Make the textures, then
the target, once.

```java
CgTexture2D colour = CgTextures.of("mymod.view", CgTextureDesc.of(w, h, CgFormat.RGBA16_FLOAT));
CgTexture2D depth = CgTextures.of("mymod.view.depth", CgTextureDesc.of(w, h, CgFormat.DEPTH32_FLOAT));
CgExternalTarget view = CgTextures.target("mymod.view", colour, depth);

try (CgExecutor.Into ignored = CgExecutor.into(view)) {
    stage.fire();               // the stage draws into colour and depth
}
material.applyProperties(b -> b.sampler("_View", 0, colour));   // then anything samples it
```

- **Render thread, outside a recording.** Writes queued on the textures land first.
- **Make it again after you remake a texture** (a resize). A target holds the textures' storage as it was.
- Either may be `null`: `CgTextures.target("mymod.mask", colour, null)` has no depth.
- Clearing and copying outside a frame graph are `CgImmediate.clear(view, ...)` and `CgImmediate.copyColor(from, to)`.
- A frame graph imports one with `CgGraphTexture.imported(name, target)`. Textures that live one frame are the
  graph's own (`CgGraphTexture.transientTexture(name, desc)`), not yours. Only those can be multisampled.

---

## 10. Reading back

```java
CgReadback.texture(lut, 0, x, y, 1, 1, data -> {
    int red = data.get(0) & 0xFF;   // texels as the format holds them (here RGBA8), rows from row 0
});
```

- **Render thread, outside a recording.** Inside a frame graph, `rec.readback` orders it after whatever writes the
  texture.
- **The callback runs frames later.** The buffer is valid only during the call: copy what you keep.
- Override `failed(String)` on a `CgReadback.Sink` to hear when it never arrives (the context went away).

---

## 11. Lifetime

| | From a file | Yours (name + description) |
|---|---|---|
| Shared | by every caller of the path | no |
| Loads | at its first use | at once, empty |
| Sampler | its settings file | its default, or `.sampler(...)` |
| `.sampler(...)` | throws | sets it |
| `release()` | never: others hold it | yours to call |
| `reload()` | decodes the files again, into the same texture | nothing |
| `isMissing()` | true when its files would not load | false |

**Load ahead of time.** A file decodes on the first thread that uses it, and that may be the render thread mid-frame.
Touch it earlier on a worker, behind the loading screen:

```java
ICON.getWidth();   // decodes now, on this thread
```

`docs/LOADING.md` shows where that goes.

---

## 12. What goes wrong

**A purple and black checkerboard.** The file would not load: a wrong path, or a file missing from the jar. The log
names it, and `isMissing()` is true. A volume or a cube that will not load is clear instead, 1x1; an array leaves
that frame's layer clear. Images of different channel counts in one array do the same.

**An image looks washed out or too dark.** Its colour space is wrong. Colour is sRGB, data is linear: add
`colour: linear` to a mask's or a normal map's settings file, or remove it from a colour image's.

**The same PNG looks different in a material and in code.** A material file treats an image with no settings file as a
texture asset: sRGB, mips, trilinear and repeated. `CgTextures.of` treats it as plain: linear, one level, bilinear and
clamped. Give any image both use a settings file, and both agree.

**`IllegalStateException` from `.sampler(...)`.** The texture came from a file, and every caller of the path shares it.
Say it in the settings file, or at the binding (§ 4).

**A sampler block with `anisotropy` throws.** Anisotropy belongs to a binding. Move it to the material's `sampler`
block.

**`IllegalArgumentException` from `CgTextures.frames(name, desc)` and its kin.** The description is another kind than
the call's: `frames` takes `CgTextureDesc.array`, `volume` takes `CgTextureDesc.volume`, `cube` takes
`CgTextureDesc.cube`.

**A texture made from a path string, empty.** The first argument of the description overloads is a name. A path there
loads nothing: use the one-argument call for a file.

**A target draws into the old texture after a resize.** Make the target again from the new textures.

**A stacked cube throws at its first use.** The image is not `w x 6w`.

**A cooked texture is sampled bilinear and clamped.** Its settings file is not beside the `.cgtex`. Cook again; the task
copies it.

**The game stutters the first time something appears.** Its textures loaded on the render thread at their first use.
Load them ahead (§ 11).

---

## Where to go next

- [`SHADERS.md`](SHADERS.md): declaring `sampler2D`, `sampler2DArray`, `sampler3D` and `samplerCube` properties in a
  shader.
- [`LOADING.md`](LOADING.md): loading textures behind the loading screen, so play loads none.
- [`ENGINE_API.md` § Targets](ENGINE_API.md#targets): drawing into your textures, in depth.
- [`MINECRAFT_RENDERING_CONVENTIONS.md`](MINECRAFT_RENDERING_CONVENTIONS.md): the samplers Minecraft leaves bound when
  you draw into its frame.
