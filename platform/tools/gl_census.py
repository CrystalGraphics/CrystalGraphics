"""Draws the CgGLBackend census: every backend method, the CgGL facade that reaches it, and who calls it.

    python platform/tools/gl_census.py            # from CrystalGraphics, inside a CrystalGUI checkout

Writes platform/src/main/java/com/crystalgraphics/platform/gl/CENSUS.md. Static: a method is reached when
main code in a group calls a CgGL facade that dispatches to it. Every Stonecutter branch counts as live.
"""
import os, re, sys
from collections import defaultdict

CG = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
ROOT = os.path.dirname(CG)
GL = os.path.join(CG, 'platform/src/main/java/com/crystalgraphics/platform/gl')
OUT = os.path.join(GL, 'CENSUS.md')

GROUPS = {
    'core': [os.path.join(CG, 'core/src/main')],
    'CrystalGUI': [os.path.join(ROOT, 'core/src/main'), os.path.join(ROOT, 'language/src/main')],
    'hosts': [os.path.join(CG, 'runtime/mc'), os.path.join(ROOT, 'runtime/mc')],
    'platform': [os.path.join(CG, 'platform/src/main')],
    'harness': [os.path.join(ROOT, 'gl-debug-harness/src/main')],
}
PRODUCT = ('core', 'CrystalGUI', 'hosts', 'platform')
SKIP_PLATFORM = ('CgGL.java', 'CgGLBackend.java', 'CgGlRecordingBackend.java')

# D3.4's build order. Each domain is built after the ones above it.
DOMAINS = [
    ('Context and queries', 'what `CgCapabilities.detect()` and the state providers read before anything draws', [
        'initContext', 'isContextCurrent', 'ownedByCurrentThread', 'glGetInteger', 'glGetBoolean', 'glGetFloat',
        'glGetError', 'glGetIntegeri', 'glGetString', 'glGetStringi']),
    ('State', 'the pipeline key, and what the scope suite (D3.5) restores', [
        'glEnable', 'glDisable', 'glBlendFunc', 'glBlendFuncSeparate', 'glBlendEquationSeparate', 'glDepthMask',
        'glDepthFunc', 'glCullFace', 'glFrontFace', 'glColorMask', 'glColorMaski', 'glStencilFunc', 'glStencilOp',
        'glStencilMask', 'glPolygonOffset', 'glViewport', 'glScissor', 'glClearColor', 'glClearDepth',
        'glClearStencil']),
    ('Buffers and vertex arrays', 'every draw reads them; the frame ring and orphan-as-rename live here', [
        'glGenBuffers', 'glDeleteBuffers', 'glBindBuffer', 'glBufferData', 'glBufferSubData', 'glBufferStorage',
        'glMapBufferRange', 'glFlushMappedBufferRange', 'glUnmapBuffer', 'glBindBufferBase', 'glBindBufferRange',
        'glTexBuffer', 'glGenVertexArrays', 'glDeleteVertexArrays', 'glBindVertexArray',
        'glEnableVertexAttribArray', 'glVertexAttribPointer', 'glVertexAttribIPointer', 'glVertexAttribDivisor',
        'glCopyBufferSubData', 'cgFillBuffer']),
    ('Programs', 'the link-time rewrite (D3.6) produces the tables these answer from', [
        'glCreateShader', 'glShaderSource', 'glCompileShader', 'glGetShaderi', 'glGetShaderInfoLog',
        'glDeleteShader', 'glCreateProgram', 'glAttachShader', 'glDetachShader', 'glGetAttachedShaders',
        'glLinkProgram', 'glGetProgrami', 'glGetProgramInfoLog', 'glUseProgram', 'glDeleteProgram',
        'glBindAttribLocation', 'glGetUniformLocation', 'glGetActiveUniform', 'glUniform1i', 'glUniform1f',
        'glUniform2f', 'glUniform3f', 'glUniform4f', 'glUniform1', 'glUniformMatrix3', 'glUniformMatrix4',
        'glUniformMatrix4fv', 'glGetUniformBlockIndex', 'glUniformBlockBinding', 'glGetProgramResourceIndex',
        'glShaderStorageBlockBinding', 'glTransformFeedbackVaryings']),
    ('Textures and samplers', 'uploads are encoder work outside a pass', [
        'glGenTextures', 'glDeleteTextures', 'glActiveTexture', 'glBindTexture', 'glBindSampler', 'glTexImage2D',
        'glTexSubImage2D', 'glTexImage3D', 'glTexSubImage3D', 'glTexImage2DMultisample', 'glTexParameteri',
        'glGenerateMipmap', 'glPixelStorei', 'copyImageSubData', 'importHostTexture']),
    ('Framebuffers and renderbuffers', 'attachment sets; a bind picks the next pass\'s target', [
        'genFramebuffers', 'deleteFramebuffers', 'bindFramebuffer', 'framebufferTexture2D',
        'framebufferTextureLayer', 'framebufferTexture', 'checkFramebufferStatus', 'drawBuffers', 'glDrawBuffer', 'glReadBuffer',
        'getFramebufferAttachmentParameteriv', 'blitFramebuffer', 'glGenRenderbuffers', 'glDeleteRenderbuffers',
        'glBindRenderbuffer', 'glRenderbufferStorage', 'glRenderbufferStorageMultisample',
        'glFramebufferRenderbuffer']),
    ('Draws and clears', 'the tracker\'s draw, and a clear as a load op', [
        'glDrawArrays', 'glDrawElements', 'glDrawArraysInstanced', 'glDrawElementsInstanced',
        'glDrawElementsInstancedBaseVertex', 'glDrawArraysIndirect', 'glDrawElementsIndirect',
        'glMultiDrawArraysIndirect', 'glMultiDrawElementsIndirect', 'glMultiDrawArraysIndirectCount',
        'glMultiDrawElementsIndirectCount', 'glBeginTransformFeedback', 'glEndTransformFeedback', 'glClear']),
    ('Compute', 'dispatches, storage images and the barriers the frame graph derives; async work on a compute queue, '
                'copies on a transfer queue', [
        'glDispatchCompute', 'glDispatchComputeIndirect', 'glBindImageTexture', 'glMemoryBarrier', 'cgBufferBarrier',
        'cgImageBarrier', 'cgBeginAsync', 'cgEndAsync', 'cgWaitAsync', 'cgBeginTransfer', 'cgEndTransfer']),
    ('Sync, timers and readback', 'fences on the frame that recorded them; a readback stalls', [
        'glFenceSync', 'glClientWaitSync', 'glDeleteSync', 'glGenQuery', 'glBeginTimeElapsedQuery',
        'glEndTimeElapsedQuery', 'glQueryTimestamp', 'glIsQueryResultAvailable', 'glGetQueryResultNanos', 'glDeleteQuery',
        'glReadPixels', 'glGetTexImage']),
    ('Fixed function', 'accepted, ignored, one warning each -- a core profile has none of them either', [
        'glAlphaFunc', 'glLineWidth', 'glPointSize', 'glPolygonMode']),
    ('Host sections', 'end our pass and hand the host its state back (D4 wires the brackets)', [
        'toHost', 'fromHost']),
    ('Backend selection', 'not GL', ['isAvailable', 'getPriority']),
]

