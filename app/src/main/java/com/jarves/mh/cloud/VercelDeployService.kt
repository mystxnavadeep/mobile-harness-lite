package com.jarves.mh.cloud

import android.content.Context
import com.jarves.mh.vercel.VercelService
import com.jarves.mh.vercel.VercelDeployment
import com.jarves.mh.vercel.VercelProject
import com.jarves.mh.model.GitHubRepoLink
import com.jarves.mh.model.Project
import com.jarves.mh.model.ProjectDetectionResult
import com.jarves.mh.model.ProjectDetector
import com.jarves.mh.runtime.RuntimeEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Vercel Deploy Service handles web project deployments to Vercel.
 */
class VercelDeployService(private val context: Context) {
    private val vercel = VercelService(context)

    /** Deploy a web project to Vercel. */
    suspend fun deploy(
        project: Project,
        projectDetection: ProjectDetectionResult,
        onEvent: suspend (RuntimeEvent) -> Unit,
    ): VercelDeployResult = withContext(Dispatchers.IO) {
        val token = vercel.getToken() ?: return@withContext VercelDeployResult.Failure("Vercel not configured. Add token in Settings.")

        // Get GitHub repo link from project
        val repoLink = project.githubRepoLink
            ?: return@withContext VercelDeployResult.Failure(
                "No GitHub repository linked to this project. " +
                "Open project settings and link a GitHub repository first."
            )

        // Determine Vercel project ID or create new
        val vercelProjectId = project.vercelProjectId
        val workspaceRoot = File(context.filesDir, "workspaces/${project.id}")
        val framework = VercelService.detectFramework(ProjectDetector.collectFileNames(workspaceRoot)) ?: "auto"

        val sessionId = java.util.UUID.randomUUID().toString()

        return@withContext try {
            onEvent(RuntimeEvent.VercelDeploymentStarted(
                sessionId = sessionId,
                deploymentUrl = "https://vercel.com",
            ))

            // Get or create Vercel project
            var vercelProject: VercelProject?
            if (vercelProjectId != null) {
                try {
                    vercelProject = vercel.getProject(vercelProjectId)
                } catch (e: Exception) {
                    // Project doesn't exist anymore, will create new
                    vercelProject = null
                }
            }

            if (vercelProject == null) {
                vercelProject = vercel.createProject(
                    name = project.slug,
                    gitRepo = repoLink.fullName,
                    gitBranch = repoLink.branch,
                    framework = framework,
                )
                // Update project with new Vercel project ID (caller should persist)
            }

            // Create deployment
            val deployment = vercel.createDeployment(
                projectId = vercelProject.id,
                gitBranch = repoLink.branch,
                target = com.jarves.mh.vercel.VercelDeploymentTarget.PREVIEW,
            )

            onEvent(RuntimeEvent.VercelDeploymentStarted(
                sessionId = sessionId,
                deploymentUrl = "https://${deployment.url}",
            ))

        // Poll for completion
        var finalDeployment = deployment
        var completed = false
        while (!completed) {
            delay(10_000)
            finalDeployment = vercel.getDeployment(deployment.id)

            when (finalDeployment.readyState) {
                "READY" -> completed = true
                "ERROR" -> {
                    return@withContext VercelDeployResult.Failure(
                        "Deployment failed. Check logs at https://vercel.com/${vercelProject.id}/${deployment.id}"
                    )
                }
                "CANCELED" -> {
                    return@withContext VercelDeployResult.Failure("Deployment was canceled.")
                }
                else -> {
                    // Still building, continue polling
                }
            }
        }

        val previewUrl = "https://${finalDeployment.url}"

        onEvent(RuntimeEvent.VercelDeploymentCompleted(
            sessionId = java.util.UUID.randomUUID().toString(),
            success = true,
            previewUrl = previewUrl,
            deploymentUrl = "https://vercel.com/${vercelProject.id}/${finalDeployment.id}",
        ))

        VercelDeployResult.Success(
            previewUrl = previewUrl,
            deploymentUrl = "https://vercel.com/${vercelProject.id}/${finalDeployment.id}",
            vercelProjectId = vercelProject.id,
        )
    }.catch { e ->
        val errorMessage = when {
            e.message?.contains("401") == true || e.message?.contains("Unauthorized") == true ->
                "Vercel authentication failed (401). Check your token."
            e.message?.contains("403") == true || e.message?.contains("Forbidden") == true ->
                "Vercel access forbidden (403). Check token permissions."
            e.message?.contains("404") == true || e.message?.contains("Not Found") == true ->
                "Vercel project or deployment not found (404)."
            e.message?.contains("429") == true || e.message?.contains("rate limit") == true ->
                "Vercel rate limit exceeded (429). Please wait and try again."
            e.message?.contains("500") == true || e.message?.contains("502") == true || e.message?.contains("503") == true ->
                "Vercel server error (5xx). Please try again later."
            e.message?.contains("timeout") == true || e.message?.contains("Timeout") == true ->
                "Request timed out. Check your internet connection."
            else ->
                "Deployment failed: ${e.message ?: e.javaClass.simpleName}"
        }
        onEvent(RuntimeEvent.VercelDeploymentCompleted(
            sessionId = sessionId,
            success = false,
            previewUrl = null,
            deploymentUrl = null,
        ))
        VercelDeployResult.Failure(errorMessage)
    }
}

sealed interface VercelDeployResult {
    data class Success(
        val previewUrl: String,
        val deploymentUrl: String,
        val vercelProjectId: String,
    ) : VercelDeployResult
    data class Failure(val error: String) : VercelDeployResult
}