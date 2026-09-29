# `CgGLBackend` — the census

**Generated** by `python platform/tools/gl_census.py`; edit the script, not this file. Every backend
method, the `CgGL` facade that dispatches to it, and how many call sites in each group's main code reach
it -- statically, with every Stonecutter branch counted as live. `platform` is the state manager's
restores, the providers and the trace; `harness` is the GL debug harness, listed apart since it is a
test application.

**132 methods** (144 declarations with overloads): **119 reached** by core, CrystalGUI, the hosts or `platform`; **1 by the harness only**; **12 by nothing** -- `isAvailable`, `getPriority`, `glUniformMatrix4fv`, `glBindBufferRange`, `glTexSubImage2D`, `glGetTexImage`, `isContextCurrent`, `importHostTexture`, `hostSectionBegin`, `hostSectionEnd`, `ownedByCurrentThread`, `glTexImage2DMultisample`.

**What it orders** (D3.4): the tracked backend is built domain by domain in the order below, reached
methods first within each. An unreached method is still built -- `CgGL` is public API outside mods
call (decision 18) -- but last, and a test reaches it before it counts.

## Context and queries

What `CgCapabilities.detect()` and the state providers read before anything draws.

| Method | via `CgGL` | core | CrystalGUI | hosts | platform | harness |
|---|---|---:|---:|---:|---:|---:|
| `initContext` | direct | 1 |  |  |  |  |
| `glGetInteger` ×2 | `glGetInteger` | 4 | 11 | 2 | 38 |  |
| `glGetBoolean` ×2 | `glGetBoolean` |  | 2 |  | 11 | 2 |
| `glGetFloat` ×2 | `glGetFloat` |  |  |  | 5 |  |
| `glGetError` | `glGetError` |  | 6 |  |  |  |
| `isContextCurrent` | `isContextCurrent` |  |  |  |  |  |
| `ownedByCurrentThread` | `ownedByCurrentThread` |  |  |  |  |  |

## State

The pipeline key, and what the scope suite (D3.5) restores.

| Method | via `CgGL` | core | CrystalGUI | hosts | platform | harness |
|---|---|---:|---:|---:|---:|---:|
| `glEnable` | `glEnable` | 5 | 1 |  | 1 | 5 |
| `glDisable` | `glDisable` | 13 | 4 |  | 1 | 5 |
| `glBlendFuncSeparate` | `glBlendFuncSeparate` | 1 |  |  | 1 |  |
| `glDepthMask` | `glDepthMask` | 2 |  |  | 1 | 2 |
| `glCullFace` | `glCullFace` | 1 |  |  | 1 |  |
| `glViewport` | `glViewport` | 2 | 3 | 1 | 1 |  |
| `glScissor` | `glScissor` | 1 | 1 |  | 1 |  |
| `glColorMask` | `glColorMask` | 2 | 2 |  | 1 |  |
| `glStencilFunc` | `glStencilFunc` | 1 |  |  | 1 |  |
| `glStencilOp` | `glStencilOp` | 1 |  |  | 1 |  |
| `glClearDepth` | `glClearDepth` | 1 |  | 1 |  |  |
| `glClearColor` | `glClearColor` | 3 |  |  |  |  |
| `glClearStencil` | `glClearStencil` | 1 |  |  |  |  |
| `glDepthFunc` | `glDepthFunc` | 2 |  |  | 1 |  |
| `glStencilMask` | `glStencilMask` | 2 |  |  | 1 |  |
| `glBlendEquationSeparate` | `glBlendEquationSeparate` | 2 |  |  | 1 |  |
| `glColorMaski` | `glColorMaski` | 1 |  |  | 1 |  |
| `glFrontFace` | `glFrontFace` | 1 |  |  | 1 |  |
| `glPolygonOffset` | `glPolygonOffset` |  |  |  | 1 |  |
| `glBlendFunc` | `glBlendFunc` |  |  |  |  | 1 |

