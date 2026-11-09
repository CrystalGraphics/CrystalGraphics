package com.crystalgraphics.vulkan.toolchain;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Manifest;

import com.crystalgraphics.platform.gl.pipeline.GLNativeTranslator;
import com.crystalgraphics.platform.gl.pipeline.GLShaderTranslator;
import com.crystalgraphics.platform.service.CgCacheDirectory;
import com.crystalgraphics.platform.rhi.shader.CgGlslCompiler;
import com.crystalgraphics.vulkan.shader.ShadercGlslCompiler;
import com.crystalgraphics.vulkan.shader.SpvcGlslTranslator;

/**
 * The shader toolchain, shaderc and SPIRV-Cross on LWJGL 3.4, loaded apart from the host's own LWJGL, whatever version
 * that is, LWJGL 2 included: what a GL-era host's device compiles with once it is on disk. The jars come from
 * {@code CgShaderToolchainFiles.fetch}.
 *
 * <pre>{@code
 * CgShaderToolchain toolchain = CgShaderToolchain.load(jars, CgCacheDirectory.of("shader-toolchain").resolve("natives"));
 * toolchain.installOver(translator);   // the GLNativeTranslator the device was made with
 * }</pre>
 *
 * <ul>
 *   <li>Its LWJGL, the compiler's package and their resources (the natives among them) come from its own loader first;
 *       everything else, the platform interfaces the two halves are answered as, from the host's. Each LWJGL extracts
 *       and loads its own natives, under its own version's folder.</li>
 *   <li>{@link #compiler} and {@link #translator} load the natives: a platform whose natives will not load throws
 *       there, a {@link LinkageError} or the native's own failure.</li>
 *   <li>One per process is enough; a second loads the classes again.</li>
 * </ul>
 */
public final class CgShaderToolchain {

    /** The package defined again in the toolchain's loader, from the host's bytes: it names LWJGL 3.4 directly. */
    private static final String COMPILERS = ShadercGlslCompiler.class.getPackage().getName() + ".";
    private static final String LWJGL = "org.lwjgl.";

    private final ClassLoader loader;

    private CgShaderToolchain(ClassLoader loader) {
        this.loader = loader;
    }

    /**
     * The toolchain over {@code jars}: LWJGL core, shaderc and spvc 3.4, each with this platform's natives, which it
     * extracts into {@code natives} and loads from there alone. A folder of its own: a host on the same LWJGL version
     * extracts to the same versioned folder, and a library loaded by the host's loader cannot load in this one.
     */
    public static CgShaderToolchain load(List<Path> jars, Path natives) {
        if (jars.isEmpty()) throw new IllegalArgumentException("no shader toolchain jars");
        URL[] urls = new URL[jars.size()];
        try {
            for (int i = 0; i < urls.length; i++) urls[i] = jars.get(i).toUri().toURL();
        } catch (MalformedURLException e) {
            throw new UncheckedIOException(e);
        }
        CgShaderToolchain toolchain = new CgShaderToolchain(new Isolated(urls, CgShaderToolchain.class.getClassLoader()));
        toolchain.configure("SHARED_LIBRARY_EXTRACT_PATH", natives.toAbsolutePath().toString());
        toolchain.configure("LIBRARY_PATH", natives.toAbsolutePath().toString());
        return toolchain;
    }

    /** Sets one of its own LWJGL's {@code Configuration} options, before anything of it loads a native. */
    private void configure(String option, String value) {
        try {
            Object setting = Class.forName("org.lwjgl.system.Configuration", true, loader).getField(option).get(null);
            setting.getClass().getMethod("set", Object.class).invoke(setting, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("the shader toolchain's LWJGL has no Configuration." + option, e);
        }
    }

    /** shaderc as the process's GLSL compiler; {@code cacheDir} keeps its modules across launches, null for none. */
    public CgGlslCompiler compiler(Path cacheDir) {
        return make(ShadercGlslCompiler.class.getName(), CgGlslCompiler.class, new Class<?>[] {Path.class}, cacheDir);
    }

    /**
     * A GL device started on the driver's own compiler takes this toolchain from now on: SPIR-V through
     * {@code translator}, and shaderc as the process's compiler, its modules kept in the engine's cache. What the
     * driver compiled before stays. Render thread.
     */
    public void installOver(GLNativeTranslator translator) {
        translator.spirv(translator());
        CgGlslCompiler.install(compiler(CgCacheDirectory.of("spirv")));
    }

    /** SPIRV-Cross as a GL device's translator, SPIR-V back to GLSL 4.60. */
    public GLShaderTranslator translator() {
        return make(SpvcGlslTranslator.class.getName(), GLShaderTranslator.class, new Class<?>[0]);
    }

    private <T> T make(String className, Class<T> as, Class<?>[] parameters, Object... arguments) {
        try {
            Class<?> type = Class.forName(className, true, loader);
            return as.cast(type.getConstructor(parameters).newInstance(arguments));
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException r) throw r;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(className + " failed to start", cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(className + " is not in the shader toolchain", e);
        }
    }

    /**
     * Child-first for LWJGL and the compilers' package, parent-first for everything else. The compilers' classes are
     * defined here from the parent's bytes, so the LWJGL they name is this loader's.
     */
    private static final class Isolated extends URLClassLoader {

        static {
            registerAsParallelCapable();
        }

        /** Java 8's getPackage asks the parent too, and LWJGL 2 seals {@code org.lwjgl}: the packages defined here. */
        private final Map<String, Package> own = Collections.synchronizedMap(new HashMap<>());

        Isolated(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        private static boolean ours(String name) {
            return name.startsWith(LWJGL) || name.startsWith(COMPILERS);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) c = ours(name) ? findClass(name) : getParent().loadClass(name);
                if (resolve) resolveClass(c);
                return c;
            }
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!name.startsWith(COMPILERS)) return super.findClass(name);
            String path = name.replace('.', '/') + ".class";
            try (InputStream in = getParent().getResourceAsStream(path)) {
                if (in == null) throw new ClassNotFoundException(name);
                byte[] bytes = readAll(in);
                String pkg = name.substring(0, name.lastIndexOf('.'));
                if (own.get(pkg) == null) {
                    try {
                        definePackage(pkg, null, null, null, null, null, null, null);
                    } catch (IllegalArgumentException definedMeanwhile) {
                        // another thread's class of the same package defined it first
                    }
                }
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }

        private static byte[] readAll(InputStream in) throws IOException {
            byte[] buffer = new byte[8192];
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            return out.toByteArray();
        }

        @Override
        protected Package definePackage(String name, Manifest man, URL url) {
            Package p = super.definePackage(name, man, url);
            own.put(name, p);
            return p;
        }

        @Override
        protected Package definePackage(String name, String specTitle, String specVersion, String specVendor,
                                        String implTitle, String implVersion, String implVendor, URL sealBase) {
            Package p = super.definePackage(name, specTitle, specVersion, specVendor, implTitle, implVersion,
                    implVendor, sealBase);
            own.put(name, p);
            return p;
        }

        @Override
        @SuppressWarnings("deprecation")
        protected Package getPackage(String name) {
            return ours(name + ".") ? own.get(name) : super.getPackage(name);
        }

        /** Its own resources first: LWJGL finds its natives through its loader's resources. */
        @Override
        public URL getResource(String name) {
            URL own = findResource(name);
            return own != null ? own : getParent().getResource(name);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            List<URL> all = Collections.list(findResources(name));
            for (URL url : Collections.list(getParent().getResources(name))) if (!all.contains(url)) all.add(url);
            return Collections.enumeration(all);
        }
    }
}
