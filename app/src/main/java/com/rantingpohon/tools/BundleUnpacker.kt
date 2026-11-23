package com.rantingpohon.tools

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
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

        val orderedNodes = mutableListOf<TreeNode>()
        fun addChildren(parentPath: String, depth: Int) {
            val childrenDirs = dirSet.filter {
                it.substringBeforeLast('/', "") == parentPath
            }.sortedBy { it.lowercase() }

            for (dir in childrenDirs) {
                val subFilesChars = fileMap
                    .filterKeys { it.startsWith("$dir/") }
                    .values
                    .sumOf { it.length.toLong() }

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
                it.substringBeforeLast('/', "") == parentPath
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
        val rootDocId = DocumentsContract.getTreeDocumentId(rootUri)
        val dirDocIdCache = mutableMapOf<String, String>()
        dirDocIdCache[""] = rootDocId

        var successCount = 0
        fileNodes.forEachIndexed { index, node ->
            val cleanPath = node.path.trimStart('/')
            val parentPath = cleanPath.substringBeforeLast('/', "")
            val fileName = cleanPath.substringAfterLast('/')

            withContext(Dispatchers.Main) {
                onProgress(index + 1, fileNodes.size, fileName)
            }

            val targetDirDocId = getOrCreateDirDocId(context, rootUri, parentPath, dirDocIdCache)

            // Hapus file lama jika namanya sama agar tidak duplikat (misal jadi file (1).kt)
            val existingFileDocId = findChildDocId(context, rootUri, targetDirDocId, fileName, wantDir = false)
            if (existingFileDocId != null) {
                val oldUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, existingFileDocId)
                try {
                    DocumentsContract.deleteDocument(context.contentResolver, oldUri)
                } catch (_: Exception) {
                }
            }

            val parentDirUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, targetDirDocId)
            val ext = fileName.substringAfterLast('.', "").lowercase()
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: "application/octet-stream"

            val createdFileUri = DocumentsContract.createDocument(
                context.contentResolver,
                parentDirUri,
                mimeType,
                fileName
            ) ?: throw IllegalStateException("Gagal membuat file: $cleanPath")

            context.contentResolver.openOutputStream(createdFileUri)?.bufferedWriter()?.use {
                it.write(node.cachedContent ?: "")
            }
            successCount++
        }
        successCount
    }

    private fun getOrCreateDirDocId(
        context: Context,
        rootUri: Uri,
        dirPath: String,
        cache: MutableMap<String, String>
    ): String {
        if (dirPath.isEmpty()) return cache[""]!!
        cache[dirPath]?.let { return it }

        val parts = dirPath.split("/")
        var currentDocId = cache[""]!!
        var currentPath = ""

        for (part in parts) {
            currentPath = if (currentPath.isEmpty()) part else "$currentPath/$part"
            val cached = cache[currentPath]
            if (cached != null) {
                currentDocId = cached
            } else {
                val existingDocId = findChildDocId(context, rootUri, currentDocId, part, wantDir = true)
                val nextDocId = if (existingDocId != null) {
                    existingDocId
                } else {
                    val parentUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, currentDocId)
                    val newDirUri = DocumentsContract.createDocument(
                        context.contentResolver,
                        parentUri,
                        DocumentsContract.Document.MIME_TYPE_DIR,
                        part
                    ) ?: throw IllegalStateException("Gagal membuat folder: $currentPath")
                    DocumentsContract.getDocumentId(newDirUri)
                }
                cache[currentPath] = nextDocId
                currentDocId = nextDocId
            }
        }
        return currentDocId
    }

    private fun findChildDocId(
        context: Context,
        rootUri: Uri,
        parentDocId: String,
        targetName: String,
        wantDir: Boolean
    ): String? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx) ?: continue
                if (name == targetName) {
                    val mime = cursor.getString(mimeIdx) ?: ""
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    if (isDir == wantDir) {
                        return cursor.getString(idIdx)
                    }
                }
            }
        }
        return null
    }
}
