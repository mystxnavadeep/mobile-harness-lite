package com.jarves.mh.cloud

import android.content.Context
import com.jarves.mh.github.GitHubService
import com.jarves.mh.github.GitHubWorkflowRun
import com.jarves.mh.model.BuildMode
import com.jarves.mh.model.GitHubRepoLink
import com.jarves.mh.model.Project
import com.jarves.mh.model.ProjectDetectionResult
import com.jarves.mh.model.ProjectDetector
import com.jarves.mh.runtime.InstalledRuntime
import com.jarves.mh.runtime.NativeSpawnProcess
import com.jarves.mh.runtime.RuntimeEvent
import com.jarves.mh.runtime.RuntimeInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Cloud Build Service orchestrates Android APK builds on GitHub Actions.
 * Handles the full flow: git operations → workflow dispatch → polling → artifact download.
 */
class CloudBuildService(private val context: Context) {
    private val github = GitHubService(context)
    private val installer = RuntimeInstaller(context)

    /** Build an Android APK using GitHub Actions. */
    suspend fun buildApk(
        project: Project,
        projectDetection: ProjectDetectionResult,
        onEvent: suspend (RuntimeEvent) -> Unit,
    ): CloudBuildResult = withContext(Dispatchers.IO) {
        val token = github.getToken() ?: return@withContext CloudBuildResult.Failure("GitHub not configured. Add token in Settings.")

        // Get repository from project settings
        val repoLink = project.githubRepoLink
            ?: return@withContext CloudBuildResult.Failure(
                "No GitHub repository linked to this project. " +
                "Open project settings and link a GitHub repository first."
            )

        val owner = repoLink.owner
        val repo = repoLink.repo
        val branch = repoLink.branch

        // Emit start event
        val sessionId = java.util.UUID.randomUUID().toString()
        onEvent(RuntimeEvent.CloudBuildStarted(
            sessionId = sessionId,
            workflowUrl = repoLink.actionsUrl,
            runId = 0,
        ))

        return@withContext try {
            // Ensure workflow file exists
            onEvent(RuntimeEvent.CloudBuildProgress(
                sessionId = sessionId,
                status = CloudBuildStatus.PREPARING,
                message = "Preparing build workflow...",
                logsUrl = null,
            ))

            github.ensureBuildApkWorkflow(owner, repo, branch)

        // Push local changes to GitHub
        onEvent(RuntimeEvent.CloudBuildProgress(
            sessionId = sessionId,
            status = CloudBuildStatus.PUSHING_TO_GITHUB,
            message = "Pushing changes to GitHub...",
            logsUrl = null,
        ))

        val pushResult = pushToGitHub(project, owner, repo, branch)
        if (!pushResult.success) {
            return@withContext CloudBuildResult.Failure("Failed to push to GitHub: ${pushResult.error}")
        }

        // Dispatch workflow
        onEvent(RuntimeEvent.CloudBuildProgress(
            sessionId = sessionId,
            status = CloudBuildStatus.QUEUED,
            message = "Queuing build on GitHub Actions...",
            logsUrl = repoLink.actionsUrl,
        ))

        val workflowId = "build-apk.yml"
        val inputs = mapOf(
            "build_type" to "debug",
            "gradle_task" to "",
        )
        github.dispatchWorkflow(owner, repo, workflowId, branch, inputs)

        // Poll for workflow run
        var runId: Long = 0
        var run: GitHubWorkflowRun? = null
        var attempts = 0
        val maxAttempts = 30 // ~5 minutes max wait for queued

        while (attempts < maxAttempts) {
            delay(10_000)
            attempts++
            val runs = github.getWorkflowRuns(owner, repo, workflowId, branch, perPage = 5)
            run = runs.firstOrNull { it.headBranch == branch && it.headSha == pushResult.commitSha }
            if (run != null) {
                runId = run.id
                break
            }
        }

        if (run == null) {
            return@withContext CloudBuildResult.Failure("Workflow run not found after dispatch. Check GitHub Actions tab.")
        }

        onEvent(RuntimeEvent.CloudBuildProgress(
            sessionId = sessionId,
            status = CloudBuildStatus.BUILDING,
            message = "Building APK on GitHub Actions...",
            logsUrl = run.htmlUrl,
        ))

        // Poll for completion
        var completed = false
        var finalRun: GitHubWorkflowRun = run
        var lastLogLine = ""
        while (!completed) {
            delay(30_000) // Poll every 30 seconds
            finalRun = github.getWorkflowRun(owner, repo, runId)

            // Fetch recent log snippet for progress
            val logSnippet = github.getWorkflowRunLogs(owner, repo, runId, maxLines = 50)

            onEvent(RuntimeEvent.CloudBuildProgress(
                sessionId = sessionId,
                status = when (finalRun.status) {
                    "queued" -> CloudBuildStatus.QUEUED
                    "in_progress" -> CloudBuildStatus.BUILDING
                    "completed" -> CloudBuildStatus.COMPLETED
                    else -> CloudBuildStatus.BUILDING
                },
                message = logSnippet.ifBlank { "Build ${finalRun.status} (${finalRun.conclusion ?: "running"})" },
                logsUrl = finalRun.htmlUrl,
            ))

            if (finalRun.status == "completed") {
                completed = true
            }
        }

        val success = finalRun.conclusion == "success"

        if (!success) {
            return@withContext CloudBuildResult.Failure(
                "Build failed: ${finalRun.conclusion}. Check logs at ${finalRun.htmlUrl}"
            )
        }

        // Get artifacts
        onEvent(RuntimeEvent.CloudBuildProgress(
            sessionId = sessionId,
            status = CloudBuildStatus.UPLOADING_ARTIFACT,
            message = "Downloading APK artifact...",
            logsUrl = finalRun.htmlUrl,
        ))

        val artifacts = github.getWorkflowArtifacts(owner, repo, runId)
        val apkArtifact = artifacts.firstOrNull { it.name.contains("apk", ignoreCase = true) }
            ?: artifacts.firstOrNull()

        if (apkArtifact == null) {
            return@withContext CloudBuildResult.Failure("No APK artifact found in build.")
        }

        // Download artifact to local cache
        val cacheDir = File(context.cacheDir, "cloud-builds/$owner-$repo-$runId")
        val zipFile = github.downloadArtifact(owner, repo, apkArtifact.id, cacheDir)

        // Extract APK from zip
        val apkPath = extractApkFromZip(zipFile, cacheDir)

        // Clean up zip file after extraction
        zipFile.delete()

        onEvent(RuntimeEvent.CloudBuildCompleted(
            sessionId = sessionId,
            success = true,
            artifactUrl = apkArtifact.archiveDownloadUrl,
            apkPath = apkPath?.absolutePath,
            runUrl = finalRun.htmlUrl,
        ))

        CloudBuildResult.Success(
            apkPath = apkPath?.absolutePath ?: "",
            artifactUrl = apkArtifact.archiveDownloadUrl,
            runUrl = finalRun.htmlUrl,
        )
    }.catch { e ->
        val errorMessage = when {
            e.message?.contains("401") == true || e.message?.contains("Unauthorized") == true ->
                "GitHub authentication failed (401). Check your token has 'repo' and 'workflow' scopes."
            e.message?.contains("403") == true || e.message?.contains("Forbidden") == true ->
                "GitHub access forbidden (403). Check token permissions and repository access."
            e.message?.contains("404") == true || e.message?.contains("Not Found") == true ->
                "Repository or workflow not found (404). Check the repository name and branch."
            e.message?.contains("429") == true || e.message?.contains("rate limit") == true ->
                "GitHub rate limit exceeded (429). Please wait and try again."
            e.message?.contains("500") == true || e.message?.contains("502") == true || e.message?.contains("503") == true ->
                "GitHub server error (5xx). Please try again later."
            e.message?.contains("timeout") == true || e.message?.contains("Timeout") == true ->
                "Request timed out. Check your internet connection."
            else ->
                "Build failed: ${e.message ?: e.javaClass.simpleName}"
        }
        onEvent(RuntimeEvent.CloudBuildCompleted(
            sessionId = sessionId,
            success = false,
            artifactUrl = null,
            apkPath = null,
            runUrl = repoLink.actionsUrl,
        ))
        CloudBuildResult.Failure(errorMessage)
    }
}

