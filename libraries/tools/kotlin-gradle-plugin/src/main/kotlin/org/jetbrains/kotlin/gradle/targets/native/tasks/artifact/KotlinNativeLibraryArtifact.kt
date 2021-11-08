/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.native.tasks.artifact

import org.gradle.api.Project
import org.gradle.api.attributes.Usage
import org.jetbrains.kotlin.gradle.dsl.KotlinCommonToolOptions
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.LanguageSettingsBuilder
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinUsages
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.utils.lowerCamelCaseName
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.konan.target.presetName

interface KotlinNativeLibraryArtifact {
    fun registerAssembleTask(project: Project, name: String, config: KotlinNativeLibraryConfig)

    fun Project.registerLibsDependencies(target: KonanTarget, artifactName: String, deps: List<Any>): String {
        val librariesConfigurationName = lowerCamelCaseName(target.presetName, artifactName, "linkLibrary")
        configurations.maybeCreate(librariesConfigurationName).apply {
            isVisible = false
            isCanBeConsumed = false
            isCanBeResolved = true
            isTransitive = true
            attributes.attribute(KotlinPlatformType.attribute, KotlinPlatformType.native)
            attributes.attribute(KotlinNativeTarget.konanTargetAttribute, target.name)
            attributes.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, KotlinUsages.KOTLIN_API))
        }
        deps.forEach { dependencies.add(librariesConfigurationName, it) }
        return librariesConfigurationName
    }

    fun Project.registerExportDependencies(target: KonanTarget, artifactName: String, deps: List<Any>): String {
        val exportConfigurationName = lowerCamelCaseName(target.presetName, artifactName, "linkExport")
        configurations.maybeCreate(exportConfigurationName).apply {
            isVisible = false
            isCanBeConsumed = false
            isCanBeResolved = true
            isTransitive = false
            attributes.attribute(KotlinPlatformType.attribute, KotlinPlatformType.native)
            attributes.attribute(KotlinNativeTarget.konanTargetAttribute, target.name)
            attributes.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, KotlinUsages.KOTLIN_API))
        }
        deps.forEach { dependencies.add(exportConfigurationName, it) }
        return exportConfigurationName
    }
}

class KotlinNativeLibraryConfig {
    var targets: Set<KonanTarget> = emptySet()
    var modes: Set<NativeBuildType> = NativeBuildType.DEFAULT_BUILD_TYPES
    var isStatic: Boolean = false
    var linkerOptions: List<String> = emptyList()
    var artifact: (() -> KotlinNativeLibraryArtifact)? = null

    internal val exportDeps = mutableListOf<Any>()
    fun from(vararg project: Any) {
        exportDeps.addAll(project)
    }

    internal var languageSettingsFn: LanguageSettingsBuilder.() -> Unit = {}
    fun languageSettings(fn: LanguageSettingsBuilder.() -> Unit) {
        languageSettingsFn = fn
    }

    internal var kotlinOptionsFn: KotlinCommonToolOptions.() -> Unit = {}
    fun kotlinOptions(fn: KotlinCommonToolOptions.() -> Unit) {
        kotlinOptionsFn = fn
    }

    internal val binaryOptions: MutableMap<String, String> = mutableMapOf()
    fun binaryOption(name: String, value: String) {
        binaryOptions[name] = value
    }
}


//DSL
fun Project.nativeLibrary(name: String, configure: KotlinNativeLibraryConfig.() -> Unit) {
    val config = KotlinNativeLibraryConfig().apply(configure)
    val artifact = config.artifact

    if (config.targets.isEmpty()) {
        logger.error("Native library '${name}' wasn't configured because it requires at least one target")
        return
    }

    if (artifact == null) {
        logger.error("Native library '${name}' wasn't configured because it requires artifact type")
        return
    }

    if (config.exportDeps.isEmpty()) {
        logger.error("Native library '${name}' wasn't configured because it requires at least one module for linking")
        return
    }

    if (config.modes.isEmpty()) {
        logger.error("Native library '${name}' wasn't configured because it requires at least one build type in modes")
        return
    }


    artifact().registerAssembleTask(this, name, config)
}