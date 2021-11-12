import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.tree.ClassNode

import java.io.*
import java.util.jar.JarFile

class Transform {
    companion object {
        fun storeLookups(jarFile: File, lookupCacheDir: File) {
            val lookups = ArrayList<SimpleLookupInfo>()
            load(jarFile).mapValues { classReader ->
                val node = ClassNode()
                classReader.value.accept(node, 0)
                lookups.add(SimpleLookupInfo(
                    classReader.key,
                    classReader.key,
                    classReader.key
                ))
                lookups.addAll(node.methods
                    .filter { it.access == ACC_PUBLIC }
                    .map { method ->
                        SimpleLookupInfo(
                            classReader.key,
                            method.name,
                            node.name
                        )
                    }
                )
                lookups.addAll(node.fields
                    .filter { it.access == ACC_PUBLIC }
                    .map { method ->
                        SimpleLookupInfo(
                            classReader.key,
                            method.name,
                            node.name
                        )
                    }
                )
            }
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
                            classReaderMap[entry.realName] = reader
                        }
                    }
                }
            } catch (e: IOException) {
                throw InternalError("Can't read jar file " + jarFile.name, e)
            }
            return classReaderMap
        }

        private fun parseLookups(inputStream: InputStream): Unit {
            val reader = BufferedReader(InputStreamReader(inputStream))
            reader.readLines()
            System.out.println(reader.readLines())
        }

    }

}

data class SimpleLookupInfo(
    val filePath: String,
    val scopeFqName: String,
    val name: String
) : Serializable