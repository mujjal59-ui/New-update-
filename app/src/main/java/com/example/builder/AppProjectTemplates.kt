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
      org.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8
      org.gradle.daemon=false
      kotlin.compiler.execution.strategy=in-process
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
    val effectiveCategory = AppRichScreenTemplates.resolveEffectiveCategory(project.category, desc)

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

    files["app/src/main/res/mipmap-anydpi/ic_launcher.xml"] =
      """
      <?xml version="1.0" encoding="utf-8"?>
      <layer-list xmlns:android="http://schemas.android.com/apk/res/android">
          <item android:drawable="@color/surface_bg" />
          <item android:drawable="@drawable/ic_launcher_foreground" />
      </layer-list>
      """.trimIndent()

    if (!isCompose && effectiveCategory == AppCategory.CALCULATOR) {
      files["app/src/main/res/drawable/bg_calc_display.xml"] =
        """
        <shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
            <solid android:color="#141822" />
            <corners android:radius="18dp" />
        </shape>
        """.trimIndent()
      files["app/src/main/res/drawable/bg_calc_num.xml"] =
        """
        <shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
            <solid android:color="#1B202C" />
        </shape>
        """.trimIndent()
      files["app/src/main/res/drawable/bg_calc_op.xml"] =
        """
        <shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
            <solid android:color="#262D3D" />
        </shape>
        """.trimIndent()
      files["app/src/main/res/drawable/bg_calc_action.xml"] =
        """
        <shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
            <solid android:color="#333C4D" />
        </shape>
        """.trimIndent()
      files["app/src/main/res/drawable/bg_calc_eq.xml"] =
        """
        <shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
            <solid android:color="#FF9500" />
        </shape>
        """.trimIndent()
    }

    // 4. Layout XML (for Kotlin XML / Java XML) + Source Code
    when (project.language) {
      ProgrammingLanguage.KOTLIN_COMPOSE -> {
        files["app/src/main/java/$pkgPath/MainActivity.kt"] =
          AppRichScreenTemplates.composeMainActivity(pkg, appTitle, desc, effectiveCategory, theme)
      }
      ProgrammingLanguage.KOTLIN -> {
        when (effectiveCategory) {
          AppCategory.CALCULATOR -> {
            files["app/src/main/res/layout/activity_main.xml"] = generateCalculatorXmlLayout(appTitle)
            files["app/src/main/java/$pkgPath/MainActivity.kt"] = generateCalculatorKotlinActivity(pkg)
          }
          AppCategory.MINI_GAME -> {
            files["app/src/main/res/layout/activity_main.xml"] = generateMiniGameXmlLayout(appTitle)
            files["app/src/main/java/$pkgPath/MainActivity.kt"] = generateMiniGameKotlinActivity(pkg)
          }
          else -> {
            files["app/src/main/res/layout/activity_main.xml"] =
              generateMainXmlLayout(appTitle, desc, effectiveCategory)
            files["app/src/main/java/$pkgPath/MainActivity.kt"] =
              generateKotlinXmlMainActivity(pkg, appTitle, desc, effectiveCategory)
          }
        }
      }
      ProgrammingLanguage.JAVA -> {
        when (effectiveCategory) {
          AppCategory.CALCULATOR -> {
            files["app/src/main/res/layout/activity_main.xml"] = generateCalculatorXmlLayout(appTitle)
            files["app/src/main/java/$pkgPath/MainActivity.java"] = generateCalculatorJavaActivity(pkg)
          }
          AppCategory.MINI_GAME -> {
            files["app/src/main/res/layout/activity_main.xml"] = generateMiniGameXmlLayout(appTitle)
            files["app/src/main/java/$pkgPath/MainActivity.java"] = generateMiniGameJavaActivity(pkg)
          }
          else -> {
            files["app/src/main/res/layout/activity_main.xml"] =
              generateMainXmlLayout(appTitle, desc, effectiveCategory)
            files["app/src/main/java/$pkgPath/MainActivity.java"] =
              generateJavaXmlMainActivity(pkg, appTitle, desc, effectiveCategory)
          }
        }
      }
    }

    files["README_BUILD.md"] =
      """
      # $appTitle
      Package: `$pkg`
      Category: ${effectiveCategory.titleEn} (${effectiveCategory.titleBn})
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

  private fun generateCalculatorXmlLayout(appTitle: String): String {
    fun calcBtn(id: String, label: String, bgDrawable: String, textColor: String): String =
      """
            <TextView
                android:id="@+id/$id"
                android:layout_width="0dp"
                android:layout_height="64dp"
                android:layout_weight="1"
                android:layout_margin="5dp"
                android:background="@drawable/$bgDrawable"
                android:clickable="true"
                android:focusable="true"
                android:gravity="center"
                android:text="$label"
                android:textColor="$textColor"
                android:textSize="22sp"
                android:textStyle="bold" />
      """.trimIndent()

    return """
    <?xml version="1.0" encoding="utf-8"?>
    <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:background="#0A0C10"
        android:gravity="bottom"
        android:orientation="vertical"
        android:padding="16dp">

        <TextView
            android:id="@+id/tvCalcTitle"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="${escapeXml(appTitle)}"
            android:textColor="#00E5FF"
            android:textSize="16sp"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/tvHistory"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:gravity="end"
            android:maxLines="1"
            android:text="প্রস্তুত"
            android:textColor="#94A3B8"
            android:textSize="14sp" />

        <TextView
            android:id="@+id/tvDisplay"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="6dp"
            android:layout_marginBottom="14dp"
            android:background="@drawable/bg_calc_display"
            android:gravity="end"
            android:maxLines="1"
            android:paddingHorizontal="16dp"
            android:paddingVertical="18dp"
            android:text="0"
            android:textColor="#FFFFFF"
            android:textSize="42sp" />

        <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="horizontal">
            ${calcBtn("btnAc", "AC", "bg_calc_action", "#00E5FF")}
            ${calcBtn("btnBack", "⌫", "bg_calc_action", "#00E5FF")}
            ${calcBtn("btnPct", "%", "bg_calc_op", "#FF9F1A")}
            ${calcBtn("btnDiv", "÷", "bg_calc_op", "#FF9F1A")}
        </LinearLayout>

        <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="horizontal">
            ${calcBtn("btn7", "7", "bg_calc_num", "#FFFFFF")}
            ${calcBtn("btn8", "8", "bg_calc_num", "#FFFFFF")}
            ${calcBtn("btn9", "9", "bg_calc_num", "#FFFFFF")}
            ${calcBtn("btnMul", "×", "bg_calc_op", "#FF9F1A")}
        </LinearLayout>

        <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="horizontal">
            ${calcBtn("btn4", "4", "bg_calc_num", "#FFFFFF")}
            ${calcBtn("btn5", "5", "bg_calc_num", "#FFFFFF")}
            ${calcBtn("btn6", "6", "bg_calc_num", "#FFFFFF")}
            ${calcBtn("btnSub", "−", "bg_calc_op", "#FF9F1A")}
        </LinearLayout>

        <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="horizontal">
            ${calcBtn("btn1", "1", "bg_calc_num", "#FFFFFF")}
            ${calcBtn("btn2", "2", "bg_calc_num", "#FFFFFF")}
            ${calcBtn("btn3", "3", "bg_calc_num", "#FFFFFF")}
            ${calcBtn("btnAdd", "+", "bg_calc_op", "#FF9F1A")}
        </LinearLayout>

        <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="horizontal">
            ${calcBtn("btnSign", "±", "bg_calc_action", "#00E5FF")}
            ${calcBtn("btn0", "0", "bg_calc_num", "#FFFFFF")}
            ${calcBtn("btnDot", ".", "bg_calc_num", "#FFFFFF")}
            ${calcBtn("btnEq", "=", "bg_calc_eq", "#FFFFFF")}
        </LinearLayout>
    </LinearLayout>
    """.trimIndent()
  }

  private fun generateCalculatorKotlinActivity(pkg: String): String =
    """
    package $pkg

    import android.os.Bundle
    import android.widget.TextView
    import androidx.appcompat.app.AppCompatActivity
    import java.util.Locale

    class MainActivity : AppCompatActivity() {
      private var expr = ""

      override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val tvDisplay = findViewById<TextView>(R.id.tvDisplay)
        val tvHistory = findViewById<TextView>(R.id.tvHistory)

        fun appendToken(tok: String) {
          val ops = setOf("+", "−", "×", "÷", "%")
          if (expr.isEmpty() && tok in ops && tvDisplay.text.toString() != "0" && tvDisplay.text.toString() != "Error") {
            expr = tvDisplay.text.toString() + tok
          } else {
            expr += tok
          }
          tvDisplay.text = expr
        }

        val digitMap = mapOf(
          R.id.btn0 to "0", R.id.btn1 to "1", R.id.btn2 to "2", R.id.btn3 to "3",
          R.id.btn4 to "4", R.id.btn5 to "5", R.id.btn6 to "6", R.id.btn7 to "7",
          R.id.btn8 to "8", R.id.btn9 to "9", R.id.btnDot to ".",
          R.id.btnAdd to "+", R.id.btnSub to "−", R.id.btnMul to "×", R.id.btnDiv to "÷", R.id.btnPct to "%"
        )
        digitMap.forEach { (id, tok) ->
          findViewById<TextView>(id).setOnClickListener { appendToken(tok) }
        }

        findViewById<TextView>(R.id.btnAc).setOnClickListener {
          expr = ""
          tvDisplay.text = "0"
        }

        findViewById<TextView>(R.id.btnBack).setOnClickListener {
          if (expr.isNotEmpty()) {
            expr = expr.dropLast(1)
            tvDisplay.text = expr.ifEmpty { "0" }
          }
        }

        findViewById<TextView>(R.id.btnSign).setOnClickListener {
          val cur = tvDisplay.text.toString().toDoubleOrNull()
          if (cur != null && cur != 0.0) {
            val f = -cur
            val fmt = if (f % 1.0 == 0.0) f.toLong().toString() else f.toString()
            expr = fmt
            tvDisplay.text = fmt
          }
        }

        findViewById<TextView>(R.id.btnEq).setOnClickListener {
          val source = expr.ifBlank { tvDisplay.text.toString() }
          val res = evaluate(source)
          tvHistory.text = "${'$'}source = ${'$'}res"
          tvDisplay.text = res
          expr = if (res == "Error") "" else res
        }
      }

      private fun evaluate(raw: String): String {
        return try {
          val clean = raw.replace("×", "*").replace("÷", "/").replace("−", "-")
          val tokens = Regex("(-?\\d+(?:\\.\\d+)?|[+\\-*/%])").findAll(clean).map { it.value }.toList()
          if (tokens.isEmpty()) return "0"
          var acc = tokens[0].toDouble()
          var i = 1
          while (i + 1 < tokens.size) {
            val op = tokens[i]
            val rhs = tokens[i + 1].toDouble()
            acc = when (op) {
              "+" -> acc + rhs
              "-" -> acc - rhs
              "*" -> acc * rhs
              "/" -> if (rhs == 0.0) return "Error" else acc / rhs
              "%" -> acc % rhs
              else -> acc
            }
            i += 2
          }
          if (acc % 1.0 == 0.0) acc.toLong().toString()
          else String.format(Locale.US, "%.4f", acc).trimEnd('0').trimEnd('.')
        } catch (_: Exception) {
          "Error"
        }
      }
    }
    """.trimIndent()

  private fun generateCalculatorJavaActivity(pkg: String): String =
    """
    package $pkg;

    import android.os.Bundle;
    import android.widget.TextView;
    import androidx.appcompat.app.AppCompatActivity;
    import java.util.ArrayList;
    import java.util.List;
    import java.util.Locale;
    import java.util.regex.Matcher;
    import java.util.regex.Pattern;

    public class MainActivity extends AppCompatActivity {
      private String expr = "";

      @Override
      protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView tvDisplay = findViewById(R.id.tvDisplay);
        TextView tvHistory = findViewById(R.id.tvHistory);

        int[] ids = {R.id.btn0, R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4, R.id.btn5, R.id.btn6, R.id.btn7, R.id.btn8, R.id.btn9, R.id.btnDot, R.id.btnAdd, R.id.btnSub, R.id.btnMul, R.id.btnDiv, R.id.btnPct};
        String[] toks = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "+", "−", "×", "÷", "%"};
        for (int i = 0; i < ids.length; i++) {
          final String tok = toks[i];
          findViewById(ids[i]).setOnClickListener(v -> {
            String cur = tvDisplay.getText().toString();
            if (expr.isEmpty() && "+−×÷%".contains(tok) && !cur.equals("0") && !cur.equals("Error")) {
              expr = cur + tok;
            } else {
              expr += tok;
            }
            tvDisplay.setText(expr);
          });
        }

        findViewById(R.id.btnAc).setOnClickListener(v -> {
          expr = "";
          tvDisplay.setText("0");
        });

        findViewById(R.id.btnBack).setOnClickListener(v -> {
          if (!expr.isEmpty()) {
            expr = expr.substring(0, expr.length() - 1);
            tvDisplay.setText(expr.isEmpty() ? "0" : expr);
          }
        });

        findViewById(R.id.btnSign).setOnClickListener(v -> {
          try {
            double val = Double.parseDouble(tvDisplay.getText().toString());
            if (val != 0) {
              double f = -val;
              String fmt = (f % 1.0 == 0.0) ? String.valueOf((long) f) : String.valueOf(f);
              expr = fmt;
              tvDisplay.setText(fmt);
            }
          } catch (Exception ignored) {}
        });

        findViewById(R.id.btnEq).setOnClickListener(v -> {
          String source = expr.isEmpty() ? tvDisplay.getText().toString() : expr;
          String res = evaluate(source);
          tvHistory.setText(source + " = " + res);
          tvDisplay.setText(res);
          expr = res.equals("Error") ? "" : res;
        });
      }

      private String evaluate(String raw) {
        try {
          String clean = raw.replace("×", "*").replace("÷", "/").replace("−", "-");
          Matcher m = Pattern.compile("(-?\\d+(?:\\.\\d+)?|[+\\-*/%])").matcher(clean);
          List<String> tokens = new ArrayList<>();
          while (m.find()) tokens.add(m.group());
          if (tokens.isEmpty()) return "0";
          double acc = Double.parseDouble(tokens.get(0));
          for (int i = 1; i + 1 < tokens.size(); i += 2) {
            String op = tokens.get(i);
            double rhs = Double.parseDouble(tokens.get(i + 1));
            if (op.equals("+")) acc += rhs;
            else if (op.equals("-")) acc -= rhs;
            else if (op.equals("*")) acc *= rhs;
            else if (op.equals("/")) {
              if (rhs == 0) return "Error";
              acc /= rhs;
            } else if (op.equals("%")) acc %= rhs;
          }
          if (acc % 1.0 == 0.0) return String.valueOf((long) acc);
          return String.format(Locale.US, "%.4f", acc).replaceAll("0+$", "").replaceAll("\\.$", "");
        } catch (Exception e) {
          return "Error";
        }
      }
    }
    """.trimIndent()

  private fun generateMiniGameXmlLayout(appTitle: String): String {
    fun cellBtn(idx: Int): String =
      """
            <Button
                android:id="@+id/cell$idx"
                android:layout_width="0dp"
                android:layout_height="88dp"
                android:layout_weight="1"
                android:layout_margin="5dp"
                android:backgroundTint="#16213E"
                android:text=""
                android:textColor="#00E5FF"
                android:textSize="30sp"
                android:textStyle="bold" />
      """.trimIndent()

    return """
    <?xml version="1.0" encoding="utf-8"?>
    <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:background="@color/surface_bg"
        android:gravity="center_horizontal"
        android:orientation="vertical"
        android:padding="20dp">

        <TextView
            android:id="@+id/tvGameTitle"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="${escapeXml(appTitle)}"
            android:textColor="@color/brand_primary"
            android:textSize="24sp"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/tvScore"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:text="আপনি (X): 0  •  AI (O): 0"
            android:textColor="@color/brand_accent"
            android:textSize="16sp"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/tvStatus"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="10dp"
            android:layout_marginBottom="14dp"
            android:text="আপনার চাল (X) — যেকোনো ঘরে ট্যাপ করুন"
            android:textColor="@color/text_primary"
            android:textSize="15sp" />

        <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="horizontal">
            ${cellBtn(0)} ${cellBtn(1)} ${cellBtn(2)}
        </LinearLayout>
        <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="horizontal">
            ${cellBtn(3)} ${cellBtn(4)} ${cellBtn(5)}
        </LinearLayout>
        <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="horizontal">
            ${cellBtn(6)} ${cellBtn(7)} ${cellBtn(8)}
        </LinearLayout>

        <Button
            android:id="@+id/btnResetGame"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="18dp"
            android:backgroundTint="@color/brand_primary"
            android:text="নতুন রাউন্ড শুরু করুন"
            android:textColor="#000000" />
    </LinearLayout>
    """.trimIndent()
  }

  private fun generateMiniGameKotlinActivity(pkg: String): String =
    """
    package $pkg

    import android.os.Bundle
    import android.widget.Button
    import android.widget.TextView
    import androidx.appcompat.app.AppCompatActivity

    class MainActivity : AppCompatActivity() {
      private val board = Array(9) { "" }
      private var playerScore = 0
      private var aiScore = 0
      private var over = false

      override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val tvScore = findViewById<TextView>(R.id.tvScore)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val cells = listOf(
          R.id.cell0, R.id.cell1, R.id.cell2,
          R.id.cell3, R.id.cell4, R.id.cell5,
          R.id.cell6, R.id.cell7, R.id.cell8,
        ).map { findViewById<Button>(it) }

        fun winner(): String? {
          val lines = arrayOf(
            intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8),
            intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8),
            intArrayOf(0, 4, 8), intArrayOf(2, 4, 6),
          )
          for (l in lines) {
            val a = board[l[0]]
            if (a.isNotEmpty() && a == board[l[1]] && a == board[l[2]]) return a
          }
          return null
        }

        fun updateUi() {
          for (i in 0 until 9) cells[i].text = board[i]
          tvScore.text = "আপনি (X): ${'$'}playerScore  •  AI (O): ${'$'}aiScore"
        }

        cells.forEachIndexed { idx, btn ->
          btn.setOnClickListener {
            if (over || board[idx].isNotEmpty()) return@setOnClickListener
            board[idx] = "X"
            if (winner() == "X") {
              playerScore++
              over = true
              tvStatus.text = "অভিনন্দন! আপনি (X) জিতেছেন!"
              updateUi()
              return@setOnClickListener
            }
            val empty = (0 until 9).filter { board[it].isEmpty() }
            if (empty.isEmpty()) {
              over = true
              tvStatus.text = "ম্যাচ ড্র হয়েছে!"
              updateUi()
              return@setOnClickListener
            }
            val aiPick = if (4 in empty) 4 else empty.random()
            board[aiPick] = "O"
            if (winner() == "O") {
              aiScore++
              over = true
              tvStatus.text = "AI (O) জিতেছে! আবার চেষ্টা করুন"
            } else if (board.none { it.isEmpty() }) {
              over = true
              tvStatus.text = "ম্যাচ ড্র হয়েছে!"
            }
            updateUi()
          }
        }

        findViewById<Button>(R.id.btnResetGame).setOnClickListener {
          for (i in 0 until 9) board[i] = ""
          over = false
          tvStatus.text = "আপনার চাল (X) — যেকোনো ঘরে ট্যাপ করুন"
          updateUi()
        }
      }
    }
    """.trimIndent()

  private fun generateMiniGameJavaActivity(pkg: String): String =
    """
    package $pkg;

    import android.os.Bundle;
    import android.widget.Button;
    import android.widget.TextView;
    import androidx.appcompat.app.AppCompatActivity;
    import java.util.ArrayList;
    import java.util.List;
    import java.util.Random;

    public class MainActivity extends AppCompatActivity {
      private final String[] board = {"", "", "", "", "", "", "", "", ""};
      private int playerScore = 0;
      private int aiScore = 0;
      private boolean over = false;
      private final Random rnd = new Random();

      @Override
      protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView tvScore = findViewById(R.id.tvScore);
        TextView tvStatus = findViewById(R.id.tvStatus);
        int[] ids = {R.id.cell0, R.id.cell1, R.id.cell2, R.id.cell3, R.id.cell4, R.id.cell5, R.id.cell6, R.id.cell7, R.id.cell8};
        Button[] cells = new Button[9];
        for (int i = 0; i < 9; i++) cells[i] = findViewById(ids[i]);

        Runnable refresh = () -> {
          for (int i = 0; i < 9; i++) cells[i].setText(board[i]);
          tvScore.setText("আপনি (X): " + playerScore + "  •  AI (O): " + aiScore);
        };

        for (int i = 0; i < 9; i++) {
          final int idx = i;
          cells[i].setOnClickListener(v -> {
            if (over || !board[idx].isEmpty()) return;
            board[idx] = "X";
            if ("X".equals(winner())) {
              playerScore++;
              over = true;
              tvStatus.setText("অভিনন্দন! আপনি (X) জিতেছেন!");
              refresh.run();
              return;
            }
            List<Integer> empty = new ArrayList<>();
            for (int j = 0; j < 9; j++) if (board[j].isEmpty()) empty.add(j);
            if (empty.isEmpty()) {
              over = true;
              tvStatus.setText("ম্যাচ ড্র হয়েছে!");
              refresh.run();
              return;
            }
            int pick = empty.contains(4) ? 4 : empty.get(rnd.nextInt(empty.size()));
            board[pick] = "O";
            if ("O".equals(winner())) {
              aiScore++;
              over = true;
              tvStatus.setText("AI (O) জিতেছে! আবার চেষ্টা করুন");
            } else if (empty.size() == 1) {
              over = true;
              tvStatus.setText("ম্যাচ ড্র হয়েছে!");
            }
            refresh.run();
          });
        }

        findViewById(R.id.btnResetGame).setOnClickListener(v -> {
          for (int i = 0; i < 9; i++) board[i] = "";
          over = false;
          tvStatus.setText("আপনার চাল (X) — যেকোনো ঘরে ট্যাপ করুন");
          refresh.run();
        });
      }

      private String winner() {
        int[][] lines = {{0,1,2},{3,4,5},{6,7,8},{0,3,6},{1,4,7},{2,5,8},{0,4,8},{2,4,6}};
        for (int[] l : lines) {
          String a = board[l[0]];
          if (!a.isEmpty() && a.equals(board[l[1]]) && a.equals(board[l[2]])) return a;
        }
        return null;
      }
    }
    """.trimIndent()

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
    val isChatLike = category == AppCategory.AI_ASSISTANT || category == AppCategory.CHATTING || category == AppCategory.VOICE_ASSISTANT
    val logicSnippet =
      if (isChatLike) {
        """
        items.add(0, "🤖 উত্তর: আপনার বার্তা \"${'$'}input\" পেয়েছি — আমি সাহায্য করতে প্রস্তুত!")
        items.add(0, "👤 আপনি: ${'$'}input")
        """.trimIndent()
      } else {
        """
        items.add(0, "• ${'$'}input")
        """.trimIndent()
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
    }
    """.trimIndent()
  }

  private fun generateJavaXmlMainActivity(
    pkg: String,
    appTitle: String,
    desc: String,
    category: AppCategory,
  ): String {
    val isChatLike = category == AppCategory.AI_ASSISTANT || category == AppCategory.CHATTING || category == AppCategory.VOICE_ASSISTANT
    val actionLogic =
      if (isChatLike) {
        """
                history.insert(0, "👤 আপনি: " + input + "\n🤖 উত্তর: আপনার বার্তা পেয়েছি — আমি প্রস্তুত!\n\n");
        """.trimIndent()
      } else {
        """
                history.insert(0, "• " + input + "\n\n");
        """.trimIndent()
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
    }
    """.trimIndent()
  }
}
