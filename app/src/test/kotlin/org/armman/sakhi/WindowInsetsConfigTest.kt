package org.armman.sakhi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Guards the window-inset configuration that broke the login screen: the Sakhi tapped Username or
 * Password and the on-screen keyboard covered the field, with no scroll and no way to see what she
 * was typing.
 *
 * The UI code was never at fault. Every screen root already applies `safeDrawingPadding()`, and
 * `WindowInsets.safeDrawing` includes the IME inset — but nothing was *dispatching* insets into the
 * Compose hierarchy, so all twelve screens resolved their bottom inset to 0dp. Two window-level
 * settings are required, and neither works without the other:
 *
 *  1. `android:windowSoftInputMode="adjustResize"` on the Activity (manifest), and
 *  2. `enableEdgeToEdge()` — i.e. decor-fits-system-windows off — before `setContent`.
 *
 * This is exactly the failure mode that makes a unit test worth having: a *configuration* mismatch
 * spread across a manifest, an Activity and twelve screens, invisible to the compiler, with no
 * signal other than someone opening the app on a device. There are eight more form screens still to
 * be built, each of which can silently reintroduce it.
 *
 * Deliberately a source-scanning test, following the precedent set by [FileProviderPathsTest]: this
 * module's unit tests are JVM-only (no Robolectric, no instrumentation), so there is no real
 * `Window` to read `softInputMode` from and no way to inject a synthetic IME inset and measure
 * where a field landed. Asserting the *configuration* is statically checkable and deterministic;
 * asserting rendered pixel positions is not, and belongs in the manual verification matrix for this
 * CR. See the note at the bottom of this file before replacing this with a Robolectric suite.
 */
class WindowInsetsConfigTest {

  private val moduleDir: File by lazy {
    // Gradle runs unit tests with the module directory as the working directory. Fall back to the
    // repo root so the test still resolves when run from an IDE configured differently.
    val fromWorkingDir = File("src/main")
    if (fromWorkingDir.isDirectory) File(".") else File("app")
  }

  private val mainSrc: File get() = File(moduleDir, "src/main/kotlin")
  private val manifest: File get() = File(moduleDir, "src/main/AndroidManifest.xml")
  private val mainActivity: File
    get() = File(mainSrc, "org/armman/sakhi/MainActivity.kt")

  private val screenFiles: List<File>
    get() = mainSrc.walkTopDown()
      .filter { it.isFile && it.name.endsWith("Screen.kt") }
      .sortedBy { it.name }
      .toList()

  /** Compose's `Dialog(` — the lookbehind keeps `DatePickerDialog(` (a platform dialog) out. */
  private val composeDialog = Regex("""(?<![A-Za-z])Dialog\(""")

  private val textInput =
    Regex("""AppTextField|AppTextInputField|(?<![A-Za-z])TextField\(|BasicTextField""")

  @Test
  fun `source tree is resolvable`() {
    // Fails loudly rather than letting every assertion below vacuously pass on a bad working dir.
    assertTrue(
      "Could not locate src/main/kotlin from working dir ${File(".").absolutePath} — " +
        "fix the path resolution in this test rather than deleting it.",
      mainSrc.isDirectory,
    )
    assertTrue("Missing ${manifest.path}", manifest.isFile)
    assertTrue("Missing ${mainActivity.path}", mainActivity.isFile)
    assertTrue("Found no *Screen.kt files — the scan has gone stale.", screenFiles.isNotEmpty())
  }

