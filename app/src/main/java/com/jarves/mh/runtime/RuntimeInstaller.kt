package com.jarves.mh.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.system.Os
import com.jarves.mh.BuildConfig
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import com.jarves.mh.model.DevStack
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.json.JSONObject

data class InstalledRuntime(
    val proot: File,
    val rootfs: File,
    val claude: File,
    val version: String,
)

data class RuntimeInstallProgress(
    val message: String,
    val fraction: Float,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val terminalLine: String? = null,
    val indeterminate: Boolean = false,
    val event: RuntimeInstallEvent = RuntimeInstallEvent.STAGE,
)

enum class RuntimeInstallEvent { STAGE, COMMAND, OUTPUT, DOWNLOAD, COMMAND_COMPLETED, COMPLETED }

private data class RuntimeBundle(
    val label: String,
    val fileName: String,
    val sha256: String,
    val compressedBytes: Long,
)

class RuntimeInstaller(private val context: Context) {
    private val runtimeDir = File(context.filesDir, "runtime")
    private val rootfs = File(runtimeDir, "ubuntu")
    private val downloads = File(context.cacheDir, "runtime-downloads")
    private val marker = File(rootfs, ".pocket-runtime-ready")
    private val bundledClaudeMarker = File(rootfs, ".pocket-bundled-claude-version")
    private val rootfsMarker = File(rootfs, ".pocket-rootfs-version")
    private val coreToolsMarker = File(rootfs, ".pocket-core-tools-version")
    private val systemUpgradeMarker = File(rootfs, ".pocket-system-upgrade-version")
    private val devStacksFile = File(rootfs, ".pocket-dev-stacks.json")
    private val macosMetadataRepairMarker = File(rootfs, ".pocket-macos-metadata-repair")

    fun isInstalled(): Boolean {
        val proot = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
        val coreToolsReady = File(rootfs, "usr/bin/git").exists() &&
            coreToolsMarker.readTextOrNull() == CORE_TOOLS_VERSION
        val ready = proot.canExecute() &&
            File(rootfs, "usr/bin/bash").exists() &&
            rootfsMarker.readTextOrNull() == ROOTFS_VERSION &&
            File(rootfs, "usr/local/bin/claude").exists() &&
            File(rootfs, "usr/local/bin/node").exists() &&
            coreToolsReady &&
            marker.exists()
        if (ready) repairLegacyMacosMetadata()
        return ready
    }

    /**
     * One-shot cleanup for devices that already extracted a tarball built on
     * macOS without the `--no-mac-metadata` flag. Such bundles contain
     * `._filename` AppleDouble metadata files that crash Python 3.8 when it
     * reads every `.pth` file in site-packages. After the first successful
     * cleanup we write a marker so we never walk the entire rootfs again.
     */
    private fun repairLegacyMacosMetadata() {
        if (macosMetadataRepairMarker.isFile) return
        if (!rootfs.isDirectory) return
        stripMacosMetadataArtifacts(rootfs)
        macosMetadataRepairMarker.parentFile?.mkdirs()
        macosMetadataRepairMarker.writeText("1")
    }

    /** Returns the already verified runtime without performing network or update checks. */
    fun installedRuntime(): InstalledRuntime {
        check(isInstalled()) { "Claude Code setup is incomplete. Reopen Mobile Harness to repair it." }
        return InstalledRuntime(
            proot = File(context.applicationInfo.nativeLibraryDir, "libproot.so"),
            rootfs = rootfs,
            claude = File(rootfs, "usr/local/bin/claude"),
            version = marker.readText().trim(),
        )
    }

    /** Removes only scaffolding written automatically by earlier PocketDev alpha builds. */
    fun cleanupLegacyWorkspaceScaffolding() {
        val workspaces = File(context.filesDir, "workspaces")
        workspaces.listFiles { file -> file.isDirectory }.orEmpty().forEach { workspace ->
            File(workspace, "README.md").deleteIfExact(LEGACY_README)
            File(workspace, "index.html").deleteIfExact(LEGACY_INDEX)

            listOf(
                File(workspace, ".claude/settings.json"),
                File(workspace, ".claude.json"),
            ).forEach { settings ->
                if (settings.isFile && settings.readTextOrNull()?.contains("/opt/pocket/permission-hook.sh") == true) {
                    settings.delete()
                }
            }
            File(workspace, ".claude").takeIf { it.isDirectory && it.list().isNullOrEmpty() }?.delete()
        }
    }

