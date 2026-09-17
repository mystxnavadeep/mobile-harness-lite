package com.jarves.mh.model

import java.io.File

/**
 * Smart project type detection based on workspace files.
 * Used to determine the appropriate build/deployment strategy.
 */
object ProjectDetector {

    /** Detect project type from workspace files. */
    fun detect(projectRoot: File): ProjectDetectionResult {
        val files = collectFileNames(projectRoot)
        return detectFromFiles(files)
    }

    /** Detect project type from a list of file names. */
    fun detectFromFiles(fileNames: List<String>): ProjectDetectionResult {
        val fileSet = fileNames.toSet()
        val details = mutableListOf<String>()

        // Check for Android project
        if (isAndroidProject(fileSet, details)) {
            return ProjectDetectionResult(
                projectType = ProjectType.ANDROID,
                confidence = 0.95,
                details = details,
            )
        }

        // Check for Next.js
        if (isNextJsProject(fileSet, details)) {
            return ProjectDetectionResult(
                projectType = ProjectType.WEB_NEXT,
                confidence = 0.9,
                details = details,
            )
        }

        // Check for Vite
        if (isViteProject(fileSet, details)) {
            return ProjectDetectionResult(
                projectType = ProjectType.WEB_VITE,
                confidence = 0.9,
                details = details,
            )
        }

        // Check for Express
        if (isExpressProject(fileSet, details)) {
            return ProjectDetectionResult(
                projectType = ProjectType.WEB_EXPRESS,
                confidence = 0.8,
                details = details,
            )
        }

        // Check for static HTML
        if (isStaticProject(fileSet, details)) {
            return ProjectDetectionResult(
                projectType = ProjectType.WEB_STATIC,
                confidence = 0.7,
                details = details,
            )
        }

        return ProjectDetectionResult(
            projectType = ProjectType.UNKNOWN,
            confidence = 0.0,
            details = listOf("No recognized project structure found"),
        )
    }

    internal fun collectFileNames(root: File): List<String> {
        if (!root.exists() || !root.isDirectory) return emptyList()
        val names = mutableListOf<String>()
        root.walkTopDown()
            .maxDepth(3)
            .filter { it.isFile }
            .forEach { file ->
                val relative = root.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                names.add(relative)
            }
        return names
    }

    private fun isAndroidProject(files: Set<String>, details: MutableList<String>): Boolean {
        val hasGradleSettings = files.any { it.matches("settings.gradle(.kts)?".toRegex()) }
        val hasBuildGradle = files.any { it.matches("build.gradle(.kts)?".toRegex()) }
        val hasAndroidManifest = files.any { it.endsWith("AndroidManifest.xml") }
        val hasGradleWrapper = files.any { it == "gradlew" || it == "gradlew.bat" }

        if (hasGradleSettings && hasBuildGradle) {
            details.add("Found Gradle build files (settings.gradle, build.gradle)")
            if (hasAndroidManifest) details.add("Found AndroidManifest.xml")
            if (hasGradleWrapper) details.add("Found Gradle wrapper")
            return true
        }
        return false
    }

    private fun isNextJsProject(files: Set<String>, details: MutableList<String>): Boolean {
        val hasNextConfig = files.any { it.matches("next.config.(js|ts|mjs)".toRegex()) }
        val hasPackageJson = files.contains("package.json")
        val hasAppDir = files.any { it.startsWith("app/") && it.endsWith(".tsx") }
        val hasPagesDir = files.any { it.startsWith("pages/") && (it.endsWith(".tsx") || it.endsWith(".jsx")) }

        if (hasNextConfig && hasPackageJson) {
            details.add("Found next.config.* and package.json")
            if (hasAppDir) details.add("Found Next.js App Router (app/)")
            if (hasPagesDir) details.add("Found Next.js Pages Router (pages/)")
            return true
        }
        return false
    }

    private fun isViteProject(files: Set<String>, details: MutableList<String>): Boolean {
        val hasViteConfig = files.any { it.matches("vite.config.(js|ts|mjs)".toRegex()) }
        val hasPackageJson = files.contains("package.json")
        val hasIndexHtml = files.contains("index.html")

        if (hasViteConfig && hasPackageJson) {
            details.add("Found vite.config.* and package.json")
            if (hasIndexHtml) details.add("Found index.html")
            return true
        }
        return false
    }

    private fun isExpressProject(files: Set<String>, details: MutableList<String>): Boolean {
        val hasPackageJson = files.contains("package.json")
        val hasServerFile = files.any { it.matches("(server|app|index)\\.(js|ts)".toRegex()) && it !in setOf("index.html") }

        if (hasPackageJson && hasServerFile) {
            // Could optionally parse package.json for express dependency
            details.add("Found package.json and server entry point")
            return true
        }
        return false
    }

    private fun isStaticProject(files: Set<String>, details: MutableList<String>): Boolean {
        val hasIndexHtml = files.contains("index.html")
        val hasPackageJson = files.contains("package.json")

        if (hasIndexHtml) {
            details.add("Found index.html")
            if (hasPackageJson) details.add("Found package.json (may have build scripts)")
            return true
        }
        return false
    }

    /** Get recommended build mode for a project type. */
    fun getRecommendedBuildMode(projectType: ProjectType): BuildMode {
        return when (projectType) {
            ProjectType.ANDROID -> BuildMode.CLOUD // Default to cloud for Android
            ProjectType.WEB_NEXT,
            ProjectType.WEB_VITE,
            ProjectType.WEB_EXPRESS,
            ProjectType.WEB_STATIC -> BuildMode.CLOUD // Web projects deploy to Vercel
            else -> BuildMode.CLOUD
        }
    }

    /** Get recommended deployment target for a project type. */
    fun getRecommendedDeploymentTarget(projectType: ProjectType): DeploymentTarget {
        return when (projectType) {
            ProjectType.ANDROID -> DeploymentTarget.GITHUB_ACTIONS
            ProjectType.WEB_NEXT,
            ProjectType.WEB_VITE,
            ProjectType.WEB_EXPRESS,
            ProjectType.WEB_STATIC -> DeploymentTarget.VERCEL
            else -> DeploymentTarget.GITHUB_ACTIONS
        }
    }
}

enum class DeploymentTarget {
    GITHUB_ACTIONS,
    VERCEL,
    LOCAL,
}