  @Test
  fun `MainActivity declares windowSoftInputMode adjustResize`() {
    val activityBlock = Regex("""<activity\b[^>]*android:name="\.MainActivity"[^>]*>""")
      .find(manifest.readText())
      ?.value
      ?: Regex("""<activity\b(?:[^>]|\n)*?>""", RegexOption.DOT_MATCHES_ALL)
        .findAll(manifest.readText())
        .firstOrNull { it.value.contains(".MainActivity") }
        ?.value

    assertTrue(
      "Could not find the <activity> declaration for .MainActivity in AndroidManifest.xml.",
      activityBlock != null,
    )
    assertTrue(
      "MainActivity is missing android:windowSoftInputMode=\"adjustResize\". Without it the window " +
        "pans instead of resizing, the IME inset is never dispatched, and the keyboard covers the " +
        "focused input on EVERY screen — the original login defect. Found:\n$activityBlock",
      activityBlock!!.contains("""android:windowSoftInputMode="adjustResize""""),
    )
  }

  /**
   * Import lines contain the same identifiers as the call sites (`androidx.activity.compose
   * .setContent`), which makes a naive `indexOf` compare an import against a call and report a
   * bogus ordering. Strip them before any positional assertion.
   */
  private fun bodyOf(file: File): String = file.readLines()
    .filterNot { it.trimStart().startsWith("import ") || it.trimStart().startsWith("package ") }
    .joinToString("\n")

  @Test
  fun `MainActivity enables edge to edge before setContent`() {
    val source = bodyOf(mainActivity)

    val edgeToEdgeAt = source.indexOf("enableEdgeToEdge(")
    val setContentAt = source.indexOf("setContent {")

    assertTrue(
      "MainActivity does not call enableEdgeToEdge(). Without it decor-fits-system-windows stays " +
        "on, window insets (including the IME) never reach Compose, and every " +
        "safeDrawingPadding() in the app resolves to 0dp.",
      edgeToEdgeAt >= 0,
    )
    assertTrue(
      "MainActivity does not call setContent { … } — has it been renamed or reformatted?",
      setContentAt >= 0,
    )
    assertTrue(
      "enableEdgeToEdge() must be called BEFORE setContent, otherwise the first composition runs " +
        "with stale inset values.",
      edgeToEdgeAt < setContentAt,
    )
  }

  @Test
  fun `system bar styles are pinned to light rather than auto`() {
    val source = bodyOf(mainActivity)

    assertTrue(
      "Status and navigation bar styles must be pinned with SystemBarStyle.light. ArogyaTheme is a " +
        "hardcoded lightColorScheme (White / BackgroundLavender #F1EDF9) on an AppCompat DayNight " +
        "platform theme, so the default `auto` style flips to light-on-dark bar icons whenever the " +
        "DEVICE is in dark mode — illegible icons over our permanently light background.",
      source.contains("SystemBarStyle.light"),
    )
    assertFalse(
      "SystemBarStyle.auto follows the platform DayNight theme, which this app does not follow. " +
        "Use SystemBarStyle.light for both bars.",
      source.contains("SystemBarStyle.auto"),
    )
  }

  @Test
  fun `every screen root applies safeDrawingPadding`() {
    val missing = screenFiles.filterNot { it.readText().contains("safeDrawingPadding") }

    if (missing.isNotEmpty()) {
      fail(
        buildString {
          appendLine("These screens never apply safeDrawingPadding():")
          appendLine()
          missing.forEach { appendLine("  ${it.name}") }
          appendLine()
          appendLine("With edge-to-edge enabled the app draws behind the system bars, so a screen")
          appendLine("without it will slide content under the status bar / navigation bar, and any")
          appendLine("text field on it will be covered by the keyboard.")
          appendLine()
          append("Fix: apply Modifier.safeDrawingPadding() to the screen's root container.")
        },
      )
    }
  }

  @Test
  fun `no screen nests imePadding under safeDrawingPadding`() {
    // safeDrawingPadding() consumes the IME inset, so a nested imePadding() in the same modifier
    // chain is dead code. Harmless at runtime, but it reads as "the keyboard is handled here" and
    // sent the original investigation down the wrong path — the login screen had exactly this.
    val chained = Regex(
      """safeDrawingPadding\(\)\s*(?:\.\w+\([^)]*\)\s*)*?\.imePadding\(\)""",
      RegexOption.DOT_MATCHES_ALL,
    )

    val offenders = screenFiles.filter { chained.containsMatchIn(it.readText()) }

    if (offenders.isNotEmpty()) {
      fail(
        buildString {
          appendLine("These screens chain .imePadding() after .safeDrawingPadding():")
          appendLine()
          offenders.forEach { appendLine("  ${it.name}") }
          appendLine()
          appendLine("safeDrawing already includes the IME inset and consumes it, so the trailing")
          appendLine("imePadding() is a no-op. Remove it — keeping it implies keyboard handling")
          append("that is actually coming from safeDrawingPadding().")
        },
      )
    }
  }

  @Test
  fun `dialogs containing text inputs opt out of decorFitsSystemWindows`() {
    // A Dialog gets its own window, which keeps decor-fits-system-windows ON regardless of what the
    // Activity window does — so IME insets stop at the dialog boundary and a text field inside sits
    // under the keyboard. Only dialogs that actually take typed input need the override.
    val offenders = mainSrc.walkTopDown()
      .filter { it.isFile && it.extension == "kt" }
      .filter { file ->
        val text = file.readText()
        composeDialog.containsMatchIn(text) &&
          textInput.containsMatchIn(text) &&
          !text.contains("decorFitsSystemWindows = false")
      }
      .map { it.name }
      .sorted()
      .toList()

    if (offenders.isNotEmpty()) {
      fail(
        buildString {
          appendLine("These files declare a Compose Dialog() alongside a text input, but do not set")
          appendLine("DialogProperties(decorFitsSystemWindows = false):")
          appendLine()
          offenders.forEach { appendLine("  $it") }
          appendLine()
          appendLine("The keyboard will cover the field. Fix:")
          appendLine("  Dialog(")
          appendLine("    onDismissRequest = …,")
          appendLine("    properties = DialogProperties(decorFitsSystemWindows = false),")
          appendLine("  ) { Surface(modifier = Modifier.imePadding()) { … } }")
          appendLine()
          append("imePadding() belongs OUTSIDE the card so the card lifts, not grows.")
        },
      )
    }
  }

  @Test
  fun `mother picker dialog handles the keyboard`() {
    // Pinned explicitly so this specific regression cannot return even if the generic scan above is
    // weakened or the file is refactored. It is the only dialog in the app that takes typed input.
    val source = File(mainSrc, "org/armman/sakhi/ui/childregistration/MotherLinkField.kt")
    assertTrue("Missing ${source.path}", source.isFile)

    val text = source.readText()
    assertTrue(
      "MotherPickerDialog must set DialogProperties(decorFitsSystemWindows = false) — its search " +
        "field is otherwise covered by the keyboard.",
      text.contains("decorFitsSystemWindows = false"),
    )
    assertTrue(
      "MotherPickerDialog must apply imePadding() so the card lifts clear of the keyboard.",
      text.contains("imePadding()"),
    )
  }
}

/*
 * Note on scope, recorded so the next person does not redo this decision:
 *
 * The approved plan for this CR called for adding Robolectric + compose-ui-test-junit4 and asserting
 * that a focused field's measured bounds sit above the IME inset. That was not implemented, for two
 * reasons:
 *
 *  1. Compose reads IME insets from the platform via internal state that has no public injection
 *     point, so a Robolectric test cannot produce a synthetic keyboard. The assertion would either
 *     be tautological or flaky.
 *  2. It would add two test dependencies and a second testing idiom to a module that has 80+ JVM-only
 *     unit tests and an established source-scanning pattern for exactly this class of configuration
 *     bug (FileProviderPathsTest).
 *
 * If runtime assertions are wanted later, the right home is an instrumented androidTest source set
 * (which does not exist yet) driving a real IME on a device — not Robolectric.
 */
