import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.seethrough.raw"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.seethrough.raw"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("junit:junit:4.13.2")
}

abstract class CopyRoomModelTask : DefaultTask() {
    @get:InputFile
    abstract val source: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val from = source.get().asFile
        if (!from.isFile) {
            throw GradleException(
                "Missing ${from.path}. Train a model with: python ml/room_forest.py train --data <labelled.json>",
            )
        }
        val destDir = outputDir.get().asFile
        destDir.mkdirs()
        from.copyTo(File(destDir, "room_forest.json"), overwrite = true)
    }
}

val copyRoomModel = tasks.register<CopyRoomModelTask>("copyRoomModel") {
    source.set(rootProject.layout.projectDirectory.file("ml/models/room_forest.json"))
    outputDir.set(layout.buildDirectory.dir("generated/roomModel"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets!!.addGeneratedSourceDirectory(copyRoomModel) { it.outputDir }
    }
}
