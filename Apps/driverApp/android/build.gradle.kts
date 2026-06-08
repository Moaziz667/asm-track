allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

val newBuildDir: Directory = rootProject.layout.projectDirectory
    .dir(providers.gradleProperty("driverApp.buildDir").orElse("C:/tmp/driverApp-build").get())
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)
}
subprojects {
    project.evaluationDependsOn(":app")
}

val flutterExpectedBuildDir: Directory = rootProject.layout.projectDirectory.dir("../build")

tasks.register<Copy>("copyOutputsForFlutterTool") {
    from(newBuildDir.dir("app/outputs"))
    into(flutterExpectedBuildDir.dir("app/outputs"))
}

project(":app").tasks.matching { it.name.startsWith("assemble") || it.name.startsWith("bundle") }.configureEach {
    finalizedBy(rootProject.tasks.named("copyOutputsForFlutterTool"))
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
    delete(flutterExpectedBuildDir)
}
