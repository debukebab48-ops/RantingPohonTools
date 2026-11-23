package com.rantingpohon.tools

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
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

    suspend fun scanGithubRepo(repoUrl: String, token: String?): List<TreeNode> = withContext(Dispatchers.IO) {
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
        val defaultBranch = repoJson.optString("default_branch", "main")

        val treeReqBuilder = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/git/trees/$defaultBranch?recursive=1")
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

    private fun getFileShaIfExists(owner: String, repo: String, filePath: String, token: String): String? {
        val cleanPath = filePath.trimStart('/')
        val req = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/contents/$cleanPath")
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
        commitMessage: String
    ) = withContext(Dispatchers.IO) {
        val cleanToken = token.trim()
        if (cleanToken.isEmpty()) throw IllegalArgumentException("GitHub Token wajib diisi untuk mengubah repo.")
        val (owner, repo) = parseOwnerRepo(repoUrl)
        val cleanPath = filePath.trim().trimStart('/')
        if (cleanPath.isEmpty()) throw IllegalArgumentException("Path file tidak boleh kosong.")

        val existingSha = getFileShaIfExists(owner, repo, cleanPath, cleanToken)
        val encodedContent = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        val payload = JSONObject().apply {
            put("message", commitMessage.ifBlank { "Update $cleanPath via RantingPohonTools" })
            put("content", encodedContent)
            if (existingSha != null) put("sha", existingSha)
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
        commitMessage: String
    ) = withContext(Dispatchers.IO) {
        val cleanToken = token.trim()
        if (cleanToken.isEmpty()) throw IllegalArgumentException("GitHub Token wajib diisi.")
        val (owner, repo) = parseOwnerRepo(repoUrl)
        val cleanPath = filePath.trim().trimStart('/')

        val sha = getFileShaIfExists(owner, repo, cleanPath, cleanToken)
            ?: throw Exception("File tidak ditemukan di GitHub atau sudah terhapus.")

        val payload = JSONObject().apply {
            put("message", commitMessage.ifBlank { "Delete $cleanPath via RantingPohonTools" })
            put("sha", sha)
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
