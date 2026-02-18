plugins {
    id("com.gtnewhorizons.gtnhconvention")
    `java-library` // Use java-library for reusable code
}

group = providers.gradleProperty("modGroup").orElse("io.github.somehussar").get()
version = providers.gradleProperty("modVersion").orElse("1.0.0").get()

apply(from = "repositories.gradle")
apply(from = "dependencies.gradle")

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

repositories {
    mavenCentral()
    maven("https://repo.spongepowered.org/repository/maven-public/") {
        name = "Sponge"
    }
    maven("https://libraries.minecraft.net/") {
        name = "Minecraft"
    }
}

tasks.withType<JavaCompile>().configureEach {
    if (JavaVersion.current().isJava9Compatible) {
        options.release.set(8)
    } else {
        sourceCompatibility = JavaVersion.VERSION_1_8.toString()
        targetCompatibility = JavaVersion.VERSION_1_8.toString()
    }
}

val extractNatives = tasks.register<Copy>("extractNatives") {
    val nativeFiles = configurations.runtimeClasspath.get().filter { it.name.contains("lwjgl-platform") }

    nativeFiles.forEach {
        from(zipTree(it))
    }

    into(layout.buildDirectory.dir("natives"))
    exclude("META-INF/**")
}

//tasks.register<JavaExec>("runExample") {
//    group = "verification"
//    mainClass.set("io.github.somehussar.crystalgraphics.util.GraphicsExample")
//    classpath = sourceSets["main"].runtimeClasspath
//    dependsOn(extractNatives)
//    systemProperty("java.library.path", layout.buildDirectory.dir("natives").get().asFile.absolutePath)
//}

tasks.jar {
//    // Exclude the example class from the final jar
//    exclude("io/github/somehussar/crystalgraphics/util/GraphicsExample.class")
//
//    // Also exclude any inner classes or anonymous lambdas generated from it
//    exclude("io/github/somehussar/crystalgraphics/util/GraphicsExample$*.class")

    archiveFileName.set("crystalgraphics-${project.version}.jar")
}
