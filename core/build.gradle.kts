plugins { `java-library` }

group = "com.crystalgraphics"
version = rootProject.version.toString()

java {
    // Use Java 8 toolchain to match gtnhconvention's compilation environment.
    // Without this, Gradle uses the daemon JVM (JDK 25), on which Lombok 1.18.32 fails.
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}

repositories {
    maven {
        name = "GTNH Maven"
        url = uri("https://nexus.gtnewhorizons.com/repository/public/")
    }
    maven {
        name = "Minecraft Libraries"
        url = uri("https://libraries.minecraft.net/")
    }
    mavenCentral()
}

dependencies {
    compileOnly(files(rootProject.layout.buildDirectory.dir("classes/java/patchedMc")))
    compileOnly("org.lwjgl.lwjgl:lwjgl:2.9.4-nightly-20150209")
    compileOnly("org.joml:joml-jdk8:1.10.1")
    compileOnly("org.projectlombok:lombok:1.18.32")
    annotationProcessor("org.projectlombok:lombok:1.18.32")
    implementation(project(":platform"))
    implementation("org.apache.logging.log4j:log4j-api:2.20.0")
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<JavaCompile> { options.encoding = "UTF-8" }

// Compile-time guardrail: fail if any MC imports sneak in
tasks.named<JavaCompile>("compileJava") {
    doLast {
        val violations = fileTree("src/main/java").filter { f ->
            f.readText().contains("net.minecraft") ||
            f.readText().contains("cpw.mods.fml") ||
            f.readText().contains("net.minecraftforge")
        }
        if (!violations.isEmpty) error("MC imports found in core/: ${violations.files.map { it.name }}")
    }
}
