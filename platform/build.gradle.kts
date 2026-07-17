plugins { `java-library` }

val isStandalone = rootProject.name == project.name
if (isStandalone) {
    apply(plugin = "maven-publish")
}

group = "com.crystalgraphics"
version = rootProject.version.toString()

// Version shorthands from gradle.properties
val lombokVer  = rootProject.properties["dep.lombok"].toString()
val log4jVer   = rootProject.properties["dep.log4j"].toString()
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
    compileOnly("org.projectlombok:lombok:1.18.44")
    annotationProcessor("org.projectlombok:lombok:1.18.44")
    testCompileOnly("org.projectlombok:lombok:1.18.44")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.44")
    implementation("org.apache.logging.log4j:log4j-api:$log4jVer")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.jar {
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

// Only configure publishing when building standalone
if (isStandalone) {
    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])

                pom {
                    name.set("FreeType-MSDFgen-HarfBuzz Java Bindings")
                    description.set("JNI bindings for FreeType, MSDFgen, and HarfBuzz, compatible with LWJGL 2.9.3 and Java 8")
                    url.set("https://github.com/somehussar/freetype-msdfgen-harfbuzz-bindings")
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
