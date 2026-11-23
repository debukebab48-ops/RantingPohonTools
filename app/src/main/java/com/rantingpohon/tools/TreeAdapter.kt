package com.rantingpohon.tools

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.rantingpohon.tools.databinding.ItemTreeNodeBinding
import java.text.NumberFormat
import java.util.Locale

class TreeAdapter(
    private val onToggleExpand: (TreeNode) -> Unit,
    private val onToggleCheck: (TreeNode, Boolean) -> Unit,
    private val onFileClick: (TreeNode) -> Unit
) : RecyclerView.Adapter<TreeAdapter.NodeViewHolder>() {

    private var visibleNodes: List<TreeNode> = emptyList()
    private val numberFormat = NumberFormat.getNumberInstance(Locale("id", "ID"))

    fun submitList(nodes: List<TreeNode>) {
        visibleNodes = nodes
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NodeViewHolder {
        val binding = ItemTreeNodeBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return NodeViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NodeViewHolder, position: Int) {
        holder.bind(visibleNodes[position])
    }

    override fun getItemCount(): Int = visibleNodes.size

    inner class NodeViewHolder(
        private val binding: ItemTreeNodeBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(node: TreeNode) {
            val density = binding.root.context.resources.displayMetrics.density
            val leftPadding = (node.depth * 16 * density).toInt()
            binding.rowContainer.setPadding(leftPadding, 0, (8 * density).toInt(), 0)

            if (node.isDirectory) {
                binding.tvArrow.visibility = View.VISIBLE
                binding.tvArrow.text = if (node.isExpanded) "▼" else "▶"
                binding.tvIcon.text = "📁"
                binding.tvName.setTextColor(Color.parseColor("#E6EDF3"))
            } else {
                binding.tvArrow.visibility = View.INVISIBLE
                binding.tvIcon.text = "📄"
                binding.tvName.setTextColor(Color.parseColor("#C9D1D9"))
            }

            binding.tvName.text = node.name
            binding.tvCharCount.text = "${numberFormat.format(node.charCount)} karakter"

            binding.tvCharCount.setTextColor(
                when {
                    node.charCount > 30_000 -> Color.parseColor("#F85149")
                    node.charCount > 10_000 -> Color.parseColor("#D29922")
                    else -> Color.parseColor("#8B949E")
                }
            )

            binding.cbNode.setOnCheckedChangeListener(null)
            binding.cbNode.isChecked = node.isChecked
            binding.cbNode.setOnCheckedChangeListener { _, isChecked ->
                onToggleCheck(node, isChecked)
            }

            binding.rowContainer.setOnClickListener {
                if (node.isDirectory) {
                    onToggleExpand(node)
                } else {
                    onFileClick(node)
                }
            }
        }
    }
}