    private fun File.deleteIfExact(expected: String) {
        if (isFile && runCatching { readText() }.getOrNull() == expected) delete()
    }

    suspend fun ensureInstalled(
        selectedStacks: Set<DevStack> = emptySet(),
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ): InstalledRuntime {
        require(android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a")) { "Pocket runtime requires an ARM64 device" }
        val proot = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
        require(proot.canExecute()) { "The embedded PRoot launcher is unavailable" }

        if (!File(rootfs, "usr/bin/bash").exists() || rootfsMarker.readTextOrNull() != ROOTFS_VERSION) {
            onProgress(RuntimeInstallProgress("Preparing the private development runtime", 0.03f))
            val archive = obtainRuntimeBundle(
                CORE_BUNDLE,
                preferEmbedded = BuildConfig.OFFLINE_RUNTIME_BUNDLES,
                from = 0.03f,
                to = 0.25f,
                onProgress,
            )
            onProgress(RuntimeInstallProgress("Verifying and unpacking the Core runtime", 0.28f))
            val staging = File(runtimeDir, "ubuntu.installing")
            staging.deleteRecursively()
            staging.mkdirs()
            extractZstdTar(archive, staging)
            stripMacosMetadataArtifacts(staging)
            require(File(staging, "usr/bin/bash").isFile) { "Core bundle is missing Bash" }
            require(File(staging, "usr/local/bin/claude").isFile) { "Core bundle is missing Claude Code" }
            rootfs.deleteRecursively()
            check(staging.renameTo(rootfs)) { "Could not activate the Linux environment" }
            writeResolver()
            if (archive.parentFile == downloads) archive.delete()
        }

        val claude = File(rootfs, "usr/local/bin/claude")
        check(claude.isFile) { "The Core runtime does not contain Claude Code" }
        if (!marker.isFile) {
            val bundledVersion = bundledClaudeMarker.readTextOrNull()
            require(bundledVersion?.matches(CLAUDE_VERSION_PATTERN) == true) {
                "The bundled Claude Code version is missing"
            }
            marker.writeText(bundledVersion)
        }
        ensureSettingsAndHooks()

        if (hasInternetConnection()) {
            onProgress(RuntimeInstallProgress("Checking the latest Claude Code release", 0.32f))
            runCatching {
                val latestVersion = fetchText("https://registry.npmjs.org/@anthropic-ai/claude-code/latest")
                    .let { JSONObject(it).getString("version") }
                    .also { require(it.matches(CLAUDE_VERSION_PATTERN)) }
                if (marker.readText().trim() != latestVersion) {
                    onProgress(RuntimeInstallProgress("Downloading Claude Code $latestVersion from Anthropic", 0.35f))
                    val base = "https://downloads.claude.ai/claude-code-releases/$latestVersion"
                    val manifest = JSONObject(fetchText("$base/manifest.json"))
                    val checksum = manifest.getJSONObject("platforms").getJSONObject("linux-arm64").getString("checksum")
                    val downloaded = File(downloads, "claude-$latestVersion")
                    downloadVerified("$base/linux-arm64/claude", downloaded, checksum) { bytes, total ->
                        val ratio = if (total > 0) bytes.toFloat() / total else 0f
                        onProgress(RuntimeInstallProgress("Downloading Claude Code $latestVersion", 0.35f + ratio * 0.20f, bytes, total.takeIf { it > 0 }))
                    }
                    onProgress(RuntimeInstallProgress("Verifying Claude Code", 0.56f))
                    claude.parentFile?.mkdirs()
                    val staged = File(claude.parentFile, ".claude-$latestVersion.installing")
                    downloaded.inputStream().use { input -> FileOutputStream(staged).use { input.copyTo(it) } }
                    Os.chmod(staged.absolutePath, 0b111101101)
                    Os.rename(staged.absolutePath, claude.absolutePath)
                    downloaded.delete()
                    marker.writeText(latestVersion)
                }
            }.onFailure {
                onProgress(RuntimeInstallProgress("Using bundled Claude Code ${marker.readText().trim()}", 0.56f))
            }
        } else {
            onProgress(RuntimeInstallProgress("Offline — using bundled Claude Code ${marker.readText().trim()}", 0.56f))
        }

        val version = marker.readText().trim()

        // Node.js and Git are always available in the Core runtime.
        val coreNeeded = !File(rootfs, "usr/bin/git").exists() ||
            coreToolsMarker.readTextOrNull() != CORE_TOOLS_VERSION
        if (coreNeeded) {
            installNodeIfNeeded(proot, 0.58f, 0.66f, onProgress)
        }
        if (systemUpgradeMarker.readTextOrNull() != SYSTEM_UPGRADE_VERSION) {
            runSystemMaintenance(proot, onProgress)
            systemUpgradeMarker.writeText(SYSTEM_UPGRADE_VERSION)
        }
        if (coreNeeded) {
            aptInstall(
                proot,
                listOf("git", "ca-certificates"),
                "Installing Git and base tools",
                0.70f,
                onProgress,
            )
            writeResolver()
            verifyGuest(proot, "git --version", "Base tools could not be verified")
            coreToolsMarker.writeText(CORE_TOOLS_VERSION)
        }

        // In Lite mode, only WEB stack is supported (Node.js/npm already in Core)
        val missingStacks = selectedStacks.filterNot(::isStackInstalled)
        missingStacks.forEachIndexed { index, stack ->
            val slice = 0.26f / maxOf(1, missingStacks.size)
            val from = 0.72f + index * slice
            applyStack(proot, stack, from, from + slice, onProgress)
        }

        onProgress(RuntimeInstallProgress("Setup complete", 1f))
        return InstalledRuntime(proot, rootfs, claude, version)
    }

    /**
     * Installs one optional development stack inside Ubuntu. Safe to call again:
     * already-installed stacks return immediately without network access.
     */
    suspend fun ensureStackInstalled(
        stack: DevStack,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val runtime = installedRuntime()
        if (isStackInstalled(stack)) return
        applyStack(runtime.proot, stack, 0.05f, 0.95f, onProgress)
        onProgress(RuntimeInstallProgress("${stack.label} tools are ready", 1f))
    }

    private suspend fun applyStack(
        proot: File,
        stack: DevStack,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        var verified = true
        when (stack) {
            DevStack.WEB -> {
                onProgress(RuntimeInstallProgress("Checking Node.js and npm", from))
                verifyGuest(proot, "node --version && npm --version", "Node.js tools could not be verified")
            }
        }
        if (!verified) return
        writeDevStackState(readDevStackState().apply { put(stack.name, true) })
        onProgress(RuntimeInstallProgress("${stack.label} installed", to))
    }

    private suspend fun installNodeIfNeeded(
        proot: File,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        if (File(rootfs, "usr/local/bin/node").exists()) return
        onProgress(RuntimeInstallProgress("Downloading Node.js $NODE_VERSION LTS", from))
        downloads.mkdirs()
        val nodeFileName = "node-$NODE_VERSION-linux-arm64.tar.gz"
        val nodeBaseUrl = "https://nodejs.org/dist/$NODE_VERSION"
        val checksum = fetchText("$nodeBaseUrl/SHASUMS256.txt")
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.endsWith("  $nodeFileName") }
            ?.substringBefore(' ')
            ?: error("Node.js checksum was not found")
        val nodeArchive = File(downloads, nodeFileName)
        downloadVerified("$nodeBaseUrl/$nodeFileName", nodeArchive, checksum) { downloaded, total ->
            val ratio = if (total > 0) downloaded.toFloat() / total else 0f
            onProgress(
                RuntimeInstallProgress(
                    "Downloading Node.js $NODE_VERSION LTS",
                    from + ratio * (to - from),
                    downloaded,
                    total.takeIf { it > 0 },
                ),
            )
        }
        onProgress(RuntimeInstallProgress("Installing Node.js and npm", to))
        val nodeStaging = File(runtimeDir, "node.installing")
        nodeStaging.deleteRecursively()
        nodeStaging.mkdirs()
        extractNodeArchive(nodeArchive, nodeStaging)
        val nodeHome = File(rootfs, "usr/local/lib/nodejs")
        nodeHome.deleteRecursively()
        nodeHome.parentFile?.mkdirs()
        check(nodeStaging.renameTo(nodeHome)) { "Could not activate Node.js" }
        val localBin = File(rootfs, "usr/local/bin").apply { mkdirs() }
        listOf("node", "npm", "npx", "corepack").forEach { command ->
            val link = File(localBin, command)
            if (link.exists() || java.nio.file.Files.isSymbolicLink(link.toPath())) link.delete()
            Os.symlink("../lib/nodejs/bin/$command", link.absolutePath)
        }
        nodeArchive.delete()
    }

