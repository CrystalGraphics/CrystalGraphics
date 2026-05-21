plugins { id("cg-java17") }

repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases") { name = "NeoForge" }
    maven("https://maven.parchmentmc.org/") { name = "ParchmentMC" }
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
    maven("https://repo.spongepowered.org/repository/maven-public/") { name = "Sponge" }
    maven("https://maven.minecraftforge.net/") { name = "Forge" }
}

dependencies {
    // compileOnly: shadowJar bundles these manually (see each loader's build.gradle.kts).
    // runtimeOnly: picked up by Fabric/Loom dev runs via Gradle's standard runtimeClasspath.
    // ModDevGradle (Forge/NeoForge) dev runs ignore runtimeClasspath and instead use the
    // mods{} sourceSet declarations in each loader's build.gradle.kts.
    "compileOnly"(project(":mc1201:common"))
    "compileOnly"(project(":platform"))
    "compileOnly"(project(":core"))
    "runtimeOnly"(project(":mc1201:common"))
    "runtimeOnly"(project(":platform"))
    "runtimeOnly"(project(":core"))
    // Mixin compileOnly — loaders bundle it at runtime
    "compileOnly"("org.spongepowered:mixin:${rootProject.properties["mc1201.mixin"]}")
    "annotationProcessor"("org.spongepowered:mixin:${rootProject.properties["mc1201.mixin"]}:processor")
    "compileOnly"("io.github.llamalad7:mixinextras-common:${rootProject.properties["mc1201.mixinextras"]}")
}
