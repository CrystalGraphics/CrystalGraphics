# `CgGLBackend` — the census

**Generated** by `python platform/tools/gl_census.py`; edit the script, not this file. Every backend
method, the `CgGL` facade that dispatches to it, and how many call sites in each group's main code reach
it -- statically, with every Stonecutter branch counted as live. `platform` is the state manager's
restores, the providers and the trace; `harness` is the GL debug harness, listed apart since it is a
test application.

**158 methods** (172 declarations with overloads): **146 reached** by core, CrystalGUI, the hosts or `platform`; **2 by the harness only**; **10 by nothing** -- `isAvailable`, `getPriority`, `glVertexAttribDivisor`, `glDrawElementsInstanced`, `glMultiDrawArraysIndirect`, `glMultiDrawArraysIndirectCount`, `glMultiDrawElementsIndirectCount`, `glMemoryBarrier`, `isContextCurrent`, `glTexImage2DMultisample`.

**What it orders** (D3.4): the tracked backend is built domain by domain in the order below, reached
methods first within each. An unreached method is still built -- `CgGL` is public API outside mods
call (decision 18) -- but last, and a test reaches it before it counts.

## Context and queries

What `CgCapabilities.detect()` and the state providers read before anything draws.

| Method | via `CgGL` | core | CrystalGUI | hosts | platform | harness |
|---|---|---:|---:|---:|---:|---:|
| `initContext` | direct | 1 |  |  |  |  |
| `glGetInteger` ×2 | `glGetInteger` | 12 | 11 |  | 26 | 12 |
| `glGetBoolean` ×2 | `glGetBoolean` |  | 2 |  | 2 | 5 |
| `glGetFloat` ×2 | `glGetFloat` |  |  |  | 1 |  |
| `glGetString` | `glGetString` |  |  |  | 4 |  |
| `glGetStringi` | `glGetStringi` |  |  |  | 1 |  |
| `glGetIntegeri` | `glGetIntegeri` |  |  |  | 6 |  |
| `glGetError` | `glGetError` | 2 | 6 |  |  | 3 |
| `ownedByCurrentThread` | `mayIssueGl`, `ownedByCurrentThread` | 2 |  |  |  |  |
| `isContextCurrent` | `isContextCurrent` |  |  |  |  |  |

## State

The pipeline key, and what the scope suite (D3.5) restores.

| Method | via `CgGL` | core | CrystalGUI | hosts | platform | harness |
|---|---|---:|---:|---:|---:|---:|
| `glEnable` | `glEnable` | 10 |  |  | 2 | 6 |
| `glDisable` | `glDisable` | 31 | 3 |  | 2 | 6 |
| `glBlendFunc` | `glBlendFunc` | 1 |  |  |  | 1 |
| `glBlendFuncSeparate` | `glBlendFuncSeparate` | 1 |  |  | 1 |  |
| `glDepthMask` | `glDepthMask` | 3 |  |  | 1 | 3 |
| `glCullFace` | `glCullFace` | 1 |  |  | 1 |  |
| `glViewport` | `glViewport` | 11 |  | 1 | 1 | 5 |
| `glScissor` | `glScissor` | 5 |  |  | 1 |  |
| `glColorMask` | `glColorMask` | 4 | 2 |  | 1 |  |
| `glStencilFunc` | `glStencilFunc` | 1 |  |  | 1 |  |
| `glStencilOp` | `glStencilOp` | 1 |  |  | 1 |  |
| `glClearDepth` | `glClearDepth` | 2 |  | 2 |  |  |
| `glClearColor` | `glClearColor` | 4 |  |  |  | 5 |
| `glClearStencil` | `glClearStencil` | 1 |  |  |  |  |
| `glDepthFunc` | `glDepthFunc` | 2 |  |  | 1 | 2 |
| `glStencilMask` | `glStencilMask` | 2 |  |  | 1 |  |
| `glBlendEquationSeparate` | `glBlendEquationSeparate` | 3 |  |  | 1 |  |
| `glColorMaski` | `glColorMaski` | 1 |  |  | 1 |  |
| `glFrontFace` | `glFrontFace` | 1 |  |  | 1 |  |
| `glPolygonOffset` | `glPolygonOffset` |  |  |  | 1 |  |

## Buffers and vertex arrays

Every draw reads them; the frame ring and orphan-as-rename live here.