DQ, SQ, BSL, NL = chr(34), chr(39), chr(92), chr(10)
TQ = DQ * 3


def read(p):
    return open(p, encoding='utf-8', errors='replace').read()


def unwrap_stonecutter(src):
    """`//? if ...` then `/*code*///?}` becomes plain code: another node's branch is still reached."""
    src = re.sub(r'(//\?[^\n]*\n[ \t]*)/\*(?!\?)', r'\1', src)
    return src.replace('*///?', '//?')


def strip_comments(src):
    """Blanks comments, keeping string and char literals and line numbers."""
    src = unwrap_stonecutter(src)
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if src.startswith(TQ, i):
            j = src.find(TQ, i + 3); j = n if j < 0 else j + 3
            out.append(src[i:j]); i = j
        elif c == DQ or c == SQ:
            j = i + 1
            while j < n and src[j] != c:
                j += 2 if src[j] == BSL else 1
            out.append(src[i:j + 1]); i = j + 1
        elif src.startswith('//', i):
            j = src.find(NL, i); i = n if j < 0 else j
        elif src.startswith('/*', i):
            j = src.find('*/', i + 2); j = n if j < 0 else j + 2
            out.append(NL * src.count(NL, i, j)); i = j
        else:
            out.append(c); i += 1
    return ''.join(out)


def java_files(base):
    for r, ds, fs in os.walk(base):
        ds[:] = [d for d in ds if d not in ('build', '.gradle', 'test', 'headlessTest', '.claude')]
        for f in fs:
            if f.endswith('.java'):
                yield os.path.join(r, f)


