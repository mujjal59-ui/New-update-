package com.example.builder

object AppProjectTemplates {

  fun sanitizePackageName(raw: String, fallbackSlug: String): String {
    val cleaned =
      raw
        .lowercase()
        .replace(Regex("[^a-z0-9._]"), "")
        .split('.')
        .filter { it.isNotBlank() && it.first().isLetter() }
        .joinToString(".")
    return if (cleaned.contains('.') && cleaned.length >= 5) cleaned
    else "com.lxr.generated.${fallbackSlug.filter { it.isLetterOrDigit() }.ifBlank { "myapp" }}"
  }

  fun escapeXml(text: String): String =
    text
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "\\'")

  fun generateFullProjectFiles(project: AppBuilderProject): Map<String, String> {
    val pkg = sanitizePackageName(project.packageName, project.slug)
    val pkgPath = pkg.replace('.', '/')
    val theme = project.designTheme
    val appTitle = project.name.ifBlank { project.category.titleEn }
    val desc = project.description.ifBlank { project.category.defaultFeatureHint }

    val files = linkedMapOf<String, String>()

    // 1. Root Gradle files
    files["settings.gradle.kts"] =
      """
      pluginManagement {
        repositories {
          google()
          mavenCentral()
          gradlePluginPortal()
        }
      }
      dependencyResolutionManagement {
        repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
        repositories {
          google()
          mavenCentral()
        }
      }
      rootProject.name = "${appTitle.replace("\"", "")}"
      include(":app")
      """.trimIndent()

    files["gradle.properties"] =
      """
      org.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8
      android.useAndroidX=true
      android.nonTransitiveRClass=true
      kotlin.code.style=official
      """.trimIndent()

    files["gradle/wrapper/gradle-wrapper.properties"] =
      """
      distributionBase=GRADLE_USER_HOME
      distributionPath=wrapper/dists
      distributionUrl=https\://services.gradle.org/distributions/gradle-9.1.0-bin.zip
      zipStoreBase=GRADLE_USER_HOME
      zipStorePath=wrapper/dists
      """.trimIndent()

    val isCompose = project.language == ProgrammingLanguage.KOTLIN_COMPOSE
    val isKotlin = project.language != ProgrammingLanguage.JAVA

    files["build.gradle.kts"] =
      buildString {
        appendLine("plugins {")
        appendLine("""  id("com.android.application") version "9.1.1" apply false""")
        if (isCompose) {
          appendLine("""  id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false""")
        }
        appendLine("}")
      }.trimEnd()

    // 2. Module build.gradle.kts
    files["app/build.gradle.kts"] =
      buildString {
        appendLine("plugins {")
        appendLine("""  id("com.android.application")""")
        if (isCompose) {
          appendLine("""  id("org.jetbrains.kotlin.plugin.compose")""")
        }
        appendLine("}")
        appendLine()
        appendLine("android {")
        appendLine("""  namespace = "$pkg"""")
        appendLine("  compileSdk = 36")
        appendLine()
        appendLine("  defaultConfig {")
        appendLine("""    applicationId = "$pkg"""")
        appendLine("    minSdk = ${project.minSdk.sdkInt}")
        appendLine("    targetSdk = 36")
        appendLine("    versionCode = 1")
        appendLine("""    versionName = "1.0.0"""")
        appendLine("  }")
        appendLine()
        appendLine("  buildTypes {")
        appendLine("    release {")
        appendLine("      isMinifyEnabled = false")
        appendLine("""      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")""")
        appendLine("    }")
        appendLine("  }")
        appendLine("  compileOptions {")
        appendLine("    sourceCompatibility = JavaVersion.VERSION_11")
        appendLine("    targetCompatibility = JavaVersion.VERSION_11")
        appendLine("  }")
        if (isCompose) {
          appendLine("  buildFeatures {")
          appendLine("    compose = true")
          appendLine("  }")
        }
        appendLine("}")
        appendLine()
        appendLine("dependencies {")
        if (isKotlin) {
          appendLine("""  implementation("androidx.core:core-ktx:1.15.0")""")
        } else {
          appendLine("""  implementation("androidx.core:core:1.15.0")""")
        }
        if (isCompose) {
          appendLine("""  implementation(platform("androidx.compose:compose-bom:2024.09.00"))""")
          appendLine("""  implementation("androidx.activity:activity-compose:1.10.1")""")
          appendLine("""  implementation("androidx.compose.ui:ui")""")
          appendLine("""  implementation("androidx.compose.material3:material3")""")
        } else {
          appendLine("""  implementation("androidx.appcompat:appcompat:1.7.0")""")
          appendLine("""  implementation("com.google.android.material:material:1.12.0")""")
        }
        appendLine("}")
      }.trimEnd()

    files["app/proguard-rules.pro"] = "# Generated by LXR--AI App Builder\n"

    // 3. Manifest & Resources
    val themeResName = "Theme_${project.slug.take(18).replaceFirstChar { it.uppercase() }}"
    files["app/src/main/AndroidManifest.xml"] =
      """
      <?xml version="1.0" encoding="utf-8"?>
      <manifest xmlns:android="http://schemas.android.com/apk/res/android">
          <application
              android:allowBackup="true"
              android:icon="@mipmap/ic_launcher"
              android:label="@string/app_name"
              android:roundIcon="@mipmap/ic_launcher"
              android:supportsRtl="true"
              android:theme="@style/$themeResName">
              <activity
                  android:name=".MainActivity"
                  android:exported="true">
                  <intent-filter>
                      <action android:name="android.intent.action.MAIN" />
                      <category android:name="android.intent.category.LAUNCHER" />
                  </intent-filter>
              </activity>
          </application>
      </manifest>
      """.trimIndent()

    files["app/src/main/res/values/strings.xml"] =
      """
      <?xml version="1.0" encoding="utf-8"?>
      <resources>
          <string name="app_name">${escapeXml(appTitle)}</string>
          <string name="app_tagline">${escapeXml(desc.take(160))}</string>
      </resources>
      """.trimIndent()

    files["app/src/main/res/values/colors.xml"] =
      """
      <?xml version="1.0" encoding="utf-8"?>
      <resources>
          <color name="brand_primary">${theme.primaryHex}</color>
          <color name="brand_accent">${theme.accentHex}</color>
          <color name="surface_bg">${theme.surfaceHex}</color>
          <color name="text_primary">${theme.textHex}</color>
      </resources>
      """.trimIndent()

    val parentTheme =
      if (isCompose) "android:Theme.Material.NoActionBar"
      else if (theme == AppDesignTheme.CLEAN_MINIMAL) "Theme.Material3.Light.NoActionBar"
      else "Theme.Material3.Dark.NoActionBar"

    files["app/src/main/res/values/themes.xml"] =
      """
      <?xml version="1.0" encoding="utf-8"?>
      <resources>
          <style name="$themeResName" parent="$parentTheme" />
      </resources>
      """.trimIndent()

    files["app/src/main/res/drawable/ic_launcher_foreground.xml"] =
      """
      <vector xmlns:android="http://schemas.android.com/apk/res/android"
          android:width="108dp"
          android:height="108dp"
          android:viewportWidth="108"
          android:viewportHeight="108">
          <path
              android:fillColor="${theme.primaryHex}"
              android:pathData="M54,20 L82,36 L82,72 L54,88 L26,72 L26,36 Z" />
          <path
              android:fillColor="#FFFFFF"
              android:pathData="M44,40 H64 V48 H52 V68 H44 Z" />
      </vector>
      """.trimIndent()

    files["app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml"] =
      """
      <?xml version="1.0" encoding="utf-8"?>
      <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
          <background android:drawable="@color/surface_bg" />
          <foreground android:drawable="@drawable/ic_launcher_foreground" />
      </adaptive-icon>
      """.trimIndent()

    // 4. Layout XML (for Kotlin XML / Java XML) + Source Code
    when (project.language) {
      ProgrammingLanguage.KOTLIN_COMPOSE -> {
        files["app/src/main/java/$pkgPath/MainActivity.kt"] =
          generateComposeMainActivity(pkg, appTitle, desc, project.category, theme)
      }
      ProgrammingLanguage.KOTLIN -> {
        files["app/src/main/res/layout/activity_main.xml"] =
          generateMainXmlLayout(appTitle, desc, project.category)
        files["app/src/main/java/$pkgPath/MainActivity.kt"] =
          generateKotlinXmlMainActivity(pkg, appTitle, desc, project.category)
      }
      ProgrammingLanguage.JAVA -> {
        files["app/src/main/res/layout/activity_main.xml"] =
          generateMainXmlLayout(appTitle, desc, project.category)
        files["app/src/main/java/$pkgPath/MainActivity.java"] =
          generateJavaXmlMainActivity(pkg, appTitle, desc, project.category)
      }
    }

    files["README_BUILD.md"] =
      """
      # $appTitle
      Package: `$pkg`
      Category: ${project.category.titleEn} (${project.category.titleBn})
      Language: ${project.language.labelBn}
      Min SDK: ${project.minSdk.label}

      ## Description
      $desc

      ## Termux Free Build Commands
      ```bash
      pkg install -y openjdk-17 gradle aapt2
      gradle assembleDebug -Pandroid.aapt2FromMavenOverride=$(command -v aapt2)
      ```
      """.trimIndent()

    return files
  }

  private fun generateMainXmlLayout(
    appTitle: String,
    desc: String,
    category: AppCategory,
  ): String {
    val actionLabel =
      when (category) {
        AppCategory.CALCULATOR -> "হিসাব করুন (=)"
        AppCategory.NOTES -> "নোট সেভ করুন"
        AppCategory.CHATTING, AppCategory.AI_ASSISTANT, AppCategory.VOICE_ASSISTANT -> "মেসেজ পাঠান"
        AppCategory.MUSIC_PLAYER, AppCategory.VIDEO_PLAYER -> "প্লে / পজ"
        AppCategory.PRODUCTIVITY -> "টাস্ক যোগ করুন"
        else -> "কাজ চালান"
      }
    val hintText =
      when (category) {
        AppCategory.CALCULATOR -> "যেমন: 25+15 বা 12*8 লিখুন"
        AppCategory.NOTES -> "নতুন নোট লিখুন..."
        AppCategory.PRODUCTIVITY -> "নতুন কাজের নাম লিখুন..."
        else -> "এখানে কিছু লিখুন..."
      }
    return """
    <?xml version="1.0" encoding="utf-8"?>
    <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:background="@color/surface_bg"
        android:orientation="vertical"
        android:padding="20dp">

        <TextView
            android:id="@+id/tvAppTitle"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="${escapeXml(appTitle)}"
            android:textColor="@color/brand_primary"
            android:textSize="24sp"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/tvAppSubtitle"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="6dp"
            android:text="${escapeXml(desc.take(120))}"
            android:textColor="@color/text_primary"
            android:textSize="14sp" />

        <EditText
            android:id="@+id/etPrimaryInput"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="18dp"
            android:hint="${escapeXml(hintText)}"
            android:padding="12dp"
            android:textColor="@color/text_primary"
            android:textColorHint="#94A3B8" />

        <Button
            android:id="@+id/btnPrimaryAction"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:backgroundTint="@color/brand_primary"
            android:text="${escapeXml(actionLabel)}"
            android:textColor="#000000" />

        <ScrollView
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_marginTop="16dp"
            android:layout_weight="1">

            <TextView
                android:id="@+id/tvOutputList"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="প্রস্তুত — শুরু করতে উপরে ইনপুট দিন।"
                android:textColor="@color/text_primary"
                android:textSize="15sp" />
        </ScrollView>
    </LinearLayout>
    """.trimIndent()
  }

  private fun generateKotlinXmlMainActivity(
    pkg: String,
    appTitle: String,
    desc: String,
    category: AppCategory,
  ): String {
    val logicSnippet =
      if (category == AppCategory.CALCULATOR) {
        """
        val expr = input.replace(" ", "")
        val resultText = evaluateSimpleExpression(expr)
        items.add(0, "${'$'}expr = ${'$'}resultText")
        """.trimIndent()
      } else {
        """
        items.add(0, "• ${'$'}input")
        """.trimIndent()
      }
    val helperFun =
      if (category == AppCategory.CALCULATOR) {
        """
          private fun evaluateSimpleExpression(expr: String): String {
            return try {
              val ops = listOf('+', '-', '*', '/')
              val op = ops.firstOrNull { expr.indexOf(it, 1) > 0 } ?: return expr
              val idx = expr.indexOf(op, 1)
              val a = expr.substring(0, idx).toDouble()
              val b = expr.substring(idx + 1).toDouble()
              val res = when (op) {
                '+' -> a + b
                '-' -> a - b
                '*' -> a * b
                '/' -> if (b != 0.0) a / b else Double.NaN
                else -> 0.0
              }
              if (res % 1.0 == 0.0) res.toLong().toString() else res.toString()
            } catch (_: Exception) {
              "সঠিক গাণিতিক সংখ্যা দিন (যেমন 12+8)"
            }
          }
        """.trimIndent()
      } else {
        ""
      }

    return """
    package $pkg

    import android.os.Bundle
    import android.widget.Button
    import android.widget.EditText
    import android.widget.TextView
    import androidx.appcompat.app.AppCompatActivity

    class MainActivity : AppCompatActivity() {
      private val items = mutableListOf<String>()

      override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val titleView = findViewById<TextView>(R.id.tvAppTitle)
        val subtitleView = findViewById<TextView>(R.id.tvAppSubtitle)
        val inputField = findViewById<EditText>(R.id.etPrimaryInput)
        val actionBtn = findViewById<Button>(R.id.btnPrimaryAction)
        val outputView = findViewById<TextView>(R.id.tvOutputList)

        titleView.text = "${appTitle.replace("\"", "")}"
        subtitleView.text = "${desc.take(120).replace("\"", "")}"

        actionBtn.setOnClickListener {
          val input = inputField.text?.toString()?.trim().orEmpty()
          if (input.isNotEmpty()) {
            $logicSnippet
            inputField.setText("")
            outputView.text = items.joinToString("\n\n")
          }
        }
      }

    $helperFun
    }
    """.trimIndent()
  }

  private fun generateJavaXmlMainActivity(
    pkg: String,
    appTitle: String,
    desc: String,
    category: AppCategory,
  ): String {
    val actionLogic =
      if (category == AppCategory.CALCULATOR) {
        """
                String expr = input.replace(" ", "");
                String out = evaluateSimple(expr);
                history.insert(0, expr + " = " + out + "\n\n");
        """.trimIndent()
      } else {
        """
                history.insert(0, "• " + input + "\n\n");
        """.trimIndent()
      }

    val helperMethod =
      if (category == AppCategory.CALCULATOR) {
        """
          private String evaluateSimple(String expr) {
            try {
              char[] ops = {'+', '-', '*', '/'};
              for (char op : ops) {
                int idx = expr.indexOf(op, 1);
                if (idx > 0) {
                  double a = Double.parseDouble(expr.substring(0, idx));
                  double b = Double.parseDouble(expr.substring(idx + 1));
                  double r = 0;
                  if (op == '+') r = a + b;
                  else if (op == '-') r = a - b;
                  else if (op == '*') r = a * b;
                  else if (op == '/') r = (b != 0) ? (a / b) : 0;
                  return (r % 1 == 0) ? String.valueOf((long) r) : String.valueOf(r);
                }
              }
              return expr;
            } catch (Exception e) {
              return "Invalid input";
            }
          }
        """.trimIndent()
      } else {
        ""
      }

    return """
    package $pkg;

    import android.os.Bundle;
    import android.widget.Button;
    import android.widget.EditText;
    import android.widget.TextView;
    import androidx.appcompat.app.AppCompatActivity;

    public class MainActivity extends AppCompatActivity {
      private final StringBuilder history = new StringBuilder();

      @Override
      protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView titleView = findViewById(R.id.tvAppTitle);
        TextView subtitleView = findViewById(R.id.tvAppSubtitle);
        EditText inputField = findViewById(R.id.etPrimaryInput);
        Button actionBtn = findViewById(R.id.btnPrimaryAction);
        TextView outputView = findViewById(R.id.tvOutputList);

        titleView.setText("${appTitle.replace("\"", "")}");
        subtitleView.setText("${desc.take(120).replace("\"", "")}");

        actionBtn.setOnClickListener(v -> {
          String input = inputField.getText() == null ? "" : inputField.getText().toString().trim();
          if (!input.isEmpty()) {
            $actionLogic
            inputField.setText("");
            outputView.setText(history.toString());
          }
        });
      }

    $helperMethod
    }
    """.trimIndent()
  }

  private fun generateComposeMainActivity(
    pkg: String,
    appTitle: String,
    desc: String,
    category: AppCategory,
    theme: AppDesignTheme,
  ): String {
    val bgColor = "0xFF${theme.surfaceHex.removePrefix("#")}"
    val primaryColor = "0xFF${theme.primaryHex.removePrefix("#")}"
    val textColor = "0xFF${theme.textHex.removePrefix("#")}"

    return """
    package $pkg

    import android.os.Bundle
    import androidx.activity.ComponentActivity
    import androidx.activity.compose.setContent
    import androidx.compose.foundation.background
    import androidx.compose.foundation.layout.Arrangement
    import androidx.compose.foundation.layout.Column
    import androidx.compose.foundation.layout.Spacer
    import androidx.compose.foundation.layout.fillMaxSize
    import androidx.compose.foundation.layout.fillMaxWidth
    import androidx.compose.foundation.layout.height
    import androidx.compose.foundation.layout.padding
    import androidx.compose.foundation.lazy.LazyColumn
    import androidx.compose.foundation.lazy.items
    import androidx.compose.material3.Button
    import androidx.compose.material3.MaterialTheme
    import androidx.compose.material3.OutlinedTextField
    import androidx.compose.material3.Text
    import androidx.compose.runtime.getValue
    import androidx.compose.runtime.mutableStateListOf
    import androidx.compose.runtime.mutableStateOf
    import androidx.compose.runtime.remember
    import androidx.compose.runtime.setValue
    import androidx.compose.ui.Modifier
    import androidx.compose.ui.graphics.Color
    import androidx.compose.ui.text.font.FontWeight
    import androidx.compose.ui.unit.dp
    import androidx.compose.ui.unit.sp

    class MainActivity : ComponentActivity() {
      override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
          MaterialTheme {
            var input by remember { mutableStateOf("") }
            val records = remember { mutableStateListOf<String>() }

            Column(
              modifier = Modifier
                .fillMaxSize()
                .background(Color($bgColor))
                .padding(20.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Text(
                text = "${appTitle.replace("\"", "")}",
                color = Color($primaryColor),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
              )
              Text(
                text = "${desc.take(120).replace("\"", "")}",
                color = Color($textColor),
                fontSize = 14.sp,
              )
              OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("${category.titleEn}") },
              )
              Button(
                onClick = {
                  val t = input.trim()
                  if (t.isNotEmpty()) {
                    records.add(0, t)
                    input = ""
                  }
                },
                modifier = Modifier.fillMaxWidth(),
              ) {
                Text("যোগ করুন")
              }
              Spacer(Modifier.height(4.dp))
              LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(records) { entry ->
                  Text(text = "• " + entry, color = Color($textColor), fontSize = 15.sp)
                }
              }
            }
          }
        }
      }
    }
    """.trimIndent()
  }
}
