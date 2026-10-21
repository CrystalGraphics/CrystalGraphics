package com.crystalgraphics.vfx.particle.form;

/**
 * One module input an emitter gives in a form other than a constant (a range, a pick, a curve by a lookup, a particle's
 * attribute): which module and input, the value as its file wrote it, and that value compiled. The Step kernel builds
 * the input's lanes from {@link #compiled()} each step; the module's own words hold its first end.
 *
 * <pre>{@code
 * CgVfxFormCompiler forms = new CgVfxFormCompiler(new CgVfxCurveBlock(), id -> null);
 * Object value = Map.of("range", List.of(2.0, 6.0));                       // drag, rolled per particle
 * CgVfxModuleForm drag = new CgVfxModuleForm(1, "drag", value, forms.compile("mymod:sparks/drag", property, value));
 * }</pre>
 *
 * @param module   the module's index in its emitter's stack
 * @param input    the input's declared name
 * @param value    the value as written: what an emitter file writes back
 * @param compiled its compiled form, the kernel's and the Java reference's
 */
public record CgVfxModuleForm(int module, String input, Object value, CgVfxFormInput compiled) {
}
