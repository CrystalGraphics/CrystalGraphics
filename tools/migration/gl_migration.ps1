# gl_migration.ps1 — CrystalGraphics LWJGL -> CgGL batch migration
#
# Replaces all org.lwjgl.* imports and call sites in core/src/main/java with
# their com.crystalgraphics.platform.gl.CgGL / CgBufferUtils / CgPlatform equivalents.
#
# Run: powershell -ExecutionPolicy Bypass -File tools/migration/gl_migration.ps1
# Re-running is safe (Static-Transform is idempotent).

. "X:\projects\CustomNPC-Plus\tools\migration\Static-Transform.ps1"
. "X:\projects\CustomNPC-Plus\tools\migration\Dedupe-Imports.ps1"
. "X:\projects\CustomNPC-Plus\tools\migration\Build-Report.ps1"

$CoreDir    = 'core/src/main/java'
$TestDir    = 'core/src/test/java'
$ProjectRoot = 'X:\projects\CrystalGUI\CrystalGraphics'

# ---------------------------------------------------------------------------
# Transform table — ORDER MATTERS: specific patterns before generic ones
# ---------------------------------------------------------------------------
$Transforms = @(

    # ==========================================================================
    # PHASE 1 — Import line replacements
    # Each LWJGL opengl import -> CgGL import; duplicates cleaned by Dedupe-Imports.
    # CgPlatform import added for files that use GLContext.getCapabilities().
    # ==========================================================================
    @('(?m)^import org\.lwjgl\.opengl\.[A-Za-z0-9_*]+;\r?\n',  "import com.crystalgraphics.platform.gl.CgGL;`n"),
    @('(?m)^import org\.lwjgl\.BufferUtils;\r?\n',               "import com.crystalgraphics.util.CgBufferUtils;`n"),
    @('(?m)^import org\.lwjgl\.LWJGLException;\r?\n',            ''),
    @('(?m)^import org\.lwjgl\.opengl\.Display;\r?\n',           ''),
    # Catch-all for any remaining org.lwjgl.* imports not matched above
    @('(?m)^import org\.lwjgl\.[A-Za-z0-9_.]+;\r?\n',           ''),

    # ==========================================================================
    # PHASE 2 — ARBShaderObjects: Group A unified-handle methods (BEFORE generic)
    # These map to the new CgGL.glDeleteObject / glGetObjectParameteri / etc.
    # The old plan incorrectly mapped glDeleteObjectARB -> glDeleteProgram; this is correct.
    # ==========================================================================
    @('ARBShaderObjects\.glDeleteObjectARB\s*\(',        'CgGL.glDeleteObject('),
    @('ARBShaderObjects\.glGetObjectParameteriARB\s*\(', 'CgGL.glGetObjectParameteri('),
    @('ARBShaderObjects\.glGetInfoLogARB\s*\(',          'CgGL.glGetObjectInfoLog('),
    @('ARBShaderObjects\.glGetHandleARB\s*\(',           'CgGL.glGetHandle('),

    # ==========================================================================
    # PHASE 3 — ARBShaderObjects: Group B methods that map to GL20 equivalents
    # ==========================================================================
    @('ARBShaderObjects\.glCreateShaderObjectARB\s*\(',     'CgGL.glCreateShader('),
    @('ARBShaderObjects\.glShaderSourceARB\s*\(',           'CgGL.glShaderSource('),
    @('ARBShaderObjects\.glCompileShaderARB\s*\(',          'CgGL.glCompileShader('),
    @('ARBShaderObjects\.glCreateProgramObjectARB\s*\(',    'CgGL.glCreateProgram('),
    @('ARBShaderObjects\.glAttachObjectARB\s*\(',           'CgGL.glAttachShader('),
    @('ARBShaderObjects\.glDetachObjectARB\s*\(',           'CgGL.glDetachShader('),
    @('ARBShaderObjects\.glLinkProgramARB\s*\(',            'CgGL.glLinkProgram('),
    @('ARBShaderObjects\.glUseProgramObjectARB\s*\(',       'CgGL.glUseProgram('),
    @('ARBShaderObjects\.glGetUniformLocationARB\s*\(',     'CgGL.glGetUniformLocation('),
    @('ARBShaderObjects\.glGetAttachedObjectsARB\s*\(',     'CgGL.glGetAttachedShaders('),
    @('ARBShaderObjects\.glGetActiveUniformARB\s*\(',       'CgGL.glGetActiveUniform('),
    @('ARBShaderObjects\.glUniform1iARB\s*\(',              'CgGL.glUniform1i('),
    @('ARBShaderObjects\.glUniform1fARB\s*\(',              'CgGL.glUniform1f('),
    @('ARBShaderObjects\.glUniform2fARB\s*\(',              'CgGL.glUniform2f('),
    @('ARBShaderObjects\.glUniform3fARB\s*\(',              'CgGL.glUniform3f('),
    @('ARBShaderObjects\.glUniform4fARB\s*\(',              'CgGL.glUniform4f('),
    @('ARBShaderObjects\.glUniform1ARB\s*\(',               'CgGL.glUniform1('),
    @('ARBShaderObjects\.glUniformMatrix3ARB\s*\(',         'CgGL.glUniformMatrix3('),
    @('ARBShaderObjects\.glUniformMatrix4ARB\s*\(',         'CgGL.glUniformMatrix4('),

    # ==========================================================================
    # PHASE 4 — Other ARB suffix methods (ARB/EXT suffix stripped)
    # ==========================================================================
    @('ARBVertexShader\.glBindAttribLocationARB\s*\(',      'CgGL.glBindAttribLocation('),
    @('ARBVertexShader\.glGetAttribLocationARB\s*\(',       'CgGL.glGetAttribLocation('),
    @('ARBInstancedArrays\.glVertexAttribDivisorARB\s*\(',  'CgGL.glVertexAttribDivisor('),
    @('ARBDrawInstanced\.glDrawArraysInstancedARB\s*\(',    'CgGL.glDrawArraysInstanced('),
    @('ARBDrawInstanced\.glDrawElementsInstancedARB\s*\(',  'CgGL.glDrawElementsInstanced('),
    @('ARBMultitexture\.glActiveTextureARB\s*\(',           'CgGL.glActiveTexture('),

    # ==========================================================================
    # PHASE 5 — EXTFramebufferObject suffix methods
    # ==========================================================================
    @('EXTFramebufferObject\.glBindFramebufferEXT\s*\(',         'CgGL.glBindFramebuffer('),
    @('EXTFramebufferObject\.glGenFramebuffersEXT\s*\(',         'CgGL.glGenFramebuffers('),
    @('EXTFramebufferObject\.glDeleteFramebuffersEXT\s*\(',      'CgGL.glDeleteFramebuffers('),
    @('EXTFramebufferObject\.glFramebufferTexture2DEXT\s*\(',    'CgGL.glFramebufferTexture2D('),
    @('EXTFramebufferObject\.glCheckFramebufferStatusEXT\s*\(',  'CgGL.glCheckFramebufferStatus('),
    @('EXTFramebufferObject\.glFramebufferRenderbufferEXT\s*\(', 'CgGL.glFramebufferRenderbuffer('),
    @('EXTFramebufferObject\.glGenRenderbuffersEXT\s*\(',        'CgGL.glGenRenderbuffers('),
    @('EXTFramebufferObject\.glBindRenderbufferEXT\s*\(',        'CgGL.glBindRenderbuffer('),
    @('EXTFramebufferObject\.glRenderbufferStorageEXT\s*\(',     'CgGL.glRenderbufferStorage('),
    @('EXTFramebufferObject\.glDeleteRenderbuffersEXT\s*\(',     'CgGL.glDeleteRenderbuffers('),
    @('EXTFramebufferObject\.glBlitFramebufferEXT\s*\(',         'CgGL.glBlitFramebuffer('),

    # ==========================================================================
    # PHASE 6 — Generic GLxx.glXxx call sites (GL11 through GL43)
    # Runs AFTER Phases 2-5 so ARB/EXT suffix methods are already replaced.
    # ==========================================================================
    @('GL\d+\.gl([A-Za-z0-9]+)\s*\(',                            'CgGL.gl$1('),
    # Generic ARB/EXT with standard gl-prefix methods not handled by Phases 2-5
    @('(?:ARBSync|ARBFramebufferObject|ARBVertexArrayObject|ARBSamplerObjects|ARBMapBufferRange|ARBShaderStorageBufferObject|ARBUniformBufferObject)\.gl([A-Za-z0-9]+)\s*\(', 'CgGL.gl$1('),

    # ==========================================================================
    # PHASE 7 — Constants (ClassName.GL_CONSTANT -> CgGL.GL_CONSTANT)
    # ==========================================================================
    @('(?:GL\d+|ARBSync|ARBFramebufferObject|EXTFramebufferObject|ARBShaderObjects|ARBVertexShader|ARBVertexArrayObject|ARBMultitexture|ARBFragmentShader|ARBInstancedArrays|ARBDrawInstanced)\.(GL_[A-Z0-9_]+)', 'CgGL.$1'),

    # ==========================================================================
    # PHASE 8 — BufferUtils call sites
    # ==========================================================================
    @('BufferUtils\.create([A-Za-z]+Buffer)\s*\(', 'CgBufferUtils.create$1('),

    # ==========================================================================
    # PHASE 9 — Non-import LWJGL2 singleton replacements
    # Display.isCurrent() -> CgGL.isContextCurrent()  (CgGL import already in file from Phase 1)
    # GLContext.getCapabilities() is left for T4 (ContextCapabilities files need surgical pa_editor)
    # ==========================================================================
    @('Display\.isCurrent\s*\(\s*\)', 'CgGL.isContextCurrent()'),

    # ==========================================================================
    # PHASE 10 — LWJGLException in catch / throws
    # ==========================================================================
    @('catch\s*\(\s*LWJGLException\s+(\w+)\s*\)', 'catch (RuntimeException $1)'),
    @('\bthrows\s+LWJGLException\b',               'throws RuntimeException')
)

