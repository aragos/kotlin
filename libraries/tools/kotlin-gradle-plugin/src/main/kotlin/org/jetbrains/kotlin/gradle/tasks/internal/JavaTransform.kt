/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.tasks.internal

import org.gradle.api.artifacts.transform.*
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import java.io.*
import java.util.jar.JarFile
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

@CacheableTransform
abstract class JavaTransform : TransformAction<TransformParameters.None> {

    companion object {
        fun storeLookups(jarFile: File): List<SimpleLookupInfo> {
            val lookups = ArrayList<SimpleLookupInfo>()
            load(jarFile).mapValues { classReader ->
                val node = ClassNode()
                classReader.value.accept(node, 0)
                lookups.add(
                    SimpleLookupInfo(
                        classReader.key,
                        classReader.key,
                        classReader.key
                    )
                )
                lookups.addAll(node.methods
                                   .filter { it.access == Opcodes.ACC_PUBLIC }
                                   .map { method ->
                                       SimpleLookupInfo(
                                           classReader.key,
                                           method.name,
                                           node.name
                                       )
                                   }
                )
                lookups.addAll(node.fields
                                   .filter { it.access == Opcodes.ACC_PUBLIC }
                                   .map { method ->
                                       SimpleLookupInfo(
                                           classReader.key,
                                           method.name,
                                           node.name
                                       )
                                   }
                )
            }
            return lookups
        }


        fun load(jarFile: File): Map<String, ClassReader> {
            val classReaderMap: MutableMap<String, ClassReader> = HashMap<String, ClassReader>()
            try {
                JarFile(jarFile).use { jar ->
                    val enumeration = jar.entries()
                    while (enumeration.hasMoreElements()) {
                        val entry = enumeration.nextElement()
                        if (!entry.isDirectory && entry.name.endsWith(".class")) {
                            val reader = ClassReader(jar.getInputStream(entry))
                            classReaderMap[entry.name] = reader
                        }
                    }
                }
            } catch (e: IOException) {
                throw InternalError("Can't read jar file " + jarFile.name, e)
            }
            return classReaderMap
        }

        fun storeLookups(jarFile: File, lookupCacheDir: File) {
//        val lookupStorage = LookupStorage(lookupCacheDir, pathConverter)
            val classNames: MutableList<String> = ArrayList()
            val zf = ZipFile(jarFile)
            val zip = ZipInputStream(FileInputStream(jarFile))
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".class")) {
                    // This ZipEntry represents a class. Now, what class does it represent?
                    val className: String = entry.getName().replace('/', '.') // including ".class"
                    classNames.add(className.substring(0, className.length - ".class".length))
                    parseLookups(zf.getInputStream(entry))

                }
                entry = zip.nextEntry
            }

            classNames.forEach { lookupCacheDir.writeText("$it\n") }


        }

        private fun parseLookups(inputStream: InputStream): Unit {
            val reader = BufferedReader(InputStreamReader(inputStream))
            reader.readLines()
            System.out.println(reader.readLines())
        }
    }

    @get:Classpath
    @get:InputArtifact
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val jarFile = inputArtifact.get().asFile

        val lookups = storeLookups(jarFile)
        val lookupCacheDir = outputs.file(jarFile.name.replace('.', '_') + "-files")

        lookupCacheDir.writeText("${lookups.size} \n")

        lookups.forEach {
            lookupCacheDir.writeText("${it.filePath}:${it.name}:${it.scopeFqName}")
        }
    }

    data class SimpleLookupInfo(
        val filePath: String,
//        val position: Position,
        val scopeFqName: String,
//        val scopeKind: ScopeKind,
        val name: String
    ) : Serializable

}




