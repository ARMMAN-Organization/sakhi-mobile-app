package org.armman.sakhi

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Guards the invariant that broke CR-020's consent-photo camera: every directory the UI passes to
 * `File(context.filesDir, "<dir>")` before calling `FileProvider.getUriForFile()` MUST have a
 * matching `<files-path>` entry in `res/xml/file_paths.xml`.
 *
 * When it doesn't, `getUriForFile` throws
 * `IllegalArgumentException: Failed to find configured root that contains …` from inside the
 * capture lambda and the app dies the instant the Sakhi taps the camera. Nothing catches it, the
 * compiler can't see it, and no existing unit test exercises it — the only signal is a field crash.
 *
 * The child flow hit exactly this: it was cloned from the mother flow but its capture directory was
 * renamed `dynamic-form` → `child-registration` without the corresponding manifest entry. Every
 * remaining form with an image field (ANC visit, delivery, infant, referral) can repeat it, so the
 * check is enforced here rather than left to review.
 *
 * Deliberately a source-scanning test: this module's unit tests are JVM-only (no Robolectric, so no
 * real `Context`/`FileProvider` to assert against), and the failure is a *configuration* mismatch
 * between Kotlin source and an XML resource — which is statically checkable without a device.
 */
class FileProviderPathsTest {

  /** Matches `File(context.filesDir, "some-dir")`, capturing the directory name. */
  private val filesDirUsage = Regex("""File\(\s*context\.filesDir\s*,\s*"([^"]+)"\s*\)""")

  /** Matches `path="some-dir/"` inside a `<files-path>` declaration. */
  private val declaredPath = Regex("""path\s*=\s*"([^"]+)"""")

  private val moduleDir: File by lazy {
    // Gradle runs unit tests with the module directory as the working directory. Fall back to the
    // repo root so the test still resolves when run from an IDE configured differently.
    val fromWorkingDir = File("src/main")
    if (fromWorkingDir.isDirectory) File(".") else File("app")
  }

  private val mainSrc: File get() = File(moduleDir, "src/main/kotlin")
  private val filePathsXml: File get() = File(moduleDir, "src/main/res/xml/file_paths.xml")

  @Test
  fun `every filesDir capture directory is declared in file_paths xml`() {
    assertTrue(
      "Could not locate src/main/kotlin from working dir ${File(".").absolutePath} — " +
        "fix the path resolution in this test rather than deleting it.",
      mainSrc.isDirectory,
    )
    assertTrue("Missing ${filePathsXml.path}", filePathsXml.isFile)

    val declared = declaredPath.findAll(filePathsXml.readText())
      .map { it.groupValues[1].trim('/') }
      .toSet()

    val used: Map<String, List<String>> = mainSrc.walkTopDown()
      .filter { it.isFile && it.extension == "kt" }
      .flatMap { file ->
        filesDirUsage.findAll(file.readText()).map { it.groupValues[1].trim('/') to file.name }
      }
      .groupBy({ it.first }, { it.second })

    assertTrue(
      "Found no File(context.filesDir, \"…\") usages at all — the regex in this test has probably " +
        "gone stale against a refactor and is now silently passing.",
      used.isNotEmpty(),
    )

    val undeclared = used.filterKeys { it !in declared }
    if (undeclared.isNotEmpty()) {
      fail(
        buildString {
          appendLine("Capture directories used in code but NOT declared in res/xml/file_paths.xml.")
          appendLine("FileProvider.getUriForFile() will throw IllegalArgumentException and crash")
          appendLine("the app when the camera is opened on these screens:")
          appendLine()
          undeclared.forEach { (dir, files) ->
            appendLine("  \"$dir\"  used in: ${files.distinct().sorted().joinToString(", ")}")
          }
          appendLine()
          appendLine("Declared paths: ${declared.sorted().joinToString(", ").ifEmpty { "(none)" }}")
          appendLine()
          append("Fix: add <files-path name=\"…\" path=\"<dir>/\" /> to res/xml/file_paths.xml.")
        },
      )
    }
  }

  @Test
  fun `child registration capture directory is declared`() {
    // Pinned explicitly so the CR-020 regression can never silently return, even if the generic
    // scan above is weakened or the child screen is refactored.
    val declared = declaredPath.findAll(filePathsXml.readText())
      .map { it.groupValues[1].trim('/') }
      .toSet()

    assertTrue(
      "res/xml/file_paths.xml must declare the \"child-registration\" files-path — without it the " +
        "consent-photo camera crashes the app (CR-020 regression). Declared: $declared",
      "child-registration" in declared,
    )
  }
}
