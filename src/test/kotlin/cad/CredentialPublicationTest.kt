package cad

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.*
import kotlin.test.*

class CredentialPublicationTest {
    @TempDir lateinit var directory: Path
    private fun scan(): Pair<Int,String> {
        val p=ProcessBuilder("python3",Path.of("scripts/check_secrets.py").toAbsolutePath().toString(),directory.toString()).redirectErrorStream(true).start()
        val output=p.inputStream.bufferedReader().readText();return p.waitFor() to output
    }
    @Test fun `publication guard rejects private files and credentials without printing values`() {
        Files.writeString(directory.resolve("Main.kt"),"fun main() {}")
        assertEquals(0,scan().first)
        Files.writeString(directory.resolve("auth.json"),"{}")
        assertNotEquals(0,scan().first);Files.delete(directory.resolve("auth.json"))
        val credential="sk-"+"z".repeat(48)
        Files.writeString(directory.resolve("Main.kt"),credential)
        val failed=scan();assertNotEquals(0,failed.first);assertFalse(failed.second.contains(credential));assertTrue(failed.second.contains("Main.kt:1"))
    }
    @Test fun `gitignore does not hide an already tracked authentication file from the guard`() {
        fun git(vararg args: String) { val p=ProcessBuilder(listOf("git","-C",directory.toString())+args).redirectErrorStream(true).start();p.inputStream.readAllBytes();assertEquals(0,p.waitFor()) }
        git("init");Files.writeString(directory.resolve("auth.json"),"{}");git("add","auth.json")
        Files.writeString(directory.resolve(".gitignore"),"auth.json\n")
        assertNotEquals(0,scan().first)
    }
}
