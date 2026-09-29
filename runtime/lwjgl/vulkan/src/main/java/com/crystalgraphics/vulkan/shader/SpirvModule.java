package com.crystalgraphics.vulkan.shader;

import com.crystalgraphics.platform.device.shader.CgGlslCompiler;
import com.crystalgraphics.platform.device.shader.CgShaderModule;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.SpvcReflectedResource;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.util.spvc.Spv.*;
import static org.lwjgl.util.spvc.Spvc.*;

/**
 * One SPIR-V binary and what SPIRV-Cross reads from it: every resource and interface variable with the word
 * its binding or location decoration sits in, so that word can be set in place — as Minecraft 26.2's
 * {@code IntermediaryShaderModule} does.
 *
 * <p>Blocks and samplers are the <em>active</em> ones, as GL counts them: {@code cg_env.glsl} declares
 * {@code CgFrameBlock} in every material, and one that never reads it must not need a buffer bound. Stage
 * inputs and outputs are all of them, since varyings are matched by name across the stages.</p>
 */
final class SpirvModule {

    /** The block glslang's relaxed rules gather a stage's loose uniforms into. */
    static final String DEFAULT_BLOCK = "gl_DefaultUniformBlock";

    /**
     * @param word      the index of the decoration's literal in the binary
     * @param value     the decoration's value as compiled
     * @param locations interface locations the variable takes
     */
    record Resource(String name, int word, int value, int glType, boolean texel, int locations) {}

    final ByteBuffer spirv;
    final List<Resource> uniformBuffers = new ArrayList<>(), storageBuffers = new ArrayList<>();
    final List<Resource> samplers = new ArrayList<>(), inputs = new ArrayList<>(), outputs = new ArrayList<>();
    /** The loose-uniform block's leaves, at their offsets in it; empty without one. */
    final List<CgGlslCompiler.Uniform> uniforms = new ArrayList<>();
    int defaultBlockSize;

    private final String label;

