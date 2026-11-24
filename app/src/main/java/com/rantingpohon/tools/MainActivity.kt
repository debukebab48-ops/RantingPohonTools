package com.rantingpohon.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.graphics.Typeface
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.rantingpohon.tools.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var scanner: RepoScanner
    private lateinit var adapter: TreeAdapter

    private var allNodes: List<TreeNode> = emptyList()
    private var rootLabel: String = "Project"
    private var currentMode: Int = 0 // 0 = GitHub, 1 = Local, 2 = Unpack
    private var pendingExportText: String = ""

    private val numberFormat = NumberFormat.getNumberInstance(Locale("id", "ID"))

    private val openFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            binding.tvLocalPath.text = uri.lastPathSegment ?: uri.toString()
            scanLocalFolder(uri)
        }
    }

    private val openTxtForUnpackLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            binding.tvStatus.text = "Membaca file .txt..."
            lifecycleScope.launch {
                try {
                    val rawText = withContext(Dispatchers.IO) {
                        scanner.readLocalUriText(uri)
                    }
                    binding.tvUnpackInfo.text = "Sumber: ${uri.lastPathSegment ?: "File .txt"}"
                    loadBundleForUnpack(rawText)
                } catch (e: Exception) {
                    binding.tvStatus.text = "Gagal membaca file: ${e.message}"
                }
            }
        }
    }

    private val extractDestinationLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val checkedFiles = allNodes.filter { !it.isDirectory && it.isChecked }
            if (checkedFiles.isEmpty()) {
                Toast.makeText(this, "Tidak ada file yang dicentang", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            lifecycleScope.launch {
                try {
                    val count = BundleUnpacker.extractNodesToUri(
                        this@MainActivity, uri, checkedFiles
                    ) { current, total, fileName ->
                        binding.tvStatus.text = "Mengekstrak ($current/$total): $fileName"
                    }
                    binding.tvStatus.text = "Selesai! $count file berhasil diekstrak ke folder tujuan."
                    Toast.makeText(this@MainActivity, "Berhasil extract $count file!", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    binding.tvStatus.text = "Error extract: ${e.message}"
                    Toast.makeText(this@MainActivity, "Gagal: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val createTxtLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri != null && pendingExportText.isNotEmpty()) {
            try {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                    it.write(pendingExportText)
                }
                Toast.makeText(this, "Berhasil disimpan ke .txt", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Gagal simpan: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashHandler.install(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        CrashHandler.showPendingCrashDialog(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootContainer) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        scanner = RepoScanner(this)

        adapter = TreeAdapter(
            onToggleExpand = { dirNode ->
                dirNode.isExpanded = !dirNode.isExpanded
                refreshVisibleList()
            },
            onToggleCheck = { node, checked ->
                applyCheckRecursive(node, checked)
                refreshVisibleList()
            },
            onFileClick = { fileNode ->
                openFileEditorDialog(fileNode)
            }
        )

        binding.rvTree.layoutManager = LinearLayoutManager(this)
        binding.rvTree.adapter = adapter

        val prefs = getSharedPreferences("ranting_pohon_prefs", Context.MODE_PRIVATE)
        binding.etGithubToken.setText(prefs.getString("github_token", ""))

        setupListeners()
    }

    private fun setupListeners() {
        binding.btnResetAll.setOnClickListener { resetAllState() }

        binding.btnOpenGithubStudio.setOnClickListener {
            startActivity(Intent(this, GithubStudioActivity::class.java))
        }

        binding.btnTabGithub.setOnClickListener { switchMode(0) }
        binding.btnTabLocal.setOnClickListener { switchMode(1) }
        binding.btnTabUnpack.setOnClickListener { switchMode(2) }

        binding.btnUnpackPaste.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipText = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            if (clipText.isNullOrBlank()) {
                Toast.makeText(this, "Clipboard kosong", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.tvUnpackInfo.text = "Sumber: Clipboard (${numberFormat.format(clipText.length)} karakter)"
            loadBundleForUnpack(clipText)
        }

        binding.btnUnpackPickTxt.setOnClickListener {
            openTxtForUnpackLauncher.launch(arrayOf("text/plain", "*/*"))
        }

        binding.btnExtractToFolder.setOnClickListener {
            val checkedFiles = allNodes.filter { !it.isDirectory && it.isChecked }
            if (checkedFiles.isEmpty()) {
                Toast.makeText(this, "Muat bundel & centang minimal 1 file dulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            extractDestinationLauncher.launch(null)
        }

        binding.etGithubToken.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                getSharedPreferences("ranting_pohon_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("github_token", s?.toString()?.trim() ?: "")
                    .apply()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnSelectMyRepo.setOnClickListener {
            val token = binding.etGithubToken.text.toString().trim()
            if (token.isEmpty()) {
                Toast.makeText(this, "Isi GitHub Token dulu untuk memuat daftar repo", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showMyReposDialog(token)
        }

        binding.btnPushUnpackToGithub.setOnClickListener {
            val token = getSharedPreferences("ranting_pohon_prefs", Context.MODE_PRIVATE)
                .getString("github_token", "")?.trim() ?: ""
            val checkedFiles = allNodes.filter { !it.isDirectory && it.isChecked }
            if (token.isEmpty()) {
                Toast.makeText(this, "Isi GitHub Token dulu di Repo Studio / tab GitHub", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (checkedFiles.isEmpty()) {
                Toast.makeText(this, "Centang minimal 1 file hasil unpack dulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pickTargetRepoAndPushUnpack(token, checkedFiles)
        }

        binding.btnScanGithub.setOnClickListener {
            val url = binding.etRepoUrl.text.toString()
            val token = binding.etGithubToken.text.toString()
            if (url.isBlank()) {
                Toast.makeText(this, "Masukkan URL GitHub dulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            scanGithub(url, token)
        }

        binding.btnPickFolder.setOnClickListener {
            openFolderLauncher.launch(null)
        }

        binding.cbAutoIgnore.setOnCheckedChangeListener { _, _ ->
            applyFilterChecks()
            refreshVisibleList()
        }

        binding.cbCodeOnly.setOnCheckedChangeListener { _, _ ->
            applyFilterChecks()
            refreshVisibleList()
        }

        binding.btnSelectAll.setOnClickListener {
            allNodes.forEach { it.isChecked = true }
            refreshVisibleList()
        }

        binding.btnDeselectAll.setOnClickListener {
            allNodes.forEach { it.isChecked = false }
            refreshVisibleList()
        }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshVisibleList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnCopyTree.setOnClickListener {
            val checked = allNodes.filter { it.isChecked }
            if (checked.isEmpty()) {
                Toast.makeText(this, "Pilih minimal 1 file/folder", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val treeText = TreeBuilder.generateAsciiTree(rootLabel, checked)
            copyToClipboard(treeText)
        }

        binding.btnCopyFull.setOnClickListener {
            buildFullOutput { fullText ->
                if (fullText.length > 450_000) {
                    Toast.makeText(
                        this,
                        "Teks terlalu panjang (${numberFormat.format(fullText.length)} karakter) untuk clipboard. Gunakan tombol Export .txt.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    copyToClipboard(fullText)
                }
            }
        }

        binding.btnExportTxt.setOnClickListener {
            buildFullOutput { fullText ->
                pendingExportText = fullText
                createTxtLauncher.launch("${rootLabel}_tree.txt")
            }
        }
    }

    private fun resetAllState() {
        allNodes = emptyList()
        rootLabel = "Project"
        pendingExportText = ""

        binding.etRepoUrl.text?.clear()
        binding.etSearch.text?.clear()
        binding.tvLocalPath.text = "Belum ada folder dipilih"
        binding.tvUnpackInfo.text = "Muat teks hasil Copy Tree+Code atau file .txt untuk dibongkar."
        binding.tvStatus.text = "Bersih! Siap memindai repository atau folder lokal."

        refreshVisibleList()
        Toast.makeText(this, "Semua bekas berhasil dibersihkan", Toast.LENGTH_SHORT).show()
    }

    private fun switchMode(mode: Int) {
        currentMode = mode
        binding.panelGithub.visibility = if (mode == 0) View.VISIBLE else View.GONE
        binding.panelLocal.visibility = if (mode == 1) View.VISIBLE else View.GONE
        binding.panelUnpack.visibility = if (mode == 2) View.VISIBLE else View.GONE

        binding.bottomBarPack.visibility = if (mode == 2) View.GONE else View.VISIBLE
        binding.bottomBarUnpack.visibility = if (mode == 2) View.VISIBLE else View.GONE

        binding.btnTabGithub.backgroundTintList = ColorStateList.valueOf(
            Color.parseColor(if (mode == 0) "#238636" else "#21262D")
        )
        binding.btnTabGithub.setTextColor(Color.parseColor(if (mode == 0) "#FFFFFF" else "#8B949E"))

        binding.btnTabLocal.backgroundTintList = ColorStateList.valueOf(
            Color.parseColor(if (mode == 1) "#1F6FEB" else "#21262D")
        )
        binding.btnTabLocal.setTextColor(Color.parseColor(if (mode == 1) "#FFFFFF" else "#8B949E"))

        binding.btnTabUnpack.backgroundTintList = ColorStateList.valueOf(
            Color.parseColor(if (mode == 2) "#8957E5" else "#21262D")
        )
        binding.btnTabUnpack.setTextColor(Color.parseColor(if (mode == 2) "#FFFFFF" else "#8B949E"))
    }

    private fun loadBundleForUnpack(rawText: String) {
        val parsed = BundleUnpacker.parseBundleToNodes(rawText)
        if (parsed.isEmpty()) {
            binding.tvStatus.text = "Format bundel tidak dikenali (pastikan ada blok FILE: ...)."
            Toast.makeText(this, "Tidak ditemukan blok FILE: dalam teks", Toast.LENGTH_SHORT).show()
            return
        }
        allNodes = parsed
        refreshVisibleList()
        val fileCount = parsed.count { !it.isDirectory }
        binding.tvStatus.text = "Siap bongkar: $fileCount file ditemukan. Silakan pilih folder tujuan."
    }

    private fun showMyReposDialog(token: String) {
        binding.tvStatus.text = "Mengambil daftar repository GitHub kamu..."
        lifecycleScope.launch {
            try {
                val repos = scanner.fetchUserRepos(token)
                if (repos.isEmpty()) {
                    binding.tvStatus.text = "Tidak ditemukan repository pada akun ini."
                    return@launch
                }
                binding.tvStatus.text = "Ditemukan ${repos.size} repository."
                val labels = repos.map { repo ->
                    val badge = if (repo.isPrivate) "[Privat]" else "[Publik]"
                    "$badge ${repo.fullName}"
                }.toTypedArray()

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Pilih Repository (${repos.size})")
                    .setItems(labels) { _, which ->
                        val selected = repos[which]
                        binding.etRepoUrl.setText(selected.htmlUrl)
                        scanGithub(selected.htmlUrl, token)
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            } catch (e: Exception) {
                binding.tvStatus.text = "Error: ${e.message}"
                Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openFileEditorDialog(node: TreeNode) {
        val repoUrl = binding.etRepoUrl.text.toString().trim()
        val token = binding.etGithubToken.text.toString().trim()

        binding.tvStatus.text = "Membuka ${node.name}..."
        lifecycleScope.launch {
            val content = when {
                node.cachedContent != null -> node.cachedContent!!
                currentMode == 0 -> scanner.fetchGithubFileContent(node, token)
                node.localUriString != null -> withContext(Dispatchers.IO) {
                    scanner.readLocalUriText(Uri.parse(node.localUriString))
                }
                else -> ""
            }
            binding.tvStatus.text = "Siap memindai repository atau folder lokal."

            val intent = Intent(this@MainActivity, CodeEditorActivity::class.java).apply {
                putExtra("repo_url", repoUrl)
                putExtra("token", token)
                putExtra("file_path", node.path)
                putExtra("content", content)
                putExtra("is_new_file", currentMode != 0)
            }
            startActivity(intent)
        }
    }

    private fun pickTargetRepoAndPushUnpack(token: String, files: List<TreeNode>) {
        binding.tvStatus.text = "Mengambil daftar repo tujuan..."
        lifecycleScope.launch {
            try {
                val repos = scanner.fetchUserRepos(token)
                if (repos.isEmpty()) {
                    binding.tvStatus.text = "Tidak ada repository ditemukan."
                    return@launch
                }
                val labels = repos.map {
                    "${if (it.isPrivate) "[Privat]" else "[Publik]"} ${it.fullName}"
                }.toTypedArray()

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Pilih Repo Tujuan Push (${files.size} file)")
                    .setItems(labels) { _, which ->
                        val targetUrl = repos[which].htmlUrl
                        pushUnpackedFilesToGithub(targetUrl, token, files)
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            } catch (e: Exception) {
                binding.tvStatus.text = "Error: ${e.message}"
                Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun pushUnpackedFilesToGithub(repoUrl: String, token: String, files: List<TreeNode>) {
        lifecycleScope.launch {
            try {
                for ((index, file) in files.withIndex()) {
                    binding.tvStatus.text = "Push (${index + 1}/${files.size}): ${file.path}"
                    scanner.upsertGithubFile(
                        repoUrl = repoUrl,
                        token = token,
                        filePath = file.path,
                        content = file.cachedContent ?: "",
                        commitMessage = "Update ${file.path} via RantingPohonTools"
                    )
                }
                binding.tvStatus.text = "Selesai! ${files.size} file berhasil di-push ke GitHub."
                Toast.makeText(this@MainActivity, "Berhasil push ${files.size} file!", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                binding.tvStatus.text = "Error saat push: ${e.message}"
                Toast.makeText(this@MainActivity, "Gagal: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun scanGithub(url: String, token: String) {
        binding.tvStatus.text = "Memindai repository GitHub..."
        lifecycleScope.launch {
            try {
                rootLabel = url.trim().removeSuffix("/").removeSuffix(".git").substringAfterLast('/')
                allNodes = scanner.scanGithubRepo(url, token)
                applyFilterChecks()
                refreshVisibleList()
                binding.tvStatus.text = "Selesai: ${allNodes.size} item ditemukan."
            } catch (e: Exception) {
                binding.tvStatus.text = "Error: ${e.message}"
            }
        }
    }

    private fun scanLocalFolder(uri: Uri) {
        binding.tvStatus.text = "Memindai folder storage..."
        lifecycleScope.launch {
            try {
                rootLabel = (uri.lastPathSegment ?: "LocalFolder").substringAfterLast(':').substringAfterLast('/')
                allNodes = scanner.scanLocalTree(uri)
                applyFilterChecks()
                refreshVisibleList()
                binding.tvStatus.text = "Selesai: ${allNodes.size} item lokal ditemukan."
            } catch (e: Exception) {
                binding.tvStatus.text = "Error: ${e.message}"
            }
        }
    }

    private fun applyFilterChecks() {
        val autoIgnore = binding.cbAutoIgnore.isChecked
        val codeOnly = binding.cbCodeOnly.isChecked

        for (node in allNodes) {
            val ignored = autoIgnore && scanner.shouldAutoIgnore(node.path)
            val nonCode = !node.isDirectory && codeOnly && !scanner.isCodeFile(node.name)
            node.isChecked = !ignored && !nonCode
        }
    }

    private fun applyCheckRecursive(target: TreeNode, checked: Boolean) {
        target.isChecked = checked
        if (target.isDirectory) {
            val prefix = "${target.path}/"
            allNodes.filter { it.path.startsWith(prefix) }.forEach { it.isChecked = checked }
        }
    }

    private fun refreshVisibleList() {
        val query = binding.etSearch.text.toString().trim().lowercase()
        val collapsedPrefixes = allNodes
            .filter { it.isDirectory && !it.isExpanded }
            .map { "${it.path}/" }

        val visible = allNodes.filter { node ->
            val hiddenByCollapse = collapsedPrefixes.any { prefix -> node.path.startsWith(prefix) }
            if (hiddenByCollapse) return@filter false
            if (query.isNotEmpty()) {
                node.path.lowercase().contains(query)
            } else {
                true
            }
        }

        adapter.submitList(visible)
        updateTotalCharsBadge()
    }

    private fun updateTotalCharsBadge() {
        val totalChars = allNodes.filter { !it.isDirectory && it.isChecked }.sumOf { it.charCount }
        binding.tvTotalChars.text = "${numberFormat.format(totalChars)} karakter"
    }

    private fun buildFullOutput(onReady: (String) -> Unit) {
        val checkedFiles = allNodes.filter { !it.isDirectory && it.isChecked }
        val checkedAll = allNodes.filter { it.isChecked }
        if (checkedAll.isEmpty()) {
            Toast.makeText(this, "Pilih minimal 1 file/folder", Toast.LENGTH_SHORT).show()
            return
        }

        binding.tvStatus.text = "Mengambil isi ${checkedFiles.size} file..."
        val token = binding.etGithubToken.text.toString()

        lifecycleScope.launch {
            val treeHeader = TreeBuilder.generateAsciiTree(rootLabel, checkedAll)
            val sb = StringBuilder()
            sb.append(treeHeader).append("\n\n")

            for (fileNode in checkedFiles) {
                val content = if (currentMode == 0) {
                    scanner.fetchGithubFileContent(fileNode, token)
                } else {
                    fileNode.cachedContent ?: withContext(Dispatchers.IO) {
                        val txt = scanner.readLocalUriText(Uri.parse(fileNode.localUriString))
                        fileNode.cachedContent = txt
                        fileNode.charCount = txt.length.toLong()
                        txt
                    }
                }
                sb.append("========================================\n")
                sb.append("FILE: ${fileNode.path}\n")
                sb.append("========================================\n")
                sb.append(content).append("\n\n")
            }

            scanner.recalculateFolderChars(allNodes)
            refreshVisibleList()

            val result = sb.toString()
            binding.tvStatus.text = "Siap (${numberFormat.format(result.length)} karakter total)."
            onReady(result)
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("RantingPohonTools", text))
        Toast.makeText(
            this,
            "Tersalin (${numberFormat.format(text.length)} karakter)",
            Toast.LENGTH_SHORT
        ).show()
    }
}