    private suspend fun aptInstall(
        proot: File,
        packages: List<String>,
        message: String,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        onProgress(RuntimeInstallProgress(message, fraction))
        aptInstallInternal(proot, packages, fraction, onProgress)
    }

    private suspend fun runSystemMaintenance(
        proot: File,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        onProgress(
            RuntimeInstallProgress(
                message = "Updating the private Ubuntu environment",
                fraction = 0.69f,
                indeterminate = true,
            ),
        )
        writeResolver()
        val command = "export DEBIAN_FRONTEND=noninteractive; " +
            "dpkg --configure -a && " +
            "apt-get -o DPkg::Lock::Timeout=120 -f install -y && " +
            "apt-get -o DPkg::Lock::Timeout=120 update && " +
            "apt-get -o DPkg::Lock::Timeout=120 upgrade -y"
        runGuestCommand(
            proot = proot,
            command = command,
            displayCommand = "dpkg --configure -a && apt-get -f install -y && apt-get update && apt-get upgrade -y",
            fraction = 0.69f,
            timeoutMs = 35 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = "Ubuntu maintenance could not be completed",
        )
    }

    private suspend fun aptInstallInternal(
        proot: File,
        packages: List<String>,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        require(packages.isNotEmpty()) { "No packages selected" }
        writeResolver()
        val packageNames = packages.joinToString(" ")
        val command = "export DEBIAN_FRONTEND=noninteractive; " +
            "dpkg --configure -a && " +
            "apt-get -o DPkg::Lock::Timeout=120 -f install -y && " +
            "apt-get -o DPkg::Lock::Timeout=120 update && " +
            "apt-get -o DPkg::Lock::Timeout=120 install -y --no-install-recommends $packageNames && " +
            "apt-get clean && rm -rf /var/lib/apt/lists/*"
        runGuestCommand(
            proot = proot,
            command = command,
            displayCommand = "dpkg --configure -a && apt-get -f install -y && apt-get install -y $packageNames",
            fraction = fraction,
            timeoutMs = 30 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = "Could not install: $packageNames",
        )
    }

