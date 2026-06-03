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

tasks.register<Copy>("copyDebugApkForFlutterTool") {
    from(newBuildDir.dir("app/outputs/flutter-apk"))
    into(flutterExpectedBuildDir.dir("app/outputs/flutter-apk"))
    include("*.apk")
}

project(":app").tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy(rootProject.tasks.named("copyDebugApkForFlutterTool"))
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
    delete(flutterExpectedBuildDir)
}
