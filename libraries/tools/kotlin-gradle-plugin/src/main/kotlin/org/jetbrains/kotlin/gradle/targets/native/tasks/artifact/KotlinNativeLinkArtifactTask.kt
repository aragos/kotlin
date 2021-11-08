/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.native.tasks.artifact

import groovy.lang.Closure
import org.gradle.api.DefaultTask
import org.gradle.api.file.FileCollection
import org.gradle.api.internal.BuildType
import org.gradle.api.tasks.*
import org.jetbrains.kotlin.compilerRunner.KotlinNativeCompilerRunner
import org.jetbrains.kotlin.gradle.dsl.KotlinCommonToolOptions
import org.jetbrains.kotlin.gradle.internal.ensureParentDirsCreated
import org.jetbrains.kotlin.gradle.plugin.LanguageSettingsBuilder
import org.jetbrains.kotlin.gradle.plugin.PropertiesProvider
import org.jetbrains.kotlin.gradle.plugin.mpp.BitcodeEmbeddingMode
import org.jetbrains.kotlin.gradle.plugin.sources.DefaultLanguageSettingsBuilder
import org.jetbrains.kotlin.gradle.targets.native.tasks.buildKotlinNativeBinaryLinkerArgs
import org.jetbrains.kotlin.gradle.utils.getValue
import org.jetbrains.kotlin.konan.target.CompilerOutputKind
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.konan.util.visibleName
import java.io.File
import javax.inject.Inject

open class KotlinNativeLinkArtifactTask @Inject constructor(
    @get:Input val konanTarget: KonanTarget,
    @get:Input val outputKind: CompilerOutputKind
) : DefaultTask() {

    @get:Input
    var baseName: String = project.name

    private val defaultDestinationDir: File
        get() {
            val kind = outputKind.visibleName
            val target = konanTarget.visibleName
            val type = if (debuggable) "debug" else "release"
            return project.buildDir.resolve("out/$kind/$target/$type")
        }

    private var customDestinationDir: File? = null

    @get:OutputDirectory
    var destinationDir: File
        get() = customDestinationDir ?: defaultDestinationDir
        set(value) {
            customDestinationDir = value
        }

    @get:Input
    var optimized: Boolean = false

    @get:Input
    var debuggable: Boolean = true

    @get:Input
    var enableEndorsedLibs: Boolean = false

    @get:Input
    var processTests: Boolean = false

    @get:Optional
    @get:Input
    var entryPoint: String? = null

    @get:Input
    var isStaticFramework: Boolean = false

    @get:Input
    var embedBitcode: BitcodeEmbeddingMode = BitcodeEmbeddingMode.DISABLE

    @get:Internal
    val languageSettings: LanguageSettingsBuilder = DefaultLanguageSettingsBuilder(project)

    fun languageSettings(fn: LanguageSettingsBuilder.() -> Unit) {
        languageSettings.fn()
    }

    fun languageSettings(fn: Closure<*>) {
        fn.delegate = languageSettings
        fn.call()
    }

    @get:Optional
    @get:Input
    val languageVersion: String?
        get() = languageSettings.languageVersion

    @get:Optional
    @get:Input
    val apiVersion: String?
        get() = languageSettings.apiVersion

    @get:Input
    val progressiveMode: Boolean
        get() = languageSettings.progressiveMode

    @get:Input
    val enabledLanguageFeatures: Set<String>
        get() = languageSettings.enabledLanguageFeatures

    @get:Input
    val optInAnnotationsInUse: Set<String>
        get() = languageSettings.optInAnnotationsInUse

    @get:Internal
    var librariesConfiguration: String? = null

    @get:Classpath
    val libraries: FileCollection by project.provider {
        librariesConfiguration?.let {
            project.configurations.getByName(it)
        } ?: project.objects.fileCollection()
    }

    @get:Internal
    var exportLibrariesConfiguration: String? = null

    @get:Classpath
    val exportLibraries: FileCollection by project.provider {
        exportLibrariesConfiguration?.let {
            project.configurations.getByName(it)
        } ?: project.objects.fileCollection()
    }

    @get:Internal
    var includeLibrariesConfiguration: String? = null

    @get:Classpath
    val includeLibraries: FileCollection by project.provider {
        includeLibrariesConfiguration?.let {
            project.configurations.getByName(it)
        } ?: project.objects.fileCollection()
    }

    @get:Input
    var linkerOptions: List<String> = emptyList()

    @get:Input
    var binaryOptions: Map<String, String> = emptyMap()

    @get:Internal
    val kotlinOptions = object : KotlinCommonToolOptions {
        override var allWarningsAsErrors: Boolean = false
        override var suppressWarnings: Boolean = false
        override var verbose: Boolean = false
        override var freeCompilerArgs: List<String> = listOf()
    }

    fun kotlinOptions(fn: KotlinCommonToolOptions.() -> Unit) {
        kotlinOptions.fn()
    }

    fun kotlinOptions(fn: Closure<*>) {
        fn.delegate = kotlinOptions
        fn.call()
    }

    @get:Input
    val allWarningsAsErrors: Boolean
        get() = kotlinOptions.allWarningsAsErrors

    @get:Input
    val suppressWarnings: Boolean
        get() = kotlinOptions.suppressWarnings

    @get:Input
    val verbose: Boolean
        get() = kotlinOptions.verbose

    @get:Input
    val freeCompilerArgs: List<String>
        get() = kotlinOptions.freeCompilerArgs

    @get:Internal
    val outputFile: File
        get() {
            val outFileName = "${outputKind.prefix(konanTarget)}$baseName${outputKind.suffix(konanTarget)}".replace('-', '_')
            return destinationDir.resolve(outFileName)
        }

    @TaskAction
    fun link() {
        val outFile = outputFile
        outFile.ensureParentDirsCreated()

        fun FileCollection.klibs() = files.filter { it.extension == "klib" }

        val localKotlinOptions = object : KotlinCommonToolOptions {
            override var allWarningsAsErrors = kotlinOptions.allWarningsAsErrors
            override var suppressWarnings = kotlinOptions.suppressWarnings
            override var verbose = kotlinOptions.verbose
            override var freeCompilerArgs = kotlinOptions.freeCompilerArgs +
                    ((languageSettings as? DefaultLanguageSettingsBuilder)?.freeCompilerArgs ?: emptyList())
        }

        val localBinaryOptions = PropertiesProvider(project).nativeBinaryOptions + binaryOptions

        val buildArgs = buildKotlinNativeBinaryLinkerArgs(
            outFile,
            optimized,
            debuggable,
            konanTarget,
            outputKind,
            libraries.klibs(),
            languageSettings,
            enableEndorsedLibs,
            localKotlinOptions,
            emptyList(),//todo CompilerPlugins
            processTests,
            entryPoint,
            embedBitcode,
            linkerOptions,
            localBinaryOptions,
            isStaticFramework,
            exportLibraries.klibs(),
            includeLibraries.klibs(),
            emptyList()//todo external deps and cache
        )

        KotlinNativeCompilerRunner(project).run(buildArgs)
    }
}