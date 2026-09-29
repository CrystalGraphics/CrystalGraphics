package com.crystalgraphics.vulkan;

import com.crystalgraphics.platform.device.CgBindingLayout;
import com.crystalgraphics.platform.device.CgCommandEncoder;
import com.crystalgraphics.platform.device.CgDevice;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.platform.device.CgDeviceObject;
import com.crystalgraphics.platform.device.CgFormat;
import com.crystalgraphics.platform.device.CgGpuBuffer;
import com.crystalgraphics.platform.device.CgGpuSampler;
import com.crystalgraphics.platform.device.CgGpuTexture;
import com.crystalgraphics.platform.device.CgPipeline;
import com.crystalgraphics.platform.device.CgPipelineDesc;
import com.crystalgraphics.platform.device.CgShaderModule;
import com.crystalgraphics.platform.device.CgTimerQuery;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferViewCreateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceLimits;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDevicePushDescriptorPropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfoKHR;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkStencilOpState;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.crystalgraphics.vulkan.VulkanCheck.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memByteBuffer;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK11.VK_FORMAT_FEATURE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;

/**
 * {@code CgDevice} over Vulkan 1.2 with dynamic rendering and push descriptors — what the tracked backend draws
 * through when {@code CgGL} runs on Vulkan (plan/device-vulkan.md). Everything from outside comes through its
 * {@link CgVulkanHost}.
 *
 * <pre>{@code
 * CgVulkanDevice device = new CgVulkanDevice(host, width, height);
 * CgTrackedGLBackend gl = new CgTrackedGLBackend(device, new ShadercGlslCompiler(), debug);
 * CgGL.init(gl);
 * ... the engine draws through CgGL ...
 * gl.endFrame();                  // the host submits and presents
 * device.resize(w, h);            // the window's framebuffer changed
 * device.close();                 // before the host closes
 * }</pre>
 *
 * <p>The default framebuffer is an image of the device's own, RGBA8 with depth-stencil, which the host presents;
 * its row 0 is GL's bottom. Owner thread only.</p>
 */
public final class CgVulkanDevice implements CgDevice, AutoCloseable {

    private final CgVulkanHost host;
    private final VkDevice device;
    private final long vma;
    private final long pipelineCache;
    private final VulkanFormats formats;
    private final CgDeviceInfo info;
    private final float timestampPeriod;
    private final int maxPushDescriptors;
    private final Thread owner = Thread.currentThread();
    private final Map<CgGpuSampler.Desc, VulkanSampler> samplers = new HashMap<>();
    private final Set<Object> live = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<VulkanBuffer, Map<Long, Long>> texelViews = new IdentityHashMap<>();
    private final VulkanStaging staging;
    private final VulkanEncoder encoder;
    private VulkanTexture surfaceColor, surfaceDepth;
    private VulkanBuffer scratch;
    int barriers;

