plugins { `java-library` }

val isStandalone = rootProject.name == project.name
if (isStandalone) {
    apply(plugin = "maven-publish")
}

group = "com.crystalgraphics"
version = rootProject.version.toString()

// Version shorthands from gradle.properties
val lombokVer     = rootProject.properties["dep.lombok"].toString()
val log4jVer      = rootProject.properties["dep.log4j"].toString()
val jabelVer      = rootProject.properties["dep.jabel"].toString()
val downgraderVer = rootProject.properties["dep.jvmdowngrader"]?.toString() ?: "0.9.0"

// Mirrors :core exactly — same toolchain, same source/target, same Jabel and jvmDowngrader deps.
// The two are consumed together by every loader and shadowed into the same jar, so a module that
// compiled to a different bytecode level than its sibling would be a trap rather than a safety net.
//
// This used to be Java 8 source, which meant `platform` could not use records, `var`, switch
// expressions or anything else `core` takes for granted — a real constraint on an SPI module that
// carries value types (CgSystemInput's event records are the ones that hit it first).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    toolchain {
        // Jabel is stable on 17 and 21. It is not stable on 25.
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    if (isStandalone) {
        withSourcesJar()
        withJavadocJar()
    }
}

repositories {
    maven {
        name = "WagYourTail Maven"
        url = uri("https://maven.wagyourtail.xyz/releases")
    }
    mavenCentral()
}

dependencies {
    compileOnly("xyz.wagyourtail.jvmdowngrader:jvmdowngrader-java-api:$downgraderVer:downgraded-8")

    compileOnly("org.projectlombok:lombok:1.18.44")
    annotationProcessor("org.projectlombok:lombok:1.18.44")
    testCompileOnly("org.projectlombok:lombok:1.18.44")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.44")

    // compileOnly so an `import ...Desugar` compiles under the plain Java 17 pass, exactly as in
    // :core. No annotationProcessor(jabel) here either — the dual pipeline is not wired up there.
    compileOnly("com.github.bsideup.jabel:jabel-javac-plugin:$jabelVer")

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
