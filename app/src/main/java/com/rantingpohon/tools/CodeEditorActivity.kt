package com.rantingpohon.tools

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.rantingpohon.tools.databinding.ActivityCodeEditorBinding
import kotlinx.coroutines.launch

class CodeEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCodeEditorBinding
    private lateinit var scanner: RepoScanner

    private var repoUrl: String = ""
    private var token: String = ""
    private var filePath: String = ""
    private var isNewFile: Boolean = false

    private var isHighlighting = false
    private var highlightedMatchStart = -1
    private var highlightedMatchEnd = -1
    private var isEditorLocked = false
    private var savedKeyListener: android.text.method.KeyListener? = null

    private val keywordPattern = Regex(
        "\\b(package|import|class|interface|object|fun|val|var|private|public|protected|internal|override|open|abstract|sealed|data|enum|suspend|return|if|else|when|for|while|do|try|catch|finally|throw|true|false|null|this|super|in|is|as|by|init|companion|void|int|boolean|String|List|Map|Set)\\b"
    )
    private val stringPattern = Regex("\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*'")
    private val commentPattern = Regex("//.*|/\\*[\\s\\S]*?\\*/|<!--([\\s\\S]*?)-->")
    private val numberPattern = Regex("\\b\\d+(\\.\\d+)?[fFlL]?\\b")
    private val xmlTagPattern = Regex("</?[a-zA-Z0-9_.:-]+|/?>")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCodeEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.editorRoot) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomPadding = maxOf(systemBars.bottom, ime.bottom)
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, bottomPadding)
            insets
        }

        scanner = RepoScanner(this)
        repoUrl = intent.getStringExtra("repo_url") ?: ""
        token = intent.getStringExtra("token") ?: ""
        filePath = intent.getStringExtra("file_path") ?: ""
        isNewFile = intent.getBooleanExtra("is_new_file", false)
        val initialContent = intent.getStringExtra("content") ?: ""

        binding.tvEditorFileName.text = filePath.substringAfterLast('/')
        binding.tvEditorInfo.text = filePath
        binding.btnDeleteThisFile.visibility = if (isNewFile) View.GONE else View.VISIBLE

        val btnTestBuild = Button(this).apply {
            text = "Tes Build"
            textAllCaps = false
            setTextColor(Color.parseColor("#FFFFFF"))
            setBackgroundColor(Color.parseColor("#1F6FEB"))
            textSize = 11f
            minimumWidth = 0
            minHeight = 0
            setPadding(16, 6, 16, 6)
            setOnClickListener {
                if (repoUrl.isEmpty() || token.isEmpty()) {
                    Toast.makeText(this@CodeEditorActivity, "Repo atau token belum tersedia", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val activeBranchExtra = intent.getStringExtra("branch")
                binding.tvEditorInfo.text = "Memicu Tes Build di GitHub Actions..."
                lifecycleScope.launch {
                    try {
                        val msg = scanner.triggerWorkflowDispatch(repoUrl, token, activeBranchExtra)
                        binding.tvEditorInfo.text = "$msg • Cek status build & log error..."
                        kotlinx.coroutines.delay(2500L)
                        val runs = scanner.fetchWorkflowRuns(repoUrl, token, activeBranchExtra)
                        val latest = runs.firstOrNull()
                        if (latest == null) {
                            binding.tvEditorInfo.text = "$msg (menunggu run muncul)"
                        } else if (latest.status == "completed" && latest.conclusion == "failure") {
                            val logSnippet = scanner.fetchRunFailureLog(repoUrl, token, latest.id)
                            AlertDialog.Builder(this@CodeEditorActivity)
                                .setTitle("Build Gagal (#${latest.id})")
                                .setMessage(logSnippet)
                                .setPositiveButton("OK", null)
                                .show()
                        } else {
                            binding.tvEditorInfo.text = "Run #${latest.id}: ${latest.status} (${latest.conclusion.ifEmpty { "-" }})"
                        }
                    } catch (e: Exception) {
                        binding.tvEditorInfo.text = "Tes Build gagal: ${e.message}"
                        Toast.makeText(this@CodeEditorActivity, e.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        (binding.btnDeleteThisFile.parent as? android.view.ViewGroup)?.addView(btnTestBuild)

        setupSymbolBar()
        setupListeners()

        binding.etCodeEditor.setText(initialContent)
        applySyntaxHighlighting(binding.etCodeEditor.text)
        updateLineNumbers()
    }

    private fun setupSymbolBar() {
        val symbols = listOf("{", "}", "(", ")", "[", "]", "=", ".", ",", ";", "\"", "/", "<", ">", ":")
        for (sym in symbols) {
            val btn = Button(this).apply {
                text = sym
                setTextColor(Color.parseColor("#E6EDF3"))
                setBackgroundColor(Color.TRANSPARENT)
                textSize = 13f
                minimumWidth = 0
                minWidth = 96
                setPadding(12, 0, 12, 0)
                setOnClickListener {
                    val start = binding.etCodeEditor.selectionStart.coerceAtLeast(0)
                    val end = binding.etCodeEditor.selectionEnd.coerceAtLeast(0)
                    binding.etCodeEditor.text.replace(minOf(start, end), maxOf(start, end), sym)
                }
            }
            binding.symbolBar.addView(btn)
        }
    }

    private fun setupListeners() {
        binding.btnEditorBack.setOnClickListener { finish() }

        binding.btnToggleFindPanel.setOnClickListener {
            binding.panelFindReplace.visibility =
                if (binding.panelFindReplace.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        binding.etCodeEditor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isHighlighting || s == null) return
                highlightedMatchStart = -1
                highlightedMatchEnd = -1
                updateLineNumbers()
                applySyntaxHighlighting(s)
                updateMatchCount()
                refreshSyntaxCheck()
            }
        })

        binding.etFindText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                highlightedMatchStart = -1
                highlightedMatchEnd = -1
                updateMatchCount()
                applySyntaxHighlighting(binding.etCodeEditor.text)
            }
        })

        val btnLockEditor = Button(this).apply {
            text = "Kunci: OFF"
            textAllCaps = false
            setTextColor(Color.parseColor("#E6EDF3"))
            setBackgroundColor(Color.parseColor("#21262D"))
            textSize = 11f
            minimumWidth = 0
            minHeight = 0
            setPadding(16, 6, 16, 6)
            setOnClickListener {
                isEditorLocked = !isEditorLocked
                if (isEditorLocked) {
                    if (savedKeyListener == null) {
                        savedKeyListener = binding.etCodeEditor.keyListener
                    }
                    binding.etCodeEditor.keyListener = null
                    binding.etCodeEditor.isCursorVisible = false
                    text = "Kunci: ON"
                    setTextColor(Color.parseColor("#3FB950"))
                    Toast.makeText(this@CodeEditorActivity, "Area kode dikunci (aman dari kesenggol)", Toast.LENGTH_SHORT).show()
                } else {
                    binding.etCodeEditor.keyListener = savedKeyListener
                    binding.etCodeEditor.isCursorVisible = true
                    text = "Kunci: OFF"
                    setTextColor(Color.parseColor("#E6EDF3"))
                    Toast.makeText(this@CodeEditorActivity, "Area kode dibuka kembali", Toast.LENGTH_SHORT).show()
                }
            }
        }
        (binding.tvMatchCounter.parent as? android.view.ViewGroup)?.addView(btnLockEditor)

        val tvSyntaxCheck = TextView(this).apply {
            text = "Cek: true"
            textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#3FB950"))
            setPadding(12, 4, 16, 4)
        }

        var lastIssues = emptyList<String>()

        fun refreshSyntaxCheck() {
            val raw = binding.etCodeEditor.text.toString()
            val issues = mutableListOf<String>()

            data class OpenToken(val ch: Char, val line: Int)
            val stack = ArrayDeque<OpenToken>()

            var lineNum = 1
            var inDoubleQuote = false
            var quoteStartLine = 1
            var escaped = false

            for (ch in raw) {
                if (escaped) {
                    escaped = false
                    if (ch == '\n') lineNum++
                    continue
                }
                if (ch == '\\') {
                    escaped = true
                    continue
                }
                if (ch == '"') {
                    if (!inDoubleQuote) {
                        inDoubleQuote = true
                        quoteStartLine = lineNum
                    } else {
                        inDoubleQuote = false
                    }
                    continue
                }
                if (!inDoubleQuote) {
                    when (ch) {
                        '{', '(', '[' -> stack.addLast(OpenToken(ch, lineNum))
                        '}' -> {
                            val top = stack.lastOrNull()
                            if (top != null && top.ch == '{') {
                                stack.removeLast()
                            } else {
                                issues.add("Baris $lineNum: '}' berlebih / tidak ada pasangan '{'")
                            }
                        }
                        ')' -> {
                            val top = stack.lastOrNull()
                            if (top != null && top.ch == '(') {
                                stack.removeLast()
                            } else {
                                issues.add("Baris $lineNum: ')' berlebih / tidak ada pasangan '('")
                            }
                        }
                        ']' -> {
                            val top = stack.lastOrNull()
                            if (top != null && top.ch == '[') {
                                stack.removeLast()
                            } else {
                                issues.add("Baris $lineNum: ']' berlebih / tidak ada pasangan '['")
                            }
                        }
                    }
                }
                if (ch == '\n') {
                    lineNum++
                }
            }

            if (inDoubleQuote) {
                issues.add("Baris $quoteStartLine: Tanda kutip '\"' belum ditutup")
            }
            while (stack.isNotEmpty()) {
                val rem = stack.removeLast()
                val pair = when (rem.ch) {
                    '{' -> '}'
                    '(' -> ')'
                    '[' -> ']'
                    else -> '?'
                }
                issues.add("Baris ${rem.line}: '${rem.ch}' belum ditutup dengan '$pair'")
            }

            lastIssues = issues
            val isBalanced = issues.isEmpty()
            tvSyntaxCheck.text = if (isBalanced) "true" else "false"
            tvSyntaxCheck.setTextColor(
                Color.parseColor(if (isBalanced) "#3FB950" else "#F85149")
            )
        }

        tvSyntaxCheck.setOnClickListener {
            if (lastIssues.isEmpty()) {
                Toast.makeText(this, "Cek lokal: true (tidak ada issue baris terdeteksi)", Toast.LENGTH_SHORT).show()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Detail Baris Bermasalah (${lastIssues.size})")
                    .setMessage(lastIssues.joinToString("\n"))
                    .setPositiveButton("OK", null)
                    .show()
            }
        }

        (binding.btnDeleteThisFile.parent as? android.view.ViewGroup)?.let { parent ->
            val idx = parent.indexOfChild(binding.btnDeleteThisFile)
            if (idx >= 0) {
                parent.addView(tvSyntaxCheck, idx)
            } else {
                parent.addView(tvSyntaxCheck)
            }
        }
        refreshSyntaxCheck()

        binding.btnFindNext.setOnClickListener {
            findNextMatch(forward = true)
        }

        binding.btnFindPrev.setOnClickListener {
            findNextMatch(forward = false)
        }

        // LOGIKA 2-LANGKAH:
        // Tekan ke-1 -> Cari & sorot (highlight) teksnya dulu tanpa mengubah kode.
        // Tekan ke-2 -> Jika sudah tersorot, ganti teks tersebut & lompat ke kecocokan berikutnya.
        binding.btnReplaceStep.setOnClickListener {
            val query = binding.etFindText.text.toString()
            if (query.isEmpty()) {
                Toast.makeText(this, "Isi teks yang ingin dicari di kolom Menemukan", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val editable = binding.etCodeEditor.text
            val isCurrentlyHighlighted =
                highlightedMatchStart >= 0 &&
                highlightedMatchEnd <= editable.length &&
                editable.substring(highlightedMatchStart, highlightedMatchEnd).equals(query, ignoreCase = true)

            if (!isCurrentlyHighlighted) {
                // Langkah 1: Tunjukkan & sorot dulu target yang akan diganti
                val found = findNextMatch(forward = true)
                if (found) {
                    Toast.makeText(this, "Target disorot. Tekan MENGGANTI lagi untuk eksekusi.", Toast.LENGTH_SHORT).show()
                }
            } else {
                // Langkah 2: Eksekusi penggantian pada teks yang sedang disorot
                val replacement = binding.etReplaceText.text.toString()
                val oldStart = highlightedMatchStart
                editable.replace(highlightedMatchStart, highlightedMatchEnd, replacement)
                val newCursor = oldStart + replacement.length
                binding.etCodeEditor.setSelection(newCursor)
                highlightedMatchStart = -1
                highlightedMatchEnd = -1
                findNextMatch(forward = true)
            }
        }

        binding.btnReplaceAll.setOnClickListener {
            val query = binding.etFindText.text.toString()
            if (query.isEmpty()) return@setOnClickListener
            val replacement = binding.etReplaceText.text.toString()
            val fullText = binding.etCodeEditor.text.toString()
            val updated = fullText.replace(query, replacement, ignoreCase = true)
            if (updated == fullText) {
                Toast.makeText(this, "Tidak ada teks yang cocok", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.etCodeEditor.setText(updated)
            highlightedMatchStart = -1
            highlightedMatchEnd = -1
            Toast.makeText(this, "Semua kecocokan telah diganti", Toast.LENGTH_SHORT).show()
        }

        binding.btnEditorCommit.setOnClickListener {
            showCommitDialog()
        }

        binding.btnDeleteThisFile.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Hapus File dari GitHub?")
                .setMessage("Yakin ingin menghapus permanen:\n$filePath")
                .setPositiveButton("Hapus") { _, _ ->
                    binding.tvEditorInfo.text = "Menghapus file..."
                    lifecycleScope.launch {
                        try {
                            scanner.deleteGithubFile(repoUrl, token, filePath, "Delete $filePath")
                            Toast.makeText(this@CodeEditorActivity, "File dihapus!", Toast.LENGTH_SHORT).show()
                            setResult(Activity.RESULT_OK, Intent().putExtra("deleted", true))
                            finish()
                        } catch (e: Exception) {
                            binding.tvEditorInfo.text = "Gagal hapus: ${e.message}"
                            Toast.makeText(this@CodeEditorActivity, e.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .setNegativeButton("Batal", null)
                .show()
        }
    }

    private fun findNextMatch(forward: Boolean): Boolean {
        val query = binding.etFindText.text.toString()
        val text = binding.etCodeEditor.text.toString()
        if (query.isEmpty() || text.isEmpty()) {
            Toast.makeText(this, "Hasil: 0", Toast.LENGTH_SHORT).show()
            return false
        }

        val foundIdx = if (forward) {
            val startPos = if (highlightedMatchEnd >= 0) highlightedMatchEnd else binding.etCodeEditor.selectionEnd.coerceAtLeast(0)
            var idx = text.indexOf(query, startPos, ignoreCase = true)
            if (idx == -1 && startPos > 0) {
                idx = text.indexOf(query, 0, ignoreCase = true)
            }
            idx
        } else {
            val startPos = if (highlightedMatchStart > 0) highlightedMatchStart - 1 else text.length - 1
            var idx = text.lastIndexOf(query, startPos, ignoreCase = true)
            if (idx == -1) {
                idx = text.lastIndexOf(query, text.length - 1, ignoreCase = true)
            }
            idx
        }

        if (foundIdx == -1) {
            highlightedMatchStart = -1
            highlightedMatchEnd = -1
            Toast.makeText(this, "Tidak ditemukan: \"$query\"", Toast.LENGTH_SHORT).show()
            return false
        }

        highlightedMatchStart = foundIdx
        highlightedMatchEnd = foundIdx + query.length
        binding.etCodeEditor.requestFocus()
        binding.etCodeEditor.setSelection(highlightedMatchStart, highlightedMatchEnd)
        applySyntaxHighlighting(binding.etCodeEditor.text)

        val line = binding.etCodeEditor.layout?.getLineForOffset(foundIdx) ?: 0
        val y = binding.etCodeEditor.layout?.getLineTop(line) ?: 0
        binding.svCodeEditor.smoothScrollTo(0, (y - 120).coerceAtLeast(0))
        return true
    }

    private fun updateMatchCount() {
        val query = binding.etFindText.text.toString()
        val text = binding.etCodeEditor.text.toString()
        if (query.isEmpty()) {
            binding.tvMatchCounter.text = "Hasil: 0"
            return
        }
        var count = 0
        var idx = text.indexOf(query, 0, ignoreCase = true)
        while (idx != -1) {
            count++
            idx = text.indexOf(query, idx + query.length, ignoreCase = true)
        }
        binding.tvMatchCounter.text = "Hasil: $count"
    }

    private fun updateLineNumbers() {
        val lineCount = binding.etCodeEditor.lineCount.coerceAtLeast(1)
        val sb = StringBuilder()
        for (i in 1..lineCount) {
            sb.append(i)
            if (i < lineCount) sb.append('\n')
        }
        binding.tvLineNumbers.text = sb.toString()
    }

    private fun applySyntaxHighlighting(editable: Editable) {
        isHighlighting = true
        try {
            val oldFg = editable.getSpans(0, editable.length, ForegroundColorSpan::class.java)
            for (span in oldFg) editable.removeSpan(span)

            val oldBg = editable.getSpans(0, editable.length, BackgroundColorSpan::class.java)
            for (span in oldBg) editable.removeSpan(span)

            val text = editable.toString()
            if (text.length <= 120_000) {
                for (m in xmlTagPattern.findAll(text)) {
                    editable.setSpan(ForegroundColorSpan(Color.parseColor("#569CD6")), m.range.first, m.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                for (m in keywordPattern.findAll(text)) {
                    editable.setSpan(ForegroundColorSpan(Color.parseColor("#C586C0")), m.range.first, m.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                for (m in numberPattern.findAll(text)) {
                    editable.setSpan(ForegroundColorSpan(Color.parseColor("#B5CEA8")), m.range.first, m.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                for (m in stringPattern.findAll(text)) {
                    editable.setSpan(ForegroundColorSpan(Color.parseColor("#CE9178")), m.range.first, m.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                for (m in commentPattern.findAll(text)) {
                    editable.setSpan(ForegroundColorSpan(Color.parseColor("#6A9955")), m.range.first, m.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }

            if (highlightedMatchStart >= 0 && highlightedMatchEnd <= editable.length) {
                editable.setSpan(
                    BackgroundColorSpan(Color.parseColor("#D29922")),
                    highlightedMatchStart,
                    highlightedMatchEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                editable.setSpan(
                    ForegroundColorSpan(Color.parseColor("#000000")),
                    highlightedMatchStart,
                    highlightedMatchEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        } finally {
            isHighlighting = false
        }
    }

    private fun showCommitDialog() {
        val etCommit = EditText(this).apply {
            hint = "Pesan commit (contoh: Fix bug di $filePath)"
            setText(if (isNewFile) "Create $filePath" else "Update $filePath")
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val btnSkipCi = Button(this).apply {
            text = "+ [skip ci]"
            textAllCaps = false
            setOnClickListener {
                val current = etCommit.text.toString()
                if (!current.contains("[skip ci]", ignoreCase = true)) {
                    val trimmed = current.trimEnd()
                    etCommit.setText(if (trimmed.isEmpty()) "[skip ci]" else "$trimmed [skip ci]")
                    etCommit.setSelection(etCommit.text.length)
                }
            }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(48, 16, 48, 0)
            addView(etCommit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnSkipCi)
        }

        AlertDialog.Builder(this)
            .setTitle("Commit & Push ke GitHub")
            .setMessage("Target: $filePath")
            .setView(container)
            .setPositiveButton("Push Sekarang") { _, _ ->
                val finalCode = binding.etCodeEditor.text.toString()
                val msg = etCommit.text.toString().trim()
                binding.tvEditorInfo.text = "Mengirim commit ke GitHub..."

                lifecycleScope.launch {
                    try {
                        scanner.upsertGithubFile(repoUrl, token, filePath, finalCode, msg)
                        Toast.makeText(this@CodeEditorActivity, "Berhasil Commit & Push!", Toast.LENGTH_SHORT).show()
                        val res = Intent().apply {
                            putExtra("updated_path", filePath)
                            putExtra("updated_content", finalCode)
                        }
                        setResult(Activity.RESULT_OK, res)
                        finish()
                    } catch (e: Exception) {
                        binding.tvEditorInfo.text = "Gagal commit: ${e.message}"
                        Toast.makeText(this@CodeEditorActivity, e.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }
}