Write-Host "=== Running Static-Transform on $CoreDir ===" -ForegroundColor Cyan
Static-Transform -Directory $CoreDir -ProjectRoot $ProjectRoot -Transforms $Transforms

Write-Host "=== Deduplicating imports in $CoreDir ===" -ForegroundColor Cyan
Dedupe-Imports -Directory $CoreDir -ProjectRoot $ProjectRoot

# Test sources (lighter transform — mainly constants and generic GL calls)
if (Test-Path (Join-Path $ProjectRoot $TestDir)) {
    Write-Host "=== Running Static-Transform on $TestDir ===" -ForegroundColor Cyan
    Static-Transform -Directory $TestDir -ProjectRoot $ProjectRoot -Transforms @(
        @('(?m)^import org\.lwjgl\.[A-Za-z0-9_.]+;\r?\n',  "import com.crystalgraphics.platform.gl.CgGL;`n"),
        @('GL\d+\.gl([A-Za-z0-9]+)\s*\(',                   'CgGL.gl$1('),
        @('(?:GL\d+|ARB\w+|EXT\w+)\.(GL_[A-Z0-9_]+)',       'CgGL.$1'),
        @('BufferUtils\.create([A-Za-z]+Buffer)\s*\(',       'CgBufferUtils.create$1(')
    )
    Dedupe-Imports -Directory $TestDir -ProjectRoot $ProjectRoot
}

Write-Host "=== Build Report ===" -ForegroundColor Cyan
Build-Report -GradleTask ':core:compileJava' -ProjectRoot $ProjectRoot -MaxSamples 5
