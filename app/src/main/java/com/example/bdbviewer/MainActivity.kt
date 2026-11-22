package com.example.bdbviewer

import android.database.sqlite.SQLiteDatabase
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
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
    private val bookNumbers = mutableListOf<Int>()
    private val chapterNumbers = mutableListOf<Int>()

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

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { importBdbFile(it) }
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

        findViewById<Button>(R.id.btnSelectFile).setOnClickListener {
            filePickerLauncher.launch("*/*")
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
        if (savedFile.exists()) {
            openDatabaseFile(savedFile)
        }
    }

    private fun importBdbFile(uri: Uri) {
        tvStatus.text = "파일 저장 중..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val savedFile = File(filesDir, "saved_bible.db")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(savedFile).use { output ->
                        input.copyTo(output)
                    }
                }
                withContext(Dispatchers.Main) {
                    openDatabaseFile(savedFile)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "불러오기 실패: ${e.message}"
                }
            }
        }
    }

    private fun openDatabaseFile(file: File) {
        try {
            db?.close()
            db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            loadBooks()
            tvStatus.text = "성경 DB 연결됨"
        } catch (e: Exception) {
            tvStatus.text = "DB 열기 오류: ${e.message}"
        }
    }

    private fun getBookDisplayName(bookNum: Int): String {
        return if (bookNum in 1 until bibleBookNames.size) {
            "$bookNum. ${bibleBookNames[bookNum]}"
        } else {
            "${bookNum}권"
        }
    }

    private fun loadBooks() {
        val database = db ?: return
        bookNumbers.clear()
        val displayNames = mutableListOf<String>()

        val cursor = database.rawQuery("SELECT DISTINCT book FROM Bible ORDER BY book ASC", null)
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
                loadChapters(bookNumbers[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadChapters(bookNum: Int) {
        val database = db ?: return
        chapterNumbers.clear()
        val displayChapters = mutableListOf<String>()

        val cursor = database.rawQuery(
            "SELECT DISTINCT chapter FROM Bible WHERE book = ? ORDER BY chapter ASC",
            arrayOf(bookNum.toString())
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
                loadVerses(bookNum, chapterNumbers[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadVerses(bookNum: Int, chapterNum: Int) {
        val database = db ?: return
        val cursor = database.rawQuery(
            "SELECT verse, btext FROM Bible WHERE book = ? AND chapter = ? ORDER BY verse ASC",
            arrayOf(bookNum.toString(), chapterNum.toString())
        )

        val builder = SpannableStringBuilder()
        val title = "${getBookDisplayName(bookNum)} ${chapterNum}장\n\n"
        builder.append(title)
        builder.setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        while (cursor.moveToNext()) {
            val verse = cursor.getInt(0)
            val text = cursor.getString(1) ?: ""

            val start = builder.length
            val versePrefix = "$verse  "
            builder.append(versePrefix)
            builder.setSpan(ForegroundColorSpan(Color.parseColor("#1565C0")), start, start + versePrefix.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(StyleSpan(Typeface.BOLD), start, start + versePrefix.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            builder.append("$text\n\n")
        }
        cursor.close()

        tvBibleContent.text = builder
        scrollView.scrollTo(0, 0)
    }

    private fun moveChapter(delta: Int) {
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
            Toast.makeText(this, "먼저 .bdb 파일을 불러와주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val keyword = etSearch.text.toString().trim()
        if (keyword.isEmpty()) {
            Toast.makeText(this, "검색어를 입력하세요.", Toast.LENGTH_SHORT).show()
            return
        }

        tvStatus.text = "'$keyword' 검색 중..."
        lifecycleScope.launch(Dispatchers.IO) {
            val cursor = database.rawQuery(
                "SELECT book, chapter, verse, btext FROM Bible WHERE btext LIKE ? ORDER BY book ASC, chapter ASC, verse ASC LIMIT 200",
                arrayOf("%$keyword%")
            )

            val builder = SpannableStringBuilder()
            var count = 0
            while (cursor.moveToNext()) {
                count++
                val b = cursor.getInt(0)
                val c = cursor.getInt(1)
                val v = cursor.getInt(2)
                val text = cursor.getString(3) ?: ""

                val bookShortName = if (b in 1 until bibleBookNames.size) bibleBookNames[b] else "${b}권"
                val ref = "[$bookShortName $c:$v] "
                val start = builder.length

                builder.append(ref)
                builder.setSpan(
                    StyleSpan(Typeface.BOLD),
                    start,
                    builder.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                builder.append(text).append("\n\n")
            }
            cursor.close()

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
