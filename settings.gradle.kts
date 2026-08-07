rootProject.name = "CloudstreamPlugins"

// Temporary: build only AnichinV2Provider
include("AnichinV2Provider")
include("OppaDramaProvider")

// Auto-discovery disabled temporarily
// val disabled = listOf<String>()

// File(rootDir, ".").eachDir { dir ->
//     if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
//         include(dir.name)
//     }
// }

// fun File.eachDir(block: (File) -> Unit) {
//     listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
// }