    private suspend fun runGuestCommand(
        proot: File,
        command: String,
        displayCommand: String,
        fraction: Float,
        timeoutMs: Long,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
        failureMessage: String,
    ) {
        onProgress(
            RuntimeInstallProgress(
                message = displayCommand,
                fraction = fraction,
                terminalLine = "root@pocket:~# $displayCommand",
                indeterminate = true,
                event = RuntimeInstallEvent.COMMAND,
            ),
        )
        val running = process(
            proot = proot,
            rootfs = rootfs,
            workspace = File(rootfs, "root"),
            environment = emptyMap(),
            guestCommand = listOf("/usr/bin/env", "bash", "-lc", command),
        )
        val native = running as? NativeSpawnProcess
        val collected = StringBuilder()
        try {
            withTimeout(timeoutMs) {
                var offset = 0L
                var pending = ""
                while (running.isAlive || (native?.outputFile?.length() ?: 0L) > offset) {
                    coroutineContext.ensureActive()
                    val file = native?.outputFile
                    if (file != null && file.length() > offset) {
                        RandomAccessFile(file, "r").use { input ->
                            input.seek(offset)
                            val available = (input.length() - offset).coerceAtMost(256 * 1024).toInt()
                            val bytes = ByteArray(available)
                            input.readFully(bytes)
                            offset += available
                            pending += bytes.toString(Charsets.UTF_8).replace('\r', '\n')
                        }
                        val parts = pending.split('\n')
                        pending = parts.last()
                        for (raw in parts.dropLast(1)) {
                            val line = sanitizeTerminalLine(raw)
                            if (line.isNotBlank()) {
                                collected.appendLine(line)
                                if (collected.length > MAX_COLLECTED_OUTPUT) collected.delete(0, collected.length - MAX_COLLECTED_OUTPUT)
                                onProgress(
                                    RuntimeInstallProgress(
                                        message = line,
                                        fraction = fraction,
                                        terminalLine = line,
                                        indeterminate = true,
                                        event = RuntimeInstallEvent.OUTPUT,
                                    ),
                                )
                            }
                        }
                    } else {
                        delay(80)
                    }
                }
                sanitizeTerminalLine(pending).takeIf(String::isNotBlank)?.let { line ->
                    collected.appendLine(line)
                    onProgress(RuntimeInstallProgress(line, fraction, terminalLine = line, indeterminate = true, event = RuntimeInstallEvent.OUTPUT))
                }
            }
        } finally {
            if (running.isAlive) running.destroy()
        }
        val exit = running.waitFor()
        onProgress(
            RuntimeInstallProgress(
                message = if (exit == 0) "Command completed" else "Command failed (exit $exit)",
                fraction = fraction,
                terminalLine = "[exit $exit] $displayCommand",
                event = RuntimeInstallEvent.COMMAND_COMPLETED,
            ),
        )
        check(exit == 0) { collected.toString().trim().takeLast(1_000).ifBlank { failureMessage } }
    }