## Buffers and vertex arrays

Every draw reads them; the frame ring and orphan-as-rename live here.

| Method | via `CgGL` | core | CrystalGUI | hosts | platform | harness |
|---|---|---:|---:|---:|---:|---:|
| `glGenBuffers` | `glGenBuffers` | 7 |  |  |  |  |
| `glBindBuffer` | `glBindBuffer` | 24 |  |  | 2 |  |
| `glBufferData` ×3 | `glBufferData` | 10 |  |  |  |  |
| `glBufferSubData` | `glBufferSubData` | 3 |  |  |  |  |
| `glDeleteBuffers` | `glDeleteBuffers` | 9 |  |  |  |  |
| `glBindBufferBase` | `glBindBufferBase` | 4 |  |  |  |  |
| `glTexBuffer` | `glTexBuffer` | 1 |  |  |  |  |
| `glGenVertexArrays` | `glGenVertexArrays` | 2 |  |  |  |  |
| `glBindVertexArray` | `glBindVertexArray` | 7 |  |  | 1 |  |
| `glDeleteVertexArrays` | `glDeleteVertexArrays` | 2 |  |  |  |  |
| `glEnableVertexAttribArray` | `glEnableVertexAttribArray` | 7 |  |  |  |  |
| `glVertexAttribPointer` | `glVertexAttribPointer` | 10 |  |  |  |  |
| `glVertexAttribDivisor` | `glVertexAttribDivisor` | 1 |  |  |  |  |
| `glMapBufferRange` | `glMapBufferRange` | 4 |  |  |  |  |
| `glUnmapBuffer` | `glUnmapBuffer` | 3 |  |  |  |  |
| `glFlushMappedBufferRange` | `glFlushMappedBufferRange` | 1 |  |  |  |  |
| `glBufferStorage` | `glBufferStorage` | 1 |  |  |  |  |
| `glBindBufferRange` | `glBindBufferRange` |  |  |  |  |  |

## Programs

The link-time rewrite (D3.6) produces the tables these answer from.

| Method | via `CgGL` | core | CrystalGUI | hosts | platform | harness |
|---|---|---:|---:|---:|---:|---:|
| `glCreateShader` | `glCreateShader` | 2 |  |  |  |  |
| `glShaderSource` | `glShaderSource` | 2 |  |  |  |  |
| `glCompileShader` | `glCompileShader` | 2 |  |  |  |  |
| `glGetShaderi` | `glGetShaderi` | 2 |  |  |  |  |
| `glGetShaderInfoLog` | `glGetShaderInfoLog` | 2 |  |  |  |  |
| `glDeleteShader` | `glDeleteShader` | 6 |  |  |  |  |
| `glCreateProgram` | `glCreateProgram` | 1 |  |  |  |  |
| `glAttachShader` | `glAttachShader` | 2 |  |  |  |  |
| `glLinkProgram` | `glLinkProgram` | 1 |  |  |  |  |
| `glGetProgrami` | `glGetProgrami` | 3 |  |  |  |  |
| `glGetProgramInfoLog` | `glGetProgramInfoLog` | 1 |  |  |  |  |
| `glUseProgram` | `glUseProgram` | 2 |  |  | 1 |  |
| `glDeleteProgram` | `glDeleteProgram` | 1 |  |  |  |  |
| `glGetUniformLocation` | `glGetUniformLocation` | 2 |  |  |  |  |
| `glUniform1i` | `glUniform1i` | 2 |  |  |  |  |
| `glUniform1f` | `glUniform1f` | 1 |  |  |  |  |
| `glUniform2f` | `glUniform2f` | 1 |  |  |  |  |
| `glUniform3f` | `glUniform3f` | 1 |  |  |  |  |
| `glUniform4f` | `glUniform4f` | 1 |  |  |  |  |
| `glBindAttribLocation` | `glBindAttribLocation` | 1 |  |  |  |  |
| `glGetProgramResourceIndex` | `glGetProgramResourceIndex` | 1 |  |  |  |  |
| `glShaderStorageBlockBinding` | `glShaderStorageBlockBinding` | 1 |  |  |  |  |
| `glGetUniformBlockIndex` | `glGetUniformBlockIndex` | 1 |  |  |  |  |
| `glUniformBlockBinding` | `glUniformBlockBinding` | 1 |  |  |  |  |
| `glDetachShader` | `glDetachShader` | 3 |  |  |  |  |
| `glGetAttachedShaders` | `glGetAttachedShaders` | 1 |  |  |  |  |
| `glGetActiveUniform` | `glGetActiveUniform` | 1 |  |  |  |  |
| `glUniform1` ×2 | `glUniform1` | 2 |  |  |  |  |
| `glUniformMatrix3` | `glUniformMatrix3` | 2 |  |  |  |  |
| `glUniformMatrix4` | `glUniformMatrix4` | 2 |  |  |  |  |
| `glUniformMatrix4fv` | `glUniformMatrix4fv` |  |  |  |  |  |

