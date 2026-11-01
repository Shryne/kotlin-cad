import java.security.MessageDigest

plugins {
    kotlin("jvm") version "2.2.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0"
    id("org.jetbrains.compose") version "1.8.2"
    application
}

repositories { google(); mavenCentral() }
version = "1.48.0"

val buildInfoDirectory = layout.buildDirectory.dir("generated/buildInfo")
val generateBuildInfo by tasks.registering {
    val sources = files(fileTree("src/main"), fileTree("native"), "build.gradle.kts", "settings.gradle.kts")
    inputs.files(sources)
    inputs.property("version", project.version.toString())
    val output = buildInfoDirectory.map { it.file("build-info.properties") }
    outputs.file(output)
    doLast {
        val digest = MessageDigest.getInstance("SHA-256")
        sources.files.filter { it.isFile }.sortedBy { it.relativeTo(projectDir).path }.forEach {
            digest.update(it.relativeTo(projectDir).path.toByteArray())
            digest.update(it.readBytes())
        }
        val id = digest.digest().joinToString("") { "%02x".format(it) }.take(12)
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText("version=${project.version}\nbuild=$id\n")
        }
    }
}
sourceSets.named("main") { resources.srcDir(buildInfoDirectory) }
tasks.named("processResources") { dependsOn(generateBuildInfo) }
tasks.jar { archiveFileName.set("kotlin-cad.jar") }

val javafxVersion = "21.0.2"
val javafxPlatform = when {
    System.getProperty("os.name").startsWith("Mac") -> if (System.getProperty("os.arch") == "aarch64") "mac-aarch64" else "mac"
    System.getProperty("os.name").startsWith("Windows") -> "win"
    else -> "linux"
}
dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material)
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.7.2")
    for (module in listOf("base", "graphics", "controls", "swing")) {
        implementation("org.openjfx:javafx-$module:$javafxVersion:$javafxPlatform")
    }
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
kotlin { jvmToolchain(17) }
application {
    mainClass.set("cad.MainKt")
    applicationDefaultJvmArgs = if (System.getProperty("os.name").startsWith("Mac"))
        listOf("--add-exports=java.desktop/com.apple.eawt.event=ALL-UNNAMED") else emptyList()
}
val generateTestFixtures by tasks.registering(Exec::class) {
    commandLine("python3", "scripts/generate_test_fixtures.py")
    inputs.file("scripts/generate_test_fixtures.py")
    outputs.dir("samples")
}
tasks.test { useJUnitPlatform(); dependsOn(generateTestFixtures) }
