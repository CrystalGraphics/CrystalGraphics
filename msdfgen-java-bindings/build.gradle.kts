plugins {
    java
}

// Only apply maven-publish when building standalone (not as a subproject of CrystalGraphics)
val isStandalone = rootProject.name == project.name
if (isStandalone) {
    apply(plugin = "maven-publish")
}

group = "com.msdfgen"
version = "1.0.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    if (isStandalone) {
        withSourcesJar()
        withJavadocJar()
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

// Native libraries in src/main/resources/natives/ are automatically included
// by the standard processResources task — no explicit from() needed.

tasks.test {
    // Set java.library.path to include both the platform-specific directory and the
    // parent natives/ directory so that Strategy 3 (System.loadLibrary) can find the DLL
    // as a fallback when Strategy 2 (classpath extraction) is unavailable.
    val nativesDir = file("src/main/resources/natives")
    val platformDir = detectPlatformDir(nativesDir)
    val libPath = if (platformDir != null) {
        "${platformDir.absolutePath}${File.pathSeparator}${nativesDir.absolutePath}"
    } else {
        nativesDir.absolutePath
    }
    systemProperty("java.library.path", libPath)
}

fun detectPlatformDir(nativesDir: File): File? {
    val os = System.getProperty("os.name", "").lowercase()
    val arch = System.getProperty("os.arch", "").lowercase()
    val osName = when {
        os.contains("win") -> "windows"
        os.contains("mac") || os.contains("darwin") -> "macos"
        os.contains("linux") || os.contains("nux") -> "linux"
        else -> return null
    }
    val archName = when (arch) {
        "amd64", "x86_64" -> "x64"
        "aarch64", "arm64" -> "aarch64"
        "x86", "i386", "i686" -> "x86"
        else -> return null
    }
    val dir = File(nativesDir, "$osName-$archName")
    return if (dir.isDirectory) dir else null
}

// Only configure publishing when building standalone
if (isStandalone) {
    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])

                pom {
                    name.set("msdfgen-java")
                    description.set("Java bindings for MSDFgen (Multi-Channel Signed Distance Field Generator)")
                    url.set("https://github.com/crystalgraphics/msdfgen-java-bindings")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                    developers {
                        developer {
                            id.set("crystalgraphics")
                            name.set("CrystalGraphics Team")
                        }
                    }
                }
            }
        }
    }
}
