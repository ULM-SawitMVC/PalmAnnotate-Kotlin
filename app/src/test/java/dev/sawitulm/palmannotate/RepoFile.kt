package dev.sawitulm.palmannotate

import java.io.File

/**
 * Locate a repository file from a unit test.
 *
 * Gradle sets a test task's working directory to the module directory, but that is a default a
 * future build change could move, so the lookup walks upward from wherever the JVM actually
 * started instead of assuming a fixed depth. Failing loudly beats silently skipping: these
 * helpers back contract tests whose whole value is that they cannot be quietly disabled.
 */
internal fun repoFile(relativePath: String): File {
    val start = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
    return generateSequence(start) { it.parentFile }
        .map { File(it, relativePath) }
        .firstOrNull(File::isFile)
        ?: error("Required repository file not found: $relativePath (searched upward from $start)")
}

/** XML with comments stripped, so a rule can be asserted on real elements only. */
internal fun readXmlWithoutComments(relativePath: String): String =
    repoFile(relativePath).readText().replace(Regex("(?s)<!--.*?-->"), "")
