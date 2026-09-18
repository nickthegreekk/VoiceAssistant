package com.nikosm.voiceassistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L4: pins the name -> attachment-kind decision behind [isPlainTextAttachment].
 *
 * `plaintextExtensions` holds three extension-LESS entries ("dockerfile", "makefile",
 * "gitignore") because those files are conventionally named without one. The old lookup
 * unconditionally did `substringAfterLast(".", "")` and then required the result to be a
 * known extension, so `Dockerfile` produced "" and every such file was rejected with
 * "'…' is not a supported plain-text file" — the entries were unreachable dead weight.
 *
 * The check is a pure function over a file name (a Uri cannot be constructed off-device),
 * so it runs as a fast JVM unit test; `readAttachmentText`'s MIME/IO half is unchanged and
 * still needs a device.
 */
class PlainTextAttachmentNameTest {

    // ---------------------------------------------------------------------
    // The regression: extension-less conventional names.
    // ---------------------------------------------------------------------

    @Test
    fun extensionLessConventionalFileNamesAreAccepted() {
        assertTrue("Dockerfile", isPlainTextFileName("Dockerfile"))
        assertTrue("Makefile", isPlainTextFileName("Makefile"))
        // Dot-prefixed: the "extension" of ".gitignore" is the whole basename.
        assertTrue(".gitignore", isPlainTextFileName(".gitignore"))
    }

    @Test
    fun extensionLessNamesAreMatchedCaseInsensitively() {
        assertTrue("dockerfile", isPlainTextFileName("dockerfile"))
        assertTrue("DOCKERFILE", isPlainTextFileName("DOCKERFILE"))
        assertTrue("MakeFile", isPlainTextFileName("MakeFile"))
        assertTrue(".GITIGNORE", isPlainTextFileName(".GITIGNORE"))
    }

    @Test
    fun aDotPrefixIsNotRequiredForASingleWordEntry() {
        // "env" is both a normal extension (config.env) and, historically, a dot-prefixed
        // name (.env) — both spellings must resolve to the same entry.
        assertTrue(".env", isPlainTextFileName(".env"))
        assertTrue("config.env", isPlainTextFileName("config.env"))
    }

    // ---------------------------------------------------------------------
    // The unchanged half: dotted names are still matched by their EXTENSION.
    // ---------------------------------------------------------------------

    @Test
    fun dottedFileNamesAreMatchedByTheirLastExtension() {
        assertTrue("notes.md", isPlainTextFileName("notes.md"))
        assertTrue("README.MD", isPlainTextFileName("README.MD"))
        assertTrue("config.yaml", isPlainTextFileName("config.yaml"))
        assertTrue("Main.kt", isPlainTextFileName("Main.kt"))
        assertTrue("index.html", isPlainTextFileName("index.html"))
        assertTrue("requirements.txt", isPlainTextFileName("requirements.txt"))
    }

    @Test
    fun onlyTheLastExtensionCounts() {
        // A plain-text name carrying a non-text extension is still rejected: the trailing
        // extension is what determines the type, exactly as before L4.
        assertFalse("notes.md.bak", isPlainTextFileName("notes.md.bak"))
        assertFalse("Dockerfile.backup", isPlainTextFileName("Dockerfile.backup"))
        assertFalse("script.py.gpg", isPlainTextFileName("script.py.gpg"))
    }

    // ---------------------------------------------------------------------
    // Rejections: the basename path must not become a blanket accept.
    // ---------------------------------------------------------------------

    @Test
    fun nonTextExtensionsAreRejected() {
        assertFalse("photo.png", isPlainTextFileName("photo.png"))
        assertFalse("archive.zip", isPlainTextFileName("archive.zip"))
        assertFalse("clip.mp4", isPlainTextFileName("clip.mp4"))
        assertFalse("song.mp3", isPlainTextFileName("song.mp3"))
        assertFalse("report.pdf", isPlainTextFileName("report.pdf"))
        assertFalse("app.apk", isPlainTextFileName("app.apk"))
    }

    @Test
    fun extensionLessNamesThatAreNotListedAreRejected() {
        assertFalse("noextension", isPlainTextFileName("noextension"))
        assertFalse("Gemfile", isPlainTextFileName("Gemfile"))
        assertFalse(".gitattributes", isPlainTextFileName(".gitattributes"))
    }

    @Test
    fun degenerateNamesAreRejected() {
        assertFalse("empty name", isPlainTextFileName(""))
        assertFalse("trailing dot", isPlainTextFileName("notes."))
        assertFalse("bare dot", isPlainTextFileName("."))
    }
}
