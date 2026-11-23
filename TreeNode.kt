package com.rantingpohon.tools

data class TreeNode(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val depth: Int,
    var charCount: Long = 0L,
    var isChecked: Boolean = true,
    var isExpanded: Boolean = true,
    val localUriString: String? = null,
    val githubRawUrl: String? = null,
    var cachedContent: String? = null
)
