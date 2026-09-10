// mc-shared — what every loader variant in the single jar shares, and what none of them may name.
//
// One copy in the merged jar, never relocated and never remapped, because it names no Minecraft
// class and no loader: only Mixin's own API. That is what lets one config plugin decide, for four
// loaders at once, whose mixins may apply.
//
// JAVA 8 SOURCE AND TARGET. Its classes run under FML 1.7.10, whose ModDiscoverer reads every entry
// of every jar with asm-debug-all-5.0.3 and refuses anything above major 52 -- and unlike `core`,
// nothing downgrades this module on the way in.

plugins {
    `java-library`
}

group = providers.gradleProperty("modGroup").orElse("com.crystalgraphics").get()
version = providers.gradleProperty("modVersion").orElse("1.0.0").get()
base { archivesName.set("crystalgraphics-mc-shared") }

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

repositories {
    mavenCentral()
    maven("https://repo.spongepowered.org/repository/maven-public/") { name = "Sponge" }
}

dependencies {
    // The ONLY dependency, and compileOnly: every loader supplies Mixin at runtime, and a second copy
    // in the jar would be a second `MixinService` for the one already running.
    //
    // 0.8.5 rather than the shaded Mixin GTNH's 1.7.10 toolchain puts on `mc1710`'s classpath, whose
    // IMixinConfigPlugin takes `org.spongepowered.asm.lib.tree.ClassNode`. E-J2 measured that
    // UniMixins TRANSFORMS a plugin compiled against vanilla 0.8.5 to fit its environment, so one
    // class serves LaunchWrapper, ModLauncher and Knot alike.
    compileOnly("org.spongepowered:mixin:0.8.5")

    // ASM, because `IMixinConfigPlugin` takes a `ClassNode` in two of its methods and Mixin's own POM
    // does not bring it. compileOnly for the same reason Mixin is: every loader has one, and the
    // version it has is the one that must be used.
    compileOnly("org.ow2.asm:asm-tree:9.10")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}