    public CgVulkanDevice(CgVulkanHost host, int width, int height) {
        this.host = host;
        this.device = host.device();
        this.formats = new VulkanFormats(host.physicalDevice());
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pp = stack.mallocPointer(1);
            VmaAllocatorCreateInfo ci = VmaAllocatorCreateInfo.calloc(stack)
                    .physicalDevice(host.physicalDevice()).device(device).instance(host.instance())
                    .vulkanApiVersion(host.apiVersion())
                    .pVulkanFunctions(VmaVulkanFunctions.calloc(stack).set(host.instance(), device));
            check(vmaCreateAllocator(ci, pp), "vmaCreateAllocator");
            vma = pp.get(0);

            LongBuffer lp = stack.mallocLong(1);
            check(vkCreatePipelineCache(device, VkPipelineCacheCreateInfo.calloc(stack).sType$Default(), null, lp),
                    "vkCreatePipelineCache");
            pipelineCache = lp.get(0);

            VkPhysicalDevicePushDescriptorPropertiesKHR push = VkPhysicalDevicePushDescriptorPropertiesKHR.calloc(stack)
                    .sType$Default();
            VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default()
                    .pNext(push.address());
            vkGetPhysicalDeviceProperties2(host.physicalDevice(), props2);
            maxPushDescriptors = push.maxPushDescriptors();
            VkPhysicalDeviceProperties props = props2.properties();
            timestampPeriod = props.limits().timestampPeriod();
            info = info(props);
        }
        staging = new VulkanStaging(this);
        encoder = new VulkanEncoder(this);
        resize(width, height);
    }

    private static CgDeviceInfo info(VkPhysicalDeviceProperties props) {
        VkPhysicalDeviceLimits l = props.limits();
        int samples = Integer.highestOneBit(l.framebufferColorSampleCounts() & l.framebufferDepthSampleCounts());
        // Binding counts at GL's scale: the tracker sizes its tables by them, and Vulkan's are in the millions.
        CgDeviceInfo.Limits limits = new CgDeviceInfo.Limits(l.maxImageDimension2D(), l.maxImageDimension3D(),
                l.maxImageArrayLayers(), l.maxColorAttachments(), samples, Math.min(16, l.maxVertexInputAttributes()),
                Math.min(32, l.maxPerStageDescriptorSamplers()), l.maxUniformBufferRange(),
                Math.min(24, l.maxPerStageDescriptorUniformBuffers()), Math.min(16, l.maxPerStageDescriptorStorageBuffers()),
                l.maxTexelBufferElements(), (int) l.minUniformBufferOffsetAlignment(),
                (int) l.minStorageBufferOffsetAlignment(), (int) l.minTexelBufferOffsetAlignment(),
                l.maxViewportDimensions(0), l.maxSamplerAnisotropy());
        int v = props.driverVersion();
        return new CgDeviceInfo(props.deviceNameString(), "vendor 0x" + Integer.toHexString(props.vendorID()),
                (v >>> 22) + "." + ((v >>> 12) & 0x3FF) + "." + (v & 0xFFF), limits,
                l.timestampComputeAndGraphics(), l.maxSamplerAnisotropy() > 1f, true);
    }

    // ── what the encoder and pass reach ────────────────────────────────────────

    VkDevice vk() { return device; }
    CgVulkanHost host() { return host; }
    VulkanFormats formats() { return formats; }
    VulkanStaging staging() { return staging; }
    float timestampPeriod() { return timestampPeriod; }

    /** A host-visible buffer for {@link VulkanStaging}, outside the object count a caller sees. */
    VulkanBuffer stagingBuffer(long size) {
        return (VulkanBuffer) createBuffer(new CgGpuBuffer.Desc("staging", size,
                EnumSet.of(CgGpuBuffer.Usage.COPY_SRC, CgGpuBuffer.Usage.COPY_DST), true));
    }

    /** Device-local memory a copy between images passes through; grown, and the old one released. */
    VulkanBuffer copyScratch(long size) {
        if (scratch == null || scratch.size() < size) {
            if (scratch != null) release(scratch);
            scratch = (VulkanBuffer) createBuffer(new CgGpuBuffer.Desc("copy scratch", size,
                    EnumSet.of(CgGpuBuffer.Usage.COPY_SRC, CgGpuBuffer.Usage.COPY_DST), false));
        }
        return scratch;
    }

    /** A {@code VkBufferView} over a range, kept until the buffer goes. */
    long texelView(VulkanBuffer buffer, long offset, long size, CgFormat format) {
        Map<Long, Long> views = texelViews.computeIfAbsent(buffer, b -> new HashMap<>());
        long key = offset * 31 + size * 7 + format.ordinal();
        Long view = views.get(key);
        if (view != null) return view;
        try (MemoryStack stack = stackPush()) {
            LongBuffer lp = stack.mallocLong(1);
            check(vkCreateBufferView(device, VkBufferViewCreateInfo.calloc(stack).sType$Default().buffer(buffer.buffer)
                    .format(formats.vk(format)).offset(offset).range(size), null, lp), "vkCreateBufferView");
            views.put(key, lp.get(0));
            return lp.get(0);
        }
    }

    // ── CgDevice: objects ──────────────────────────────────────────────────────

    @Override public CgDeviceInfo info() { return info; }

    @Override public boolean ownedByCurrentThread() { return Thread.currentThread() == owner; }

    @Override
    public boolean supports(CgFormat format, CgGpuTexture.Usage usage) {
        int f = formats.features(formats.vk(format));
        switch (usage) {
            case SAMPLED: return (f & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT) != 0;
            case ATTACHMENT: return (f & (format.aspect() == CgFormat.Aspect.COLOR ? VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT
                    : VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT)) != 0;
            default: return (f & (VK_FORMAT_FEATURE_TRANSFER_SRC_BIT | VK_FORMAT_FEATURE_TRANSFER_DST_BIT)) != 0;
        }
    }

    @Override
    public CgGpuBuffer createBuffer(CgGpuBuffer.Desc desc) {
        try (MemoryStack stack = stackPush()) {
            int usage = 0;
            for (CgGpuBuffer.Usage u : desc.usage()) {
                switch (u) {
                    case VERTEX: usage |= VK_BUFFER_USAGE_VERTEX_BUFFER_BIT; break;
                    case INDEX: usage |= VK_BUFFER_USAGE_INDEX_BUFFER_BIT; break;
                    case UNIFORM: usage |= VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT; break;
                    case STORAGE: usage |= VK_BUFFER_USAGE_STORAGE_BUFFER_BIT; break;
                    case TEXEL: usage |= VK_BUFFER_USAGE_UNIFORM_TEXEL_BUFFER_BIT; break;
                    case COPY_SRC: usage |= VK_BUFFER_USAGE_TRANSFER_SRC_BIT; break;
                    case COPY_DST: usage |= VK_BUFFER_USAGE_TRANSFER_DST_BIT; break;
                }
            }
            long size = Math.max(4, desc.size());
            VkBufferCreateInfo bci = VkBufferCreateInfo.calloc(stack).sType$Default().size(size).usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo aci = VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_AUTO);
            if (desc.hostVisible()) {
                aci.flags(VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT)
                        .requiredFlags(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            }
            LongBuffer lp = stack.mallocLong(1);
            PointerBuffer pp = stack.mallocPointer(1);
            VmaAllocationInfo ai = VmaAllocationInfo.malloc(stack);
            check(vmaCreateBuffer(vma, bci, aci, lp, pp, ai), "vmaCreateBuffer " + desc.label());
            ByteBuffer mapped = desc.hostVisible()
                    ? memByteBuffer(ai.pMappedData(), (int) desc.size()).order(ByteOrder.nativeOrder()) : null;
            VulkanBuffer b = new VulkanBuffer(desc, lp.get(0), pp.get(0), mapped);
            live.add(b);
            return b;
        }
    }

    @Override
    public CgGpuTexture createTexture(CgGpuTexture.Desc desc) {
        int format = formats.vk(desc.format());
        int aspect = formats.aspectOf(desc.format());
        boolean depth = (aspect & VK_IMAGE_ASPECT_COLOR_BIT) == 0;
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        if (desc.usage().contains(CgGpuTexture.Usage.SAMPLED)) usage |= VK_IMAGE_USAGE_SAMPLED_BIT;
        if (desc.usage().contains(CgGpuTexture.Usage.ATTACHMENT))
            usage |= depth ? VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT : VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        boolean volume = desc.kind() == CgGpuTexture.Kind.D3;
        try (MemoryStack stack = stackPush()) {
            VkImageCreateInfo ici = VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(volume ? VK_IMAGE_TYPE_3D : VK_IMAGE_TYPE_2D).format(format)
                    .mipLevels(desc.mips()).arrayLayers(volume ? 1 : desc.depthOrLayers()).samples(desc.samples())
                    .tiling(VK_IMAGE_TILING_OPTIMAL).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .flags(desc.kind() == CgGpuTexture.Kind.CUBE ? VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT : 0);
            ici.extent().set(desc.width(), desc.height(), volume ? desc.depthOrLayers() : 1);
            VmaAllocationCreateInfo aci = VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            LongBuffer lp = stack.mallocLong(1);
            PointerBuffer pp = stack.mallocPointer(1);
            check(vmaCreateImage(vma, ici, aci, lp, pp, null), "vmaCreateImage " + desc.label());
            VulkanTexture t = new VulkanTexture(desc, lp.get(0), pp.get(0), format, aspect);
            live.add(t);
            // Its first layout goes ahead of the frame, since a pass may be open in it now.
            barriers += t.transitionAll(host.setupCommandBuffer(), t.resting, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
            return t;
        }
    }

    @Override
    public CgGpuSampler createSampler(CgGpuSampler.Desc desc) {
        VulkanSampler cached = samplers.get(desc);
        if (cached != null) return cached;
        try (MemoryStack stack = stackPush()) {
            boolean mipped = desc.mip() != CgGpuSampler.MipFilter.NONE;
            float anisotropy = Math.min(desc.maxAnisotropy(), info.limits().maxAnisotropy());
            VkSamplerCreateInfo ci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VulkanFormats.filter(desc.mag())).minFilter(VulkanFormats.filter(desc.min()))
                    .mipmapMode(desc.mip() == CgGpuSampler.MipFilter.LINEAR ? VK_SAMPLER_MIPMAP_MODE_LINEAR
                            : VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VulkanFormats.wrap(desc.u())).addressModeV(VulkanFormats.wrap(desc.v()))
                    .addressModeW(VulkanFormats.wrap(desc.w()))
                    .anisotropyEnable(anisotropy > 1f).maxAnisotropy(Math.max(1f, anisotropy))
                    .compareEnable(desc.compare() != null)
                    .compareOp(desc.compare() == null ? VK_COMPARE_OP_ALWAYS : VulkanFormats.compare(desc.compare()))
                    // A texture without mips samples its base level: Vulkan's spelling of GL's non-mip filters.
                    .minLod(mipped ? Math.max(0f, desc.minLod()) : 0f)
                    .maxLod(mipped ? Math.min(desc.maxLod(), VK_LOD_CLAMP_NONE) : 0.25f)
                    .borderColor(VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK);
            LongBuffer lp = stack.mallocLong(1);
            check(vkCreateSampler(device, ci, null, lp), "vkCreateSampler");
            VulkanSampler s = new VulkanSampler(desc, lp.get(0));
            samplers.put(desc, s);
            return s;
        }
    }

    @Override
    public CgShaderModule createShaderModule(CgShaderModule.Stage stage, ByteBuffer spirv, String label) {
        try (MemoryStack stack = stackPush()) {
            LongBuffer lp = stack.mallocLong(1);
            check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv),
                    null, lp), "vkCreateShaderModule " + label);
            VulkanShaderModule m = new VulkanShaderModule(stage, lp.get(0), label);
            live.add(m);
            return m;
        }
    }

    @Override
    public CgBindingLayout createBindingLayout(String label, List<CgBindingLayout.Slot> slots) {
        if (slots.size() > maxPushDescriptors)
            throw new UnsupportedOperationException(label + " has " + slots.size() + " bindings; a push-descriptor set "
                    + "holds " + maxPushDescriptors);
        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(slots.size(), stack);
            for (int i = 0; i < slots.size(); i++) {
                bindings.get(i).binding(slots.get(i).binding()).descriptorType(descriptorType(slots.get(i).type()))
                        .descriptorCount(1).stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
            }
            LongBuffer lp = stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
                    .flags(VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR).pBindings(bindings), null, lp),
                    "vkCreateDescriptorSetLayout " + label);
            long setLayout = lp.get(0);
            check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(setLayout)), null, lp), "vkCreatePipelineLayout " + label);
            VulkanBindingLayout l = new VulkanBindingLayout(label, List.copyOf(slots), setLayout, lp.get(0));
            live.add(l);
            return l;
        }
    }

    static int descriptorType(CgBindingLayout.Type type) {
        switch (type) {
            case UNIFORM_BUFFER: return VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
            case STORAGE_BUFFER: return VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            case TEXEL_BUFFER: return VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER;
            default: return VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        }
    }

    @Override
    public CgPipeline createPipeline(CgPipelineDesc d) {
        try (MemoryStack stack = stackPush()) {
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT)
                    .module(((VulkanShaderModule) d.vertex()).module).pName(stack.UTF8("main"));
            stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT)
                    .module(((VulkanShaderModule) d.fragment()).module).pName(stack.UTF8("main"));

            int attribCount = 0;
            for (CgPipelineDesc.VertexBuffer vb : d.vertexBuffers()) attribCount += vb.attribs().size();
            VkVertexInputBindingDescription.Buffer vbs = VkVertexInputBindingDescription.calloc(d.vertexBuffers().size(), stack);
            VkVertexInputAttributeDescription.Buffer attrs = VkVertexInputAttributeDescription.calloc(attribCount, stack);
            int a = 0;
            for (int i = 0; i < d.vertexBuffers().size(); i++) {
                CgPipelineDesc.VertexBuffer vb = d.vertexBuffers().get(i);
                vbs.get(i).binding(vb.binding()).stride(vb.stride())
                        .inputRate(vb.perInstance() ? VK_VERTEX_INPUT_RATE_INSTANCE : VK_VERTEX_INPUT_RATE_VERTEX);
                for (CgPipelineDesc.VertexAttrib at : vb.attribs()) {
                    attrs.get(a++).location(at.location()).binding(vb.binding())
                            .format(VulkanFormats.attrib(at.format())).offset(at.offset());
                }
            }
            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType$Default().pVertexBindingDescriptions(vbs).pVertexAttributeDescriptions(attrs);
            VkPipelineInputAssemblyStateCreateInfo assembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType$Default().topology(VulkanFormats.topology(d.topology()));
            VkPipelineViewportStateCreateInfo viewport = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default()
                    .viewportCount(1).scissorCount(1);
            CgPipelineDesc.Raster r = d.raster();
            VkPipelineRasterizationStateCreateInfo raster = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType$Default().polygonMode(VulkanFormats.polygonMode(r.polygonMode()))
                    .cullMode(VulkanFormats.cull(r.cull()))
                    // Inverted, and on purpose: Vulkan's winding area carries the opposite sign to GL's, so under
                    // the positive viewport that keeps GL's rows the same triangle winds the other way.
                    .frontFace(r.frontFace() == CgPipelineDesc.FrontFace.CCW ? VK_FRONT_FACE_CLOCKWISE
                            : VK_FRONT_FACE_COUNTER_CLOCKWISE)
                    .depthBiasEnable(r.depthBias()).lineWidth(1f);
            VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType$Default().rasterizationSamples(d.samples());

            CgPipelineDesc.DepthStencil ds = d.depthStencil();
            VkPipelineDepthStencilStateCreateInfo depth = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType$Default().depthTestEnable(ds.depthTest()).depthWriteEnable(ds.depthWrite())
                    .depthCompareOp(VulkanFormats.compare(ds.depthCompare())).stencilTestEnable(ds.stencilTest());
            stencil(depth.front(), ds.front(), ds);
            stencil(depth.back(), ds.back(), ds);

            VkPipelineColorBlendAttachmentState.Buffer blends =
                    VkPipelineColorBlendAttachmentState.calloc(d.colorTargets().size(), stack);
            IntBuffer colorFormats = stack.mallocInt(d.colorTargets().size());
            for (int i = 0; i < d.colorTargets().size(); i++) {
                CgPipelineDesc.ColorTarget t = d.colorTargets().get(i);
                colorFormats.put(i, formats.vk(t.format()));
                VkPipelineColorBlendAttachmentState s = blends.get(i).colorWriteMask(t.writeMask());
                if (t.blend() != null) {
                    s.blendEnable(true)
                            .srcColorBlendFactor(VulkanFormats.blendFactor(t.blend().srcColor()))
                            .dstColorBlendFactor(VulkanFormats.blendFactor(t.blend().dstColor()))
                            .colorBlendOp(VulkanFormats.blendOp(t.blend().colorOp()))
                            .srcAlphaBlendFactor(VulkanFormats.blendFactor(t.blend().srcAlpha()))
                            .dstAlphaBlendFactor(VulkanFormats.blendFactor(t.blend().dstAlpha()))
                            .alphaBlendOp(VulkanFormats.blendOp(t.blend().alphaOp()));
                }
            }
            VkPipelineColorBlendStateCreateInfo blend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType$Default().pAttachments(blends);
            // Only what the pipeline uses: a declared dynamic state must be set before every draw with it.
            IntBuffer states = stack.mallocInt(4).put(VK_DYNAMIC_STATE_VIEWPORT).put(VK_DYNAMIC_STATE_SCISSOR);
            if (r.depthBias()) states.put(VK_DYNAMIC_STATE_DEPTH_BIAS);
            if (ds.stencilTest()) states.put(VK_DYNAMIC_STATE_STENCIL_REFERENCE);
            VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                    .pDynamicStates(states.flip());

            int depthFormat = d.depthFormat() == null ? VK_FORMAT_UNDEFINED : formats.vk(d.depthFormat());
            int aspect = d.depthFormat() == null ? 0 : formats.aspectOf(d.depthFormat());
            VkPipelineRenderingCreateInfoKHR rendering = VkPipelineRenderingCreateInfoKHR.calloc(stack).sType$Default()
                    .colorAttachmentCount(d.colorTargets().size()).pColorAttachmentFormats(colorFormats)
                    .depthAttachmentFormat((aspect & VK_IMAGE_ASPECT_DEPTH_BIT) != 0 ? depthFormat : VK_FORMAT_UNDEFINED)
                    .stencilAttachmentFormat((aspect & VK_IMAGE_ASPECT_STENCIL_BIT) != 0 ? depthFormat : VK_FORMAT_UNDEFINED);

            VkGraphicsPipelineCreateInfo.Buffer ci = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            ci.get(0).sType$Default().pNext(rendering.address()).pStages(stages).pVertexInputState(vertexInput)
                    .pInputAssemblyState(assembly).pViewportState(viewport).pRasterizationState(raster)
                    .pMultisampleState(multisample).pDepthStencilState(depth).pColorBlendState(blend)
                    .pDynamicState(dynamic).layout(((VulkanBindingLayout) d.layout()).pipelineLayout);
            LongBuffer lp = stack.mallocLong(1);
            check(vkCreateGraphicsPipelines(device, pipelineCache, ci, null, lp), "vkCreateGraphicsPipelines " + d.label());
            VulkanPipeline p = new VulkanPipeline(d, lp.get(0));
            live.add(p);
            return p;
        }
    }

    private static void stencil(VkStencilOpState s, CgPipelineDesc.StencilFace f, CgPipelineDesc.DepthStencil ds) {
        s.failOp(VulkanFormats.stencilOp(f.fail())).passOp(VulkanFormats.stencilOp(f.pass()))
                .depthFailOp(VulkanFormats.stencilOp(f.depthFail())).compareOp(VulkanFormats.compare(f.compare()))
                .compareMask(ds.readMask()).writeMask(ds.writeMask()).reference(0);
    }

    @Override
    public CgTimerQuery createTimerQuery(String label) {
        try (MemoryStack stack = stackPush()) {
            LongBuffer lp = stack.mallocLong(1);
            check(vkCreateQueryPool(device, VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                    .queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(2), null, lp), "vkCreateQueryPool");
            VulkanTimerQuery q = new VulkanTimerQuery(this, lp.get(0), label);
            live.add(q);
            return q;
        }
    }

    @Override
    public void release(CgDeviceObject object) {
        if (object instanceof VulkanSampler) return;                   // cached by description, kept
        host.whenFrameRetired(host.frameIndex(), () -> destroy(object));
    }

    /** Destroys at once: the object is known to be out of every frame in flight. Idempotent. */
    void destroy(Object o) {
        if (!live.remove(o)) return;
        if (o instanceof VulkanBuffer b) {
            Map<Long, Long> views = texelViews.remove(b);
            if (views != null) for (long v : views.values()) vkDestroyBufferView(device, v, null);
            vmaDestroyBuffer(vma, b.buffer, b.allocation);
        } else if (o instanceof VulkanTexture t) {
            t.destroyViews(device);
            vmaDestroyImage(vma, t.image, t.allocation);
        } else if (o instanceof VulkanShaderModule m) {
            vkDestroyShaderModule(device, m.module, null);
        } else if (o instanceof VulkanBindingLayout l) {
            vkDestroyPipelineLayout(device, l.pipelineLayout, null);
            vkDestroyDescriptorSetLayout(device, l.setLayout, null);
        } else if (o instanceof VulkanPipeline p) {
            vkDestroyPipeline(device, p.pipeline, null);
        } else if (o instanceof VulkanTimerQuery q) {
            vkDestroyQueryPool(device, q.pool, null);
        }
    }

    // ── CgDevice: commands and frames ──────────────────────────────────────────

    @Override public CgCommandEncoder encoder() { return encoder; }
    @Override public CgGpuTexture surfaceColor() { return surfaceColor; }
    @Override public CgGpuTexture surfaceDepth() { return surfaceDepth; }
    @Override public long frameIndex() { return host.frameIndex(); }
    @Override public long retiredFrame() { return host.retiredFrame(); }
    @Override public void whenRetired(long frame, Runnable action) { host.whenFrameRetired(frame, action); }
    @Override public boolean ownsSubmission() { return host.ownsSubmission(); }

    @Override
    public void endFrame() {
        if (encoder.openPass() != null) throw new IllegalStateException("endFrame with a pass open");
        barriers += surfaceColor.transitionAll(host.commandBuffer(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        long frame = host.frameIndex();
        host.endFrame(new CgVulkanImage(surfaceColor.image, surfaceColor.format,
                surfaceColor.desc.width(), surfaceColor.desc.height()));
        staging.endFrame(frame);
    }

    @Override
    public void waitRetired(long frame) {
        if (!host.ownsSubmission()) throw new IllegalStateException("A hosted device cannot wait for frame " + frame);
        if (frame >= host.frameIndex()) endFrame();
        host.waitRetired(frame);
    }

    /** A new default framebuffer at the window's size; the old images go once the frames using them retire. */
    public void resize(int width, int height) {
        if (surfaceColor != null && surfaceColor.desc.width() == width && surfaceColor.desc.height() == height) return;
        if (surfaceColor != null) release(surfaceColor);
        if (surfaceDepth != null) release(surfaceDepth);
        Set<CgGpuTexture.Usage> usage = EnumSet.of(CgGpuTexture.Usage.ATTACHMENT,
                CgGpuTexture.Usage.COPY_SRC, CgGpuTexture.Usage.COPY_DST);
        surfaceColor = (VulkanTexture) createTexture(new CgGpuTexture.Desc("surface", CgGpuTexture.Kind.D2,
                CgFormat.RGBA8_UNORM, width, height, 1, 1, 1, usage));
        surfaceDepth = (VulkanTexture) createTexture(new CgGpuTexture.Desc("surfaceDepth", CgGpuTexture.Kind.D2,
                CgFormat.DEPTH24_PLUS_STENCIL8, width, height, 1, 1, 1, usage));
    }

    /** Barriers recorded since the device began, for {@code CgDeviceStats}. */
    public int barriers() { return barriers; }

    /** Validation errors the host has seen. */
    public int validationErrors() { return host.validationErrors(); }

    /** Waits for the GPU, then destroys everything still alive. Close the host after. */
    @Override
    public void close() {
        vkDeviceWaitIdle(device);
        staging.destroy();
        for (Object o : new ArrayList<>(live)) destroy(o);
        for (VulkanSampler s : samplers.values()) vkDestroySampler(device, s.sampler, null);
        samplers.clear();
        vkDestroyPipelineCache(device, pipelineCache, null);
        vmaDestroyAllocator(vma);
    }
}