## Textures and samplers

Uploads are encoder work outside a pass.

| Method | via `CgGL` | core | CrystalGUI | hosts | platform | harness |
|---|---|---:|---:|---:|---:|---:|
| `copyImageSubData` | `glCopyImageSubData` | 1 |  |  |  |  |
| `glGenTextures` | `glGenTextures` | 7 |  |  |  |  |
| `glBindTexture` | `glBindTexture` | 30 |  |  | 1 |  |
| `glDeleteTextures` | `glDeleteTextures` | 4 |  |  |  |  |
| `glTexImage2D` ×2 | `glTexImage2D` | 4 |  |  |  |  |
| `glTexImage3D` ×2 | `glTexImage3D` | 4 |  |  |  |  |
| `glTexSubImage3D` ×3 | `glTexSubImage3D` | 5 |  |  |  |  |
| `glGenerateMipmap` | `glGenerateMipmap` | 1 |  |  |  |  |
| `glActiveTexture` | `glActiveTexture` | 7 | 2 |  | 4 |  |
| `glTexParameteri` | `glTexParameteri` | 9 |  |  |  |  |
| `glPixelStorei` | `glPixelStorei` | 2 |  |  |  |  |
| `glBindSampler` | `glBindSampler` | 1 |  |  |  |  |
| `glTexSubImage2D` ×2 | `glTexSubImage2D` |  |  |  |  |  |
| `importHostTexture` | `importHostTexture` |  |  |  |  |  |
| `glTexImage2DMultisample` | `glTexImage2DMultisample` |  |  |  |  |  |

## Framebuffers and renderbuffers

Attachment sets; a bind picks the next pass's target.

| Method | via `CgGL` | core | CrystalGUI | hosts | platform | harness |
|---|---|---:|---:|---:|---:|---:|
| `bindFramebuffer` | `glBindFramebuffer` | 17 | 2 | 2 | 3 |  |
| `blitFramebuffer` | `glBlitFramebuffer` | 3 |  |  |  |  |
| `framebufferTextureLayer` | `glFramebufferTextureLayer` | 4 |  |  |  |  |
| `genFramebuffers` | `glGenFramebuffers` | 3 |  | 1 |  |  |
| `deleteFramebuffers` | `glDeleteFramebuffers` | 3 |  | 1 |  |  |
| `framebufferTexture2D` | `glFramebufferTexture2D` | 1 |  | 2 |  |  |
| `checkFramebufferStatus` | `glCheckFramebufferStatus` | 1 | 2 |  |  |  |
| `drawBuffers` | `glDrawBuffers` | 1 |  |  |  |  |
| `getFramebufferAttachmentParameteriv` | `glGetFramebufferAttachmentParameteriv` | 1 |  |  |  |  |
| `glDrawBuffer` | `glDrawBuffer` | 1 |  |  |  |  |
| `glReadBuffer` | `glReadBuffer` | 1 |  |  |  |  |
| `glGenRenderbuffers` | `glGenRenderbuffers` | 1 |  |  |  |  |
| `glDeleteRenderbuffers` | `glDeleteRenderbuffers` | 1 |  |  |  |  |
| `glBindRenderbuffer` | `glBindRenderbuffer` | 1 |  |  |  |  |
| `glRenderbufferStorage` | `glRenderbufferStorage` | 1 |  |  |  |  |
| `glRenderbufferStorageMultisample` | `glRenderbufferStorageMultisample` | 1 |  |  |  |  |
| `glFramebufferRenderbuffer` | `glFramebufferRenderbuffer` | 1 |  |  |  |  |

