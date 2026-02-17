plugins {
    `java-library` // Use java-library for reusable code
}

group = "io.github.somehussar"
version = "1.0.0"

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

dependencies {
    val lwjglVersion = "2.9.3" // Stable, widely mirrored
    implementation("org.lwjgl.lwjgl:lwjgl:$lwjglVersion")
    implementation("org.lwjgl.lwjgl:lwjgl_util:$lwjglVersion")

    runtimeOnly("org.lwjgl.lwjgl:lwjgl-platform:$lwjglVersion:natives-windows")
    runtimeOnly("org.lwjgl.lwjgl:lwjgl-platform:$lwjglVersion:natives-linux")
    runtimeOnly("org.lwjgl.lwjgl:lwjgl-platform:$lwjglVersion:natives-osx")

    compileOnly("org.projectlombok:lombok:1.18.32")
    annotationProcessor("org.projectlombok:lombok:1.18.32")
}

tasks.withType<JavaCompile> {
    options.release.set(8)
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
