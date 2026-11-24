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
    private var activeBranch: String = ""
    private var activeBranch: String = ""

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
        activeBranch = prefs.getString("studio_active_branch", "") ?: ""
        if (activeBranch.isNotEmpty()) {
            binding.btnStudioBranch.text = "Branch: $activeBranch"
        }
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
                        putExtra("branch", activeBranch)
                    }

                        }
                        editorLauncher.launch(intent)
                    }
                }
                .setNegativeButton("Batal", null)
                .show()
        }

        binding.btnStudioBranch.setOnClickListener {
            val token = binding.etStudioToken.text.toString().trim()
            if (activeRepoUrl.isEmpty() || token.isEmpty()) {
                Toast.makeText(this, "Pilih repository & isi token dulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pickBranchDialog(token)
        }

        binding.btnStudioRename.setOnClickListener {
            val token = binding.etStudioToken.text.toString().trim()
            if (activeRepoUrl.isEmpty() || token.isEmpty()) {
                Toast.makeText(this, "Pilih repository & isi token dulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startRenameOrMoveFlow(token)
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
                        activeBranch = ""
                        binding.btnStudioBranch.text = "Branch: -"
                        binding.tvActiveRepo.text = chosen
                        getSharedPreferences("ranting_pohon_prefs", Context.MODE_PRIVATE)
                            .edit()
                            .putString("studio_active_repo", chosen)
                            .remove("studio_active_branch")
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
                if (activeBranch.isEmpty()) {
                    activeBranch = scanner.fetchDefaultBranch(repoUrl, token)
                    getSharedPreferences("ranting_pohon_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putString("studio_active_branch", activeBranch)
                        .apply()
                }
                binding.btnStudioBranch.text = "Branch: $activeBranch"
                allNodes = scanner.scanGithubRepo(repoUrl, token, activeBranch)
                refreshList()
                binding.tvStudioStatus.text = "Siap ($activeBranch): ${allNodes.size} item. Ketuk file untuk mengedit."
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
                putExtra("branch", activeBranch)
            }
            editorLauncher.launch(intent)
        }
    }

    private fun pickBranchDialog(token: String) {
        binding.tvStudioStatus.text = "Memuat daftar branch..."
        lifecycleScope.launch {
            try {
                val branches = scanner.fetchRepoBranches(activeRepoUrl, token)
                if (branches.isEmpty()) {
                    binding.tvStudioStatus.text = "Branch tidak ditemukan."
                    return@launch
                }
                val labels = branches.map {
                    if (it == activeBranch) "✓ $it (aktif)" else it
                }.toTypedArray()

                AlertDialog.Builder(this@GithubStudioActivity)
                    .setTitle("Pilih Branch (${branches.size})")
                    .setItems(labels) { _, which ->
                        val chosenBranch = branches[which]
                        activeBranch = chosenBranch
                        binding.btnStudioBranch.text = "Branch: $chosenBranch"
                        getSharedPreferences("ranting_pohon_prefs", Context.MODE_PRIVATE)
                            .edit()
                            .putString("studio_active_branch", chosenBranch)
                            .apply()
                        loadRepoTree(activeRepoUrl)
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            } catch (e: Exception) {
                binding.tvStudioStatus.text = "Error branch: ${e.message}"
                Toast.makeText(this@GithubStudioActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startRenameOrMoveFlow(token: String) {
        val fileNodes = allNodes.filter { !it.isDirectory }
        if (fileNodes.isEmpty()) {
            Toast.makeText(this, "Tidak ada file di repository ini", Toast.LENGTH_SHORT).show()
            return
        }
        val checkedFiles = fileNodes.filter { it.isChecked }
        if (checkedFiles.size == 1) {
            showRenameInputDialog(token, checkedFiles[0].path)
            return
        }
        val paths = fileNodes.map { it.path }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Pilih File untuk Rename / Pindah")
            .setItems(paths) { _, which ->
                showRenameInputDialog(token, paths[which])
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showRenameInputDialog(token: String, oldPath: String) {
        val etNewPath = EditText(this).apply {
            setText(oldPath)
            setSelection(oldPath.length)
            isSingleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle("Rename / Pindah File")
            .setMessage("Path lama:\n$oldPath\n\nUbah nama file atau path folder tujuan:")
            .setView(etNewPath)
            .setPositiveButton("Simpan") { _, _ ->
                val newPath = etNewPath.text.toString().trim()
                if (newPath.isEmpty() || newPath == oldPath) {
                    Toast.makeText(this, "Path baru tidak valid atau masih sama", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                binding.tvStudioStatus.text = "Memindahkan $oldPath -> $newPath..."
                lifecycleScope.launch {
                    try {
                        scanner.renameOrMoveGithubFile(
                            repoUrl = activeRepoUrl,
                            token = token,
                            oldPath = oldPath,
                            newPath = newPath,
                            branch = activeBranch
                        )
                        Toast.makeText(this@GithubStudioActivity, "Berhasil dipindah ke $newPath", Toast.LENGTH_SHORT).show()
                        loadRepoTree(activeRepoUrl)
                    } catch (e: Exception) {
                        binding.tvStudioStatus.text = "Gagal rename/pindah: ${e.message}"
                        Toast.makeText(this@GithubStudioActivity, e.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
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
