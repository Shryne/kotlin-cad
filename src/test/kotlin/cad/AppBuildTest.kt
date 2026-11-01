package cad

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class AppBuildTest {
    @Test fun packagedResourcesIdentifyTheBuild() {
        assertTrue(AppBuild.version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")), "Missing application version: ${AppBuild.version}")
        assertTrue(AppBuild.id.matches(Regex("[a-f0-9]{12}")), "Missing source fingerprint: ${AppBuild.id}")
    }
}
