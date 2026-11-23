package com.rantingpohon.tools

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object BundleUnpacker {

    private val FILE_HEADER_REGEX = Regex(
        """={10,}\r?\nFILE:\s*(.+?)\r?\n={10,}\r?\n"""
    )

    fun parseBundleToNodes(rawText: String): List<TreeNode> {
        val matches = FILE_HEADER_REGEX.findAll(rawText).toList()
        if (matches.isEmpty()) return emptyList()

        val fileMap = linkedMapOf<String, String>()

        for (i in matches.indices) {
            val match = matches[i]
            val rawPath = match.groupValues[1].trim().replace("\\", "/").trimStart('/')
            if (rawPath.isBlank()) continue

            val contentStart = match.range.last + 1
            val contentEnd = if (i + 1 < matches.size) {
                matches[i + 1].range.first
            } else {
                rawText.length
            }

            var content = rawText.substring(contentStart, contentEnd)
            // Hapus 2 newline pemisah antar blok file di bagian akhir
            if (content.endsWith("\r\n\r\n")) {
                content = content.dropLast(4)
            } else if (content.endsWith("\n\n")) {
                content = content.dropLast(2)
            } else if (content.endsWith("\n")) {
                content = content.dropLast(1)
            }

            fileMap[rawPath] = content
        }

        val dirSet = mutableSetOf<String>()
        for (filePath in fileMap.keys) {
            val parts = filePath.split("/")
            for (depth in 1 until parts.size) {
                dirSet.add(parts.subList(0, depth).joinToString("/"))
            }
        }

        val allPaths = (dirSet + fileMap.keys).distinct().sortedWith(
            compareBy<String> { it.substringBeforeLast('/', "") }
                .thenBy { !dirSet.contains(it) }
                .thenBy { it.lowercase() }
        )

        // Urutkan secara hierarki pohon (DFS) agar tampil rapi di RecyclerView
        val orderedNodes = mutableListOf<TreeNode>()
        fun addChildren(parentPath: String, depth: Int) {
            val childrenDirs = dirSet.filter {
                val p = it.substringBeforeLast('/', "")
                p == parentPath
            }.sortedBy { it.lowercase() }

            for (dir in childrenDirs) {
                val subFilesChars = fileMap.filterKeys { it.startsWith("$dir/") }.values.sumOf { it.length.toLong() }
                orderedNodes.add(
                    TreeNode(
                        path = dir,
                        name = dir.substringAfterLast('/'),
                        isDirectory = true,
                        depth = depth,
                        charCount = subFilesChars,
                        isChecked = true,
                        isExpanded = true
                    )
                )
                addChildren(dir, depth + 1)
            }

            val childrenFiles = fileMap.keys.filter {
                val p = it.substringBeforeLast('/', "")
                p == parentPath
            }.sortedBy { it.lowercase() }

            for (file in childrenFiles) {
                val content = fileMap[file] ?: ""
                orderedNodes.add(
                    TreeNode(
                        path = file,
                        name = file.substringAfterLast('/'),
                        isDirectory = false,
                        depth = depth,
                        charCount = content.length.toLong(),
                        isChecked = true,
                        isExpanded = true,
                        cachedContent = content
                    )
                )
            }
        }

        addChildren("", 0)
        return orderedNodes
    }

    suspend fun extractNodesToUri(
        context: Context,
        rootUri: Uri,
        fileNodes: List<TreeNode>,
        onProgress: (Int, Int, String) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val rootDoc = DocumentFile.fromTreeUri(context, rootUri)
            ?: throw IllegalStateException("Folder tujuan tidak dapat diakses.")

        val dirCache = mutableMapOf<String, DocumentFile>()
        dirCache[""] = rootDoc

        var successCount = 0
        fileNodes.forEachIndexed { index, node ->
            val cleanPath = node.path.trimStart('/')
            val parentPath = cleanPath.substringBeforeLast('/', "")
            val fileName = cleanPath.substringAfterLast('/')

            withContext(Dispatchers.Main) {
                onProgress(index + 1, fileNodes.size, fileName)
            }

            val targetDir = getOrCreateDir(rootDoc, parentPath, dirCache)
            targetDir.findFile(fileName)?.delete()

            val createdFile = targetDir.createFile("application/octet-stream", fileName)
                ?: targetDir.createFile("text/plain", fileName)
                ?: throw IllegalStateException("Gagal membuat file: $cleanPath")

            context.contentResolver.openOutputStream(createdFile.uri)?.bufferedWriter()?.use {
                it.write(node.cachedContent ?: "")
            }
            successCount++
        }
        successCount
    }

    private fun getOrCreateDir(
        rootDoc: DocumentFile,
        dirPath: String,
        cache: MutableMap<String, DocumentFile>
    ): DocumentFile {
        if (dirPath.isEmpty()) return rootDoc
        cache[dirPath]?.let { return it }

        val parts = dirPath.split("/")
        var currentDoc = rootDoc
        var currentPath = ""

        for (part in parts) {
            currentPath = if (currentPath.isEmpty()) part else "$currentPath/$part"
            val cached = cache[currentPath]
            if (cached != null) {
                currentDoc = cached
            } else {
                val existing = currentDoc.findFile(part)
                val nextDoc = if (existing != null && existing.isDirectory) {
                    existing
                } else {
                    currentDoc.createDirectory(part)
                        ?: throw IllegalStateException("Gagal membuat folder: $currentPath")
                }
                cache[currentPath] = nextDoc
                currentDoc = nextDoc
            }
        }
        return currentDoc
    }
}