    /** Push local changes to GitHub using git in the PRoot environment. */
    private suspend fun pushToGitHub(project: Project, owner: String, repo: String, branch: String): PushResult {
        val workspaceRoot = getProjectWorkspaceRoot(project)
        val installed = installer.installedRuntime()

        // Check git status
        val statusResult = runGitCommand(installed, workspaceRoot, listOf("status", "--porcelain"))
        if (!statusResult.success) {
            return PushResult(false, null, "Failed to get git status: ${statusResult.output}")
        }

        val hasChanges = statusResult.output.trim().isNotBlank()

        // Check if remote exists and matches
        val remoteResult = runGitCommand(installed, workspaceRoot, listOf("remote", "get-url", "origin"))
        val expectedRemoteUrl = "https://github.com/$owner/$repo.git"

        if (!remoteResult.success || remoteResult.output.trim() != expectedRemoteUrl) {
            // Set up or update remote
            val remoteSetResult = if (remoteResult.success) {
                runGitCommand(installed, workspaceRoot, listOf("remote", "set-url", "origin", expectedRemoteUrl))
            } else {
                runGitCommand(installed, workspaceRoot, listOf("remote", "add", "origin", expectedRemoteUrl))
            }
            if (!remoteSetResult.success) {
                return PushResult(false, null, "Failed to configure git remote: ${remoteSetResult.output}")
            }
        }

        // Fetch to check for conflicts
        val fetchResult = runGitCommand(installed, workspaceRoot, listOf("fetch", "origin", branch))
        if (!fetchResult.success) {
            return PushResult(false, null, "Failed to fetch from remote: ${fetchResult.output}")
        }

        // Check if local branch is behind
        val revListResult = runGitCommand(installed, workspaceRoot, listOf("rev-list", "--count", "HEAD..origin/$branch"))
        if (revListResult.success) {
            val behindCount = revListResult.output.trim().toIntOrNull() ?: 0
            if (behindCount > 0) {
                return PushResult(false, null, "Remote has $behindCount commits not in local branch. Pull first or force push.")
            }
        }

        var commitSha: String? = null

        if (hasChanges) {
            // Stage all changes
            val addResult = runGitCommand(installed, workspaceRoot, listOf("add", "-A"))
            if (!addResult.success) {
                return PushResult(false, null, "Failed to stage changes: ${addResult.output}")
            }

            // Commit
            val commitMsg = "Mobile Harness: Update before cloud build - ${java.time.Instant.now()}"
            val commitResult = runGitCommand(installed, workspaceRoot, listOf("commit", "-m", commitMsg))
            if (!commitResult.success) {
                return PushResult(false, null, "Failed to commit changes: ${commitResult.output}")
            }

            // Get commit SHA
            val shaResult = runGitCommand(installed, workspaceRoot, listOf("rev-parse", "HEAD"))
            if (shaResult.success) {
                commitSha = shaResult.output.trim()
            }
        } else {
            // No changes, get current HEAD SHA
            val shaResult = runGitCommand(installed, workspaceRoot, listOf("rev-parse", "HEAD"))
            if (shaResult.success) {
                commitSha = shaResult.output.trim()
            }
        }

        // Push to GitHub (using token authentication via GIT_ASKPASS/environment)
        val token = github.getToken() ?: return PushResult(false, null, "GitHub token not available")
        val pushUrl = "https://github.com/$owner/$repo.git"

        // Configure git to use credential helper that reads GITHUB_TOKEN from environment
        val credHelperConfig = runGitCommand(installed, workspaceRoot, listOf("config", "credential.helper", "!f() { echo \"username=x-access-token\"; echo \"password=\$GITHUB_TOKEN\"; }; f"))
        if (!credHelperConfig.success) {
            return PushResult(false, null, "Failed to configure git credential helper: ${credHelperConfig.output}")
        }

        val pushResult = runGitCommand(installed, workspaceRoot, listOf("push", pushUrl, "HEAD:refs/heads/$branch"), token)
        if (!pushResult.success) {
            val error = pushResult.output
            // Check for common error patterns
            val friendlyError = when {
                error.contains("authentication failed", true) || error.contains("Permission denied") ->
                    "GitHub authentication failed. Check your token has 'repo' and 'workflow' scopes."
                error.contains("rejected", true) || error.contains("non-fast-forward") ->
                    "Push rejected. Remote has changes not in local branch. Pull first."
                error.contains("not found", true) ->
                    "Repository not found. Check the repository name and your access."
                else ->
                    "Push failed: $error"
            }
            return PushResult(false, commitSha, friendlyError)
        }

        return PushResult(true, commitSha, null)
    }

