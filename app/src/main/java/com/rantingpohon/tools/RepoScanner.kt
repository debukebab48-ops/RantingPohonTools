package com.rantingpohon.tools

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class GithubRepoItem(
    val fullName: String,
    val htmlUrl: String,
    val isPrivate: Boolean
)

data class WorkflowRunItem(
    val id: Long,
    val name: String,
    val headBranch: String,
    val status: String,
    val conclusion: String,
    val displayTitle: String,
    val createdAt: String
)

data class WorkflowArtifactItem(
    val id: Long,
    val name: String,
    val sizeInBytes: Long,
    val expired: Boolean
)

data class GithubCommitItem(
    val sha: String,
    val message: String,
    val authorName: String,
    val dateIso: String
)

class RepoScanner(private val context: Context) {

    private val client = OkHttpClient()

    private val codeExtensions = setOf(
        "kt", "java", "xml", "gradle", "kts", "json", "yaml", "yml",
        "js", "ts", "jsx", "tsx", "html", "css", "py", "cpp", "c", "h",
        "cs", "go", "rs", "php", "rb", "swift", "dart", "sh", "bat",
        "md", "txt", "properties", "toml", "ini", "sql"
    )

    private val ignoredSegments = setOf(
        ".git", "build", ".gradle", "node_modules", ".idea", ".cxx", "__pycache__", ".dart_tool"
    )

    fun isCodeFile(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in codeExtensions || fileName.equals("Dockerfile", true) || fileName.equals("Makefile", true)
    }

    fun shouldAutoIgnore(path: String): Boolean {
        return path.split("/").any { it in ignoredSegments }
    }

