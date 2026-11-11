// Compile-only stand-ins for the Forge family's types a bootstrapper names, so it compiles once at Java 8 for every
// version: the @Mod both Forge eras scan for and legacy FML's lifecycle events, FML 1.7.10's @Mod, and NeoForge's @Mod and
// mod event bus.
// Never shipped. @see runtime/mc/forge-bootstrap, ForgeStart, NeoForgeStart
plugins { `java-library` }

group = providers.gradleProperty("modGroup").orElse("com.crystalgraphics").get()
version = providers.gradleProperty("modVersion").orElse("1.0.0").get()
base { archivesName.set("crystalgraphics-forge-stubs") }

tasks.withType<JavaCompile>().configureEach { options.release.set(8) }