def main():
    backend_src = strip_comments(read(os.path.join(GL, 'CgGLBackend.java')))
    backend, declarations = [], defaultdict(int)
    for m in re.finditer(r'public\s+(?:abstract\s+)?[\w<>\[\]]+\s+(\w+)\s*\(', backend_src):
        declarations[m.group(1)] += 1
        if m.group(1) not in backend:
            backend.append(m.group(1))

    placed = [b for _, _, names in DOMAINS for b in names]
    missing, stale = set(backend) - set(placed), set(placed) - set(backend)
    if missing or stale:
        sys.exit(f'DOMAINS is out of date -- unplaced: {sorted(missing)}, gone: {sorted(stale)}')

    cggl = strip_comments(read(os.path.join(GL, 'CgGL.java')))
    to_backend, to_facade, public = defaultdict(set), defaultdict(set), set()
    for m in re.finditer(r'(public\s+)?static\s+[\w<>\[\]]+\s+(\w+)\s*\([^)]*\)\s*\{', cggl):
        name = m.group(2)
        if m.group(1):
            public.add(name)
        depth, i = 1, m.end()
        while depth:
            depth += (cggl[i] == '{') - (cggl[i] == '}')
            i += 1
        body = cggl[m.end():i]
        to_backend[name] |= set(re.findall(r'\b(?:backend|gl\(\))\.(\w+)\(', body))
        to_facade[name] |= {f for f in re.findall(r'(?<![.\w])(\w+)\(', body) if f != name}

    def reach(facade, seen):
        if facade in seen:
            return set()
        seen.add(facade)
        out = set(to_backend.get(facade, ()))
        for f in to_facade.get(facade, ()):
            if f in to_backend:
                out |= reach(f, seen)
        return out

    facade_calls = defaultdict(lambda: defaultdict(int))
    direct_calls = defaultdict(lambda: defaultdict(int))
    for group, bases in GROUPS.items():
        for base in bases:
            for p in java_files(base):
                if group == 'platform' and p.endswith(SKIP_PLATFORM):
                    continue
                src = strip_comments(read(p))
                for f in re.findall(r'\bCgGL\.(\w+)\s*\(', src):
                    facade_calls[f][group] += 1
                for b in re.findall(r'\bCgPlatform\.gl\(\)\.(\w+)\s*\(', src):
                    direct_calls[b][group] += 1

    rows = {}
    for b in backend:
        via = sorted(f for f in public if b in reach(f, set()))
        counts = defaultdict(int)
        for f in via:
            for g, n in facade_calls[f].items():
                counts[g] += n
        for g, n in direct_calls[b].items():
            counts[g] += n
        rows[b] = (via, counts)

    reached = [b for b in backend if any(rows[b][1].get(g) for g in PRODUCT)]
    harness_only = [b for b in backend if b not in reached and rows[b][1].get('harness')]
    unreached = [b for b in backend if b not in reached and b not in harness_only]

    groups = list(GROUPS)
    lines = [
        '# `CgGLBackend` — the census',
        '',
        '**Generated** by `python platform/tools/gl_census.py`; edit the script, not this file. Every backend',
        'method, the `CgGL` facade that dispatches to it, and how many call sites in each group\'s main code reach',
        'it -- statically, with every Stonecutter branch counted as live. `platform` is the state manager\'s',
        'restores, the providers and the trace; `harness` is the GL debug harness, listed apart since it is a',
        'test application.',
        '',
        f'**{len(backend)} methods** ({sum(declarations.values())} declarations with overloads): '
        f'**{len(reached)} reached** by core, CrystalGUI, the hosts or `platform`; '
        f'**{len(harness_only)} by the harness only**; **{len(unreached)} by nothing** -- '
        + ', '.join(f'`{b}`' for b in unreached) + '.',
        '',
        '**What it orders** (D3.4): the tracked backend is built domain by domain in the order below, reached',
        'methods first within each. An unreached method is still built -- `CgGL` is public API outside mods',
        'call (decision 18) -- but last, and a test reaches it before it counts.',
        '',
    ]
    for title, why, names in DOMAINS:
        lines += [f'## {title}', '', why[0].upper() + why[1:] + '.', '',
                  '| Method | via `CgGL` | ' + ' | '.join(groups) + ' |',
                  '|---|---|' + '---:|' * len(groups)]
        for b in sorted(names, key=lambda b: (b not in reached, backend.index(b))):
            via, counts = rows[b]
            over = f' ×{declarations[b]}' if declarations[b] > 1 else ''
            lines.append(f'| `{b}`{over} | ' + (', '.join(f'`{v}`' for v in via) or 'direct') + ' | '
                         + ' | '.join(str(counts.get(g, '')) for g in groups) + ' |')
        lines.append('')

    with open(OUT, 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(lines))
    print(f'{OUT}: {len(backend)} methods, {len(reached)} reached, {len(harness_only)} harness only, '
          f'{len(unreached)} unreached')


if __name__ == '__main__':
    main()
