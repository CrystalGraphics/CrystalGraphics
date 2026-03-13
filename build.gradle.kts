plugins {
    id("com.gtnewhorizons.gtnhconvention")
    `java-library`
}

group = providers.gradleProperty("modGroup").orElse("io.github.somehussar").get()
version = providers.gradleProperty("modVersion").orElse("1.0.0").get()

apply(from = "repositories.gradle")
apply(from = "dependencies.gradle")

// Remove Kotlin and a Java 9 file from JOML when shadowing.
// Gradle pulls in a transitive dependency (Kotlin) and packages it for some reason.
// This is for distributing the jar.
// JOML is compiled for Java 8, so the fact I have to do this is stupid asf.
// - Hussar
// Sidenote, using the `-jdk8` published version, it might no longer try to pull that shit anymore but keeping it still
tasks.shadowJar {
    dependencies {
        exclude(dependency("org.jetbrains.kotlin:.*"))
    }

    exclude("module-info.class")
    exclude("kotlin/**")
    exclude("org/jetbrains/kotlin/**")

    // Include JNI binding subproject JARs (unpacked) in the shadow JAR.
    // These are implementation deps but cannot use shadowImplementation because the GTNH
    // convention plugin requires RFG obfuscation variant attributes that plain Java
    // subprojects don't publish (causes variant ambiguity errors).
    dependsOn(":msdfgen-java-bindings:jar", ":freetype-harfbuzz-java-bindings:jar")
}

// Resolve subproject jar outputs after evaluation (subproject tasks don't exist during
// root project configuration). Unpacks both JARs into the shadow JAR.
afterEvaluate {
    tasks.shadowJar.configure {
        val msdfgenJar = project(":msdfgen-java-bindings").tasks.named<Jar>("jar").get()
        val freetypeJar = project(":freetype-harfbuzz-java-bindings").tasks.named<Jar>("jar").get()

        from(zipTree(msdfgenJar.archiveFile.get()))
        from(zipTree(freetypeJar.archiveFile.get()))
    }
}

fun findJarBySubstring(part: String): File {
    val matches = configurations.runtimeClasspath.get().files.filter {
        it.name.contains(part)
    }

    if (matches.isEmpty()) {
        throw GradleException("Cannot find JAR containing '$part' in runtimeClasspath")
    }

    return matches.first()
}

tasks.named<JavaExec>("runClient") {
    doFirst {
        val agent = findJarBySubstring("unimixins")
        jvmArgs("-javaagent:${agent.absolutePath}")

        val hotswapAgent = findJarBySubstring("hotswap-agent")
        jvmArgs("-javaagent:${hotswapAgent.absolutePath}")
    }
}

// Ensure JNI native libraries from subprojects are loadable during tests.
// Strategy 2 (classpath extraction) works because subproject resources are on the
// testRuntimeClasspath via implementation(project(...)). Strategy 3 (java.library.path)
// is configured here as a fallback pointing to the platform-specific native directories.
tasks.withType<Test> {
    val nativePaths = listOf(
        project(":freetype-harfbuzz-java-bindings").file("src/main/resources/natives"),
        project(":msdfgen-java-bindings").file("src/main/resources/natives")
    )
    val os = System.getProperty("os.name", "").lowercase()
    val arch = System.getProperty("os.arch", "").lowercase()
    val osName = when {
        os.contains("win") -> "windows"
        os.contains("mac") || os.contains("darwin") -> "macos"
        os.contains("linux") || os.contains("nux") -> "linux"
        else -> null
    }
    val archName = when (arch) {
        "amd64", "x86_64" -> "x64"
        "aarch64", "arm64" -> "aarch64"
        "x86", "i386", "i686" -> "x86"
        else -> null
    }
    if (osName != null && archName != null) {
        val platformDirs = nativePaths.map { File(it, "$osName-$archName") }
            .filter { it.isDirectory }
        if (platformDirs.isNotEmpty()) {
            val libPath = platformDirs.joinToString(File.pathSeparator) { it.absolutePath }
            systemProperty("java.library.path", libPath)
        }
    }
}