    private fun sanitizeTerminalLine(raw: String): String = raw
        .replace(ANSI_ESCAPE, "")
        .filter { it == '\t' || it.code >= 32 }
        .take(MAX_TERMINAL_LINE)

    private suspend fun verifyGuest(proot: File, command: String, failureMessage: String) {
        val verify = process(
            proot = proot,
            rootfs = rootfs,
            workspace = File(rootfs, "root"),
            environment = emptyMap(),
            guestCommand = listOf("/usr/bin/env", "bash", "-lc", command),
        )
        withTimeout(60_000L) {
            while (verify.isAlive) delay(50)
        }
        val exit = verify.waitFor()
        val output = (verify as? NativeSpawnProcess)?.outputFile
            ?.let(::readProcessOutputSafely)
            .orEmpty()
            .trim()
        check(exit == 0) { output.ifBlank { failureMessage } }
    }

    /**
     * Reads a captured PRoot process output file as UTF-8 with replacement,
     * so a stray non-UTF-8 byte in the bundled runtime (for example a binary
     * .pth file from a tarball built on a non-UTF-8 filesystem) does not
     * abort setup.
     */
    private fun readProcessOutputSafely(file: File): String = runCatching {
        val decoder = java.nio.charset.StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
        val bytes = file.readBytes()
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        decoder.decode(buffer).toString()
    }.getOrElse { error ->
        android.util.Log.w("RuntimeInstaller", "Could not decode process output as UTF-8: ${error.message}")
        ""
    }

    suspend fun initializeExisting(onProgress: suspend (RuntimeInstallProgress) -> Unit): InstalledRuntime {
        val installed = installedRuntime()
        val proot = installed.proot
        val claude = installed.claude
        val version = installed.version
        onProgress(RuntimeInstallProgress("Checking private runtime files", 0.15f))
        writeResolver()
        ensureSettingsAndHooks()
        File(context.filesDir, "runtime-bridge").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        onProgress(RuntimeInstallProgress("Preparing the runtime bridge", 0.42f))
        val probe = process(proot, rootfs, File(rootfs, "root"), emptyMap(), listOf("/usr/local/bin/claude", "--version"))
        onProgress(RuntimeInstallProgress("Starting Claude Code $version", 0.68f))
        withTimeout(20_000) {
            while (probe.isAlive) delay(50)
        }
        val exit = probe.waitFor()
        val output = (probe as? NativeSpawnProcess)?.outputFile?.readText().orEmpty().trim()
        check(exit == 0) { output.ifBlank { "Claude Code initialization failed (exit $exit)" } }
        onProgress(RuntimeInstallProgress("Claude Code is ready", 1f))
        return installed
    }