    SpirvModule(ByteBuffer spirv, String label) {
        this.spirv = spirv;
        this.label = label;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer p = stack.callocPointer(1);
            check(spvc_context_create(p), "context");
            long context = p.get(0);
            try {
                check(spvc_context_parse_spirv(context, spirv.asIntBuffer(), spirv.remaining() / 4, p), "parse");
                long ir = p.get(0);
                check(spvc_context_create_compiler(context, SPVC_BACKEND_NONE, ir, SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, p), "compiler");
                long compiler = p.get(0);
                check(spvc_compiler_create_shader_resources(compiler, p), "resources");
                long resources = p.get(0);
                check(spvc_compiler_get_active_interface_variables(compiler, p), "active variables");
                long activeSet = p.get(0);
                check(spvc_compiler_create_shader_resources_for_active_variables(compiler, p, activeSet), "active resources");
                long active = p.get(0);
                read(stack, compiler, active, SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, SpvDecorationBinding, uniformBuffers);
                read(stack, compiler, active, SPVC_RESOURCE_TYPE_STORAGE_BUFFER, SpvDecorationBinding, storageBuffers);
                read(stack, compiler, active, SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, SpvDecorationBinding, samplers);
                read(stack, compiler, resources, SPVC_RESOURCE_TYPE_STAGE_INPUT, SpvDecorationLocation, inputs);
                read(stack, compiler, resources, SPVC_RESOURCE_TYPE_STAGE_OUTPUT, SpvDecorationLocation, outputs);
            } finally {
                spvc_context_destroy(context);
            }
        }
    }

    /** Sets a decoration's value in the binary. */
    void set(Resource r, int value) {
        spirv.putInt(r.word * 4, value);
    }

    private void read(MemoryStack stack, long compiler, long resources, int type, int decoration, List<Resource> out) {
        PointerBuffer list = stack.callocPointer(1), count = stack.callocPointer(1);
        check(spvc_resources_get_resource_list_for_type(resources, type, list, count), "resource list");
        SpvcReflectedResource.Buffer rs = SpvcReflectedResource.create(list.get(0), (int) count.get(0));
        IntBuffer word = stack.callocInt(1);
        for (SpvcReflectedResource r : rs) {
            String name = r.nameString();
            if (!spvc_compiler_get_binary_offset_for_decoration(compiler, r.id(), decoration, word))
                throw new CgShaderModule.CompileException(label + ": " + name + " has no decoration " + decoration);
            int value = spvc_compiler_get_decoration(compiler, r.id(), decoration);
            long typeHandle = spvc_compiler_get_type_handle(compiler, r.type_id());
            int glType = 0, locations = 1;
            boolean texel = false;
            if (type == SPVC_RESOURCE_TYPE_SAMPLED_IMAGE) {
                int dim = spvc_type_get_image_dimension(typeHandle);
                texel = dim == SpvDimBuffer;
                glType = samplerGlType(dim, spvc_type_get_image_arrayed(typeHandle), spvc_type_get_image_is_depth(typeHandle));
            } else if (type == SPVC_RESOURCE_TYPE_STAGE_INPUT || type == SPVC_RESOURCE_TYPE_STAGE_OUTPUT) {
                glType = glType(typeHandle);
                locations = Math.max(1, spvc_type_get_columns(typeHandle));
            } else if (type == SPVC_RESOURCE_TYPE_UNIFORM_BUFFER && DEFAULT_BLOCK.equals(name)) {
                long struct = spvc_compiler_get_type_handle(compiler, r.base_type_id());
                PointerBuffer size = stack.callocPointer(1);
                check(spvc_compiler_get_declared_struct_size(compiler, struct, size), "struct size");
                defaultBlockSize = (int) size.get(0);
                members(stack, compiler, r.base_type_id(), "", 0);
            }
            out.add(new Resource(name, word.get(0), value, glType, texel, locations));
        }
    }

    /** The default block's leaves, structs flattened to {@code s.m} as GL names them. */
    private void members(MemoryStack stack, long compiler, int structId, String prefix, int base) {
        long struct = spvc_compiler_get_type_handle(compiler, structId);
        IntBuffer v = stack.callocInt(1);
        for (int i = 0; i < spvc_type_get_num_member_types(struct); i++) {
            String name = prefix + spvc_compiler_get_member_name(compiler, structId, i);
            int memberId = spvc_type_get_member_type(struct, i);
            long member = spvc_compiler_get_type_handle(compiler, memberId);
            check(spvc_compiler_type_struct_member_offset(compiler, struct, i, v), "member offset");
            int offset = base + v.get(0);
            boolean array = spvc_type_get_num_array_dimensions(member) > 0;
            if (spvc_type_get_basetype(member) == SPVC_BASETYPE_STRUCT) {
                if (array) throw new CgShaderModule.CompileException(label + ": uniform struct arrays are not carried: " + name);
                members(stack, compiler, memberId, name + ".", offset);
                continue;
            }
            int count = array ? spvc_type_get_array_dimension(member, 0) : 1, stride = 0, matrixStride = 16;
            if (array) {
                check(spvc_compiler_type_struct_member_array_stride(compiler, struct, i, v), "array stride");
                stride = v.get(0);
            }
            int columns = spvc_type_get_columns(member);
            if (columns > 1) {
                check(spvc_compiler_type_struct_member_matrix_stride(compiler, struct, i, v), "matrix stride");
                matrixStride = v.get(0);
            }
            int basetype = spvc_type_get_basetype(member);
            boolean integer = basetype != SPVC_BASETYPE_FP32 && basetype != SPVC_BASETYPE_FP16 && basetype != SPVC_BASETYPE_FP64;
            uniforms.add(new CgGlslCompiler.Uniform(name, glType(member), count, stride, matrixStride, offset, -1,
                    columns, spvc_type_get_vector_size(member), integer));
        }
    }

    private static int glType(long type) {
        int base = spvc_type_get_basetype(type), size = spvc_type_get_vector_size(type), columns = spvc_type_get_columns(type);
        if (base == SPVC_BASETYPE_FP32) {
            if (columns > 1) return columns == size ? new int[] {0, 0, 0x8B5A, 0x8B5B, 0x8B5C}[columns] : 0;
            return new int[] {0, 0x1406, 0x8B50, 0x8B51, 0x8B52}[size];
        }
        if (base == SPVC_BASETYPE_INT32) return new int[] {0, 0x1404, 0x8B53, 0x8B54, 0x8B55}[size];
        if (base == SPVC_BASETYPE_UINT32) return new int[] {0, 0x1405, 0x8DC6, 0x8DC7, 0x8DC8}[size];
        if (base == SPVC_BASETYPE_BOOLEAN) return new int[] {0, 0x8B56, 0x8B57, 0x8B58, 0x8B59}[size];
        return 0;
    }

    private static int samplerGlType(int dim, boolean arrayed, boolean depth) {
        switch (dim) {
            case SpvDim2D:     return arrayed ? (depth ? 0x8DC4 : 0x8DC1) : (depth ? 0x8B62 : 0x8B5E);
            case SpvDim3D:     return 0x8B5F;
            case SpvDimCube:   return depth ? 0x8DC5 : 0x8B60;
            case SpvDimBuffer: return 0x8DC2;
            default: return 0;
        }
    }

    private void check(int result, String what) {
        if (result != SPVC_SUCCESS) throw new CgShaderModule.CompileException(label + ": SPIRV-Cross " + what + " failed (" + result + ")");
    }
}
