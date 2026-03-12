plugins {
    `java-library`
    `maven-publish`
}

group = "com.crystalgraphics"
version = "1.0.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
}

dependencies {
    // Testing
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    if (JavaVersion.current().isJava9Compatible) {
        options.release.set(8)
    }
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "FreeType-HarfBuzz Java Bindings",
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "CrystalGraphics"
        )
    }

    // Include native libraries in the JAR
    from("src/main/resources") {
        include("natives/**")
    }
}

tasks.test {
    // Pass native library path for tests
    systemProperty("java.library.path", file("src/main/resources/natives").absolutePath)
    // Also check system property for custom native path
    systemProperty("freetype.harfbuzz.native.path",
        System.getProperty("freetype.harfbuzz.native.path", ""))
}

publishing {
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
