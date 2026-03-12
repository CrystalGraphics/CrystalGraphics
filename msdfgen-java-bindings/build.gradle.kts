plugins {
    java
    `maven-publish`
}

group = "com.msdfgen"
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
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

// Include native libraries from resources
tasks.jar {
    from("src/main/resources") {
        include("natives/**")
    }
}

tasks.test {
    // Pass native library path for tests
    systemProperty("java.library.path", file("src/main/resources/natives").absolutePath)
}

publishing {
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
