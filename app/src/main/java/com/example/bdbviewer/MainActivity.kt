package com.example.bdbviewer

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
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
    private lateinit var tvResult: TextView

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            processSqliteBdbFile(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnSelectFile = findViewById<Button>(R.id.btnSelectFile)
        tvStatus = findViewById(R.id.tvStatus)
        tvResult = findViewById(R.id.tvResult)

        btnSelectFile.setOnClickListener {
            filePickerLauncher.launch("*/*")
        }
    }

    private fun processSqliteBdbFile(uri: Uri) {
        tvStatus.text = "SQLite DB 읽는 중..."
        tvResult.text = ""

        lifecycleScope.launch(Dispatchers.IO) {
            var db: SQLiteDatabase? = null
            try {
                // 1. 선택한 .bdb 파일을 앱 내부 임시 파일로 복사
                val tempDbFile = File(cacheDir, "temp_bible.db")
                if (tempDbFile.exists()) {
                    tempDbFile.delete()
                }
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempDbFile).use { output ->
                        input.copyTo(output)
                    }
                }

                // 2. 안드로이드 기본 SQLite 엔진으로 읽기 전용 오픈
                db = SQLiteDatabase.openDatabase(
                    tempDbFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY
                )

                val sb = StringBuilder()

                // 3. DB 내부의 모든 테이블 목록 조회
                val tableCursor = db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%'",
                    null
                )
                val tables = mutableListOf<String>()
                while (tableCursor.moveToNext()) {
                    tables.add(tableCursor.getString(0))
                }
                tableCursor.close()

                if (tables.isEmpty()) {
                    sb.append("테이블이 존재하지 않습니다.")
                } else {
                    for (tableName in tables) {
                        sb.append("==============================\n")
                        sb.append("📂 테이블 이름: $tableName\n")
                        sb.append("==============================\n\n")

                        // 각 테이블의 데이터 조회 (최대 500행 미리보기)
                        val dataCursor = db.rawQuery("SELECT * FROM \"$tableName\" LIMIT 500", null)
                        val colNames = dataCursor.columnNames
                        var rowCount = 0

                        while (dataCursor.moveToNext()) {
                            rowCount++
                            val rowItems = mutableListOf<String>()
                            for (i in colNames.indices) {
                                val value = dataCursor.getString(i) ?: "NULL"
                                rowItems.add("${colNames[i]}: $value")
                            }
                            sb.append("[$rowCount] ${rowItems.joinToString(" | ")}\n\n")
                        }
                        dataCursor.close()

                        if (rowCount >= 500) {
                            sb.append("... (데이터가 많아 500개까지만 표시합니다) ...\n\n")
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    tvStatus.text = "읽기 완료! (테이블 ${tables.size}개 발견)"
                    tvResult.text = sb.toString()
                }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    tvStatus.text = "오류 발생"
                    tvResult.text = "파일을 열 수 없습니다:\n${e.message}"
                }
            } finally {
                db?.close()
            }
        }
    }
}
