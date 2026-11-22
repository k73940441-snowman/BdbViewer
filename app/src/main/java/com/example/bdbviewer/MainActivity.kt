package com.example.bdbviewer

import android.database.sqlite.SQLiteDatabase
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var etSearch: EditText
    private lateinit var spinnerBook: Spinner
    private lateinit var spinnerChapter: Spinner
    private lateinit var tvBibleContent: TextView
    private lateinit var scrollView: ScrollView

    private var db: SQLiteDatabase? = null

    private enum class DbMode { VERSE_BASED, HYMNAL, GENERAL }
    private var currentMode = DbMode.GENERAL
    private var activeTableName = ""
    private var textColumnName = ""

    private val bookNumbers = mutableListOf(0).apply { clear() }
    private val chapterNumbers = mutableListOf(0).apply { clear() }
    private val hymnChapters = mutableListOf(0).apply { clear() }
    private val tableNames = mutableListOf("").apply { clear() }

    private val bibleBookNames = listOf(
        "",
        "창세기", "출애굽기", "레위기", "민수기", "신명기", "여호수아", "사사기", "룻기", "사무엘상", "사무엘하",
        "열왕기상", "열왕기하", "역대상", "역대하", "에스라", "느헤미야", "에스더", "욥기", "시편", "잠언",
        "전도서", "아가", "이사야", "예레미야", "예레미야애가", "에스겔", "다니엘", "호세아", "요엘", "아모스",
        "오바댜", "요나", "미가", "나훔", "하박국", "스바냐", "학개", "스가랴", "말라기",
        "마태복음", "마가복음", "누가복음", "요한복음", "사도행전", "로마서", "고린도전서", "고린도후서", "갈라디아서", "에베소서",
        "빌립보서", "골로새서", "데살로니가전서", "데살로니가후서", "디모데전서", "디모데후서", "디도서", "빌레몬서", "히브리서", "야고보서",
        "베드로전서", "베드로후서", "요한일서", "요한이서", "요한삼서", "유다서", "요한계시록"
    )

    // 모든 확장자(.bdb, .cdb, .cmp, .hdb, .sdb)를 회색 비활성화 없이 선택할 수 있도록 OpenDocument 사용
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { importDbFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        etSearch = findViewById(R.id.etSearch)
        spinnerBook = findViewById(R.id.spinnerBook)
        spinnerChapter = findViewById(R.id.spinnerChapter)
        tvBibleContent = findViewById(R.id.tvBibleContent)
        scrollView = findViewById(R.id.scrollView)

        val btnSelectFile = findViewById<Button>(R.id.btnSelectFile)
        btnSelectFile.text = "DB 파일 열기"
        btnSelectFile.setOnClickListener {
            filePickerLauncher.launch(arrayOf("*/*"))
        }

        findViewById<Button>(R.id.btnSearch).setOnClickListener {
            performSearch()
        }

        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else {
                false
            }
        }

        findViewById<Button>(R.id.btnPrevChapter).setOnClickListener {
            moveChapter(-1)
        }

        findViewById<Button>(R.id.btnNextChapter).setOnClickListener {
            moveChapter(1)
        }

        val savedFile = File(filesDir, "saved_bible.db")
        val savedName = getSharedPreferences("db_prefs", MODE_PRIVATE).getString("last_file_name", "저장된 DB")
        if (savedFile.exists()) {
            openDatabaseFile(savedFile, savedName ?: "저장된 DB")
        }
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var name = "선택된 파일"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    private fun importDbFile(uri: Uri) {
        val fileName = getFileNameFromUri(uri)
        tvStatus.text = "'$fileName' 불러오는 중..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val savedFile = File(filesDir, "saved_bible.db")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(savedFile).use { output ->
                        input.copyTo(output)
                    }
                }
                getSharedPreferences("db_prefs", MODE_PRIVATE).edit()
                    .putString("last_file_name", fileName)
                    .apply()

                withContext(Dispatchers.Main) {
                    openDatabaseFile(savedFile, fileName)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "불러오기 실패: ${e.message}"
                }
            }
        }
    }

    // HTML 태그(<font>, <b>, <br>) 변환 및 스트롱 코드(<WH1234>) 정리 함수
    private fun formatRichText(raw: String): CharSequence {
        // 1. <WH1234>, <WG567> 같은 스트롱 번호 태그 제거
        val cleaned = raw.replace(Regex("<W[HG]\\d+>"), "")
        // 2. HTML 태그가 포함되어 있으면 스타일을 살려서 변환
        return if (cleaned.contains("<") && cleaned.contains(">")) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Html.fromHtml(cleaned, Html.FROM_HTML_MODE_LEGACY)
            } else {
                @Suppress("DEPRECATION")
                Html.fromHtml(cleaned)
            }
        } else {
            cleaned
        }
    }

    private fun getFileTypeBadge(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "bdb" -> "성경(.bdb)"
            "cdb" -> "주석(.cdb)"
            "cmp" -> "비교(.cmp)"
            "hdb" -> "찬송가(.hdb)"
            "sdb" -> "사전(.sdb)"
            else -> "DB(.$ext)"
        }
    }

    private fun openDatabaseFile(file: File, fileName: String) {
        try {
            db?.close()
            db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val database = db ?: return

            val tables = mutableListOf("").apply { clear() }
            val c = database.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%'",
                null
            )
            while (c.moveToNext()) {
                tables.add(c.getString(0))
            }
            c.close()

            val badge = getFileTypeBadge(fileName)

            // 1. 찬송가 테이블(hymnal) 확인 (.hdb)
            val hymnalTable = tables.firstOrNull { it.equals("hymnal", ignoreCase = true) }
            if (hymnalTable != null) {
                currentMode = DbMode.HYMNAL
                activeTableName = hymnalTable
                textColumnName = "htext"
                spinnerChapter.visibility = View.GONE
                loadHymnalList(hymnalTable)
                tvStatus.text = "[$badge] $fileName"
                return
            }

            // 2. 권/장 기반 테이블 확인 (.bdb 성경, .cdb 주석, .cmp 비교)
            var verseBasedTable: String? = null
            var detectedTextCol = ""
            for (tbl in tables) {
                val colCursor = database.rawQuery("PRAGMA table_info([$tbl])", null)
                val cols = mutableListOf("").apply { clear() }
                while (colCursor.moveToNext()) {
                    cols.add(colCursor.getString(1).lowercase())
                }
                colCursor.close()

                if (cols.contains("book") && cols.contains("chapter")) {
                    verseBasedTable = tbl
                    detectedTextCol = cols.firstOrNull {
                        it in listOf("btext", "ctext", "text", "content", "comment", "data", "body", "note")
                    } ?: cols.last()
                    break
                }
            }

            if (verseBasedTable != null) {
                currentMode = DbMode.VERSE_BASED
                activeTableName = verseBasedTable
                textColumnName = detectedTextCol
                spinnerChapter.visibility = View.VISIBLE
                loadBooksFromTable(verseBasedTable, detectedTextCol)
                tvStatus.text = "[$badge] $fileName"
                return
            }

            // 3. 사전(.sdb) 및 일반 테이블 구조
            currentMode = DbMode.GENERAL
            spinnerChapter.visibility = View.GONE
            loadGeneralTables(tables)
            tvStatus.text = "[$badge] $fileName (테이블 ${tables.size}개)"

        } catch (e: Exception) {
            tvStatus.text = "열기 오류: $fileName"
            tvBibleContent.text = "파일을 열 수 없습니다:\n${e.message}"
        }
    }

    // ================= [모드 1] 성경/주석/비교 뷰어 (.bdb, .cdb, .cmp) =================
    private fun getBookDisplayName(bookNum: Int): String {
        return if (bookNum in 1 until bibleBookNames.size) {
            "$bookNum. ${bibleBookNames[bookNum]}"
        } else {
            "${bookNum}권"
        }
    }

    private fun loadBooksFromTable(tableName: String, textCol: String) {
        val database = db ?: return
        bookNumbers.clear()
        val displayNames = mutableListOf("").apply { clear() }

        val cursor = database.rawQuery("SELECT DISTINCT book FROM [$tableName] ORDER BY book ASC", null)
        while (cursor.moveToNext()) {
            val bNum = cursor.getInt(0)
            bookNumbers.add(bNum)
            displayNames.add(getBookDisplayName(bNum))
        }
        cursor.close()

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerBook.adapter = adapter

        spinnerBook.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                loadChaptersFromTable(tableName, bookNumbers[position], textCol)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadChaptersFromTable(tableName: String, bookNum: Int, textCol: String) {
        val database = db ?: return
        chapterNumbers.clear()
        val displayChapters = mutableListOf("").apply { clear() }

        val cursor = database.rawQuery(
            "SELECT DISTINCT chapter FROM [$tableName] WHERE book = $bookNum ORDER BY chapter ASC",
            null
        )
        while (cursor.moveToNext()) {
            val ch = cursor.getInt(0)
            chapterNumbers.add(ch)
            displayChapters.add("${ch}장")
        }
        cursor.close()

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayChapters)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerChapter.adapter = adapter

        spinnerChapter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                loadVersesFromTable(tableName, bookNum, chapterNumbers[position], textCol)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadVersesFromTable(tableName: String, bookNum: Int, chapterNum: Int, textCol: String) {
        val database = db ?: return
        val cursor = database.rawQuery(
            "SELECT * FROM [$tableName] WHERE book = $bookNum AND chapter = $chapterNum ORDER BY verse ASC",
            null
        )
        val colNames = cursor.columnNames
        val verseIdx = colNames.indexOfFirst { it.equals("verse", ignoreCase = true) || it.equals("fromverse", ignoreCase = true) }
        val toVerseIdx = colNames.indexOfFirst { it.equals("toverse", ignoreCase = true) }
        val textIdx = colNames.indexOfFirst { it.equals(textCol, ignoreCase = true) }

        val builder = SpannableStringBuilder()
        val title = "${getBookDisplayName(bookNum)} ${chapterNum}장\n\n"
        builder.append(title)
        builder.setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        while (cursor.moveToNext()) {
            val verseLabel = if (verseIdx >= 0) {
                val v1 = cursor.getInt(verseIdx)
                val v2 = if (toVerseIdx >= 0) cursor.getInt(toVerseIdx) else v1
                if (v2 > v1) "[$v1-$v2절]\n" else "$v1  "
            } else {
                "• "
            }

            val rawText = if (textIdx >= 0) (cursor.getString(textIdx) ?: "") else ""
            val formattedText = formatRichText(rawText)

            val start = builder.length
            builder.append(verseLabel)
            builder.setSpan(ForegroundColorSpan(Color.parseColor("#1565C0")), start, start + verseLabel.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(StyleSpan(Typeface.BOLD), start, start + verseLabel.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            builder.append(formattedText)
            builder.append("\n\n")
        }
        cursor.close()

        tvBibleContent.text = builder
        scrollView.scrollTo(0, 0)
    }

    private fun loadHymnalList(tableName: String) {
        val database = db ?: return
        hymnChapters.clear()
        val displayTitles = mutableListOf("").apply { clear() }

        val cursor = database.rawQuery("SELECT chapter, title FROM [$tableName] ORDER BY chapter ASC", null)
        while (cursor.moveToNext()) {
            val ch = cursor.getInt(0)
            val title = cursor.getString(1) ?: ""
            hymnChapters.add(ch)
            displayTitles.add("${ch}장. $title")
        }
        cursor.close()

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayTitles)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerBook.adapter = adapter

        spinnerBook.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                loadHymnDetail(tableName, hymnChapters[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadHymnDetail(tableName: String, chapterNum: Int) {
        val database = db ?: return
        val cursor = database.rawQuery(
            "SELECT chapter, title, htext FROM [$tableName] WHERE chapter = $chapterNum",
            null
        )
        val builder = SpannableStringBuilder()
        if (cursor.moveToFirst()) {
            val ch = cursor.getInt(0)
            val title = cursor.getString(1) ?: ""
            val rawHtext = cursor.getString(2) ?: ""

            val header = "🎵 찬송가 ${ch}장 - $title\n\n"
            builder.append(header)
            builder.setSpan(ForegroundColorSpan(Color.parseColor("#1565C0")), 0, header.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(StyleSpan(Typeface.BOLD), 0, header.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            builder.append(formatRichText(rawHtext))
        }
        cursor.close()

        tvBibleContent.text = builder
        scrollView.scrollTo(0, 0)
    }

    private fun loadGeneralTables(tables: List<String>) {
        tableNames.clear()
        tableNames.addAll(tables)

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, tableNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerBook.adapter = adapter

        spinnerBook.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                activeTableName = tableNames[position]
                loadGeneralTableData(activeTableName)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadGeneralTableData(tableName: String) {
        val database = db ?: return
        val cursor = database.rawQuery("SELECT * FROM [$tableName] LIMIT 300", null)
        val colNames = cursor.columnNames
        if (colNames.isNotEmpty()) {
            textColumnName = colNames.last()
        }

        val builder = SpannableStringBuilder()
        val header = "📂 테이블: $tableName (최대 300개 항목 표시)\n\n"
        builder.append(header)
        builder.setSpan(StyleSpan(Typeface.BOLD), 0, header.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        var idx = 0
        while (cursor.moveToNext()) {
            idx++
            val titleStart = builder.length
            val itemHeader = "[$idx]\n"
            builder.append(itemHeader)
            builder.setSpan(ForegroundColorSpan(Color.parseColor("#2E7D32")), titleStart, titleStart + itemHeader.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(StyleSpan(Typeface.BOLD), titleStart, titleStart + itemHeader.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            for (i in colNames.indices) {
                val value = cursor.getString(i) ?: ""
                if (value.isNotBlank()) {
                    builder.append("• ${colNames[i]}: ")
                    builder.append(formatRichText(value))
                    builder.append("\n")
                }
            }
            builder.append("\n────────────────────\n\n")
        }
        cursor.close()

        tvBibleContent.text = builder
        scrollView.scrollTo(0, 0)
    }

    private fun moveChapter(delta: Int) {
        if (currentMode == DbMode.HYMNAL) {
            if (hymnChapters.isEmpty()) return
            val nextIdx = spinnerBook.selectedItemPosition + delta
            if (nextIdx in hymnChapters.indices) {
                spinnerBook.setSelection(nextIdx)
            } else {
                Toast.makeText(this, "더 이상 이동할 찬송가가 없습니다.", Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (currentMode == DbMode.GENERAL) {
            Toast.makeText(this, "사전 모드에서는 상단 검색창으로 단어를 검색하세요.", Toast.LENGTH_SHORT).show()
            return
        }

        if (chapterNumbers.isEmpty()) return
        val currentChIdx = spinnerChapter.selectedItemPosition
        val nextChIdx = currentChIdx + delta

        if (nextChIdx in chapterNumbers.indices) {
            spinnerChapter.setSelection(nextChIdx)
        } else if (delta > 0 && spinnerBook.selectedItemPosition + 1 < bookNumbers.size) {
            spinnerBook.setSelection(spinnerBook.selectedItemPosition + 1)
        } else if (delta < 0 && spinnerBook.selectedItemPosition - 1 >= 0) {
            spinnerBook.setSelection(spinnerBook.selectedItemPosition - 1)
        } else {
            Toast.makeText(this, "더 이상 이동할 장이 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun performSearch() {
        val database = db
        if (database == null) {
            Toast.makeText(this, "먼저 DB 파일을 불러와주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val keyword = etSearch.text.toString().trim()
        if (keyword.isEmpty()) {
            Toast.makeText(this, "검색어를 입력하세요.", Toast.LENGTH_SHORT).show()
            return
        }

        tvStatus.text = "'$keyword' 검색 중..."
        lifecycleScope.launch(Dispatchers.IO) {
            val builder = SpannableStringBuilder()
            var count = 0
            val safeKeyword = keyword.replace("'", "''")

            if (currentMode == DbMode.VERSE_BASED) {
                val cursor = database.rawQuery(
                    "SELECT * FROM [$activeTableName] WHERE [$textColumnName] LIKE '%$safeKeyword%' ORDER BY book ASC, chapter ASC LIMIT 200",
                    null
                )
                val colNames = cursor.columnNames
                val bookIdx = colNames.indexOfFirst { it.equals("book", ignoreCase = true) }
                val chIdx = colNames.indexOfFirst { it.equals("chapter", ignoreCase = true) }
                val vIdx = colNames.indexOfFirst { it.equals("verse", ignoreCase = true) || it.equals("fromverse", ignoreCase = true) }
                val txtIdx = colNames.indexOfFirst { it.equals(textColumnName, ignoreCase = true) }

                while (cursor.moveToNext()) {
                    count++
                    val b = if (bookIdx >= 0) cursor.getInt(bookIdx) else 0
                    val c = if (chIdx >= 0) cursor.getInt(chIdx) else 0
                    val v = if (vIdx >= 0) cursor.getInt(vIdx) else 0
                    val rawText = if (txtIdx >= 0) (cursor.getString(txtIdx) ?: "") else ""

                    val bookShortName = if (b in 1 until bibleBookNames.size) bibleBookNames[b] else "${b}권"
                    val ref = if (v > 0) "[$bookShortName $c:$v]\n" else "[$bookShortName ${c}장]\n"
                    val start = builder.length
                    builder.append(ref)
                    builder.setSpan(ForegroundColorSpan(Color.parseColor("#2E7D32")), start, start + ref.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(StyleSpan(Typeface.BOLD), start, start + ref.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.append(formatRichText(rawText))
                    builder.append("\n\n")
                }
                cursor.close()
            } else if (currentMode == DbMode.HYMNAL) {
                val cursor = database.rawQuery(
                    "SELECT chapter, title, htext FROM [$activeTableName] WHERE chapter LIKE '%$safeKeyword%' OR title LIKE '%$safeKeyword%' OR htext LIKE '%$safeKeyword%' ORDER BY chapter ASC LIMIT 200",
                    null
                )
                while (cursor.moveToNext()) {
                    count++
                    val ch = cursor.getInt(0)
                    val title = cursor.getString(1) ?: ""
                    val rawText = cursor.getString(2) ?: ""

                    val ref = "[찬송가 ${ch}장 - $title]\n"
                    val start = builder.length
                    builder.append(ref)
                    builder.setSpan(ForegroundColorSpan(Color.parseColor("#2E7D32")), start, start + ref.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(StyleSpan(Typeface.BOLD), start, start + ref.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.append(formatRichText(rawText))
                    builder.append("\n\n────────────────────\n\n")
                }
                cursor.close()
            } else if (activeTableName.isNotEmpty() && textColumnName.isNotEmpty()) {
                val colCursor = database.rawQuery("PRAGMA table_info([$activeTableName])", null)
                val allCols = mutableListOf("").apply { clear() }
                while (colCursor.moveToNext()) {
                    allCols.add(colCursor.getString(1))
                }
                colCursor.close()

                val whereClause = allCols.joinToString(" OR ") { "[$it] LIKE '%$safeKeyword%'" }

                val cursor = database.rawQuery(
                    "SELECT * FROM [$activeTableName] WHERE $whereClause LIMIT 200",
                    null
                )
                val colNames = cursor.columnNames
                while (cursor.moveToNext()) {
                    count++
                    builder.append("[$count]\n")
                    for (i in colNames.indices) {
                        val value = cursor.getString(i) ?: ""
                        if (value.isNotBlank()) {
                            builder.append("• ${colNames[i]}: ")
                            builder.append(formatRichText(value))
                            builder.append("\n")
                        }
                    }
                    builder.append("\n────────────────────\n\n")
                }
                cursor.close()
            }

            withContext(Dispatchers.Main) {
                tvStatus.text = "검색 결과: ${count}건" + if (count >= 200) " (최대 200건 표시)" else ""
                if (count == 0) {
                    tvBibleContent.text = "'$keyword'에 대한 검색 결과가 없습니다."
                } else {
                    tvBibleContent.text = builder
                    scrollView.scrollTo(0, 0)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        db?.close()
    }
}
