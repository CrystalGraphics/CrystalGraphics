plugins { `java-library` }

group = "com.crystalgraphics"
version = rootProject.version.toString()

// Version shorthands from gradle.properties
val lombokVer  = rootProject.properties["dep.lombok"].toString()
val log4jVer   = rootProject.properties["dep.log4j"].toString()
val jdkVersion = rootProject.properties["dep.jdk.toolchain"].toString().toInt()

java {
    // Match the root project's toolchain so the compiled bytecode is consistent.
    // platform/ uses only plain Java 8 syntax (no modern features), so no Jabel needed.
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(jdkVersion))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("org.projectlombok:lombok:$lombokVer")
    annotationProcessor("org.projectlombok:lombok:$lombokVer")
    implementation("org.apache.logging.log4j:log4j-api:$log4jVer")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    // Target Java 8 bytecode so core/ (which also targets Java 8) can depend on platform/.
    options.release.set(8)
}
