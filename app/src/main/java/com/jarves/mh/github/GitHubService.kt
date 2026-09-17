package com.jarves.mh.github

import com.jarves.mh.data.ApiKeyVault
import com.jarves.mh.data.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * GitHub API integration for cloud builds and repository management.
 * All credentials are stored securely in Android Keystore via ApiKeyVault.
 */
class GitHubService(private val context: android.content.Context) {
    private val preferences = AppPreferences(context)
    private val vault = ApiKeyVault(context)
    private val baseUrl = "https://api.github.com"

    /** Current authenticated user's login name. */
    @Volatile private var cachedUserLogin: String? = null

    /** Get the stored GitHub token, or null if not configured. */
    fun getToken(): String? = vault.get("github")

    /** Check if GitHub is configured. */
    fun isConfigured(): Boolean = getToken() != null

    /** Get the authenticated user's login name. */
    suspend fun getUserLogin(): String = withContext(Dispatchers.IO) {
        cachedUserLogin?.let { return@withContext it }
        val token = getToken() ?: error("GitHub token not configured")
        val response = request("GET", "/user", token)
        check(response.code in 200..299) { "GitHub auth failed: ${response.body}" }
        val json = JSONObject(response.body)
        val login = json.getString("login")
        cachedUserLogin = login
        preferences.gitHubUsername = login
        login
    }