    suspend fun fetchUserRepos(token: String): List<GithubRepoItem> = withContext(Dispatchers.IO) {
        val cleanToken = token.trim()
        if (cleanToken.isEmpty()) {
            throw IllegalArgumentException("Masukkan GitHub Token dulu.")
        }

        val req = Request.Builder()
            .url("https://api.github.com/user/repos?per_page=100&sort=updated&affiliation=owner,collaborator")
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $cleanToken")
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw Exception("Gagal memuat daftar repo (${resp.code}). Pastikan Token valid.")
            }
            val arr = JSONArray(resp.body?.string() ?: "[]")
            val repos = mutableListOf<GithubRepoItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                repos.add(
                    GithubRepoItem(
                        fullName = obj.getString("full_name"),
                        htmlUrl = obj.getString("html_url"),
                        isPrivate = obj.optBoolean("private", false)
                    )
                )
            }
            repos
        }
    }

    suspend fun fetchDefaultBranch(repoUrl: String, token: String?): String = withContext(Dispatchers.IO) {
        val (owner, repo) = parseOwnerRepo(repoUrl)
        val reqBuilder = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo")
            .header("Accept", "application/vnd.github+json")
        if (!token.isNullOrBlank()) {
            reqBuilder.header("Authorization", "Bearer ${token.trim()}")
        }
        client.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Gagal akses repo (${resp.code}).")
            JSONObject(resp.body?.string() ?: "{}").optString("default_branch", "main")
        }
    }

    suspend fun fetchRepoBranches(repoUrl: String, token: String?): List<String> = withContext(Dispatchers.IO) {
        val (owner, repo) = parseOwnerRepo(repoUrl)
        val reqBuilder = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/branches?per_page=100")
            .header("Accept", "application/vnd.github+json")
        if (!token.isNullOrBlank()) {
            reqBuilder.header("Authorization", "Bearer ${token.trim()}")
        }
        client.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Gagal memuat branch (${resp.code}).")
            val arr = JSONArray(resp.body?.string() ?: "[]")
            val branches = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                branches.add(arr.getJSONObject(i).getString("name"))
            }
            branches
        }
    }

    suspend fun scanGithubRepo(repoUrl: String, token: String?, branch: String? = null): List<TreeNode> = withContext(Dispatchers.IO) {
        val cleanUrl = repoUrl.trim().removeSuffix("/").removeSuffix(".git")
        val regex = Regex("github\\.com/([^/]+)/([^/]+)")
        val match = regex.find(cleanUrl)
            ?: throw IllegalArgumentException("Format URL GitHub tidak valid. Contoh: https://github.com/user/repo")

        val owner = match.groupValues[1]
        val repo = match.groupValues[2]

        val repoReqBuilder = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo")
            .header("Accept", "application/vnd.github+json")
        if (!token.isNullOrBlank()) {
            repoReqBuilder.header("Authorization", "Bearer ${token.trim()}")
        }

        val repoJson = client.newCall(repoReqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Gagal akses repo (${resp.code}). Cek URL atau Token.")
            JSONObject(resp.body?.string() ?: "{}")
        }
        val defaultBranch = if (!branch.isNullOrBlank()) branch.trim() else repoJson.optString("default_branch", "main")

        val treeReqBuilder = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/git/trees/${Uri.encode(defaultBranch)}?recursive=1")
            .header("Accept", "application/vnd.github+json")
        if (!token.isNullOrBlank()) {
            treeReqBuilder.header("Authorization", "Bearer ${token.trim()}")
        }

        val treeJson = client.newCall(treeReqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Gagal membaca struktur pohon (${resp.code}).")
            JSONObject(resp.body?.string() ?: "{}")
        }

        val treeArray = treeJson.getJSONArray("tree")
        val rawNodes = mutableListOf<TreeNode>()

        for (i in 0 until treeArray.length()) {
            val item = treeArray.getJSONObject(i)
            val path = item.getString("path")
            val type = item.getString("type")
            val isDir = type == "tree"
            val name = path.substringAfterLast('/')
            val depth = path.count { it == '/' }
            val sizeChars = if (isDir) 0L else item.optLong("size", 0L)
            val rawUrl = if (isDir) null else "https://raw.githubusercontent.com/$owner/$repo/$defaultBranch/$path"

            rawNodes.add(
                TreeNode(
                    path = path,
                    name = name,
                    isDirectory = isDir,
                    depth = depth,
                    charCount = sizeChars,
                    githubRawUrl = rawUrl
                )
            )
        }

        val sorted = sortHierarchy(rawNodes)
        recalculateFolderChars(sorted)
        sorted
    }

    suspend fun fetchGithubFileContent(node: TreeNode, token: String?): String = withContext(Dispatchers.IO) {
        node.cachedContent?.let { return@withContext it }
        val url = node.githubRawUrl ?: return@withContext ""
        val reqBuilder = Request.Builder().url(url)
        if (!token.isNullOrBlank()) {
            reqBuilder.header("Authorization", "Bearer ${token.trim()}")
        }
        val text = client.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) "// Gagal memuat file (${resp.code})"
            else resp.body?.string() ?: ""
        }
        node.cachedContent = text
        node.charCount = text.length.toLong()
        text
    }

    private fun parseOwnerRepo(repoUrl: String): Pair<String, String> {
        val cleanUrl = repoUrl.trim().removeSuffix("/").removeSuffix(".git")
        val match = Regex("github\\.com/([^/]+)/([^/]+)").find(cleanUrl)
            ?: throw IllegalArgumentException("Format URL GitHub tidak valid.")
        return match.groupValues[1] to match.groupValues[2]
    }

    private fun getFileShaIfExists(owner: String, repo: String, filePath: String, token: String, branch: String? = null): String? {
        val cleanPath = filePath.trimStart('/')
        val refQuery = if (!branch.isNullOrBlank()) "?ref=${Uri.encode(branch.trim())}" else ""
        val req = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/contents/$cleanPath$refQuery")
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer ${token.trim()}")
            .build()
        return client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) {
                JSONObject(resp.body?.string() ?: "{}").optString("sha").takeIf { it.isNotEmpty() }
            } else null
        }
    }

    suspend fun upsertGithubFile(
        repoUrl: String,
        token: String,
        filePath: String,
        content: String,
        commitMessage: String,
        branch: String? = null
    ) = withContext(Dispatchers.IO) {
        val cleanToken = token.trim()
        if (cleanToken.isEmpty()) throw IllegalArgumentException("GitHub Token wajib diisi untuk mengubah repo.")
        val (owner, repo) = parseOwnerRepo(repoUrl)
        val cleanPath = filePath.trim().trimStart('/')
        if (cleanPath.isEmpty()) throw IllegalArgumentException("Path file tidak boleh kosong.")

        val existingSha = getFileShaIfExists(owner, repo, cleanPath, cleanToken, branch)
        val encodedContent = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        val payload = JSONObject().apply {
            put("message", commitMessage.ifBlank { "Update $cleanPath via RantingPohonTools" })
            put("content", encodedContent)
            if (existingSha != null) put("sha", existingSha)
            if (!branch.isNullOrBlank()) put("branch", branch.trim())
        }

        val req = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/contents/$cleanPath")
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $cleanToken")
            .put(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string() ?: ""
                throw Exception("Gagal commit (${resp.code}): $errBody")
            }
        }
    }

    suspend fun deleteGithubFile(
        repoUrl: String,
        token: String,
        filePath: String,
        commitMessage: String,
        branch: String? = null
    ) = withContext(Dispatchers.IO) {
        val cleanToken = token.trim()
        if (cleanToken.isEmpty()) throw IllegalArgumentException("GitHub Token wajib diisi.")
        val (owner, repo) = parseOwnerRepo(repoUrl)
        val cleanPath = filePath.trim().trimStart('/')

        val sha = getFileShaIfExists(owner, repo, cleanPath, cleanToken, branch)
            ?: throw Exception("File tidak ditemukan di GitHub atau sudah terhapus.")

        val payload = JSONObject().apply {
            put("message", commitMessage.ifBlank { "Delete $cleanPath via RantingPohonTools" })
            put("sha", sha)
            if (!branch.isNullOrBlank()) put("branch", branch.trim())
        }

        val req = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/contents/$cleanPath")
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $cleanToken")
            .delete(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw Exception("Gagal menghapus file (${resp.code}).")
            }
        }
    }

    suspend fun renameOrMoveGithubFile(
        repoUrl: String,
        token: String,
        oldPath: String,
        newPath: String,
        branch: String? = null
    ) = withContext(Dispatchers.IO) {
        val cleanToken = token.trim()
        if (cleanToken.isEmpty()) throw IllegalArgumentException("GitHub Token wajib diisi.")
        val (owner, repo) = parseOwnerRepo(repoUrl)
        val cleanOld = oldPath.trim().trimStart('/')
        val cleanNew = newPath.trim().trimStart('/')
        if (cleanOld.isEmpty() || cleanNew.isEmpty()) {
            throw IllegalArgumentException("Path file lama dan baru tidak boleh kosong.")
        }
        if (cleanOld == cleanNew) {
            throw IllegalArgumentException("Path baru sama dengan path lama.")
        }

        val targetExists = getFileShaIfExists(owner, repo, cleanNew, cleanToken, branch)
        if (targetExists != null) {
            throw Exception("File tujuan sudah ada: $cleanNew")
        }

        val refQuery = if (!branch.isNullOrBlank()) "?ref=${Uri.encode(branch.trim())}" else ""
        val getReq = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/contents/$cleanOld$refQuery")
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $cleanToken")
            .build()

        val (oldSha, base64Content) = client.newCall(getReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw Exception("Gagal membaca file asal (${resp.code}).")
            }
            val json = JSONObject(resp.body?.string() ?: "{}")
            val sha = json.getString("sha")
            val rawBase64 = json.optString("content", "").replace("\n", "").replace("\r", "")
            val finalBase64 = if (rawBase64.isNotEmpty()) {
                rawBase64
            } else {
                val dlUrl = json.optString("download_url", "")
                if (dlUrl.isEmpty()) throw Exception("Konten file kosong atau tidak terbaca.")
                val dlReq = Request.Builder().url(dlUrl)
                    .header("Authorization", "Bearer $cleanToken")
                    .build()
                client.newCall(dlReq).execute().use { dlResp ->
                    if (!dlResp.isSuccessful) throw Exception("Gagal mengunduh konten file (${dlResp.code}).")
                    val bytes = dlResp.body?.bytes() ?: ByteArray(0)
                    Base64.encodeToString(bytes, Base64.NO_WRAP)
                }
            }
            sha to finalBase64
        }

        val createPayload = JSONObject().apply {
            put("message", "Rename $cleanOld to $cleanNew via RantingPohonTools")
            put("content", base64Content)
            if (!branch.isNullOrBlank()) put("branch", branch.trim())
        }
        val putReq = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/contents/$cleanNew")
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $cleanToken")
            .put(createPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(putReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string() ?: ""
                throw Exception("Gagal membuat file tujuan (${resp.code}): $errBody")
            }
        }

        val delPayload = JSONObject().apply {
            put("message", "Remove old path $cleanOld via RantingPohonTools")
            put("sha", oldSha)
            if (!branch.isNullOrBlank()) put("branch", branch.trim())
        }
        val delReq = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/contents/$cleanOld")
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $cleanToken")
            .delete(delPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(delReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw Exception("File baru terbuat, tapi gagal hapus file lama (${resp.code}).")
            }
        }
    }

    suspend fun fetchWorkflowRuns(
        repoUrl: String,
        token: String,
        branch: String? = null
    ): List<WorkflowRunItem> = withContext(Dispatchers.IO) {
        val cleanToken = token.trim()
        if (cleanToken.isEmpty()) throw IllegalArgumentException("GitHub Token wajib diisi.")
        val (owner, repo) = parseOwnerRepo(repoUrl)
        val branchQuery = if (!branch.isNullOrBlank()) "&branch=${Uri.encode(branch.trim())}" else ""
        val req = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/actions/runs?per_page=15$branchQuery")
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $cleanToken")
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw Exception("Gagal memuat GitHub Actions (${resp.code}). Pastikan Token punya izin 'actions:read' / 'repo'.")
            }
            val root = JSONObject(resp.body?.string() ?: "{}")
            val arr = root.optJSONArray("workflow_runs") ?: JSONArray()
            val list = mutableListOf<WorkflowRunItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    WorkflowRunItem(
                        id = obj.getLong("id"),
                        name = obj.optString("name", "Workflow"),
                        headBranch = obj.optString("head_branch", "-"),
                        status = obj.optString("status", "unknown"),
                        conclusion = obj.optString("conclusion", ""),
                        displayTitle = obj.optString("display_title", obj.optString("head_commit", "")),
                        createdAt = obj.optString("created_at", "")
                    )
                )
            }
            list
        }
    }

    suspend fun fetchRunJobStepsSummary(
        repoUrl: String,
        token: String?,
        runId: Long
    ): String = withContext(Dispatchers.IO) {
        val (owner, repo) = parseOwnerRepo(repoUrl)
        val reqBuilder = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/actions/runs/$runId/jobs")
            .header("Accept", "application/vnd.github+json")
        if (!token.isNullOrBlank()) {
            reqBuilder.header("Authorization", "Bearer ${token.trim()}")
        }

        client.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                return@withContext "(Progres step belum tersedia: HTTP ${resp.code})"
            }
            val root = JSONObject(resp.body?.string() ?: "{}")
            val jobsArr = root.optJSONArray("jobs") ?: JSONArray()
            if (jobsArr.length() == 0) {
                return@withContext "Menunggu runner GitHub Actions mengalokasikan job..."
            }

            val sb = StringBuilder()
            for (i in 0 until jobsArr.length()) {
                val job = jobsArr.getJSONObject(i)
                val jName = job.optString("name", "job")
                val jStatus = job.optString("status", "queued")
                val jConclusion = job.optString("conclusion", "").takeIf { it.isNotEmpty() && it != "null" }
                val stateLabel = if (jConclusion != null) "$jStatus/$jConclusion" else jStatus
                sb.append("Job: $jName ($stateLabel)\n")

                val stepsArr = job.optJSONArray("steps") ?: JSONArray()
                for (s in 0 until stepsArr.length()) {
                    val step = stepsArr.getJSONObject(s)
                    val sName = step.optString("name", "Step")
                    val sStatus = step.optString("status", "queued")
                    val sConclusion = step.optString("conclusion", "").takeIf { it.isNotEmpty() && it != "null" }
                    val icon = when {
                        sStatus == "in_progress" -> "⏳"
                        sConclusion == "success" -> "✅"
                        sConclusion == "failure" -> "❌"
                        sConclusion == "skipped" -> "⏭️"
                        else -> "•"
                    }
                    val detail = if (sConclusion != null) "$sStatus/$sConclusion" else sStatus
                    sb.append("  $icon $sName [$detail]\n")
                }
                sb.append("\n")
            }
            sb.toString().trim()
        }
    }

    suspend fun fetchRunFailureLog(
        repoUrl: String,
        token: String,
        runId: Long
    ): String = withContext(Dispatchers.IO) {
        val cleanToken = token.trim()
        if (cleanToken.isEmpty()) throw IllegalArgumentException("GitHub Token wajib diisi.")
        val (owner, repo) = parseOwnerRepo(repoUrl)

        val jobsReq = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/actions/runs/$runId/jobs")
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $cleanToken")
            .build()

        val jobsArray = client.newCall(jobsReq).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Gagal memuat detail job (${resp.code}).")
            JSONObject(resp.body?.string() ?: "{}").optJSONArray("jobs") ?: JSONArray()
        }

        if (jobsArray.length() == 0) return@withContext "Tidak ada data job pada run ini."

        var targetJobId: Long = -1L
        var targetJobName = ""
        val summary = StringBuilder()

        for (i in 0 until jobsArray.length()) {
            val job = jobsArray.getJSONObject(i)
            val jId = job.getLong("id")
            val jName = job.optString("name", "Job")
            val jConclusion = job.optString("conclusion", job.optString("status", ""))
            summary.append("Job: $jName [$jConclusion]\n")

            val steps = job.optJSONArray("steps") ?: JSONArray()
            for (s in 0 until steps.length()) {
                val step = steps.getJSONObject(s)
                val sName = step.optString("name", "Step")
                val sConc = step.optString("conclusion", "")
                if (sConc == "failure" || sConc == "cancelled") {
                    summary.append("  ✖ Step gagal: $sName ($sConc)\n")
                }
            }

            if (jConclusion == "failure" || job.optString("status") == "in_progress" || targetJobId == -1L) {
                targetJobId = jId
                targetJobName = jName
            }
        }

        if (targetJobId == -1L) return@withContext summary.toString()

        val logReq = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/actions/jobs/$targetJobId/logs")
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $cleanToken")
            .build()

        val rawLog = client.newCall(logReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                return@withContext "$summary\n(Log teks tidak tersedia: HTTP ${resp.code})"
            }
            resp.body?.string() ?: ""
        }

        val lines = rawLog.lines()
        val noisePrefixes = listOf(
            "Build Date:",
            "Image:",
            "Included Software:",
            "Image Release:",
            "Secret source:",
            "Cache mode:",
            "Complete job name:",
            "sparse-checkout-cone-mode:",
            "java-package:",
            "server-username:",
            "dependency-graph-continue-on-failure:",
            "Cache Size:",
            "##[warning]"
        )

        val errorLines = lines.filter { line ->
            val trimmed = line
                .replace(Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z?\s*"""), "")
                .trim()
            val isNoise = noisePrefixes.any { prefix -> trimmed.startsWith(prefix, ignoreCase = true) }
            !isNoise && (
                trimmed.startsWith("e: ") ||
                    trimmed.contains(": error:", ignoreCase = true) ||
                    trimmed.contains("FAILURE:", ignoreCase = true) ||
                    trimmed.contains("FAILED", ignoreCase = true) ||
                    trimmed.contains("Exception", ignoreCase = true) ||
                    trimmed.contains("Unresolved reference", ignoreCase = true) ||
                    trimmed.contains("Missing '", ignoreCase = true) ||
                    trimmed.startsWith("##[error]")
            )
        }

        val snippet = if (errorLines.isNotEmpty()) {
            val uniqueErrors = LinkedHashMap<String, Int>()
            for (rawLine in errorLines) {
                val cleaned = rawLine
                    .replace(Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z?\s*"""), "")
                    .trim()
                if (cleaned.isNotEmpty()) {
                    uniqueErrors[cleaned] = (uniqueErrors[cleaned] ?: 0) + 1
                }
            }

            val topCause = uniqueErrors.keys.firstOrNull { key ->
                key.startsWith("e: ") ||
                    key.contains("Unresolved reference", ignoreCase = true) ||
                    key.contains("Missing '", ignoreCase = true) ||
                    key.contains("FAILURE:", ignoreCase = true) ||
                    key.contains("Exception", ignoreCase = true)
            } ?: uniqueErrors.keys.firstOrNull().orEmpty()
            val causeBlock = if (topCause.isNotEmpty()) {
                "=== PENYEBAB UTAMA ===\n$topCause\n\n=== ERROR UNIK (${uniqueErrors.size} baris, total ${errorLines.size} kemunculan) ===\n"
            } else {
                ""
            }

            val dedupedList = uniqueErrors.entries.toList().takeLast(50).joinToString("\n") { (msg, count) ->
                if (count > 1) "• [x$count] $msg" else "• $msg"
            }
            "$causeBlock$dedupedList"
        } else {
            lines.takeLast(80).joinToString("\n")
        }

        "$summary\n--- Log ($targetJobName) ---\n$snippet"
    }

    suspend fun fetchRunArtifacts(
        repoUrl: String,
        token: String,
        runId: Long
    ): List<WorkflowArtifactItem> = withContext(Dispatchers.IO) {
        val cleanToken = token.trim()
        if (cleanToken.isEmpty()) throw IllegalArgumentException("GitHub Token wajib diisi.")
        val (owner, repo) = parseOwnerRepo(repoUrl)

        val req = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/actions/runs/$runId/artifacts")
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $cleanToken")
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Gagal memuat daftar artifact (${resp.code}).")
            val arr = JSONObject(resp.body?.string() ?: "{}").optJSONArray("artifacts") ?: JSONArray()
            val items = mutableListOf<WorkflowArtifactItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                items.add(
                    WorkflowArtifactItem(
                        id = obj.getLong("id"),
                        name = obj.optString("name", "artifact"),
                        sizeInBytes = obj.optLong("size_in_bytes", 0L),
                        expired = obj.optBoolean("expired", false)
                    )
                )
            }
            items
        }
    }

    suspend fun downloadArtifactApkToDownloads(
        repoUrl: String,
        token: String,
        artifactId: Long,
        artifactName: String
    ): String = withContext(Dispatchers.IO) {
        val cleanToken = token.trim()
        if (cleanToken.isEmpty()) throw IllegalArgumentException("GitHub Token wajib diisi.")
        val (owner, repo) = parseOwnerRepo(repoUrl)

        val req = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/actions/artifacts/$artifactId/zip")
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $cleanToken")
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw Exception("Gagal mengunduh artifact (${resp.code}).")
            }
            val bodyStream = resp.body?.byteStream()
                ?: throw Exception("Stream unduhan kosong.")

            var extractedApkName: String? = null
            var extractedApkBytes: ByteArray? = null

            val tempZip = File(context.cacheDir, "gh_artifact_$artifactId.zip")
            FileOutputStream(tempZip).use { out ->
                bodyStream.copyTo(out)
            }

            ZipInputStream(tempZip.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                        extractedApkName = entry.name.substringAfterLast('/')
                        extractedApkBytes = zis.readBytes()
                        break
                    }
                    entry = zis.nextEntry
                }
            }

            val finalFileName: String
            val finalMime: String
            val finalBytes: ByteArray

            if (extractedApkName != null && extractedApkBytes != null) {
                finalFileName = extractedApkName!!
                finalMime = "application/vnd.android.package-archive"
                finalBytes = extractedApkBytes!!
            } else {
                finalFileName = "${artifactName.replace(Regex("[^a-zA-Z0-9._-]"), "_")}.zip"
                finalMime = "application/zip"
                finalBytes = tempZip.readBytes()
            }

            tempZip.delete()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, finalFileName)
                    put(MediaStore.Downloads.MIME_TYPE, finalMime)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw Exception("Gagal membuat file di folder Download.")
                resolver.openOutputStream(uri)?.use { it.write(finalBytes) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "Download/$finalFileName"
            } else {
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
                val outFile = File(dir, finalFileName)
                FileOutputStream(outFile).use { it.write(finalBytes) }
                outFile.absolutePath
            }
        }
    }

    suspend fun fetchRepoCommits(
        repoUrl: String,
        token: String?,
        branch: String? = null
    ): List<GithubCommitItem> = withContext(Dispatchers.IO) {
        val (owner, repo) = parseOwnerRepo(repoUrl)
        val shaQuery = if (!branch.isNullOrBlank()) "&sha=${Uri.encode(branch.trim())}" else ""
        val reqBuilder = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/commits?per_page=20$shaQuery")
            .header("Accept", "application/vnd.github+json")
        if (!token.isNullOrBlank()) {
            reqBuilder.header("Authorization", "Bearer ${token.trim()}")
        }

        client.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw Exception("Gagal memuat riwayat commit (${resp.code}).")
            }
            val arr = JSONArray(resp.body?.string() ?: "[]")
            val commits = mutableListOf<GithubCommitItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val sha = obj.optString("sha", "")
                val commitObj = obj.optJSONObject("commit")
                val msg = commitObj?.optString("message", "(Tanpa pesan)") ?: "(Tanpa pesan)"
                val authorObj = commitObj?.optJSONObject("author")
                val author = authorObj?.optString("name", "Unknown") ?: "Unknown"
                val date = authorObj?.optString("date", "") ?: ""
                commits.add(
                    GithubCommitItem(
                        sha = sha,
                        message = msg,
                        authorName = author,
                        dateIso = date
                    )
                )
            }
            commits
        }
    }

    suspend fun scanLocalTree(treeUri: Uri): List<TreeNode> = withContext(Dispatchers.IO) {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val result = mutableListOf<TreeNode>()

        fun traverse(parentDocId: String, currentPath: String, depth: Int) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            )

            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)

                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idIdx)
                    val name = cursor.getString(nameIdx) ?: continue
                    val mime = cursor.getString(mimeIdx) ?: ""
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val path = if (currentPath.isEmpty()) name else "$currentPath/$name"
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

                    if (isDir) {
                        result.add(
                            TreeNode(
                                path = path,
                                name = name,
                                isDirectory = true,
                                depth = depth,
                                localUriString = docUri.toString()
                            )
                        )
                        if (!shouldAutoIgnore(path)) {
                            traverse(docId, path, depth + 1)
                        }
                    } else {
                        var exactChars = cursor.getLong(sizeIdx)
                        var content: String? = null
                        if (isCodeFile(name) && exactChars <= 500_000L) {
                            content = readLocalUriText(docUri)
                            exactChars = content.length.toLong()
                        }
                        result.add(
                            TreeNode(
                                path = path,
                                name = name,
                                isDirectory = false,
                                depth = depth,
                                charCount = exactChars,
                                localUriString = docUri.toString(),
                                cachedContent = content
                            )
                        )
                    }
                }
            }
        }

        traverse(rootDocId, "", 0)
        val sorted = sortHierarchy(result)
        recalculateFolderChars(sorted)
        sorted
    }

    fun readLocalUriText(uri: Uri): String {
        return try {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (e: Exception) {
            "// Gagal membaca file lokal: ${e.message}"
        }
    }

    private fun sortHierarchy(nodes: List<TreeNode>): List<TreeNode> {
        return nodes.sortedWith { a, b ->
            val partsA = a.path.split("/")
            val partsB = b.path.split("/")
            val minLen = minOf(partsA.size, partsB.size)

            for (i in 0 until minLen) {
                if (partsA[i] != partsB[i]) {
                    val isLastA = i == partsA.size - 1
                    val isLastB = i == partsB.size - 1
                    val dirA = if (isLastA) a.isDirectory else true
                    val dirB = if (isLastB) b.isDirectory else true
                    if (dirA != dirB) return@sortedWith if (dirA) -1 else 1
                    return@sortedWith partsA[i].compareTo(partsB[i], ignoreCase = true)
                }
            }
            partsA.size.compareTo(partsB.size)
        }
    }

    fun recalculateFolderChars(nodes: List<TreeNode>) {
        for (dir in nodes.filter { it.isDirectory }) {
            val prefix = "${dir.path}/"
            dir.charCount = nodes.filter { !it.isDirectory && it.path.startsWith(prefix) }
                .sumOf { it.charCount }
        }
    }
}
