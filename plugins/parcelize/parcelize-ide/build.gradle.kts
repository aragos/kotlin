description = "IDE support for the Parcelize compiler plugin"

plugins {
    kotlin("jvm")
    id("jps-compatible")
}

dependencies {
    compile(project(":compiler:util"))
    compile(project(":compiler:plugin-api"))
    compile(project(":compiler:frontend"))
    compile(project(":compiler:frontend.java"))
    compile(project(":compiler:light-classes"))
    compile(project(":idea"))
    compile(project(":idea:idea-gradle"))
    compile(project(":plugins:parcelize:parcelize-compiler"))
    compile(project(":plugins:parcelize:parcelize-runtime"))

    compile(intellijDep())
    compile(intellijPluginDep("gradle"))
    compileOnly(intellijPluginDep("java")) { includeJars("java-api", "java-impl") }

    testApi(projectTests(":idea"))
    testApi(projectTests(":plugins:parcelize:parcelize-compiler"))

    testRuntime(project(":allopen-ide-plugin"))
    testRuntime(project(":noarg-ide-plugin"))
    testRuntime(project(":kotlin-scripting-idea")) { isTransitive = false }
    testRuntime(project(":kotlin-scripting-compiler-impl"))
    testRuntime(project(":sam-with-receiver-ide-plugin"))
    testRuntime(project(":kotlinx-serialization-ide-plugin"))

    testRuntime(toolsJar())

    testRuntimeOnly(intellijPluginDep("java"))
}

sourceSets {
    "main" { projectDefault() }
    "test" { projectDefault() }
}

runtimeJar()
javadocJar()
sourcesJar()

testsJar {}

projectTest(parallel = true) {
    dependsOn(":dist")
    workingDir = rootDir
    useAndroidJar()
}
