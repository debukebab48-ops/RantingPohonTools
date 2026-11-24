package com.rantingpohon.tools

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

    override fun onResume() {
        super.onResume()
        val savedToken = getSharedPreferences("ranting_pohon_prefs", Context.MODE_PRIVATE)
            .getString("github_token", "") ?: ""
        if (savedToken.isNotEmpty() && binding.etStudioToken.text.toString().trim().isEmpty()) {
            binding.etStudioToken.setText(savedToken)
        }
    }

    private fun setupListeners() {
        binding.btnBackStudio.setOnClickListener { finish() }

        binding.etStudioToken.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val clean = s?.toString()?.trim() ?: ""
                if (clean.isNotEmpty()) {
                    getSharedPreferences("ranting_pohon_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putString("github_token", clean)
                        .apply()
                }
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

        binding.btnStudioActions.setOnClickListener {
            val token = binding.etStudioToken.text.toString().trim()
            if (activeRepoUrl.isEmpty() || token.isEmpty()) {
                Toast.makeText(this, "Pilih repository & isi token dulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            openActionsMonitorDialog(token)
        }

        binding.btnStudioCommits.setOnClickListener {
            val token = binding.etStudioToken.text.toString().trim()
            if (activeRepoUrl.isEmpty()) {
                Toast.makeText(this, "Pilih repository dulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            openCommitHistoryDialog(token)
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

    private fun openActionsMonitorDialog(token: String) {
        binding.tvStudioStatus.text = "Memuat status build GitHub Actions..."
        lifecycleScope.launch {
            try {
                val runs = scanner.fetchWorkflowRuns(activeRepoUrl, token, activeBranch)
                if (runs.isEmpty()) {
                    binding.tvStudioStatus.text = "Belum ada riwayat build di branch $activeBranch."
                    Toast.makeText(this@GithubStudioActivity, "Belum ada workflow run ditemukan", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                binding.tvStudioStatus.text = "Ketuk item build untuk lihat log atau unduh APK."

                val labels = runs.map { run ->
                    val badge = when {
                        run.status != "completed" -> "⏳ RUNNING"
                        run.conclusion == "success" -> "✅ SUCCESS"
                        run.conclusion == "failure" -> "❌ FAILED"
                        else -> "⚠️ ${run.conclusion.uppercase()}"
                    }
                    val title = run.displayTitle.take(45)
                    "$badge • ${run.name}\n$title"
                }.toTypedArray()

                AlertDialog.Builder(this@GithubStudioActivity)
                    .setTitle("GitHub Actions ($activeBranch)")
                    .setItems(labels) { _, which ->
                        handleSelectedWorkflowRun(token, runs[which])
                    }
                    .setNegativeButton("Tutup", null)
                    .show()
            } catch (e: Exception) {
                binding.tvStudioStatus.text = "Error Actions: ${e.message}"
                Toast.makeText(this@GithubStudioActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleSelectedWorkflowRun(token: String, run: WorkflowRunItem) {
        val options = mutableListOf<String>()
        if (run.conclusion == "success") {
            options.add("Unduh Artifact / APK")
        }
        options.add("Lihat Log Build / Error")
        if (run.conclusion != "success") {
            options.add("Cek Artifact (Jika Ada)")
        }

        AlertDialog.Builder(this)
            .setTitle("${run.name} (${run.conclusion.ifEmpty { run.status }})")
            .setItems(options.toTypedArray()) { _, which ->
                val chosen = options[which]
                if (chosen.startsWith("Lihat Log")) {
                    showRunLogDialog(token, run)
                } else {
                    pickAndDownloadArtifact(token, run)
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showRunLogDialog(token: String, run: WorkflowRunItem) {
        binding.tvStudioStatus.text = "Mengambil log build..."
        var latestLog = "Memuat log..."

        val tvLog = TextView(this@GithubStudioActivity).apply {
            text = latestLog
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(32, 24, 32, 24)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this@GithubStudioActivity).apply {
            addView(tvLog)
        }

        val dialog = AlertDialog.Builder(this@GithubStudioActivity)
            .setTitle("Live Log #${run.id} (${run.status})")
            .setView(scroll)
            .setPositiveButton("Salin Log") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("build_log", latestLog))
                Toast.makeText(this@GithubStudioActivity, "Log disalin ke clipboard", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Refresh", null)
            .setNegativeButton("Tutup", null)
            .create()

        var pollJob: Job? = null

        suspend fun refreshOnce(): Boolean {
            return try {
                val runsNow = scanner.fetchWorkflowRuns(activeRepoUrl, token, activeBranch)
                val currentRun = runsNow.firstOrNull { it.id == run.id }
                val statusLabel = currentRun?.let {
                    if (it.conclusion.isNotEmpty()) "${it.status}/${it.conclusion}" else it.status
                } ?: run.status

                val logText = scanner.fetchRunFailureLog(activeRepoUrl, token, run.id)
                latestLog = logText
                tvLog.text = logText
                dialog.setTitle("Live Log #${run.id} ($statusLabel)")
                binding.tvStudioStatus.text = "Log #${run.id} [$statusLabel]"
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

                val stillRunning = currentRun == null || currentRun.status != "completed"
                stillRunning
            } catch (e: Exception) {
                binding.tvStudioStatus.text = "Gagal ambil log: ${e.message}"
                false
            }
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                lifecycleScope.launch { refreshOnce() }
            }
            pollJob = lifecycleScope.launch {
                while (isActive && dialog.isShowing) {
                    val keepPolling = refreshOnce()
                    if (!keepPolling) break
                    delay(3000L)
                }
            }
        }

        dialog.setOnDismissListener {
            pollJob?.cancel()
        }

        dialog.show()
    }

    private fun pickAndDownloadArtifact(token: String, run: WorkflowRunItem) {
        binding.tvStudioStatus.text = "Mengecek artifact APK..."
        lifecycleScope.launch {
            try {
                val artifacts = scanner.fetchRunArtifacts(activeRepoUrl, token, run.id)
                    .filter { !it.expired }
                if (artifacts.isEmpty()) {
                    binding.tvStudioStatus.text = "Tidak ada artifact aktif pada build ini."
                    Toast.makeText(this@GithubStudioActivity, "Artifact tidak ditemukan atau sudah expired", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val labels = artifacts.map {
                    val sizeMb = String.format("%.2f MB", it.sizeInBytes / (1024.0 * 1024.0))
                    "${it.name} ($sizeMb)"
                }.toTypedArray()

                AlertDialog.Builder(this@GithubStudioActivity)
                    .setTitle("Pilih Artifact untuk Diunduh")
                    .setItems(labels) { _, which ->
                        val chosen = artifacts[which]
                        binding.tvStudioStatus.text = "Mengunduh ${chosen.name}..."
                        Toast.makeText(this@GithubStudioActivity, "Mengunduh & mengekstrak APK...", Toast.LENGTH_SHORT).show()
                        lifecycleScope.launch {
                            try {
                                val savedLocation = scanner.downloadArtifactApkToDownloads(
                                    repoUrl = activeRepoUrl,
                                    token = token,
                                    artifactId = chosen.id,
                                    artifactName = chosen.name
                                )
                                binding.tvStudioStatus.text = "Tersimpan di: $savedLocation"
                                AlertDialog.Builder(this@GithubStudioActivity)
                                    .setTitle("Unduhan Selesai")
                                    .setMessage("File berhasil disimpan di:\n$savedLocation\n\nSilakan buka File Manager di folder Download untuk menginstal APK.")
                                    .setPositiveButton("OK", null)
                                    .show()
                            } catch (e: Exception) {
                                binding.tvStudioStatus.text = "Gagal unduh: ${e.message}"
                                Toast.makeText(this@GithubStudioActivity, e.message, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            } catch (e: Exception) {
                binding.tvStudioStatus.text = "Error artifact: ${e.message}"
                Toast.makeText(this@GithubStudioActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openCommitHistoryDialog(token: String) {
        binding.tvStudioStatus.text = "Memuat riwayat commit..."
        lifecycleScope.launch {
            try {
                val commits = scanner.fetchRepoCommits(activeRepoUrl, token, activeBranch)
                if (commits.isEmpty()) {
                    binding.tvStudioStatus.text = "Belum ada riwayat commit."
                    Toast.makeText(this@GithubStudioActivity, "Riwayat commit kosong", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                binding.tvStudioStatus.text = "Menampilkan ${commits.size} commit terakhir ($activeBranch)."

                val labels = commits.map { c ->
                    val shortSha = c.sha.take(7)
                    val firstLine = c.message.lineSequence().firstOrNull()?.take(50) ?: "(Tanpa pesan)"
                    val cleanTime = c.dateIso.replace("T", " ").removeSuffix("Z")
                    "[$shortSha] $firstLine\n${c.authorName} • $cleanTime"
                }.toTypedArray()

                AlertDialog.Builder(this@GithubStudioActivity)
                    .setTitle("Riwayat Commit ($activeBranch)")
                    .setItems(labels) { _, which ->
                        val chosen = commits[which]
                        val cleanTime = chosen.dateIso.replace("T", " ").removeSuffix("Z")
                        val detail = "SHA: ${chosen.sha}\nPenulis: ${chosen.authorName}\nWaktu: $cleanTime UTC\n\nPesan:\n${chosen.message}"
                        AlertDialog.Builder(this@GithubStudioActivity)
                            .setTitle("Detail Commit ${chosen.sha.take(7)}")
                            .setMessage(detail)
                            .setPositiveButton("Salin SHA") { _, _ ->
                                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("commit_sha", chosen.sha))
                                Toast.makeText(this@GithubStudioActivity, "SHA disalin", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("Tutup", null)
                            .show()
                    }
                    .setNegativeButton("Tutup", null)
                    .show()
            } catch (e: Exception) {
                binding.tvStudioStatus.text = "Error commit: ${e.message}"
                Toast.makeText(this@GithubStudioActivity, e.message, Toast.LENGTH_LONG).show()
            }
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