| Method | via `CgGL` | core | CrystalGUI | hosts | platform | harness |
|---|---|---:|---:|---:|---:|---:|
| `glGenBuffers` | `glGenBuffers` | 16 |  |  |  | 8 |
| `glBindBuffer` | `glBindBuffer` | 79 |  |  | 3 | 17 |
| `glBufferData` ×3 | `glBufferData` | 18 |  |  |  | 8 |
| `glBufferSubData` | `glBufferSubData` | 4 |  |  |  | 1 |
| `glCopyBufferSubData` | `glCopyBufferSubData` | 6 |  |  |  |  |
| `glDeleteBuffers` | `glDeleteBuffers` | 27 |  |  |  | 6 |
| `glBindBufferBase` | `glBindBufferBase` | 10 |  |  | 2 |  |
| `glBindBufferRange` | `glBindBufferRange` | 9 |  |  | 2 |  |
| `glTexBuffer` | `glTexBuffer` | 2 |  |  |  |  |
| `glGenVertexArrays` | `glGenVertexArrays` | 3 |  |  |  | 7 |
| `glBindVertexArray` | `glBindVertexArray` | 13 |  |  | 1 | 22 |
| `glDeleteVertexArrays` | `glDeleteVertexArrays` | 4 |  |  |  | 6 |
| `glEnableVertexAttribArray` | `glEnableVertexAttribArray` | 4 |  |  |  | 11 |
| `glVertexAttribPointer` | `glVertexAttribPointer` | 3 |  |  |  | 11 |
| `glVertexAttribIPointer` | `glVertexAttribIPointer` | 1 |  |  |  |  |
| `cgFillBuffer` | `cgFillBuffer` | 3 |  |  |  |  |
| `glMapBufferRange` | `glMapBufferRange` | 7 |  |  |  |  |
| `glUnmapBuffer` | `glUnmapBuffer` | 5 |  |  |  |  |
| `glFlushMappedBufferRange` | `glFlushMappedBufferRange` | 1 |  |  |  |  |
| `glBufferStorage` | `glBufferStorage` | 4 |  |  |  |  |
| `glVertexAttribDivisor` | `glVertexAttribDivisor` |  |  |  |  |  |

## Programs

The link-time rewrite (D3.6) produces the tables these answer from.

| Method | via `CgGL` | core | CrystalGUI | hosts | platform | harness |
|---|---|---:|---:|---:|---:|---:|
| `glCreateShader` | `glCreateShader` | 4 |  |  |  | 1 |
| `glShaderSource` | `glShaderSource` | 4 |  |  |  | 1 |
| `glCompileShader` | `glCompileShader` | 4 |  |  |  | 1 |
| `glGetShaderi` | `glGetShaderi` | 4 |  |  |  | 1 |
| `glGetShaderInfoLog` | `glGetShaderInfoLog` | 4 |  |  |  | 1 |
| `glDeleteShader` | `glDeleteShader` | 9 |  |  |  | 5 |
| `glCreateProgram` | `glCreateProgram` | 1 |  |  |  | 1 |
| `glAttachShader` | `glAttachShader` | 4 |  |  |  | 2 |
| `glLinkProgram` | `glLinkProgram` | 3 |  |  |  | 1 |
| `glGetProgrami` | `glGetProgrami` | 6 |  |  |  | 1 |
| `glGetProgramInfoLog` | `glGetProgramInfoLog` | 3 |  |  |  | 1 |
| `glUseProgram` | `glUseProgram` | 7 |  |  | 1 | 9 |
| `glDeleteProgram` | `glDeleteProgram` | 1 |  |  |  | 5 |
| `glGetUniformLocation` | `glGetUniformLocation` | 17 |  |  |  | 4 |
| `glUniform1i` | `glUniform1i` | 19 |  |  |  |  |
| `glUniform1f` | `glUniform1f` | 1 |  |  |  |  |
| `glUniform2f` | `glUniform2f` | 1 |  |  |  | 1 |
| `glUniform3f` | `glUniform3f` | 1 |  |  |  |  |
| `glUniform4f` | `glUniform4f` | 1 |  |  |  | 1 |
| `glBindAttribLocation` | `glBindAttribLocation` | 1 |  |  |  |  |
| `glGetProgramResourceIndex` | `glGetProgramResourceIndex` | 5 |  |  |  |  |
| `glShaderStorageBlockBinding` | `glShaderStorageBlockBinding` | 5 |  |  |  |  |
| `glGetUniformBlockIndex` | `glGetUniformBlockIndex` | 4 |  |  |  |  |
| `glUniformBlockBinding` | `glUniformBlockBinding` | 4 |  |  |  |  |
| `glTransformFeedbackVaryings` | `glTransformFeedbackVaryings` | 1 |  |  |  |  |
| `glDetachShader` | `glDetachShader` | 5 |  |  |  |  |
| `glGetAttachedShaders` | `glGetAttachedShaders` | 1 |  |  |  |  |
| `glGetActiveUniform` | `glGetActiveUniform` | 1 |  |  |  |  |
| `glUniform1` ×2 | `glUniform1` | 5 |  |  |  |  |
| `glUniformMatrix3` | `glUniformMatrix3` | 2 |  |  |  |  |
| `glUniformMatrix4` | `glUniformMatrix4` | 2 |  |  |  |  |
| `glUniformMatrix4fv` | `glUniformMatrix4fv` |  |  |  |  | 2 |