    /** List repositories accessible to the authenticated user. */
    suspend fun listRepositories(affiliation: String = "owner,collaborator,organization_member"): List<GitHubRepository> = withContext(Dispatchers.IO) {
        val token = getToken() ?: error("GitHub token not configured")
        val repos = mutableListOf<GitHubRepository>()
        var page = 1
        while (true) {
            val response = request("GET", "/user/repos?affiliation=$affiliation&per_page=100&page=$page&sort=updated", token)
            check(response.code in 200..299) { "Failed to list repos: ${response.body}" }
            val array = JSONArray(response.body)
            if (array.length() == 0) break
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                repos.add(GitHubRepository(
                    id = obj.getLong("id"),
                    name = obj.getString("name"),
                    fullName = obj.getString("full_name"),
                    description = obj.optString("description", ""),
                    private = obj.getBoolean("private"),
                    htmlUrl = obj.getString("html_url"),
                    cloneUrl = obj.getString("clone_url"),
                    sshUrl = obj.getString("ssh_url"),
                    defaultBranch = obj.getString("default_branch"),
                    updatedAt = obj.getString("updated_at"),
                ))
            }
            page++
        }
        repos
    }

    /** Get a specific repository. */
    suspend fun getRepository(owner: String, name: String): GitHubRepository = withContext(Dispatchers.IO) {
        val token = getToken() ?: error("GitHub token not configured")
        val response = request("GET", "/repos/$owner/$name", token)
        check(response.code in 200..299) { "Repository not found: ${response.body}" }
        val obj = JSONObject(response.body)
        GitHubRepository(
            id = obj.getLong("id"),
            name = obj.getString("name"),
            fullName = obj.getString("full_name"),
            description = obj.optString("description", ""),
            private = obj.getBoolean("private"),
            htmlUrl = obj.getString("html_url"),
            cloneUrl = obj.getString("clone_url"),
            sshUrl = obj.getString("ssh_url"),
            defaultBranch = obj.getString("default_branch"),
            updatedAt = obj.getString("updated_at"),
        )
    }

    /** Create a new repository. */
    suspend fun createRepository(
        name: String,
        description: String = "",
        private: Boolean = true,
        autoInit: Boolean = false,
    ): GitHubRepository = withContext(Dispatchers.IO) {
        val token = getToken() ?: error("GitHub token not configured")
        val body = JSONObject().apply {
            put("name", name)
            put("description", description)
            put("private", private)
            put("auto_init", autoInit)
        }.toString()
        val response = request("POST", "/user/repos", token, body)
        check(response.code in 200..299) { "Failed to create repository: ${response.body}" }
        val obj = JSONObject(response.body)
        GitHubRepository(
            id = obj.getLong("id"),
            name = obj.getString("name"),
            fullName = obj.getString("full_name"),
            description = obj.optString("description", ""),
            private = obj.getBoolean("private"),
            htmlUrl = obj.getString("html_url"),
            cloneUrl = obj.getString("clone_url"),
            sshUrl = obj.getString("ssh_url"),
            defaultBranch = obj.getString("default_branch"),
            updatedAt = obj.getString("updated_at"),
        )
    }

    /** Get repository contents (file tree). */
    suspend fun getContents(owner: String, repo: String, path: String = "", ref: String? = null): List<GitHubContent> = withContext(Dispatchers.IO) {
        val token = getToken() ?: error("GitHub token not configured")
        val refQuery = ref?.let { "?ref=$it" } ?: ""
        val response = request("GET", "/repos/$owner/$repo/contents/$path$refQuery", token)
        check(response.code in 200..299) { "Failed to get contents: ${response.body}" }
        val array = JSONArray(response.body)
        return array.mapIndexed { _, obj ->
            GitHubContent(
                name = obj.getString("name"),
                path = obj.getString("path"),
                sha = obj.getString("sha"),
                size = obj.optLong("size", 0),
                type = obj.getString("type"),
                downloadUrl = obj.optString("download_url"),
                content = obj.optString("content"),
                encoding = obj.optString("encoding"),
            )
        }
    }

    /** Get file content (decoded from base64). */
    suspend fun getFileContent(owner: String, repo: String, path: String, ref: String? = null): String = withContext(Dispatchers.IO) {
        val token = getToken() ?: error("GitHub token not configured")
        val refQuery = ref?.let { "?ref=$it" } ?: ""
        val response = request("GET", "/repos/$owner/$repo/contents/$path$refQuery", token)
        check(response.code in 200..299) { "Failed to get file: ${response.body}" }
        val obj = JSONObject(response.body)
        val content = obj.optString("content", "")
        val encoding = obj.optString("encoding", "")
        return if (encoding == "base64" && content.isNotBlank()) {
            String(Base64.getDecoder().decode(content))
        } else {
            content
        }
    }

    /** Create or update a file. */
    suspend fun putFile(
        owner: String,
        repo: String,
        path: String,
        content: String,
        message: String,
        branch: String? = null,
        sha: String? = null,
    ): GitHubCommitResult = withContext(Dispatchers.IO) {
        val token = getToken() ?: error("GitHub token not configured")
        val body = JSONObject().apply {
            put("message", message)
            put("content", Base64.getEncoder().encodeToString(content.toByteArray()))
            branch?.let { put("branch", it) }
            sha?.let { put("sha", it) }
        }.toString()
        val response = request("PUT", "/repos/$owner/$repo/contents/$path", token, body)
        check(response.code in 200..299) { "Failed to put file: ${response.body}" }
        val obj = JSONObject(response.body)
        GitHubCommitResult(
            sha = obj.getJSONObject("commit").getString("sha"),
            htmlUrl = obj.getJSONObject("commit").getString("html_url"),
        )
    }

    /** Delete a file. */
    suspend fun deleteFile(
        owner: String,
        repo: String,
        path: String,
        message: String,
        sha: String,
        branch: String? = null,
    ) = withContext(Dispatchers.IO) {
        val token = getToken() ?: error("GitHub token not configured")
        val body = JSONObject().apply {
            put("message", message)
            put("sha", sha)
            branch?.let { put("branch", it) }
        }.toString()
        val response = request("DELETE", "/repos/$owner/$repo/contents/$path", token, body)
        check(response.code in 200..299) { "Failed to delete file: ${response.body}" }
    }

    /** Trigger a workflow dispatch. */
    suspend fun dispatchWorkflow(
        owner: String,
        repo: String,
        workflowId: String,
        ref: String,
        inputs: Map<String, String> = emptyMap(),
    ): Long = withContext(Dispatchers.IO) {
        val token = getToken() ?: error("GitHub token not configured")
        val body = JSONObject().apply {
            put("ref", ref)
            if (inputs.isNotEmpty()) put("inputs", JSONObject(inputs))
        }.toString()
        val response = request("POST", "/repos/$owner/$repo/actions/workflows/$workflowId/dispatches", token, body)
        check(response.code in 200..299) { "Failed to dispatch workflow: ${response.body}" }
        // The dispatch API doesn't return run ID directly; we need to poll for it
        // For now, return 0 and the caller should poll getWorkflowRuns
        0L
    }

    /** Get recent workflow runs for a repository. */
    suspend fun getWorkflowRuns(
        owner: String,
        repo: String,
        workflowId: String? = null,
        branch: String? = null,
        perPage: Int = 20,
    ): List<GitHubWorkflowRun> = withContext(Dispatchers.IO) {
        val token = getToken() ?: error("GitHub token not configured")
        val workflowPath = workflowId?.let { "/workflows/$it" } ?: ""
        val branchQuery = branch?.let { "?branch=$it" } ?: ""
        val queryParams = if (branchQuery.isNotBlank()) branchQuery + "&per_page=$perPage" else "?per_page=$perPage"
        val response = request("GET", "/repos/$owner/$repo/actions/runs$workflowPath$queryParams", token)
        check(response.code in 200..299) { "Failed to get workflow runs: ${response.body}" }
        val obj = JSONObject(response.body)
        val array = obj.getJSONArray("workflow_runs")
        return array.mapIndexed { _, run ->
            GitHubWorkflowRun(
                id = run.getLong("id"),
                name = run.getString("name"),
                headBranch = run.getString("head_branch"),
                headSha = run.getString("head_sha"),
                status = run.getString("status"),
                conclusion = run.optString("conclusion"),
                htmlUrl = run.getString("html_url"),
                runUrl = run.getString("url"),
                createdAt = run.getString("created_at"),
                updatedAt = run.getString("updated_at"),
            )
        }
    }

    /** Get workflow run details including artifacts. */
    suspend fun getWorkflowRun(owner: String, repo: String, runId: Long): GitHubWorkflowRun = withContext(Dispatchers.IO) {
        val token = getToken() ?: error("GitHub token not configured")
        val response = request("GET", "/repos/$owner/$repo/actions/runs/$runId", token)
        check(response.code in 200..299) { "Failed to get workflow run: ${response.body}" }
        val run = JSONObject(response.body)
        GitHubWorkflowRun(
            id = run.getLong("id"),
            name = run.getString("name"),
            headBranch = run.getString("head_branch"),
            headSha = run.getString("head_sha"),
            status = run.getString("status"),
            conclusion = run.optString("conclusion"),
            htmlUrl = run.getString("html_url"),
            runUrl = run.getString("url"),
            createdAt = run.getString("created_at"),
            updatedAt = run.getString("updated_at"),
        )
    }

    /** Get workflow run logs (tail). */
    suspend fun getWorkflowRunLogs(
        owner: String,
        repo: String,
        runId: Long,
        maxLines: Int = 100,
    ): String = withContext(Dispatchers.IO) {
        val token = getToken() ?: error("GitHub token not configured")
        val response = request("GET", "/repos/$owner/$repo/actions/runs/$runId/logs", token)
        if (response.code !in 200..299) return ""
        // The logs API returns a zip file - we'd need to parse it
        // For now, return empty string - proper implementation would unzip and read
        ""
    }

    /** Get artifacts for a workflow run. */
    suspend fun getWorkflowArtifacts(owner: String, repo: String, runId: Long): List<GitHubArtifact> = withContext(Dispatchers.IO) {
        val token = getToken() ?: error("GitHub token not configured")
        val response = request("GET", "/repos/$owner/$repo/actions/runs/$runId/artifacts", token)
        check(response.code in 200..299) { "Failed to get artifacts: ${response.body}" }
        val obj = JSONObject(response.body)
        val array = obj.getJSONArray("artifacts")
        return array.mapIndexed { _, artifact ->
            GitHubArtifact(
                id = artifact.getLong("id"),
                name = artifact.getString("name"),
                sizeInBytes = artifact.getLong("size_in_bytes"),
                archiveDownloadUrl = artifact.getString("archive_download_url"),
                expired = artifact.getBoolean("expired"),
                createdAt = artifact.getString("created_at"),
                updatedAt = artifact.getString("updated_at"),
            )
        }
    }

    /** Download an artifact. Returns the local file path. */
    suspend fun downloadArtifact(
        owner: String,
        repo: String,
        artifactId: Long,
        destinationDir: File,
    ): File = withContext(Dispatchers.IO) {
        val token = getToken() ?: error("GitHub token not configured")
        val url = URL("$baseUrl/repos/$owner/$repo/actions/artifacts/$artifactId/zip")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 30_000
        connection.readTimeout = 300_000 // 5 minutes for large artifacts
        connection.setRequestProperty("Accept", "application/zip")
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        connection.setRequestProperty("User-Agent", "Mobile-Harness-Lite")

        val code = connection.responseCode
        if (code !in 200..299) {
            val errorStream = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            error("Failed to download artifact: $code $errorStream")
        }

        destinationDir.mkdirs()
        val zipFile = File(destinationDir, "artifact-$artifactId.zip")
        connection.inputStream.use { input ->
            zipFile.outputStream().use { output ->
                input.copyTo(output) // Streaming download, no large memory allocation
            }
        }
        connection.disconnect()
        zipFile
    }

    /** Check if a workflow file exists in the repository. */
    suspend fun hasWorkflowFile(owner: String, repo: String, workflowPath: String, branch: String = "main"): Boolean = withContext(Dispatchers.IO) {
        val token = getToken() ?: error("GitHub token not configured")
        val response = request("GET", "/repos/$owner/$repo/contents/$workflowPath?ref=$branch", token)
        return response.code in 200..299
    }

    /** Create or update workflow file for Android builds. */
    suspend fun ensureBuildApkWorkflow(owner: String, repo: String, branch: String = "main"): Boolean = withContext(Dispatchers.IO) {
        val workflowPath = ".github/workflows/build-apk.yml"
        val exists = hasWorkflowFile(owner, repo, workflowPath, branch)
        val workflowContent = BuildApkWorkflow.generate()
        if (exists) {
            // Get current SHA for update
            val contents = getContents(owner, repo, workflowPath, branch)
            val sha = contents.firstOrNull()?.sha
            sha?.let { putFile(owner, repo, workflowPath, workflowContent, "Update Android build workflow", branch, it) }
        } else {
            putFile(owner, repo, workflowPath, workflowContent, "Add Android build workflow", branch)
        }
        true
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
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        connection.setRequestProperty("User-Agent", "Mobile-Harness-Lite")
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

data class GitHubRepository(
    val id: Long,
    val name: String,
    val fullName: String,
    val description: String,
    val private: Boolean,
    val htmlUrl: String,
    val cloneUrl: String,
    val sshUrl: String,
    val defaultBranch: String,
    val updatedAt: String,
)

data class GitHubContent(
    val name: String,
    val path: String,
    val sha: String,
    val size: Long,
    val type: String, // "file" or "dir"
    val downloadUrl: String?,
    val content: String?,
    val encoding: String?,
)

data class GitHubCommitResult(
    val sha: String,
    val htmlUrl: String,
)

data class GitHubWorkflowRun(
    val id: Long,
    val name: String,
    val headBranch: String,
    val headSha: String,
    val status: String, // "queued", "in_progress", "completed"
    val conclusion: String?, // "success", "failure", "cancelled", "skipped", "timed_out"
    val htmlUrl: String,
    val runUrl: String,
    val createdAt: String,
    val updatedAt: String,
)

data class GitHubArtifact(
    val id: Long,
    val name: String,
    val sizeInBytes: Long,
    val archiveDownloadUrl: String,
    val expired: Boolean,
    val createdAt: String,
    val updatedAt: String,
)

/** Generates the GitHub Actions workflow for building Android APKs. */
object BuildApkWorkflow {
    fun generate(): String = """
        name: Build Android APK
        
        on:
          workflow_dispatch:
            inputs:
              build_type:
                description: 'Build type'
                required: true
                default: 'debug'
                type: choice
                options:
                  - debug
                  - release
              gradle_task:
                description: 'Gradle task to run'
                required: false
                default: ''
                type: string
        
        env:
          JAVA_VERSION: '17'
          GRADLE_VERSION: '8.14.3'
        
        jobs:
          build:
            name: Build APK
            runs-on: ubuntu-latest
            timeout-minutes: 60
            permissions:
              contents: read
              actions: write
        
            steps:
              - name: Checkout repository
                uses: actions/checkout@v4
                with:
                  fetch-depth: 0
        
              - name: Set up JDK 17
                uses: actions/setup-java@v4
                with:
                  java-version: \${{ env.JAVA_VERSION }}
                  distribution: 'temurin'
                  cache: gradle
        
              - name: Set up Android SDK
                uses: android-actions/setup-android@v3
                with:
                  api-level: 36
                  build-tools-version: 35.0.0
                  ndk-version: r26d
        
              - name: Detect Gradle project
                id: detect
                run: |
                  # Find the Gradle project root (where settings.gradle or settings.gradle.kts is)
                  PROJECT_ROOT=$(find . -name "settings.gradle*" -not -path "*/build/*" -not -path "*/.gradle/*" | head -1 | xargs dirname)
                  if [ -z "$PROJECT_ROOT" ]; then
                    echo "No Gradle project found"
                    exit 1
                  fi
                  echo "project_root=$PROJECT_ROOT" >> $GITHUB_OUTPUT
                  echo "Found Gradle project at: $PROJECT_ROOT"
        
              - name: Make gradlew executable
                run: chmod +x \${{ steps.detect.outputs.project_root }}/gradlew
        
              - name: Build with Gradle
                working-directory: \${{ steps.detect.outputs.project_root }}
                run: |
                  GRADLE_TASK="\${{ github.event.inputs.gradle_task }}"
                  if [ -z "$GRADLE_TASK" ]; then
                    if [ "\${{ github.event.inputs.build_type }}" = "release" ]; then
                      GRADLE_TASK="assembleRelease"
                    else
                      GRADLE_TASK="assembleDebug"
                    fi
                  fi
                  echo "Running: ./gradlew $GRADLE_TASK"
                  ./gradlew $GRADLE_TASK --no-daemon --console=plain
        
              - name: Find APK artifacts
                id: apks
                working-directory: \${{ steps.detect.outputs.project_root }}
                run: |
                  APKS=$(find . -name "*.apk" -path "*/outputs/apk/*" | grep -v "unsigned" | grep -v "unaligned" || true)
                  if [ -z "$APKS" ]; then
                    echo "No APK files found"
                    exit 1
                  fi
                  echo "Found APKs:"
                  echo "$APKS"
                  # Output the first APK as primary artifact
                  FIRST_APK=$(echo "$APKS" | head -1)
                  echo "apk_path=$FIRST_APK" >> $GITHUB_OUTPUT
        
              - name: Upload APK artifact
                uses: actions/upload-artifact@v4
                with:
                  name: app-apk-\${{ github.event.inputs.build_type }}
                  path: \${{ steps.detect.outputs.project_root }}/\${{ steps.apks.outputs.apk_path }}
                  retention-days: 30
        
              - name: Upload all APKs
                if: always()
                uses: actions/upload-artifact@v4
                with:
                  name: all-apks-\${{ github.event.inputs.build_type }}
                  path: \${{ steps.detect.outputs.project_root }}/**/outputs/apk/**/*.apk
                  retention-days: 30
        
        """.trimIndent()
}