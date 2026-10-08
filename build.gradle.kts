import org.gradle.internal.os.OperatingSystem

plugins {
    application
}

group = "dev.dbviewer"

val appName = "DBViewer"
val mainClassName = "dev.dbviewer.App"
val jvmFlags = listOf("-XX:+UseSerialGC", "-Xshare:auto", "--enable-native-access=ALL-UNNAMED")

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("com.formdev:flatlaf:3.7.2")
    implementation("com.formdev:flatlaf-fonts-inter:4.1")
    implementation("com.formdev:flatlaf-fonts-jetbrains-mono:2.304")
    implementation("com.fifesoft:rsyntaxtextarea:3.6.0")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")

    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.release = 21
    options.encoding = "UTF-8"
}

tasks.processResources {
    val props = mapOf("version" to version.toString(), "repo" to providers.gradleProperty("githubRepo").get())
    inputs.properties(props)
    filesMatching("app.properties") { expand(props) }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

application {
    mainClass = mainClassName
    applicationDefaultJvmArgs = jvmFlags
}

// ---- Native packaging: jlink a trimmed runtime, then jpackage it -------------------------

val os: OperatingSystem = OperatingSystem.current()
val nativeDir = when {
    os.isMacOsX -> "Mac"
    os.isWindows -> "Windows"
    else -> "Linux"
}
val jdkBin = File(System.getProperty("java.home"), "bin")
fun tool(name: String) = File(jdkBin, if (os.isWindows) "$name.exe" else name).path

// sqlite-jdbc ships natives for every platform (~14 MB); keep only the one being packaged.
val slimSqlite by tasks.registering(Zip::class) {
    archiveFileName = "sqlite-jdbc.jar"
    destinationDirectory = layout.buildDirectory.dir("package/slim")
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.startsWith("sqlite-jdbc") }
            .map { zipTree(it) }
    })
    exclude {
        val p = it.path
        p.startsWith("org/sqlite/native/") && !p.startsWith("org/sqlite/native/$nativeDir")
    }
}

val stageLibs by tasks.registering(Sync::class) {
    into(layout.buildDirectory.dir("package/libs"))
    from(tasks.jar)
    from(slimSqlite)
    from(configurations.runtimeClasspath) {
        exclude { it.name.startsWith("sqlite-jdbc") }
    }
}

val runtimeDir = layout.buildDirectory.dir("package/runtime")

val jlinkRuntime by tasks.registering(Exec::class) {
    val out = runtimeDir.get().asFile
    outputs.dir(out)
    doFirst { out.deleteRecursively() }
    commandLine(
        tool("jlink"),
        "--add-modules", "java.base,java.desktop,java.sql,java.prefs,java.logging,jdk.crypto.ec",
        "--strip-debug", "--no-header-files", "--no-man-pages",
        "--compress", "zip-9",
        "--output", out.path,
    )
}

fun registerJpackage(taskName: String, type: String, extra: List<String> = emptyList()) =
    tasks.register<Exec>(taskName) {
        group = "distribution"
        dependsOn(stageLibs, jlinkRuntime)
        val dest = layout.buildDirectory.dir("dist/$type").get().asFile
        val libs = layout.buildDirectory.dir("package/libs").get().asFile
        val runtime = runtimeDir.get().asFile
        val jarName = tasks.jar.get().archiveFileName.get()
        doFirst { dest.deleteRecursively() }
        commandLine(
            listOf(
                tool("jpackage"),
                "--type", type,
                "--name", appName,
                "--app-version", version.toString(),
                "--input", libs.path,
                "--main-jar", jarName,
                "--main-class", mainClassName,
                "--runtime-image", runtime.path,
                "--dest", dest.path,
                "--icon", file(if (os.isWindows) "packaging/DBViewer.ico" else "packaging/DBViewer.icns").path,
            ) + jvmFlags.flatMap { listOf("--java-options", it) } + extra
        )
    }

// Unpacked app (DBViewer.app / DBViewer\DBViewer.exe) in build/dist/app-image
registerJpackage("packageApp", "app-image")

// Installer for the current OS in build/dist/<type>
registerJpackage(
    "packageInstaller",
    if (os.isWindows) "msi" else if (os.isMacOsX) "dmg" else "deb",
    if (os.isWindows) listOf(
        "--win-menu", "--win-shortcut", "--win-per-user-install",
        "--win-upgrade-uuid", "6f1f0f0e-6a53-4b0c-9a0e-5d2f6c1b7d41",
    ) else emptyList(),
)
