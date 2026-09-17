package com.jarves.mh.vercel

import com.jarves.mh.data.ApiKeyVault
import com.jarves.mh.data.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Vercel API integration for web project deployments.
 * All credentials are stored securely in Android Keystore via ApiKeyVault.
 */
class VercelService(private val context: android.content.Context) {
    private val preferences = AppPreferences(context)
    private val vault = ApiKeyVault(context)
    private val baseUrl = "https://api.vercel.com"

    /** Get the stored Vercel token, or null if not configured. */
    fun getToken(): String? = vault.get("vercel")

    /** Check if Vercel is configured. */
    fun isConfigured(): Boolean = getToken() != null

    /** Get the authenticated user. */
    suspend fun getUser(): VercelUser = withContext(Dispatchers.IO) {
        val token = getToken() ?: error("Vercel token not configured")
        val response = request("GET", "/v2/user", token)
        check(response.code in 200..299) { "Vercel auth failed: ${response.body}" }
        val obj = JSONObject(response.body).getJSONObject("user")
        VercelUser(
            id = obj.getString("id"),
            username = obj.getString("username"),
            name = obj.optString("name", ""),
            email = obj.optString("email", ""),
        )
    }

    /** List projects. */
    suspend fun listProjects(limit: Int = 20): List<VercelProject> = withContext(Dispatchers.IO) {
        val token = getToken() ?: error("Vercel token not configured")
        val response = request("GET", "/v9/projects?limit=$limit", token)
        check(response.code in 200..299) { "Failed to list projects: ${response.body}" }
        val obj = JSONObject(response.body)
        val array = obj.getJSONArray("projects")
        return array.mapIndexed { _, proj ->
            VercelProject(
                id = proj.getString("id"),
                name = proj.getString("name"),
                framework = proj.optString("framework", ""),
                gitRepository = proj.optJSONObject("gitRepository")?.let { repo ->
                    VercelGitRepository(
                        repo = repo.getString("repo"),
                        owner = repo.getString("owner"),
                        type = repo.getString("type"),
                    )
                },
                createdAt = proj.getString("createdAt"),
                updatedAt = proj.getString("updatedAt"),
            )
        }
    }

    /** Get a specific project. */
    suspend fun getProject(projectId: String): VercelProject = withContext(Dispatchers.IO) {
        val token = getToken() ?: error("Vercel token not configured")
        val response = request("GET", "/v9/projects/$projectId", token)
        check(response.code in 200..299) { "Project not found: ${response.body}" }
        val proj = JSONObject(response.body)
        VercelProject(
            id = proj.getString("id"),
            name = proj.getString("name"),
            framework = proj.optString("framework", ""),
            gitRepository = proj.optJSONObject("gitRepository")?.let { repo ->
                VercelGitRepository(
                    repo = repo.getString("repo"),
                    owner = repo.getString("owner"),
                    type = repo.getString("type"),
                )
            },
            createdAt = proj.getString("createdAt"),
            updatedAt = proj.getString("updatedAt"),
        )
    }

    /** Create a new project linked to a GitHub repository. */
    suspend fun createProject(
        name: String,
        gitRepo: String, // format: "owner/repo"
        gitBranch: String = "main",
        framework: String? = null,
    ): VercelProject = withContext(Dispatchers.IO) {
        val token = getToken() ?: error("Vercel token not configured")
        val (owner, repo) = gitRepo.split("/")
        val body = JSONObject().apply {
            put("name", name)
            put("gitRepository", JSONObject().apply {
                put("type", "github")
                put("repo", repo)
                put("owner", owner)
            })
            put("targetBranch", gitBranch)
            framework?.let { put("framework", it) }
        }.toString()
        val response = request("POST", "/v9/projects", token, body)
        check(response.code in 200..299) { "Failed to create project: ${response.body}" }
        val proj = JSONObject(response.body)
        VercelProject(
            id = proj.getString("id"),
            name = proj.getString("name"),
            framework = proj.optString("framework", ""),
            gitRepository = proj.optJSONObject("gitRepository")?.let { repo ->
                VercelGitRepository(
                    repo = repo.getString("repo"),
                    owner = repo.getString("owner"),
                    type = repo.getString("type"),
                )
            },
            createdAt = proj.getString("createdAt"),
            updatedAt = proj.getString("updatedAt"),
        )
    }

