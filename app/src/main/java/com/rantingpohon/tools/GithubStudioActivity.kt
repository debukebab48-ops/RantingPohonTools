package com.rantingpohon.tools

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.rantingpohon.tools.databinding.ActivityGithubStudioBinding
import kotlinx.coroutines.launch

class GithubStudioActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGithubStudioBinding
    private lateinit var scanner: RepoScanner
    private lateinit var adapter: TreeAdapter

    private var allNodes: List<TreeNode> = emptyList()
    private var activeRepoUrl: String = ""

    private val editorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && activeRepoUrl.isNotEmpty()) {
            loadRepoTree(activeRepoUrl)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGithubStudioBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.studioRoot) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        scanner = RepoScanner(this)

        adapter = TreeAdapter(
            onToggleExpand = { dirNode ->
                dirNode.isExpanded = !dirNode.isExpanded
                refreshList()
            },
            onToggleCheck = { node, checked ->
                node.isChecked = checked
                refreshList()
            },
            onFileClick = { fileNode ->
                openInFullEditor(fileNode)
            }
        )

        binding.rvStudioTree.layoutManager = LinearLayoutManager(this)
        binding.rvStudioTree.adapter = adapter

        val prefs = getSharedPreferences("ranting_pohon_prefs", Context.MODE_PRIVATE)
        binding.etStudioToken.setText(prefs.getString("github_token", ""))
        val savedRepo = prefs.getString("studio_active_repo", "") ?: ""
        if (savedRepo.isNotEmpty()) {
            activeRepoUrl = savedRepo
            binding.tvActiveRepo.text = savedRepo
            loadRepoTree(savedRepo)
        }

        setupListeners()
    }

    private fun setupListeners() {
        binding.btnBackStudio.setOnClickListener { finish() }

        binding.etStudioToken.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                getSharedPreferences("ranting_pohon_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("github_token", s?.toString()?.trim() ?: "")
                    .apply()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnStudioPickRepo.setOnClickListener {
            val token = binding.etStudioToken.text.toString().trim()
            if (token.isEmpty()) {
                Toast.makeText(this, "Masukkan GitHub Token dulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pickRepoDialog(token)
        }

        binding.btnStudioNewFile.setOnClickListener {
            val token = binding.etStudioToken.text.toString().trim()
            if (activeRepoUrl.isEmpty() || token.isEmpty()) {
                Toast.makeText(this, "Pilih repository & isi token dulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val etPath = EditText(this).apply {
                hint = "Contoh: app/src/main/NewFile.kt"
                isSingleLine = true
            }
            AlertDialog.Builder(this)
                .setTitle("Path File Baru")
                .setView(etPath)
                .setPositiveButton("Buka Editor") { _, _ ->
                    val path = etPath.text.toString().trim()
                    if (path.isNotEmpty()) {
                        val intent = Intent(this, CodeEditorActivity::class.java).apply {
                            putExtra("repo_url", activeRepoUrl)
                            putExtra("token", token)
                            putExtra("file_path", path)
                            putExtra("content", "")
                            putExtra("is_new_file", true)
                        }
                        editorLauncher.launch(intent)
                    }
                }
                .setNegativeButton("Batal", null)
                .show()
        }

        binding.etStudioSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun pickRepoDialog(token: String) {
        binding.tvStudioStatus.text = "Memuat daftar repository..."
        lifecycleScope.launch {
            try {
                val repos = scanner.fetchUserRepos(token)
                if (repos.isEmpty()) {
                    binding.tvStudioStatus.text = "Tidak ada repository ditemukan."
                    return@launch
                }
                val labels = repos.map {
                    "${if (it.isPrivate) "[Privat]" else "[Publik]"} ${it.fullName}"
                }.toTypedArray()

                AlertDialog.Builder(this@GithubStudioActivity)
                    .setTitle("Pilih Repository (${repos.size})")
                    .setItems(labels) { _, which ->
                        val chosen = repos[which].htmlUrl
                        activeRepoUrl = chosen
                        binding.tvActiveRepo.text = chosen
                        getSharedPreferences("ranting_pohon_prefs", Context.MODE_PRIVATE)
                            .edit()
                            .putString("studio_active_repo", chosen)
                            .apply()
                        loadRepoTree(chosen)
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            } catch (e: Exception) {
                binding.tvStudioStatus.text = "Error: ${e.message}"
                Toast.makeText(this@GithubStudioActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadRepoTree(repoUrl: String) {
        val token = binding.etStudioToken.text.toString().trim()
        binding.tvStudioStatus.text = "Memuat isi repository..."
        lifecycleScope.launch {
            try {
                allNodes = scanner.scanGithubRepo(repoUrl, token)
                refreshList()
                binding.tvStudioStatus.text = "Siap: ${allNodes.size} item. Ketuk file untuk mengedit."
            } catch (e: Exception) {
                binding.tvStudioStatus.text = "Gagal memuat: ${e.message}"
            }
        }
    }

    private fun openInFullEditor(node: TreeNode) {
        val token = binding.etStudioToken.text.toString().trim()
        binding.tvStudioStatus.text = "Membuka ${node.name}..."
        lifecycleScope.launch {
            val content = scanner.fetchGithubFileContent(node, token)
            binding.tvStudioStatus.text = "Ketuk file untuk mengedit."
            val intent = Intent(this@GithubStudioActivity, CodeEditorActivity::class.java).apply {
                putExtra("repo_url", activeRepoUrl)
                putExtra("token", token)
                putExtra("file_path", node.path)
                putExtra("content", content)
                putExtra("is_new_file", false)
            }
            editorLauncher.launch(intent)
        }
    }

    private fun refreshList() {
        val query = binding.etStudioSearch.text.toString().trim().lowercase()
        val collapsed = allNodes.filter { it.isDirectory && !it.isExpanded }.map { "${it.path}/" }
        val visible = allNodes.filter { node ->
            if (collapsed.any { node.path.startsWith(it) }) return@filter false
            if (query.isNotEmpty()) node.path.lowercase().contains(query) else true
        }
        adapter.submitList(visible)
    }
}