    fun process(
        proot: File,
        rootfs: File,
        workspace: File,
        environment: Map<String, String>,
        guestCommand: List<String>,
        guestWorkspacePath: String = "/workspace",
    ): Process {
        require(
            guestWorkspacePath == "/workspace" ||
                Regex("^/workspace/[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$").matches(guestWorkspacePath),
        ) { "Invalid project workspace path" }
        workspace.mkdirs()
        File(rootfs, guestWorkspacePath.removePrefix("/")).mkdirs()
        ensureWorkspaceTrust(guestWorkspacePath)
        val bridge = File(context.filesDir, "runtime-bridge").apply { mkdirs() }
        val args = buildList {
            add(proot.absolutePath)
            add("--link2symlink")
            add("-0")
            add("-r")
            add(rootfs.absolutePath)
            add("-b")
            add("/dev")
            add("-b")
            add("/proc")
            add("-b")
            add("/sys")
            listOf("/system", "/apex", "/vendor", "/product").forEach { hostPath ->
                if (File(hostPath).exists()) {
                    File(rootfs, hostPath.removePrefix("/")).mkdirs()
                    add("-b")
                    add(hostPath)
                }
            }
            add("-b")
            add("${workspace.absolutePath}:$guestWorkspacePath")
            add("-b")
            add("${bridge.absolutePath}:/pocket-bridge")
            add("-w")
            add(guestWorkspacePath)
            addAll(guestCommand)
        }
        val prootTemp = File(context.cacheDir, "proot-tmp").apply { mkdirs() }
        return NativeSpawnProcess.start(
            argv = args,
            environment = buildMap {
                put("HOME", "/root")
                val basePath = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                put("PATH", basePath)
                put("LANG", "C.UTF-8")
                put("TERM", "xterm-256color")
                put("LD_LIBRARY_PATH", context.applicationInfo.nativeLibraryDir)
                put("PROOT_NO_SECCOMP", "1")
                put("PROOT_TMP_DIR", prootTemp.absolutePath)
                put("PROOT_LOADER", File(context.applicationInfo.nativeLibraryDir, "libprootloader.so").absolutePath)
                put("GLIBC_TUNABLES", "glibc.pthread.rseq=0")
                putAll(environment)
            },
            cwd = context.filesDir.absolutePath,
            outputFile = File(context.cacheDir, "runtime-output-${System.nanoTime()}.log"),
        )
    }

    fun ensureSettingsAndHooks() {
        val hook = File(rootfs, "opt/pocket/permission-hook.sh")
        hook.parentFile?.mkdirs()
        hook.writeText(
            """#!/bin/sh
cat > /dev/null
printf '%s\n' '{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"allow"}}}'
""",
        )
        Os.chmod(hook.absolutePath, 0b111101101)

        val settingsContent = JSONObject()
            .put("disableAllHooks", false)
            .put(
                "permissions",
                JSONObject()
                    .put("allow", claudeWorkspaceToolRules())
                    .put("defaultMode", "acceptEdits"),
            )
            .put(
                "hooks",
                JSONObject().put(
                    "PermissionRequest",
                    org.json.JSONArray().put(
                        JSONObject()
                            .put("matcher", "Bash|Edit|Write|NotebookEdit")
                            .put(
                                "hooks",
                                org.json.JSONArray().put(
                                    JSONObject().put("type", "command").put("command", "/opt/pocket/permission-hook.sh"),
                                ),
                            ),
                    ),
                ),
            )
            .toString()

        val settingsPaths = listOf(
            File(rootfs, "root/.claude/pocket-settings.json"),
            File(rootfs, "root/.claude/settings.json"),
            File(rootfs, "etc/claude/settings.json"),
        )
        for (target in settingsPaths) {
            target.parentFile?.mkdirs()
            target.writeText(settingsContent)
        }
        ensureWorkspaceTrust("/workspace")
    }

    private fun ensureWorkspaceTrust(workspacePath: String) {
        val stateFile = File(rootfs, "root/.claude.json")
        val state = runCatching { JSONObject(stateFile.readText()) }.getOrElse { JSONObject() }
        listOf("disableAllHooks", "permissions", "hooks", "allowedTools", "autoApprove")
            .forEach(state::remove)
        val projects = state.optJSONObject("projects") ?: JSONObject()
        val workspace = projects.optJSONObject(workspacePath) ?: JSONObject()
        workspace.put("hasTrustDialogAccepted", true)
        projects.put(workspacePath, workspace)
        state.put("projects", projects)
        stateFile.writeText(state.toString())
    }

    private fun claudeWorkspaceToolRules() = org.json.JSONArray().apply {
        put("Bash")
        put("Edit")
        put("Write")
        put("NotebookEdit")
        put("Read")
        put("Glob")
        put("Grep")
    }

    private fun writeResolver() {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val dns = manager.getLinkProperties(manager.activeNetwork)?.dnsServers.orEmpty()
        val servers = dns.mapNotNull { it.hostAddress }.ifEmpty { listOf("8.8.8.8", "1.1.1.1") }
        File(rootfs, "etc/resolv.conf").writeText(servers.joinToString("\n") { "nameserver $it" } + "\n")
    }

