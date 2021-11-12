import java.io.File

fun main() {
    val jarPath = "/Users/nataliya.valtman/Development/kotlin/libraries/tools/kotlin-gradle-plugin-integration-tests/src/test/resources/lib.jar"

    Transform.storeLookups(File(jarPath), File("result"))
}