plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
}

android {
  namespace = "com.orbitai"
  compileSdk = 36

  flavorDimensions += "agent"

  productFlavors {
    create("normal") {
      dimension = "agent"
      buildConfigField("String", "FLAVOR_PRESET_AGENT_ID", "\"\"")
      buildConfigField("String", "FLAVOR_PRESET_AGENT_NAME", "\"\"")
      buildConfigField("String", "FLAVOR_APP_LABEL", "\"Orbit AI\"")
      manifestPlaceholders["appLabel"] = "Orbit AI"
    }
    create("opencode") {
      dimension = "agent"
      applicationId = "Orbit.opencode"
      versionNameSuffix = "-opencode"
      buildConfigField("String", "FLAVOR_PRESET_AGENT_ID", "\"opencode\"")
      buildConfigField("String", "FLAVOR_PRESET_AGENT_NAME", "\"OpenCode\"")
      buildConfigField("String", "FLAVOR_APP_LABEL", "\"Orbit + OpenCode\"")
      // opencode is shipped as an npm package (@opencode-ai/cli), no GitHub fallback needed
      buildConfigField("String", "AGENT_FALLBACK_REPO_URL", "\"\"")
      manifestPlaceholders["appLabel"] = "Orbit + OpenCode"
    }
    create("openclaude") {
      dimension = "agent"
      applicationId = "Orbit.openclaude"
      versionNameSuffix = "-openclaude"
      buildConfigField("String", "FLAVOR_PRESET_AGENT_ID", "\"openclaude\"")
      buildConfigField("String", "FLAVOR_PRESET_AGENT_NAME", "\"OpenClaude\"")
      buildConfigField("String", "FLAVOR_APP_LABEL", "\"Orbit + OpenClaude\"")
      buildConfigField("String", "AGENT_FALLBACK_REPO_URL",
        "\"https://github.com/Gitlawb/openclaude.git\"")
      manifestPlaceholders["appLabel"] = "Orbit + OpenClaude"
    }
    create("claudecode") {
      dimension = "agent"
      applicationId = "Orbit.claudecode"
      versionNameSuffix = "-claudecode"
      buildConfigField("String", "FLAVOR_PRESET_AGENT_ID", "\"claude-code\"")
      buildConfigField("String", "FLAVOR_PRESET_AGENT_NAME", "\"Claude Code\"")
      buildConfigField("String", "FLAVOR_APP_LABEL", "\"Orbit + Claude Code\"")
      // @anthropic-ai/claude-code npm package — no public GitHub mirror
      buildConfigField("String", "AGENT_FALLBACK_REPO_URL", "\"\"")
      manifestPlaceholders["appLabel"] = "Orbit + Claude Code"
    }
    create("codex") {
      dimension = "agent"
      applicationId = "Orbit.codex"
      versionNameSuffix = "-codex"
      buildConfigField("String", "FLAVOR_PRESET_AGENT_ID", "\"codex\"")
      buildConfigField("String", "FLAVOR_PRESET_AGENT_NAME", "\"Codex\"")
      buildConfigField("String", "FLAVOR_APP_LABEL", "\"Orbit + Codex\"")
      // @openai/codex npm package — no public GitHub mirror
      buildConfigField("String", "AGENT_FALLBACK_REPO_URL", "\"\"")
      manifestPlaceholders["appLabel"] = "Orbit + Codex"
    }
    create("hermes") {
      dimension = "agent"
      applicationId = "Orbit.hermes"
      versionNameSuffix = "-hermes"
      buildConfigField("String", "FLAVOR_PRESET_AGENT_ID", "\"hermes\"")
      buildConfigField("String", "FLAVOR_PRESET_AGENT_NAME", "\"Hermes\"")
      buildConfigField("String", "FLAVOR_APP_LABEL", "\"Orbit + Hermes\"")
      // Hermes runs the REAL Nous hermes-agent (Python) locally inside a
      // glibc Debian aarch64 PRoot rootfs bundled as a flavor asset
      // (src/hermes/assets/hermes-rootfs.tar.gz). The OpenRouter key is the
      // LLM backend; the agent process itself runs on-device.
      buildConfigField("boolean", "FLAVOR_HERMES_LOCAL_AGENT", "true")
      buildConfigField("String", "AGENT_FALLBACK_REPO_URL", "\"\"")
      manifestPlaceholders["appLabel"] = "Orbit + Hermes"
    }
  }

  defaultConfig {
    applicationId = "Orbit.app"
    minSdk = 24
    targetSdk = 36
    // versionCode is AUTO-BUMPED from the build timestamp so every release is
    // strictly newer than the last. This is CRITICAL: Android's package manager
    // refuses to upgrade an APK whose versionCode is not greater than the
    // installed one. A hardcoded versionCode (old behaviour) meant updates
    // were silently rejected as "not an upgrade", forcing users to
    // uninstall/reinstall to get bug fixes.
    //
    // We use seconds since a fixed base epoch (2024-01-01) to stay well within
    // Android's int range (< 2_100_000_000) for decades of headroom.
    val EPOCH_BASE = 1_704_067_200L // 2024-01-01T00:00:00Z in seconds
    val buildTs = ((System.currentTimeMillis() / 1000L) - EPOCH_BASE).toInt()
    versionCode = buildTs
    versionName = "1.11"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // ── ABI configuration ───────────────────────────────────────────
    // Only arm64-v8a is supported — all native binaries (proot, loader,
    // libtalloc, libandroid-shmem) are arm64. The old 32-bit busybox
    // was removed because it can't run on arm64-only devices.
    ndk {
      abiFilters += listOf("arm64-v8a")
    }

    // ── Centralized, overridable app-level constants ──────────────
    // Forks can override any of these with -P<key>=<value> on the Gradle
    // command line, or via ~/.gradle/gradle.properties, without editing code.
    buildConfigField("String", "OPENROUTER_REFERRER_URL",
      "\"${project.findProperty("orbit.openRouterReferrerUrl") ?: "https://github.com/TheOneWhoSpeaksJanna/Orbit-AI"}\"")
    buildConfigField("String", "OPENROUTER_APP_TITLE",
      "\"${project.findProperty("orbit.openRouterAppTitle") ?: "Orbit AI"}\"")

    // Per-flavor fallback GitHub repo used when an agent's bundled tarball
    // is missing from APK assets. Each flavor section below can override.
    buildConfigField("String", "AGENT_FALLBACK_REPO_URL",
      "\"${project.findProperty("orbit.agentFallbackRepoUrl") ?: "https://github.com/Gitlawb/openclaude.git"}\"")
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH")?.takeIf { it.isNotBlank() } ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("ciDebug") {
      val debugKeystore = file("debug.keystore")
      // debug.keystore is committed to the repo so all builds (CI + local)
      // use the same signing cert. Do NOT generate a new one if missing —
      // that causes update-install failures due to signature mismatch.
      storeFile = debugKeystore
      storePassword = System.getenv("CI_DEBUG_STORE_PASSWORD") ?: "android"
      keyAlias = "androiddebugkey"
      keyPassword = System.getenv("CI_DEBUG_KEY_PASSWORD") ?: "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      signingConfig = signingConfigs.getByName("ciDebug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }

  // Extract native libraries (.so files) from the APK to the filesystem.
  // REQUIRED so libproot.so and libproot_loader.so are extracted to
  // /data/app/<pkg>/lib/arm64/ (apk_data_file label, exec allowed).
  // Without this, .so files stay page-aligned inside the APK and can't
  // be execve()'d — PRoot itself couldn't run.
  packaging {
    jniLibs {
      useLegacyPackaging = true
    }
  }

  // Prevent AAPT2 from re-compressing assets that are ALREADY compressed
  // archives AND that the app reads via context.assets.open() + a stream
  // decoder (ZipInputStream / raw copy). The old list ("zip","deb","tgz")
  // forced AAPT2 to STORE these already-gzip-compressed files verbatim inside
  // the APK, bloating every flavor by ~120 MB (CLI) / 30 MB (shared) with no
  // benefit — ZipInputStream and asset streams read compressed entries fine.
  // We now let AAPT2 compress them. Keep "tgz" out of noCompress too for the
  // same reason (openclaude.tgz is read via a stream, not mmap).
  // See SIZE_REDUCTION_REPORT.md (fix #1).
  androidResources {
    noCompress += listOf()
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }

  // ── Compose compiler performance flags ──────────────────────────────
  // Strong skipping (Kotlin 2.2+) lets the compiler skip recomposition
  // for lambdas and unstable params that are structurally equal, which
  // cuts a lot of unnecessary recompositions in deeply-nested UI trees
  // like ours. Default in newer Kotlin but explicit here for clarity.
  // We also enable inclusion of source info in debug builds to make
  // Compose Layout Inspector recomposition counts reliable.
  composeCompiler {
    // Disable the strong-skipping requirement for explicit @Stable
    // annotations on simple value classes — the compiler will infer
    // stability for them. (This is the default but pinned so future
    // Kotlin upgrades don't silently regress performance.)
    includeSourceInformation = true
  }

  // ── Pre-bundle offline .deb packages (agent flavors only) ─────────
  // Downloads nodejs, git, python3 + all dependencies as .deb files
  // from packages.termux.dev. These are bundled in assets/offline-debs/
  // and installed via `dpkg -i` at runtime — eliminates the 5-minute
  // apt download on first launch.
  //
  // IMPORTANT: The .debs go into FLAVOR-SPECIFIC asset dirs, not main.
  // The 'normal' flavor does NOT get them (keeps the APK at ~50 MB).
  // Only agent flavors (openclaude, opencode, claudecode, codex) bundle
  // the ~120 MB of .debs. This task is best-effort: if the download
  // fails, TermuxRuntime falls back to apt install at runtime.
  val agentFlavorSet = setOf("openclaude", "opencode", "claudecode", "codex", "hermes")
  // Hermes does NOT use the Termux .deb toolchain (it ships a glibc rootfs
  // instead), so the offline-debs download only applies to the 4 CLI agents.
  val debFlavorSet = setOf("openclaude", "opencode", "claudecode", "codex")
  androidComponents {
    onVariants { variant ->
      val flavorName = variant.flavorName ?: return@onVariants
      if (flavorName !in agentFlavorSet) return@onVariants

      // offline-debs only for the CLI-agent flavors (not Hermes)
      val capitalizedVariant = variant.name.replaceFirstChar { it.uppercase() }

      if (flavorName in debFlavorSet) {
        val downloadTaskName = "downloadOfflinePackages$capitalizedVariant"
        val downloadTask = tasks.register<Exec>(downloadTaskName) {
          val script = rootProject.projectDir.resolve("scripts/download-offline-packages.py")
          val outputDir = projectDir.resolve("src/$flavorName/assets/offline-debs")
          commandLine("python3", script.absolutePath, outputDir.absolutePath)
          outputs.dir(outputDir)
          doFirst {
            outputDir.mkdirs()
          }
        }
        tasks.matching { it.name == "merge${capitalizedVariant}Assets" }.configureEach {
          dependsOn(downloadTask)
        }
      }

      // ── Emit version-info.json into assets for the updater ─────────
      // The UpdateManager reads this from each GitHub release to decide
      // precisely whether a newer build exists (exact versionCode per
      // flavor). The versionCode matches the auto-bumped defaultConfig
      // value so BuildConfig.VERSION_CODE and this file agree.
      val versionInfoTask = tasks.register("emitVersionInfo${capitalizedVariant}") {
        val outFile = projectDir.resolve("src/$flavorName/assets/version-info.json")
        outputs.file(outFile)
        doLast {
          val epochBase = 1_704_067_200L
          val vc = ((System.currentTimeMillis() / 1000L) - epochBase).toInt()
          val info = """
          {
            "tag": "v$vc",
            "flavors": {
              "normal":     { "versionCode": $vc, "apk": "app-normal-debug.apk" },
              "openclaude": { "versionCode": $vc, "apk": "app-openclaude-debug.apk" },
              "opencode":   { "versionCode": $vc, "apk": "app-opencode-debug.apk" },
              "claudecode": { "versionCode": $vc, "apk": "app-claudecode-debug.apk" },
              "codex":      { "versionCode": $vc, "apk": "app-codex-debug.apk" },
              "hermes":     { "versionCode": $vc, "apk": "app-hermes-debug.apk" }
            }
          }
          """.trimIndent()
          outFile.parentFile.mkdirs()
          outFile.writeText(info)
        }
      }
      tasks.matching { it.name == "merge${capitalizedVariant}Assets" }.configureEach {
        dependsOn(versionInfoTask)
      }

      // ── Pre-bundle OpenClaude npm tarball ──────────────────────────
      // Downloads @gitlawb/openclaude as a .tgz during the build so
      // the app can install it offline (no npm registry access needed
      // on first launch). Only for the openclaude flavor.
      if (flavorName == "openclaude") {
        val downloadTarballTask = tasks.register<Exec>("downloadOpenclaudeTarball$capitalizedVariant") {
          val script = rootProject.projectDir.resolve("scripts/download-openclaude-tarball.py")
          val outputDir = projectDir.resolve("src/$flavorName/assets")
          commandLine("python3", script.absolutePath, outputDir.absolutePath)
          outputs.dir(outputDir)
          doFirst {
            outputDir.mkdirs()
          }
        }

        tasks.matching { it.name == "merge${capitalizedVariant}Assets" }.configureEach {
          dependsOn(downloadTarballTask)
        }
      }
    }
  }
}

secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.security.crypto)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.converter.moshi)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation("dev.rikka.shizuku:api:13.1.5")
  implementation("dev.rikka.shizuku:provider:13.1.5")
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
}