## Textures and samplers

Uploads are encoder work outside a pass.

| Method | via `CgGL` | core | CrystalGUI | hosts | platform | harness |
|---|---|---:|---:|---:|---:|---:|
| `copyImageSubData` | `glCopyImageSubData` | 1 |  |  |  |  |
| `glGenTextures` | `glGenTextures` | 11 |  |  |  | 5 |
| `glBindTexture` | `glBindTexture` | 46 |  |  | 1 | 11 |
| `glDeleteTextures` | `glDeleteTextures` | 9 |  |  |  | 3 |
| `glTexImage2D` ×2 | `glTexImage2D` | 5 |  |  |  | 3 |
| `glTexSubImage2D` ×3 | `glTexSubImage2D` | 5 |  |  |  |  |
| `glTexImage3D` ×2 | `glTexImage3D` | 4 |  |  |  | 2 |
| `glTexSubImage3D` ×4 | `glTexSubImage3D` | 9 |  |  |  |  |
| `glGenerateMipmap` | `glGenerateMipmap` | 2 |  |  |  |  |
| `glActiveTexture` | `glActiveTexture` | 9 | 2 |  | 3 | 1 |
| `glTexParameteri` | `glTexParameteri` | 21 |  |  |  | 14 |
| `glPixelStorei` | `glPixelStorei` | 8 |  |  |  | 4 |
| `glBindSampler` | `glBindSampler` | 3 |  |  |  |  |
| `importHostTexture` | `importHostTexture` | 2 |  |  |  |  |
| `glTexImage2DMultisample` | `glTexImage2DMultisample` |  |  |  |  |  |

## Framebuffers and renderbuffers

Attachment sets; a bind picks the next pass's target.

| Method | via `CgGL` | core | CrystalGUI | hosts | platform | harness |
|---|---|---:|---:|---:|---:|---:|
| `bindFramebuffer` | `glBindFramebuffer` | 35 | 2 | 2 | 3 | 16 |
| `blitFramebuffer` | `glBlitFramebuffer` | 4 |  |  |  |  |
| `framebufferTextureLayer` | `glFramebufferTextureLayer` | 7 |  |  |  | 1 |
| `genFramebuffers` | `glGenFramebuffers` | 7 |  | 1 |  | 4 |
| `deleteFramebuffers` | `glDeleteFramebuffers` | 8 |  | 1 |  | 5 |
| `framebufferTexture2D` | `glFramebufferTexture2D` | 7 |  | 2 |  | 3 |
| `checkFramebufferStatus` | `glCheckFramebufferStatus` | 2 | 2 |  |  | 4 |
| `drawBuffers` | `glDrawBuffers` | 3 |  |  |  |  |
| `getFramebufferAttachmentParameteriv` | `glGetFramebufferAttachmentParameteriv` | 2 |  |  |  | 1 |
| `glDrawBuffer` | `glDrawBuffer` | 1 |  |  |  |  |
| `glReadBuffer` | `glReadBuffer` | 1 |  |  |  |  |
| `glGenRenderbuffers` | `glGenRenderbuffers` | 1 |  |  |  | 1 |
| `glDeleteRenderbuffers` | `glDeleteRenderbuffers` | 1 |  |  |  | 1 |
| `glBindRenderbuffer` | `glBindRenderbuffer` | 1 |  |  |  | 2 |
| `glRenderbufferStorage` | `glRenderbufferStorage` | 1 |  |  |  | 1 |
| `glRenderbufferStorageMultisample` | `glRenderbufferStorageMultisample` | 1 |  |  |  |  |
| `glFramebufferRenderbuffer` | `glFramebufferRenderbuffer` | 1 |  |  |  | 1 |

## Draws and clears

The tracker's draw, and a clear as a load op.

