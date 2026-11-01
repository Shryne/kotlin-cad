package cad

import java.util.Properties

object AppBuild {
    private val properties = Properties().apply {
        AppBuild::class.java.getResourceAsStream("/build-info.properties")?.use { load(it) }
    }
    val version: String = properties.getProperty("version", "development")
    val id: String = properties.getProperty("build", "unknown")
    val description get() = "Kotlin CAD $version (Build $id)"
}