    /** Run a git command in the PRoot environment. */
    private suspend fun runGitCommand(
        installed: InstalledRuntime,
        workspace: File,
        args: List<String>,
        githubToken: String? = null,
    ): GitCommandResult {
        val command = listOf("git", *args.toTypedArray())
        val environment = if (githubToken != null) mapOf("GITHUB_TOKEN" to githubToken) else emptyMap<String, String>()
        val process = installer.process(
            proot = installed.proot,
            rootfs = installed.rootfs,
            workspace = workspace,
            environment = environment,
            guestCommand = command,
        )

        val outputFile = (process as? NativeSpawnProcess)?.outputFile
        val output = StringBuilder()
        var exitCode = -1

        // Wait for process to complete with timeout
        val timeoutMs = 60_000L
        val startTime = System.currentTimeMillis()
        while (process.isAlive && System.currentTimeMillis() - startTime < timeoutMs) {
            kotlinx.coroutines.delay(100)
            outputFile?.let { file ->
                if (file.length() > output.length) {
                    val newContent = file.readText()
                    output.append(newContent.substring(output.length))
                }
            }
        }

        if (process.isAlive) {
            process.destroyForcibly()
            kotlinx.coroutines.delay(500)
            return GitCommandResult(false, output.toString().trim(), -1)
        }

        exitCode = process.waitFor()
        val finalOutput = outputFile?.readText()?.trim() ?: output.toString().trim()
        return GitCommandResult(exitCode == 0, finalOutput, exitCode)
    }

    /** Get project workspace root. */
    private fun getProjectWorkspaceRoot(project: Project): File {
        val base = File(context.filesDir, "workspaces/${project.id}").apply { mkdirs() }
        if (project.rootPath.isBlank()) return base
        val selected = File(base, project.rootPath)
        return selected.apply { mkdirs() }
    }

    /** Extract APK from downloaded zip artifact. */
    private fun extractApkFromZip(zipFile: File, extractDir: File): File? {
        extractDir.mkdirs()
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                    val outFile = File(extractDir, entry.name.substringAfterLast("/"))
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out ->
                        zis.copyTo(out)
                    }
                    return outFile
                }
                entry = zis.nextEntry
            }
        }
        return null
    }

    data class PushResult(
        val success: Boolean,
        val commitSha: String?,
        val error: String?,
    )

    private data class GitCommandResult(
        val success: Boolean,
        val output: String,
        val exitCode: Int,
    )
}

sealed interface CloudBuildResult {
    data class Success(
        val apkPath: String,
        val artifactUrl: String,
        val runUrl: String,
    ) : CloudBuildResult
    data class Failure(val error: String) : CloudBuildResult
}