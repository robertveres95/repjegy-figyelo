import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.3"
}

// Verzió: ugyanaz a version.properties, mint az androidos appé
val versionName: String = rootDir.resolve("../version.properties").readLines()
    .first { it.startsWith("VERSION_NAME=") }
    .substringAfter("=").trim()
val buildNumber: Int = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

kotlin {
    jvmToolchain(17)
}

// A futó app ebből tudja a saját verzióját (frissítésfigyelő, Beállítások)
val genVersion = tasks.register("genVersion") {
    val out = layout.buildDirectory.dir("generated/version")
    outputs.dir(out)
    inputs.property("version", versionName)
    inputs.property("build", buildNumber)
    doLast {
        val f = out.get().file("refi-version.properties").asFile
        f.parentFile.mkdirs()
        f.writeText("version=$versionName\nbuild=$buildNumber\n")
    }
}

sourceSets {
    main {
        // A közös (Android + Windows) kód
        kotlin.srcDir("../shared/src/main/kotlin")
        // Repülőtér-lista és betűtípus az androidos appból (a 3D-s animáció fájljai nélkül)
        resources.srcDir("../app/src/main/assets")
        resources.srcDir("../app/src/main/res/font")
        resources.srcDir(genVersion)
        resources.exclude("splash/**")
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.json:json:20240303")
}

compose.desktop {
    application {
        mainClass = "hu.repjegy.figyelo.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8")

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "REFI"
            // MSI-verzió: a build sorszáma a harmadik tag, így minden új kiadás a régi fölé települ
            val (major, minor) = versionName.split('.').map { it.toInt() }
            packageVersion = "$major.$minor.$buildNumber"
            description = "REFI – repjegy figyelő"
            vendor = "REFI"
            // Teljes Java-futtatókörnyezet (TLS, magyar dátumformák stb.)
            includeAllModules = true

            windows {
                iconFile.set(project.file("icons/refi.ico"))
                menu = true
                menuGroup = "REFI"
                shortcut = true
                perUserInstall = true
                dirChooser = false
                // Állandó azonosító: így az új verzió frissíti a régit (nem települ mellé)
                upgradeUuid = "6f2b6f8e-6d0e-4b8e-9a0b-3b7c1e2f4a51"
            }
        }
    }
}
