# Third-party notices — `crystalgraphics.jar`

This file ships **inside** `crystalgraphics-<version>.jar` as `META-INF/NOTICE.md`, because MIT, BSD,
Apache 2.0 and the FreeType License all require their notices to travel with the distribution rather
than with the source repository. `checkSingleJar` asserts it is present. The repository-level index,
with the reasoning behind each entry, is [`THIRD-PARTY.md`](../THIRD-PARTY.md).

CrystalGraphics itself — `com/crystalgraphics/**` outside the rows below, and every shader and asset
under `assets/crystalgraphics/` — is licensed LGPL-3.0-or-later. The licence texts are beside this file: `META-INF/COPYING.LESSER` (LGPL-3.0) and `META-INF/COPYING` (the GPL-3.0 it builds on).

**`META-INF/LICENSE` and `META-INF/NOTICE` in this jar are Jackson's** (Apache 2.0), carried as that
licence requires; they are not CrystalGraphics' licence.

JOML is **not** in this jar. It ships in `crystalgraphics-joml-<version>.jar`, which carries JOML's own
`META-INF/LICENSE`.

| What | Where in this jar | Licence | Form |
|---|---|---|---|
| **Jackson** (via jgltf) | `com/crystalgraphics/shadow/com/fasterxml/jackson/` | Apache 2.0 — © FasterXML | Relocated. Licence and notice: `META-INF/LICENSE`, `META-INF/NOTICE` |
| **javagl Obj** 0.4.0 | `com/crystalgraphics/shadow/de/javagl/obj/` | MIT — © 2008–2015 Marco Hutter | Relocated. Licence below |
| **javagl JglTF** 2.0.4 (`jgltf-model`, `jgltf-impl-v1`, `jgltf-impl-v2`) | `com/crystalgraphics/shadow/de/javagl/jgltf/` | MIT — © 2016 Marco Hutter | Relocated. Licence below |
| **jvmDowngrader** runtime stubs | `com/crystalgraphics/shadow/xyz/wagyourtail/` | MIT — © wagyourtail | Emitted by the downgrade that makes one jar load on Java 8 |
| **FreeType** 2.13.2 | `natives/*/` (compiled in) | FreeType License (FTL) | Credit below |
| **HarfBuzz** 8.3.0 | `natives/*/` (compiled in) | "Old MIT" | Licence below |
| **msdfgen** 1.13 | `natives/*/` (compiled in) | MIT — © 2014–2025 Viktor Chlumsky | Licence below |
| **Chromium (Blink)** font fallback | `com/crystalgraphics/text/font/ScriptFallbacks` | BSD-3-Clause — © 2006–2012 Google Inc. | Ported source, modified. Licence below |
| **Skia** mask blur | `com/crystalgraphics/text/shadow/{CgMaskBlurFilter,CgGaussFilter}` | BSD-3-Clause — © 2017 Google LLC | Ported source, modified. Licence below |
| **Skia Graphite** rect blur | `assets/crystalgraphics/shaders/lib/rect_blur.glsl` | BSD-3-Clause — © 2023–2024 Google LLC | Ported source, modified. Licence below |

## FreeType

Portions of this software are copyright © 2023 The FreeType Project (www.freetype.org). All rights
reserved. Used under the FreeType License, <https://freetype.org/license.html>.

## HarfBuzz

```
HarfBuzz is licensed under the so-called "Old MIT" license.  Details follow.
For parts of HarfBuzz that are licensed under different licenses see individual
files names COPYING in subdirectories where applicable.

Copyright © 2010-2022  Google, Inc.
Copyright © 2015-2020  Ebrahim Byagowi
Copyright © 2019,2020  Facebook, Inc.
Copyright © 2012,2015  Mozilla Foundation
Copyright © 2011  Codethink Limited
Copyright © 2008,2010  Nokia Corporation and/or its subsidiary(-ies)
Copyright © 2009  Keith Stribley
Copyright © 2011  Martin Hosken and SIL International
Copyright © 2007  Chris Wilson
Copyright © 2005,2006,2020,2021,2022,2023  Behdad Esfahbod
Copyright © 2004,2007,2008,2009,2010,2013,2021,2022,2023  Red Hat, Inc.
Copyright © 1998-2005  David Turner and Werner Lemberg
Copyright © 2016  Igalia S.L.
Copyright © 2022  Matthias Clasen
Copyright © 2018,2021  Khaled Hosny
Copyright © 2018,2019,2020  Adobe, Inc
Copyright © 2013-2015  Alexei Podtelezhnikov

For full copyright notices consult the individual files in the package.


Permission is hereby granted, without written agreement and without
license or royalty fees, to use, copy, modify, and distribute this
software and its documentation for any purpose, provided that the
above copyright notice and the following two paragraphs appear in
all copies of this software.

IN NO EVENT SHALL THE COPYRIGHT HOLDER BE LIABLE TO ANY PARTY FOR
DIRECT, INDIRECT, SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES
ARISING OUT OF THE USE OF THIS SOFTWARE AND ITS DOCUMENTATION, EVEN
IF THE COPYRIGHT HOLDER HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH
DAMAGE.

THE COPYRIGHT HOLDER SPECIFICALLY DISCLAIMS ANY WARRANTIES, INCLUDING,
BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
FITNESS FOR A PARTICULAR PURPOSE.  THE SOFTWARE PROVIDED HEREUNDER IS
ON AN "AS IS" BASIS, AND THE COPYRIGHT HOLDER HAS NO OBLIGATION TO
PROVIDE MAINTENANCE, SUPPORT, UPDATES, ENHANCEMENTS, OR MODIFICATIONS.
```

## msdfgen

```
MIT License

Copyright (c) 2014 - 2025 Viktor Chlumsky

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## javagl Obj

```
The MIT License

Copyright (c) 2008-2015 Marco Hutter - http://www.javagl.de

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## javagl JglTF

```
The MIT License

Copyright (c) 2016 Marco Hutter

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Chromium and Skia (BSD-3-Clause)

The same terms cover each ported source above, with its own copyright line:
Copyright (c) 2006-2012 Google Inc. (Chromium font fallback); Copyright (c) 2017 Google LLC (Skia mask
blur); Copyright (c) 2023-2024 Google LLC (Skia Graphite rect blur).

```
Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

  * Redistributions of source code must retain the above copyright
    notice, this list of conditions and the following disclaimer.

  * Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in
    the documentation and/or other materials provided with the
    distribution.

  * Neither the name of the copyright holder nor the names of its
    contributors may be used to endorse or promote products derived
    from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```
