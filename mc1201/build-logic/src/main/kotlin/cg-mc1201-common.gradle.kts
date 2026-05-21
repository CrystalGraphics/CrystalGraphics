plugins { id("cg-java17"); `maven-publish` }

repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases") { name = "NeoForge" }
    maven("https://maven.parchmentmc.org/") { name = "ParchmentMC" }
    maven("https://repo.spongepowered.org/repository/maven-public/") { name = "Sponge" }
}

dependencies {
    // api — platform is part of common's public API surface; loader modules
    // that depend on common will see platform types transitively at compile time.
    "api"(project(":platform"))
    // implementation — core is an internal dependency consumed by common.
    "implementation"(project(":core"))
    // Mixin compileOnly — both loaders bundle it at runtime; never shade it.
    "compileOnly"("org.spongepowered:mixin:${rootProject.properties["mc1201.mixin"]}")
    "annotationProcessor"("org.spongepowered:mixin:${rootProject.properties["mc1201.mixin"]}:processor")
    "compileOnly"("io.github.llamalad7:mixinextras-common:${rootProject.properties["mc1201.mixinextras"]}")
    "annotationProcessor"("io.github.llamalad7:mixinextras-common:${rootProject.properties["mc1201.mixinextras"]}")
}

// Export compiled JAR so loader subprojects can depend on it as a binary
configurations.create("commonOutput") {
    isCanBeConsumed = true; isCanBeResolved = false
}
artifacts { add("commonOutput", tasks.named("jar")) }