| Method | via `CgGL` | core | CrystalGUI | hosts | platform | harness |
|---|---|---:|---:|---:|---:|---:|
| `glDrawArrays` | `glDrawArrays` | 11 |  |  |  | 4 |
| `glDrawArraysInstanced` | `glDrawArraysInstanced` | 1 |  |  |  |  |
| `glDrawElementsInstancedBaseVertex` | `glDrawElementsInstancedBaseVertex` | 1 |  |  |  |  |
| `glDrawArraysIndirect` | `glDrawArraysIndirect` | 2 |  |  |  |  |
| `glDrawElementsIndirect` | `glDrawElementsIndirect` | 1 |  |  |  |  |
| `glMultiDrawElementsIndirect` | `glMultiDrawElementsIndirect` | 2 |  |  |  |  |
| `glBeginTransformFeedback` | `glBeginTransformFeedback` | 2 |  |  |  |  |
| `glEndTransformFeedback` | `glEndTransformFeedback` | 2 |  |  |  |  |
| `glClear` | `glClear` | 4 |  |  |  | 5 |
| `glDrawElements` | `glDrawElements` |  |  |  |  | 2 |
| `glDrawElementsInstanced` | `glDrawElementsInstanced` |  |  |  |  |  |
| `glMultiDrawArraysIndirect` | `glMultiDrawArraysIndirect` |  |  |  |  |  |
| `glMultiDrawArraysIndirectCount` | `glMultiDrawArraysIndirectCount` |  |  |  |  |  |
| `glMultiDrawElementsIndirectCount` | `glMultiDrawElementsIndirectCount` |  |  |  |  |  |

## Compute

Dispatches, storage images and the barriers the frame graph derives; async work on a compute queue.

| Method | via `CgGL` | core | CrystalGUI | hosts | platform | harness |
|---|---|---:|---:|---:|---:|---:|
| `glDispatchCompute` | `glDispatchCompute` | 1 |  |  |  |  |
| `glDispatchComputeIndirect` | `glDispatchComputeIndirect` | 1 |  |  |  |  |
| `glBindImageTexture` | `glBindImageTexture` | 3 |  |  | 1 |  |
| `cgBufferBarrier` | `cgBufferBarrier` | 5 |  |  |  |  |
| `cgBeginAsync` | `cgBeginAsync` | 1 |  |  |  |  |
| `cgEndAsync` | `cgEndAsync` | 1 |  |  |  |  |
| `cgWaitAsync` | `cgWaitAsync` | 1 |  |  |  |  |
| `cgImageBarrier` | `cgImageBarrier` | 1 |  |  |  |  |
| `glMemoryBarrier` | `glMemoryBarrier` |  |  |  |  |  |

## Sync, timers and readback

Fences on the frame that recorded them; a readback stalls.

| Method | via `CgGL` | core | CrystalGUI | hosts | platform | harness |
|---|---|---:|---:|---:|---:|---:|
| `glGenQuery` | `glGenQuery` |  |  |  | 2 |  |
| `glBeginTimeElapsedQuery` | `glBeginTimeElapsedQuery` |  |  |  | 1 |  |
| `glEndTimeElapsedQuery` | `glEndTimeElapsedQuery` |  |  |  | 1 |  |
| `glQueryTimestamp` | `glQueryTimestamp` |  |  |  | 1 |  |
| `glIsQueryResultAvailable` | `glIsQueryResultAvailable` |  |  |  | 1 |  |
| `glGetQueryResultNanos` | `glGetQueryResultNanos` |  |  |  | 1 |  |
| `glDeleteQuery` | `glDeleteQuery` |  |  |  | 3 |  |
| `glGetTexImage` | `glGetTexImage` | 2 |  |  |  | 2 |
| `glReadPixels` ×2 | `glReadPixels` | 7 | 1 |  |  | 3 |
| `glFenceSync` | `glFenceSync` | 2 |  |  |  |  |
| `glClientWaitSync` | `glClientWaitSync` | 2 |  |  |  |  |
| `glDeleteSync` | `glDeleteSync` | 5 |  |  |  |  |

## Fixed function

Accepted, ignored, one warning each -- a core profile has none of them either.

| Method | via `CgGL` | core | CrystalGUI | hosts | platform | harness |
|---|---|---:|---:|---:|---:|---:|
| `glLineWidth` | `glLineWidth` |  |  |  | 1 | 2 |
| `glPolygonMode` | `glPolygonMode` |  |  |  | 3 |  |
| `glAlphaFunc` | `glAlphaFunc` | 1 |  |  | 1 |  |
| `glPointSize` | `glPointSize` | 1 |  |  | 1 |  |

## Host sections

End our pass and hand the host its state back (D4 wires the brackets).

| Method | via `CgGL` | core | CrystalGUI | hosts | platform | harness |
|---|---|---:|---:|---:|---:|---:|
| `fromHost` | `fromHost` | 4 |  | 7 |  | 1 |
| `toHost` | `toHost` | 4 |  | 7 |  | 1 |

## Backend selection

Not GL.

| Method | via `CgGL` | core | CrystalGUI | hosts | platform | harness |
|---|---|---:|---:|---:|---:|---:|
| `isAvailable` | direct |  |  |  |  |  |
| `getPriority` | direct |  |  |  |  |  |