    /** Create a new deployment. */
    suspend fun createDeployment(
        projectId: String,
        gitBranch: String = "main",
        gitCommitSha: String? = null,
        target: VercelDeploymentTarget = VercelDeploymentTarget.PREVIEW,
    ): VercelDeployment = withContext(Dispatchers.IO) {
        val token = getToken() ?: error("Vercel token not configured")
        val body = JSONObject().apply {
            put("name", projectId)
            put("target", target.name.lowercase())
            put("gitBranch", gitBranch)
            gitCommitSha?.let { put("gitCommitSha", it) }
            // Auto-detect framework from project settings
        }.toString()
        val response = request("POST", "/v13/deployments", token, body)
        check(response.code in 200..299) { "Failed to create deployment: ${response.body}" }
        val dep = JSONObject(response.body)
        VercelDeployment(
            id = dep.getString("id"),
            url = dep.getString("url"),
            readyState = dep.getString("readyState"),
            createdAt = dep.getString("createdAt"),
            meta = dep.optJSONObject("meta")?.toString(),
        )
    }

    /** Get deployment status. */
    suspend fun getDeployment(deploymentId: String): VercelDeployment = withContext(Dispatchers.IO) {
        val token = getToken() ?: error("Vercel token not configured")
        val response = request("GET", "/v13/deployments/$deploymentId", token)
        check(response.code in 200..299) { "Deployment not found: ${response.body}" }
        val dep = JSONObject(response.body)
        VercelDeployment(
            id = dep.getString("id"),
            url = dep.getString("url"),
            readyState = dep.getString("readyState"),
            createdAt = dep.getString("createdAt"),
            meta = dep.optJSONObject("meta")?.toString(),
        )
    }

    /** Get deployment logs (for debugging). */
    suspend fun getDeploymentLogs(deploymentId: String): String = withContext(Dispatchers.IO) {
        val token = getToken() ?: error("Vercel token not configured")
        val response = request("GET", "/v2/deployments/$deploymentId/events", token)
        check(response.code in 200..299) { "Failed to get deployment logs: ${response.body}" }
        response.body
    }

    /** List deployments for a project. */
    suspend fun listDeployments(projectId: String, limit: Int = 10): List<VercelDeployment> = withContext(Dispatchers.IO) {
        val token = getToken() ?: error("Vercel token not configured")
        val response = request("GET", "/v6/deployments?projectId=$projectId&limit=$limit", token)
        check(response.code in 200..299) { "Failed to list deployments: ${response.body}" }
        val obj = JSONObject(response.body)
        val array = obj.getJSONArray("deployments")
        return array.mapIndexed { _, dep ->
            VercelDeployment(
                id = dep.getString("id"),
                url = dep.getString("url"),
                readyState = dep.getString("readyState"),
                createdAt = dep.getString("createdAt"),
                meta = dep.optJSONObject("meta")?.toString(),
            )
        }
    }

    /** Detect framework from project files. */
    companion object {
        fun detectFramework(files: List<String>): String? {
            // Check for Next.js
            if (files.any { it.contains("next.config") || it == "next.config.js" || it == "next.config.ts" }) {
                return "nextjs"
            }
            // Check for Vite
            if (files.any { it.contains("vite.config") }) {
                return "vite"
            }
            // Check for Nuxt
            if (files.any { it.contains("nuxt.config") }) {
                return "nuxt"
            }
            // Check for SvelteKit
            if (files.any { it.contains("svelte.config") }) {
                return "sveltekit"
            }
            // Check for Astro
            if (files.any { it.contains("astro.config") }) {
                return "astro"
            }
            // Check for Remix
            if (files.any { it.contains("remix.config") }) {
                return "remix"
            }
            // Check for Gatsby
            if (files.any { it.contains("gatsby.config") }) {
                return "gatsby"
            }
            // Check for Express
            if (files.any { it == "package.json" }) {
                // Could parse package.json for express dependency
                return "node"
            }
            return null
        }
    }

    private fun request(
        method: String,
        path: String,
        token: String,
        body: String? = null,
    ): HttpResult = runCatching {
        val url = URL("$baseUrl$path")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        HttpResult(code, responseBody)
    }.getOrElse { HttpResult(0, "", it.message ?: "Network error") }

    private data class HttpResult(val code: Int, val body: String, val error: String? = null)
}

data class VercelUser(
    val id: String,
    val username: String,
    val name: String,
    val email: String,
)

data class VercelProject(
    val id: String,
    val name: String,
    val framework: String,
    val gitRepository: VercelGitRepository?,
    val createdAt: String,
    val updatedAt: String,
)

data class VercelGitRepository(
    val repo: String,
    val owner: String,
    val type: String,
)

data class VercelDeployment(
    val id: String,
    val url: String,
    val readyState: String, // "READY", "BUILDING", "ERROR", "QUEUED", "INITIALIZING"
    val createdAt: String,
    val meta: String?,
)

enum class VercelDeploymentTarget {
    PREVIEW,
    PRODUCTION,
}