## Draws and clears

The tracker's draw, and a clear as a load op.

| Method | via `CgGL` | core | CrystalGUI | hosts | platform | harness |
|---|---|---:|---:|---:|---:|---:|
| `glDrawArrays` | `glDrawArrays` | 5 |  |  |  |  |
| `glDrawElements` | `glDrawElements` | 3 |  |  |  |  |
| `glDrawArraysInstanced` | `glDrawArraysInstanced` | 1 |  |  |  |  |
| `glDrawElementsInstanced` | `glDrawElementsInstanced` | 1 |  |  |  |  |
| `glClear` | `glClear` | 3 |  |  |  |  |

## Sync, timers and readback

Fences on the frame that recorded them; a readback stalls.

| Method | via `CgGL` | core | CrystalGUI | hosts | platform | harness |
|---|---|---:|---:|---:|---:|---:|
| `glGenQuery` | `glGenQuery` |  |  |  | 1 |  |
| `glBeginTimeElapsedQuery` | `glBeginTimeElapsedQuery` |  |  |  | 1 |  |
| `glEndTimeElapsedQuery` | `glEndTimeElapsedQuery` |  |  |  | 1 |  |
| `glIsQueryResultAvailable` | `glIsQueryResultAvailable` |  |  |  | 1 |  |
| `glGetQueryResultNanos` | `glGetQueryResultNanos` |  |  |  | 1 |  |
| `glDeleteQuery` | `glDeleteQuery` |  |  |  | 2 |  |
| `glReadPixels` ×2 | `glReadPixels` | 1 | 1 |  |  |  |
| `glFenceSync` | `glFenceSync` | 2 |  |  |  |  |
| `glClientWaitSync` | `glClientWaitSync` | 2 |  |  |  |  |
| `glDeleteSync` | `glDeleteSync` | 5 |  |  |  |  |
| `glGetTexImage` | `glGetTexImage` |  |  |  |  |  |

## Fixed function

Accepted, ignored, one warning each -- a core profile has none of them either.

| Method | via `CgGL` | core | CrystalGUI | hosts | platform | harness |
|---|---|---:|---:|---:|---:|---:|
| `glLineWidth` | `glLineWidth` |  |  |  | 1 |  |
| `glPolygonMode` | `glPolygonMode` |  |  |  | 2 |  |
| `glAlphaFunc` | `glAlphaFunc` | 1 |  |  | 1 |  |
| `glPointSize` | `glPointSize` |  |  |  | 1 |  |

## Host sections

End our pass and hand the host its state back (D4 wires the brackets).

| Method | via `CgGL` | core | CrystalGUI | hosts | platform | harness |
|---|---|---:|---:|---:|---:|---:|
| `hostSectionBegin` | `hostSectionBegin` |  |  |  |  |  |
| `hostSectionEnd` | `hostSectionEnd` |  |  |  |  |  |

## Backend selection

Not GL.

| Method | via `CgGL` | core | CrystalGUI | hosts | platform | harness |
|---|---|---:|---:|---:|---:|---:|
| `isAvailable` | direct |  |  |  |  |  |
| `getPriority` | direct |  |  |  |  |  |