tasks.withType<Test> {
  maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
  forkEvery = 50
}

val kspFiles by configurations.creating {
  extendsFrom(configurations.ksp.get())
  isCanBeResolved = true
  isCanBeConsumed = false
}

val extractSqliteNative by tasks.registering {
  val inputFiles = objects.fileCollection().from(kspFiles)
  inputs.files(inputFiles)

  val outputDir = layout.buildDirectory.dir("generated/sqlite-native")
  outputs.dir(outputDir)

  doLast {
    val sqliteJar = inputFiles.files.firstOrNull {
      it.name.startsWith("sqlite-jdbc") && it.name.endsWith(".jar")
    } ?: return@doLast

    val osName = System.getProperty("os.name").lowercase()
    val osArch = System.getProperty("os.arch").lowercase()
    val osPrefix = when {
      osName.contains("linux") -> "Linux"
      osName.contains("mac") || osName.contains("darwin") -> "Mac"
      osName.contains("win") -> "Windows"
      else -> "Linux"
    }
    val archSuffix = when {
      osArch.contains("aarch64") || osArch.contains("arm64") -> "aarch64"
      osArch.contains("amd64") || osArch.contains("x86_64") -> "x86_64"
      osArch.contains("x86") -> "x86"
      else -> "x86_64"
    }

    val nativeDir = "org/sqlite/native/$osPrefix/$archSuffix"
    val libDir = outputDir.get().asFile.resolve(nativeDir)
    delete(libDir)
    libDir.mkdirs()

    copy {
      from(zipTree(sqliteJar)) {
        include("$nativeDir/libsqlitejdbc.so")
      }
      into(outputDir.get().asFile)
    }

    val soFile = libDir.resolve("libsqlitejdbc.so")
    if (soFile.exists()) {
      System.setProperty("org.sqlite.lib.path", libDir.absolutePath)
      System.setProperty("org.sqlite.lib.name", "libsqlitejdbc.so")
      logger.lifecycle("sqlite-jdbc native lib extracted to ${libDir.absolutePath}")
    } else {
      logger.warn("Native lib NOT found for $osPrefix/$archSuffix in sqlite-jdbc jar")
    }
  }
}

tasks.matching { it.name.startsWith("ksp") }.configureEach {
  dependsOn(extractSqliteNative)
}
