// CrystalGraphics' one @Mod class for every Forge: 1.13+ and legacy FML 1.8-1.12.2 alike. Java 8, merged
// once into the single jar and never relocated (cg-single-jar's libraryProjects). @see ForgeStart
plugins { `java-library` }

group = providers.gradleProperty("modGroup").orElse("com.crystalgraphics").get()
version = providers.gradleProperty("modVersion").orElse("1.0.0").get()
base { archivesName.set("crystalgraphics-forge-bootstrap") }

dependencies {
    "compileOnly"(project(":runtime:mc:forge-stubs"))
    "compileOnly"(project(":runtime:mc:shared"))
}

tasks.withType<JavaCompile>().configureEach { options.release.set(8) }
