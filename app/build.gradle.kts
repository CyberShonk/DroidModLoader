import com.android.build.api.artifact.SingleArtifact
import com.android.apksig.ApkVerifier
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.time.Instant
import java.util.Properties
import java.util.zip.ZipFile

object BuildHashes {
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}

abstract class GenerateBuildProvenance : DefaultTask() {
    @get:Input abstract val identity: MapProperty<String, String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val output = outputDirectory.file("dml-build-provenance.json").get().asFile
        output.parentFile.mkdirs()
        output.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(
            identity.get() + mapOf(
                "schemaVersion" to 1,
                "dirty" to identity.get().getValue("dirty").toBooleanStrict(),
                "versionCode" to identity.get().getValue("versionCode").toInt(),
                "builtAtUtc" to Instant.now().toString()
            )
        )) + "\n")
    }
}

abstract class WriteApkReceipts : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE)
    abstract val apkFiles: ConfigurableFileCollection

    @get:OutputFiles
    val receipts: List<File>
        get() = apkFiles.files.map { File(it.parentFile, "${it.name}.receipt.json") }

    @TaskAction
    fun writeReceipts() {
        apkFiles.files.forEach { apk ->
            val provenance = ZipFile(apk).use { zip ->
                val entry = requireNotNull(zip.getEntry("assets/dml-build-provenance.json")) {
                    "Missing embedded provenance in $apk"
                }
                zip.getInputStream(entry).use { JsonSlurper().parse(it) }
            }
            val signature = ApkVerifier.Builder(apk).build().verify()
            // This project has no release signing configuration; unsigned release APKs
            // are expected. A broken signature must never be reported as unsigned.
            val unsigned = signature.errors.all {
                it.issue == ApkVerifier.Issue.JAR_SIG_NO_SIGNATURES ||
                    it.issue == ApkVerifier.Issue.JAR_SIG_NO_MANIFEST
            } && signature.errors.isNotEmpty() && signature.signerCertificates.isEmpty()
            check(signature.isVerified || unsigned) {
                "APK signature verification failed: ${signature.errors}"
            }
            val receipt = mapOf(
                "schemaVersion" to 1,
                "apk" to mapOf(
                    "fileName" to apk.name,
                    "sizeBytes" to apk.length(),
                    "sha256" to apk.inputStream().use { stream ->
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(65536)
                        var count = stream.read(buffer)
                        while (count != -1) {
                            digest.update(buffer, 0, count)
                            count = stream.read(buffer)
                        }
                        digest.digest().joinToString("") { "%02x".format(it) }
                    }
                ),
                "build" to provenance,
                "signing" to mapOf(
                    "status" to if (signature.isVerified) "verified" else "unsigned",
                    "certificateSha256" to signature.signerCertificates.map { BuildHashes.sha256(it.encoded) }
                )
            )
            File(apk.parentFile, "${apk.name}.receipt.json").writeText(
                JsonOutput.prettyPrint(JsonOutput.toJson(receipt)) + "\n"
            )
        }
    }
}
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val versionProperties = Properties().apply {
    rootProject
        .file("version.properties")
        .inputStream()
        .use { input ->
            load(input)
        }
}

val dmlVersionName =
    versionProperties
        .getProperty("VERSION_NAME")
        ?.takeIf { it.matches(Regex("""^v\d+\.\d+\.\d+(?:-beta)?$""")) }
        ?: error(
            "VERSION_NAME must use a value such as " +
                    "v0.7.0-beta or v1.0.0."
        )

val dmlVersionCode =
    versionProperties
        .getProperty("VERSION_CODE")
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
        ?: error(
            "VERSION_CODE must be a positive integer."
        )

android {
    namespace = "com.shonkware.droidmodloader"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.shonkware.droidmodloader"
        minSdk = 30
        //noinspection OldTargetApi
        targetSdk = 36
        versionCode = dmlVersionCode
        versionName = dmlVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
}

// Provider-backed Git reads are tracked by Gradle's configuration cache. The diff
// digest also invalidates provenance when a dirty file changes again.
val sourceCommit = providers.exec {
    workingDir(rootDir)
    commandLine("git", "rev-parse", "HEAD")
}.standardOutput.asText.get().trim()
val trackedDiff = providers.exec {
    workingDir(rootDir)
    commandLine("git", "diff", "--binary", "HEAD", "--")
}.standardOutput.asText.get()
val untrackedPaths = providers.exec {
    workingDir(rootDir)
    commandLine("git", "ls-files", "--others", "--exclude-standard", "-z")
}.standardOutput.asText.get().split('\u0000').filter { it.isNotEmpty() }.sorted()
val untrackedHashes = untrackedPaths.associateWith { path ->
    BuildHashes.sha256(providers.fileContents(rootProject.layout.projectDirectory.file(path)).asBytes.get())
}

androidComponents.onVariants { variant ->
    val capitalized = variant.name.replaceFirstChar { it.uppercase() }
    val output = variant.outputs.single()
    val provenance = tasks.register<GenerateBuildProvenance>("generate${capitalized}BuildProvenance") {
        identity.set(mapOf(
            "commit" to sourceCommit,
            "dirty" to (trackedDiff.isNotEmpty() || untrackedPaths.isNotEmpty()).toString(),
            "trackedDiffSha256" to BuildHashes.sha256(trackedDiff.toByteArray(Charsets.UTF_8)),
            "untrackedFilesSha256" to BuildHashes.sha256(JsonOutput.toJson(untrackedHashes).toByteArray(Charsets.UTF_8)),
            "applicationId" to variant.applicationId.get(),
            "versionName" to output.versionName.get(),
            "versionCode" to output.versionCode.get().toString(),
            "variant" to variant.name,
            "gradle" to gradle.gradleVersion,
            "androidGradlePlugin" to com.android.Version.ANDROID_GRADLE_PLUGIN_VERSION,
            "kotlin" to libs.versions.kotlin.get(),
            "javaVersion" to System.getProperty("java.version"),
            "javaVendor" to System.getProperty("java.vendor"),
            "compileSdk" to "${android.compileSdk}.${android.compileSdkMinor ?: 0}",
            "minSdk" to android.defaultConfig.minSdk.toString(),
            "targetSdk" to android.defaultConfig.targetSdk.toString(),
            "buildTools" to android.buildToolsVersion,
            "ciRunUrl" to providers.environmentVariable("GITHUB_SERVER_URL").orElse("").get().let { server ->
                val repository = providers.environmentVariable("GITHUB_REPOSITORY").orElse("").get()
                val run = providers.environmentVariable("GITHUB_RUN_ID").orElse("").get()
                if (server.isNotEmpty() && repository.isNotEmpty() && run.isNotEmpty())
                    "$server/$repository/actions/runs/$run" else ""
            }
        ))
        outputDirectory.set(layout.buildDirectory.dir("generated/buildProvenance/${variant.name}"))
    }
    variant.sources.assets!!.addGeneratedSourceDirectory(provenance, GenerateBuildProvenance::outputDirectory)
    val receipts = tasks.register<WriteApkReceipts>("write${capitalized}ApkReceipts") {
        apkFiles.from(variant.artifacts.get(SingleArtifact.APK).map { directory ->
            directory.asFileTree.matching { include("*.apk") }
        })
    }
    tasks.matching { it.name == "assemble$capitalized" }.configureEach {
        dependsOn(receipts)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation("junit:junit:4.13.2")

    // Provides real org.json classes for local JVM unit tests.
    testImplementation("org.json:json:20260522")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.junrar)
}
