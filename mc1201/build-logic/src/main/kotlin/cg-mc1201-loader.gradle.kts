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
    "compileOnly"(project(":mc1201:common"))
    "compileOnly"(project(":platform"))
    "compileOnly"(project(":core"))
    // Mixin compileOnly — loaders bundle it at runtime
    "compileOnly"("org.spongepowered:mixin:${rootProject.properties["mc1201.mixin"]}")
    "annotationProcessor"("org.spongepowered:mixin:${rootProject.properties["mc1201.mixin"]}:processor")
    "compileOnly"("io.github.llamalad7:mixinextras-common:${rootProject.properties["mc1201.mixinextras"]}")
}
