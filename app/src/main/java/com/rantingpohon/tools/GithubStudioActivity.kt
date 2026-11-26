package com.rantingpohon.tools

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
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

    private val uploadFilePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            handleSelectedUploadUri(uri)
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

        binding.btnStudioUploadFile.setOnClickListener {
            val token = binding.etStudioToken.text.toString().trim()
            if (activeRepoUrl.isEmpty() || token.isEmpty()) {
                Toast.makeText(this, "Pilih repository & isi token dulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            uploadFilePicker.launch(arrayOf("*/*"))
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

        binding.btnStudioBackup.setOnClickListener {
            val token = binding.etStudioToken.text.toString().trim()
            if (activeRepoUrl.isEmpty() || token.isEmpty()) {
                Toast.makeText(this, "Pilih repository & isi token dulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            openBackupMenuDialog(token)
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

                val latestRun = runs.firstOrNull()
                if (latestRun != null && latestRun.status == "completed" && latestRun.conclusion == "success") {
                    autoRefreshBackupOnGreenBuild(token, latestRun.id)
                }

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
                val cleanConclusion = currentRun?.conclusion?.takeIf { it.isNotEmpty() && it != "null" }
                val statusLabel = currentRun?.let {
                    if (cleanConclusion != null) "${it.status}/$cleanConclusion" else it.status
                } ?: run.status

                val rawLog = scanner.fetchRunFailureLog(activeRepoUrl, token, run.id)
                val logText = if (rawLog.contains("HTTP 404") || rawLog.contains("Belum ada job")) {
                    val stepsSummary = scanner.fetchRunJobStepsSummary(activeRepoUrl, token, run.id)
                    if (currentRun?.status == "completed") {
                        "$stepsSummary\n\n$rawLog"
                    } else {
                        "$stepsSummary\n\n(Log teks mentah tersedia setelah proses selesai)"
                    }
                } else {
                    rawLog
                }
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

    private fun handleSelectedUploadUri(uri: Uri) {
        val token = binding.etStudioToken.text.toString().trim()
        if (activeRepoUrl.isEmpty() || token.isEmpty()) {
            Toast.makeText(this, "Repository atau token belum siap", Toast.LENGTH_SHORT).show()
            return
        }

        var displayName = "uploaded_file"
        var fileSize = -1L
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIdx != -1) displayName = cursor.getString(nameIdx) ?: displayName
                if (sizeIdx != -1) fileSize = cursor.getLong(sizeIdx)
            }
        }

        if (fileSize > 5_000_000L) {
            Toast.makeText(this, "Ukuran file maksimal 5 MB untuk upload langsung", Toast.LENGTH_LONG).show()
            return
        }

        val checkedDirs = allNodes.filter { it.isDirectory && it.isChecked }
        val defaultPath = if (checkedDirs.size == 1) {
            "${checkedDirs[0].path}/$displayName"
        } else {
            displayName
        }

        val etTargetPath = EditText(this).apply {
            setText(defaultPath)
            setSelection(defaultPath.length)
            isSingleLine = true
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("Upload ke GitHub ($activeBranch)")
            .setMessage("File: $displayName\nTentukan path tujuan di repository:")
            .setView(etTargetPath)
            .setPositiveButton("Upload Sekarang") { _, _ ->
                val targetPath = etTargetPath.text.toString().trim().trimStart('/')
                if (targetPath.isEmpty()) {
                    Toast.makeText(this, "Path tujuan tidak boleh kosong", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                binding.tvStudioStatus.text = "Mengunggah $targetPath..."
                lifecycleScope.launch {
                    try {
                        val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                ?: throw Exception("Gagal membaca file dari penyimpanan HP.")
                        }
                        scanner.upsertGithubFileBytes(
                            repoUrl = activeRepoUrl,
                            token = token,
                            filePath = targetPath,
                            bytes = bytes,
                            commitMessage = "Upload $targetPath via RantingPohonTools",
                            branch = activeBranch
                        )
                        Toast.makeText(this@GithubStudioActivity, "Berhasil upload: $targetPath", Toast.LENGTH_SHORT).show()
                        loadRepoTree(activeRepoUrl)
                    } catch (e: Exception) {
                        binding.tvStudioStatus.text = "Gagal upload: ${e.message}"
                        Toast.makeText(this@GithubStudioActivity, e.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Batal", null)

        if (scanner.isCodeFile(displayName)) {
            builder.setNeutralButton("Buka Editor") { _, _ ->
                val targetPath = etTargetPath.text.toString().trim().trimStart('/')
                if (targetPath.isNotEmpty()) {
                    val textContent = scanner.readLocalUriText(uri)
                    val intent = Intent(this, CodeEditorActivity::class.java).apply {
                        putExtra("repo_url", activeRepoUrl)
                        putExtra("token", token)
                        putExtra("file_path", targetPath)
                        putExtra("content", textContent)
                        putExtra("is_new_file", true)
                        putExtra("branch", activeBranch)
                    }
                    editorLauncher.launch(intent)
                }
            }
        }

        builder.show()
    }

    private fun getBackupBranchName(): String {
        val base = activeBranch.ifBlank { "main" }
        return "backup-$base"
    }

    private fun isAutoBackupEnabled(): Boolean {
        return getSharedPreferences("ranting_pohon_prefs", Context.MODE_PRIVATE)
            .getBoolean("studio_auto_backup", true)
    }

    private fun setAutoBackupEnabled(enabled: Boolean) {
        getSharedPreferences("ranting_pohon_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("studio_auto_backup", enabled)
            .apply()
    }

    private fun autoRefreshBackupOnGreenBuild(token: String, runId: Long) {
        if (!isAutoBackupEnabled()) return
        val prefs = getSharedPreferences("ranting_pohon_prefs", Context.MODE_PRIVATE)
        val syncKey = "studio_green_backup_${activeRepoUrl.hashCode()}_${activeBranch}"
        if (prefs.getLong(syncKey, -1L) == runId) return

        val checkedFiles = allNodes.filter { !it.isDirectory && it.isChecked }
        if (checkedFiles.isEmpty()) return

        val backupBranch = getBackupBranchName()
        lifecycleScope.launch {
            try {
                val count = scanner.saveSnapshotBackupBranch(
                    repoUrl = activeRepoUrl,
                    token = token,
                    sourceBranch = activeBranch,
                    backupBranch = backupBranch,
                    selectedFiles = checkedFiles
                )
                prefs.edit().putLong(syncKey, runId).apply()
                binding.tvStudioStatus.text = "Build ijo: backup '$backupBranch' otomatis diperbarui ($count file)."
            } catch (_: Exception) {
            }
        }
    }

    private fun openBackupMenuDialog(token: String) {
        val backupBranch = getBackupBranchName()
        val checkedFiles = allNodes.filter { !it.isDirectory && it.isChecked }
        binding.tvStudioStatus.text = "Memeriksa status & isi backup ($backupBranch)..."

        lifecycleScope.launch {
            val savedPaths = try {
                scanner.fetchBackupSnapshotPreview(activeRepoUrl, token, backupBranch)
            } catch (_: Exception) {
                emptyList()
            }

            val statusLabel = if (savedPaths.isNotEmpty()) "ADA (${savedPaths.size} file)" else "TIDAK ADA"
            val autoLabel = if (isAutoBackupEnabled()) "ON (Auto timpa saat build ijo)" else "OFF"
            val previewList = if (savedPaths.isEmpty()) {
                "• (Belum ada isi tersimpan di branch $backupBranch)"
            } else {
                savedPaths.joinToString("\n") { "• $it" }
            }
            val infoMessage = "Status Backup: $statusLabel\nBranch: $backupBranch\nAuto Backup: $autoLabel\n\nIsi di dalam backup:\n$previewList"

            binding.tvStudioStatus.text = "Backup $backupBranch: $statusLabel • Auto: ${if (isAutoBackupEnabled()) "ON" else "OFF"}"

            val options = arrayOf(
                "Simpan Backup Sekarang (${checkedFiles.size} file tercentang -> $backupBranch)",
                "Restore / Kembalikan File dari $backupBranch ke $activeBranch",
                if (isAutoBackupEnabled()) "Matikan Auto Backup (Saat Ini: ON)" else "Aktifkan Auto Backup (Saat Ini: OFF)",
                "Hapus Branch Backup ($backupBranch)",
                "Hapus Branch Bekas Backup (Tidak Terpakai)"
            )

            AlertDialog.Builder(this@GithubStudioActivity)
                .setTitle("Backup Snapshot ($activeBranch)")
                .setMessage(infoMessage)
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> {
                            if (checkedFiles.isEmpty()) {
                                Toast.makeText(this@GithubStudioActivity, "Centang minimal 1 file dulu di daftar", Toast.LENGTH_SHORT).show()
                                return@setItems
                            }
                            binding.tvStudioStatus.text = "Menyimpan snapshot ke $backupBranch..."
                            lifecycleScope.launch {
                                try {
                                    val count = scanner.saveSnapshotBackupBranch(
                                        repoUrl = activeRepoUrl,
                                        token = token,
                                        sourceBranch = activeBranch,
                                        backupBranch = backupBranch,
                                        selectedFiles = checkedFiles
                                    )
                                    binding.tvStudioStatus.text = "Backup tersimpan di $backupBranch ($count file)."
                                    Toast.makeText(this@GithubStudioActivity, "Backup $count file sukses!", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    binding.tvStudioStatus.text = "Gagal backup: ${e.message}"
                                    Toast.makeText(this@GithubStudioActivity, e.message, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        1 -> {
                            AlertDialog.Builder(this@GithubStudioActivity)
                                .setTitle("Restore dari $backupBranch?")
                                .setMessage("File-file di branch '$activeBranch' akan dikembalikan ke versi yang tersimpan di '$backupBranch'.")
                                .setPositiveButton("Restore Sekarang") { _, _ ->
                                    binding.tvStudioStatus.text = "Memulihkan file dari $backupBranch..."
                                    lifecycleScope.launch {
                                        try {
                                            val restoredCount = scanner.restoreSnapshotFromBackupBranch(
                                                repoUrl = activeRepoUrl,
                                                token = token,
                                                backupBranch = backupBranch,
                                                targetBranch = activeBranch
                                            )
                                            Toast.makeText(this@GithubStudioActivity, "Berhasil restore $restoredCount file!", Toast.LENGTH_SHORT).show()
                                            loadRepoTree(activeRepoUrl)
                                        } catch (e: Exception) {
                                            binding.tvStudioStatus.text = "Gagal restore: ${e.message}"
                                            Toast.makeText(this@GithubStudioActivity, e.message, Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                                .setNegativeButton("Batal", null)
                                .show()
                        }
                        2 -> {
                            val nextState = !isAutoBackupEnabled()
                            setAutoBackupEnabled(nextState)
                            val stateText = if (nextState) "ON" else "OFF"
                            binding.tvStudioStatus.text = "Auto Backup diubah jadi $stateText."
                            Toast.makeText(this@GithubStudioActivity, "Auto Backup: $stateText", Toast.LENGTH_SHORT).show()
                        }
                        3 -> {
                            binding.tvStudioStatus.text = "Menghapus $backupBranch..."
                            lifecycleScope.launch {
                                try {
                                    scanner.deleteGithubBranch(activeRepoUrl, token, backupBranch)
                                    binding.tvStudioStatus.text = "Branch $backupBranch telah dihapus."
                                    Toast.makeText(this@GithubStudioActivity, "Backup dihapus", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    binding.tvStudioStatus.text = "Gagal hapus backup: ${e.message}"
                                    Toast.makeText(this@GithubStudioActivity, e.message, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        4 -> {
                            showDeleteUnusedBackupBranchesDialog(token)
                        }
                    }
                }
                .setNegativeButton("Tutup", null)
                .show()
        }
    }

    private fun showDeleteUnusedBackupBranchesDialog(token: String) {
        binding.tvStudioStatus.text = "Mengecek branch bekas backup..."
        lifecycleScope.launch {
            try {
                val unusedBackups = scanner.fetchUnusedBackupBranches(activeRepoUrl, token, activeBranch)
                if (unusedBackups.isEmpty()) {
                    binding.tvStudioStatus.text = "Tidak ada branch bekas backup yang menganggur."
                    Toast.makeText(this@GithubStudioActivity, "Tidak ada branch bekas backup", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                binding.tvStudioStatus.text = "Pilih branch bekas backup untuk dihapus."
                AlertDialog.Builder(this@GithubStudioActivity)
                    .setTitle("Hapus Branch Bekas Backup")
                    .setItems(unusedBackups.toTypedArray()) { _, which ->
                        val targetBranch = unusedBackups[which]
                        AlertDialog.Builder(this@GithubStudioActivity)
                            .setTitle("Hapus Branch?")
                            .setMessage("Yakin ingin menghapus branch bekas backup:\n$targetBranch")
                            .setPositiveButton("Hapus") { _, _ ->
                                binding.tvStudioStatus.text = "Menghapus $targetBranch..."
                                lifecycleScope.launch {
                                    try {
                                        scanner.deleteGithubBranch(activeRepoUrl, token, targetBranch)
                                        binding.tvStudioStatus.text = "Branch bekas $targetBranch dihapus."
                                        Toast.makeText(this@GithubStudioActivity, "Dihapus: $targetBranch", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        binding.tvStudioStatus.text = "Gagal hapus: ${e.message}"
                                        Toast.makeText(this@GithubStudioActivity, e.message, Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                            .setNegativeButton("Batal", null)
                            .show()
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            } catch (e: Exception) {
                binding.tvStudioStatus.text = "Gagal cek branch backup: ${e.message}"
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