    private fun hasInternetConnection(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun extractRootfs(archive: File, destination: File) {
        val deferredLinks = mutableListOf<Pair<File, File>>()
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
            var entry: TarArchiveEntry? = tar.nextEntry
            while (entry != null) {
                val cleanName = entry.name.removePrefix("./")
                val target = safeChild(destination, cleanName)
                when {
                    entry.isDirectory -> target.mkdirs()
                    entry.isSymbolicLink -> {
                        target.parentFile?.mkdirs()
                        if (target.exists() || java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
                        Os.symlink(entry.linkName, target.absolutePath)
                    }
                    entry.isLink -> {
                        target.parentFile?.mkdirs()
                        val linkTarget = safeChild(destination, entry.linkName.removePrefix("./"))
                        if (linkTarget.exists()) {
                            linkTarget.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
                        } else {
                            deferredLinks += target to linkTarget
                        }
                    }
                    entry.isFile -> {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { output -> tar.copyTo(output) }
                        runCatching { Os.chmod(target.absolutePath, entry.mode and 0b111111111) }
                    }
                }
                entry = tar.nextEntry
            }
        }
        deferredLinks.forEach { (target, linkTarget) ->
            require(linkTarget.isFile) { "Archive hard-link target is missing" }
            target.parentFile?.mkdirs()
            linkTarget.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
            runCatching { Os.chmod(target.absolutePath, android.system.Os.stat(linkTarget.absolutePath).st_mode) }
        }
    }

    private fun extractZstdTar(archive: File, destination: File) {
        val deferredLinks = mutableListOf<Pair<File, File>>()
        TarArchiveInputStream(
            ZstdCompressorInputStream(BufferedInputStream(archive.inputStream())),
        ).use { tar ->
            var entry: TarArchiveEntry? = tar.nextEntry
            while (entry != null) {
                val cleanName = entry.name.removePrefix("./")
                if (cleanName.isNotBlank()) {
                    val target = safeChild(destination, cleanName)
                    when {
                        entry.isDirectory -> target.mkdirs()
                        entry.isSymbolicLink -> {
                            target.parentFile?.mkdirs()
                            if (target.exists() || java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
                            Os.symlink(entry.linkName, target.absolutePath)
                        }
                        entry.isLink -> {
                            target.parentFile?.mkdirs()
                            val linkTarget = safeChild(destination, entry.linkName.removePrefix("./"))
                            if (linkTarget.exists()) {
                                linkTarget.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
                            } else {
                                deferredLinks += target to linkTarget
                            }
                        }
                        entry.isFile -> {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { output -> tar.copyTo(output) }
                            runCatching { Os.chmod(target.absolutePath, entry.mode and 0b111111111) }
                        }
                    }
                }
                entry = tar.nextEntry
            }
        }
        deferredLinks.forEach { (target, linkTarget) ->
            require(linkTarget.isFile) { "Archive hard-link target is missing" }
            target.parentFile?.mkdirs()
            linkTarget.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
            runCatching { Os.chmod(target.absolutePath, android.system.Os.stat(linkTarget.absolutePath).st_mode) }
        }
    }

    private fun extractNodeArchive(archive: File, destination: File) {
        val deferredLinks = mutableListOf<Pair<File, File>>()
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
            var entry: TarArchiveEntry? = tar.nextEntry
            while (entry != null) {
                val relative = entry.name.removePrefix("./").substringAfter('/', "")
                if (relative.isNotBlank()) {
                    val target = safeChild(destination, relative)
                    when {
                        entry.isDirectory -> target.mkdirs()
                        entry.isSymbolicLink -> {
                            target.parentFile?.mkdirs()
                            if (target.exists() || java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
                            Os.symlink(entry.linkName, target.absolutePath)
                        }
                        entry.isLink -> {
                            val relativeLink = entry.linkName.removePrefix("./").substringAfter('/', "")
                            val linkTarget = safeChild(destination, relativeLink)
                            target.parentFile?.mkdirs()
                            if (linkTarget.exists()) {
                                linkTarget.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
                            } else {
                                deferredLinks += target to linkTarget
                            }
                        }
                        entry.isFile -> {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { output -> tar.copyTo(output) }
                            runCatching { Os.chmod(target.absolutePath, entry.mode and 0b111111111) }
                        }
                    }
                }
                entry = tar.nextEntry
            }
        }
        deferredLinks.forEach { (target, linkTarget) ->
            require(linkTarget.isFile) { "Node.js archive hard-link target is missing" }
            target.parentFile?.mkdirs()
            linkTarget.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
            runCatching { Os.chmod(target.absolutePath, android.system.Os.stat(linkTarget.absolutePath).st_mode) }
        }
    }

    private fun safeChild(root: File, relative: String): File {
        require(relative.isNotBlank() && !relative.startsWith('/')) { "Unsafe archive path" }
        val file = File(root, relative)
        val rootPath = root.canonicalFile.toPath()
        val parentPath = (file.parentFile ?: root).canonicalFile.toPath()
        require(parentPath.startsWith(rootPath)) { "Archive path escapes runtime" }
        return file
    }

    private suspend fun downloadVerified(
        url: String,
        destination: File,
        expectedChecksum: String,
        algorithm: String = "SHA-256",
        onBytes: suspend (downloaded: Long, total: Long) -> Unit,
    ) {
        destination.parentFile?.mkdirs()
        if (destination.isFile && digest(destination, algorithm).equals(expectedChecksum, ignoreCase = true)) {
            onBytes(destination.length(), destination.length())
            return
        }
        val temporary = File(destination.parentFile, "${destination.name}.part")
        var existing = temporary.takeIf(File::isFile)?.length() ?: 0L
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 120_000
        connection.instanceFollowRedirects = true
        if (existing > 0L) connection.setRequestProperty("Range", "bytes=$existing-")
        check(connection.responseCode in 200..299) { "Download failed with HTTP ${connection.responseCode}" }
        val resumed = connection.responseCode == HttpURLConnection.HTTP_PARTIAL && existing > 0L
        if (!resumed) {
            temporary.delete()
            existing = 0L
        }
        val total = connection.contentLengthLong.takeIf { it >= 0L }?.plus(existing) ?: -1L
        connection.inputStream.use { input ->
            FileOutputStream(temporary, resumed).use { output ->
                val buffer = ByteArray(128 * 1024)
                var downloaded = existing
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    downloaded += count
                    onBytes(downloaded, total)
                }
            }
        }
        connection.disconnect()
        val actual = digest(temporary, algorithm)
        if (!actual.equals(expectedChecksum, ignoreCase = true)) {
            temporary.delete()
            error("Downloaded file checksum did not match")
        }
        destination.delete()
        check(temporary.renameTo(destination)) { "Could not finish download" }
    }

    private fun fetchText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept", "application/json")
        check(connection.responseCode in 200..299) { "Request failed with HTTP ${connection.responseCode}" }
        return connection.inputStream.bufferedReader().use { it.readText() }.also { connection.disconnect() }
    }

