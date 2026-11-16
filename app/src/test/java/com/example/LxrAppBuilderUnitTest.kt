package com.example

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.builder.AndroidApiLevel
import com.example.builder.ApkBuildMode
import com.example.builder.AppBuilderFileRepository
import com.example.builder.AppBuilderProject
import com.example.builder.AppCategory
import com.example.builder.AppDesignTheme
import com.example.builder.AppProjectTemplates
import com.example.builder.AutoErrorFixer
import com.example.builder.ProgrammingLanguage
import com.example.builder.TermuxBuildBridge
import com.example.ui.CYBER_BLUE
import com.example.ui.CYBER_RED
import com.example.ui.LxrAppBuilderWorkspace
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xhdpi")
class LxrAppBuilderUnitTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun templates_generateCompleteValidProject_forAll15CategoriesAnd3Languages() {
    AppCategory.entries.forEach { category ->
      val lang =
        when (category.ordinal % 3) {
          0 -> ProgrammingLanguage.KOTLIN
          1 -> ProgrammingLanguage.JAVA
          else -> ProgrammingLanguage.KOTLIN_COMPOSE
        }
      val proj =
        AppBuilderProject(
          name = "Test ${category.titleEn}",
          description = "Build ${category.titleEn}",
          category = category,
          designTheme = AppDesignTheme.CYBER_NEON,
          packageName = "com.lxr.test.${category.name.lowercase()}",
          minSdk = AndroidApiLevel.API_26,
          language = lang,
          buildMode = ApkBuildMode.DEBUG,
        )
      val files = AppProjectTemplates.generateFullProjectFiles(proj)
      assertTrue("settings.gradle.kts missing", files.containsKey("settings.gradle.kts"))
      assertTrue("build.gradle.kts missing", files.containsKey("build.gradle.kts"))
      assertTrue("app/build.gradle.kts missing", files.containsKey("app/build.gradle.kts"))
      assertTrue("Manifest missing", files.containsKey("app/src/main/AndroidManifest.xml"))
      assertTrue("strings.xml missing", files.containsKey("app/src/main/res/values/strings.xml"))
      assertTrue("Adaptive icon missing", files.containsKey("app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml"))

      val mainKey = files.keys.firstOrNull { it.endsWith("MainActivity.kt") || it.endsWith("MainActivity.java") }
      assertNotNull("MainActivity missing for $category / $lang", mainKey)
    }
  }

  @Test
  fun repository_saveRenameBackupAndDelete_workCorrectly() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val repo = AppBuilderFileRepository(ctx)
    val base =
      AppBuilderProject(
        id = "utest123",
        name = "Unit Note App",
        description = "Simple offline note app",
        category = AppCategory.NOTES,
        packageName = "com.lxr.unit.notes",
      )
    val generated = base.copy(files = AppProjectTemplates.generateFullProjectFiles(base))
    repo.saveProject(generated)

    val history = repo.loadProjectHistory()
    assertTrue(history.any { it.id == "utest123" && it.name == "Unit Note App" })

    val renamed = repo.renameProject("utest123", "Smart Notes Pro")
    assertEquals("Smart Notes Pro", renamed?.name)

    val zipRes = repo.createProjectZipBackup(renamed!!, includeBackupSuffix = true)
    assertTrue(zipRes.isSuccess)
    val (zipFile, _) = zipRes.getOrThrow()
    assertTrue(zipFile.exists() && zipFile.length() > 200L)

    assertTrue(repo.deleteProject("utest123"))
    assertFalse(repo.loadProjectHistory().any { it.id == "utest123" })
  }

  @Test
  fun autoErrorFixer_repairsMissingDependenciesAndDetectsRepeatedError() {
    val proj =
      AppBuilderProject(
        name = "Calc Test",
        category = AppCategory.CALCULATOR,
        packageName = "com.lxr.calc.test",
        language = ProgrammingLanguage.KOTLIN,
      ).let { it.copy(files = AppProjectTemplates.generateFullProjectFiles(it)) }

    // Remove appcompat from build.gradle.kts to simulate missing dependency error
    val brokenFiles = proj.files.toMutableMap()
    brokenFiles["app/build.gradle.kts"] =
      brokenFiles["app/build.gradle.kts"]!!.replace("""implementation("androidx.appcompat:appcompat:1.7.0")""", "")
    val brokenProj = proj.copy(files = brokenFiles)

    val (diag1, fixedFiles) =
      AutoErrorFixer.diagnoseAndRepair(
        brokenProj,
        "error: Unresolved reference: AppCompatActivity",
        previousSignature = null,
        attemptCount = 1,
      )
    assertTrue(diag1.autoFixApplied)
    assertFalse(diag1.isRepeatedSameError)
    assertTrue(fixedFiles["app/build.gradle.kts"]!!.contains("androidx.appcompat:appcompat"))

    val (diagRepeat, _) =
      AutoErrorFixer.diagnoseAndRepair(
        brokenProj,
        "error: unknown persistent failure",
        previousSignature = "error: unknown persistent failure",
        attemptCount = 2,
      )
    assertTrue(diagRepeat.isRepeatedSameError)
  }

  @Test
  fun termuxBuildScript_containsFreePackagesAapt2OverrideAndResultJson() {
    val proj = AppBuilderProject(name = "Termux Demo", packageName = "com.lxr.termux.demo")
    val script = TermuxBuildBridge.generateTermuxBuildScript(proj)
    assertTrue(script.contains("openjdk-17"))
    assertTrue(script.contains("gradle"))
    assertTrue(script.contains("aapt2FromMavenOverride"))
    assertTrue(script.contains("build_result.json"))
  }

  @Test
  fun appBuilderWorkspace_rendersAllIdeTabsAndCreationControls() {
    composeTestRule.setContent {
      MaterialTheme(colorScheme = darkColorScheme(primary = CYBER_RED, secondary = CYBER_BLUE)) {
        LxrAppBuilderWorkspace(back = {}, onNavigate = {})
      }
    }

    composeTestRule.onNodeWithTag("app_builder_screen").assertIsDisplayed()
    composeTestRule.onNodeWithTag("builder_creation_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("builder_project_name_input").assertIsDisplayed()
    composeTestRule.onNodeWithTag("builder_app_desc_input").assertIsDisplayed()
    composeTestRule.onNodeWithTag("builder_generate_btn").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("builder_history_card").performScrollTo().assertIsDisplayed()

    // Switch to Build & Termux tab
    composeTestRule.onNodeWithTag("builder_tab_build").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("builder_termux_card").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("builder_apk_result_card").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("builder_console_card").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun exportSmallTestAppForGradleVerification_succeeds() {
    val proj =
      AppBuilderProject(
        name = "LxrMiniCalc",
        description = "Small test Android calculator app",
        category = AppCategory.CALCULATOR,
        designTheme = AppDesignTheme.CYBER_NEON,
        packageName = "com.lxr.minicalc.testapp",
        minSdk = AndroidApiLevel.API_26,
        language = ProgrammingLanguage.KOTLIN,
        buildMode = ApkBuildMode.DEBUG,
      )
    val files = AppProjectTemplates.generateFullProjectFiles(proj)
    val outDir = File(System.getProperty("java.io.tmpdir"), "lxr_final_test_app")
    outDir.deleteRecursively()
    outDir.mkdirs()
    files.forEach { (rel, content) ->
      val f = File(outDir, rel)
      f.parentFile?.mkdirs()
      f.writeText(content, Charsets.UTF_8)
    }
    assertTrue(File(outDir, "app/src/main/java/com/lxr/minicalc/testapp/MainActivity.kt").exists())
    assertTrue(File(outDir, "app/build.gradle.kts").exists())
  }
}
