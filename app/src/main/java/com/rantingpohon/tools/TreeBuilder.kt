package com.rantingpohon.tools

object TreeBuilder {

    fun generateAsciiTree(rootName: String, checkedNodes: List<TreeNode>): String {
        if (checkedNodes.isEmpty()) return "$rootName/\n(Tidak ada file terpilih)"

        val allPaths = mutableSetOf<String>()
        val dirSet = mutableSetOf<String>()

        for (node in checkedNodes) {
            val parts = node.path.split("/")
            var current = ""
            for (i in parts.indices) {
                current = if (current.isEmpty()) parts[i] else "$current/${parts[i]}"
                allPaths.add(current)
                if (i < parts.size - 1 || node.isDirectory) {
                    dirSet.add(current)
                }
            }
        }

        val childrenMap = mutableMapOf<String, MutableList<String>>()
        for (path in allPaths) {
            val parent = if (path.contains('/')) path.substringBeforeLast('/') else ""
            childrenMap.getOrPut(parent) { mutableListOf() }.add(path)
        }

        for ((_, list) in childrenMap) {
            list.sortWith(compareBy({ it !in dirSet }, { it.substringAfterLast('/').lowercase() }))
        }

        val sb = StringBuilder()
        sb.append("$rootName/\n")

        fun render(parentPath: String, prefix: String) {
            val children = childrenMap[parentPath] ?: return
            for (i in children.indices) {
                val childPath = children[i]
                val isLast = i == children.size - 1
                val connector = if (isLast) "└── " else "├── "
                val name = childPath.substringAfterLast('/')
                val suffix = if (childPath in dirSet) "/" else ""
                sb.append("$prefix$connector$name$suffix\n")
                if (childPath in dirSet) {
                    val nextPrefix = prefix + if (isLast) "    " else "│   "
                    render(childPath, nextPrefix)
                }
            }
        }

        render("", "")
        return sb.toString().trimEnd()
    }
}