    private fun digest(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun File.readTextOrNull(): String? = runCatching { readText().trim() }.getOrNull()

    companion object {
        private const val LEGACY_README = "# Pocket Dev project\n\nThis project is managed locally on Android.\n"
        private const val LEGACY_INDEX = "<!doctype html><title>Pocket Dev</title><h1>Hello from Android</h1>\n"
        private const val ROOTFS_VERSION = "ubuntu-20.04.5-arm64"
        private const val ROOTFS_FILE = "ubuntu-base-20.04.5-base-arm64.tar.gz"
        private const val ROOTFS_URL = "https://cdimage.ubuntu.com/ubuntu-base/releases/20.04/release/$ROOTFS_FILE"
        private const val ROOTFS_SHA256 = "f9b999afb4c4b10193087ea8c11be36d688f19e609b05179b571f29357954b52"
        private const val NODE_VERSION = "v24.19.0"
        private const val CORE_TOOLS_VERSION = "core-bundle-2026.09.4"
        private const val SYSTEM_UPGRADE_VERSION = "ubuntu-maintenance-v1"
        private const val CLAUDE_VERSION_PATTERN = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
        private val CORE_BUNDLE = RuntimeBundle(
            label = "Core",
            fileName = "pocketdev-core-arm64-2026.09.4.tar.zst",
            sha256 = "6b60d21c3441fe0b127baadded253371fa585525e60871fb814ca29b4fed0de0",
            compressedBytes = 148_844_879L,
        )
        private const val MAX_TERMINAL_LINE = 500
        private const val MAX_COLLECTED_OUTPUT = 24_000
        private val ANSI_ESCAPE = Regex("\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*(?:\\u0007|\\u001B\\\\))")
        private const val DEFAULT_BUFFER_SIZE = 128 * 1024
    }
}