plugins {
    `java-library`
}

// Only apply maven-publish when building standalone (not as a subproject of CrystalGraphics)
val isStandalone = rootProject.name == project.name
if (isStandalone) {
    apply(plugin = "maven-publish")
}

group = "com.crystalgraphics"
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

tasks.jar {
    // Set manifest version info
    manifest {
        attributes(
            "Manifest-Version" to "1.0",
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version
        )
    }

    // Native libraries in src/main/resources/natives/ are automatically included
    // by the standard processResources task — no explicit from() needed.
}

tasks.test {
    // Pass native library path for tests
    systemProperty("java.library.path", file("src/main/resources/natives").absolutePath)
    // Also check system property for custom native path
    systemProperty("freetype.harfbuzz.native.path",
        System.getProperty("freetype.harfbuzz.native.path", ""))
}

// Only configure publishing when building standalone
if (isStandalone) {
    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])

                pom {
                    name.set("FreeType-HarfBuzz Java Bindings")
                    description.set("JNI bindings for FreeType and HarfBuzz, compatible with LWJGL 2.9.3 and Java 8")
                    url.set("https://github.com/somehussar/freetype-harfbuzz-java-bindings")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                }
            }
        }
    }
}
