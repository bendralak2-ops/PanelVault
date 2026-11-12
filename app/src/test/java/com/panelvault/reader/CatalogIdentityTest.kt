package com.panelvault.reader

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CatalogIdentityTest {
    @Test fun identitySurvivesRenameAndDetectsChangedContent() {
        val directory = java.nio.file.Files.createTempDirectory("panelvault-identity").toFile()
        try {
            val original = File(directory, "issue.cbz").apply { writeText("abc") }
            val identity = CatalogIdentity.sha256(original)
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", identity)
            val renamed = File(directory, "renamed.cbz")
            assertTrue(original.renameTo(renamed))
            assertEquals(identity, CatalogIdentity.sha256(renamed))
            renamed.writeText("abd")
            assertNotEquals(identity, CatalogIdentity.sha256(renamed))
        } finally { directory.deleteRecursively() }
    }
}
