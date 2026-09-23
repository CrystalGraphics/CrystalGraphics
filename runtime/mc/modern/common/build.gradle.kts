// The `common` branch: the platform bundle every 1.20.x loader registers, and nothing any one of them
// owns. Built once per Minecraft version the tree targets -- `:runtime:mc:modern:common:<version>` --
// and every loader node compiles against the common node of its own version. The toolchain and every
// pin come from the node (`versions/<version>/gradle.properties`, read by cg-modern-common).

plugins {
    id("cg-modern-common")
}

base { archivesName.set("crystalgraphics-common-${project.name}") }

// freetype JNI bindings — compileOnly here because common's platform service
// implementation references freetype types directly. Each loader's shadowJar bundles
// the actual JAR; common never shades it.
dependencies {
    compileOnly(project(":freetype-msdfgen-harfbuzz-bindings"))
}
