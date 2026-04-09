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

    // Include JNI binding subproject JAR (unpacked) in the shadow JAR.
    // This is an implementation dep but cannot use shadowImplementation because the GTNH
    // convention plugin requires RFG obfuscation variant attributes that plain Java
    // subprojects don't publish (causes variant ambiguity errors).
    dependsOn(":freetype-msdfgen-harfbuzz-bindings:jar")
}

// Resolve subproject jar output after evaluation (subproject tasks don't exist during
// root project configuration). Unpacks the JAR into the shadow JAR.
afterEvaluate {
    tasks.shadowJar.configure {
        val bindingsJar = project(":freetype-msdfgen-harfbuzz-bindings").tasks.named<Jar>("jar").get()

        from(zipTree(bindingsJar.archiveFile.get()))
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
     //   val agent = findJarBySubstring("unimixins")
       // jvmArgs("-javaagent:${agent.absolutePath}")

       // val hotswapAgent = findJarBySubstring("hotswap-agent")
      //  jvmArgs("-javaagent:${hotswapAgent.absolutePath}")
    }
}

// Ensure JNI native libraries from subproject are loadable during tests.
// Strategy 2 (classpath extraction) works because subproject resources are on the
// testRuntimeClasspath via implementation(project(...)). Strategy 3 (java.library.path)
// is configured here as a fallback pointing to the platform-specific native directories.
tasks.withType<Test> {
    val nativesDir = project(":freetype-msdfgen-harfbuzz-bindings").file("src/main/resources/natives")
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
        val platformDir = File(nativesDir, "$osName-$archName")
        if (platformDir.isDirectory) {
            systemProperty("java.library.path", platformDir.absolutePath)
        }
    }
}
