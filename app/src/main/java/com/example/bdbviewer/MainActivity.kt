package com.example.bdbviewer

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sleepycat.je.*
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
            processBdbFile(it)
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

    private fun processBdbFile(uri: Uri) {
        tvStatus.text = "파일 읽는 중..."
        tvResult.text = ""

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dbEnvDir = File(cacheDir, "bdb_temp_env")
                if (dbEnvDir.exists()) {
                    dbEnvDir.deleteRecursively()
                }
                dbEnvDir.mkdirs()

                val targetFile = File(dbEnvDir, "00000000.jdb")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }

                val envConfig = EnvironmentConfig().apply {
                    readOnly = true
                    allowCreate = false
                }
                val env = Environment(dbEnvDir, envConfig)

                val dbNames = env.databaseNames
                val sb = StringBuilder()

                if (dbNames.isEmpty()) {
                    sb.append("데이터베이스가 비어있거나 지원하지 않는 BDB 포맷입니다.\n")
                } else {
                    for (dbName in dbNames) {
                        sb.append("=== DB 이름: $dbName ===\n")
                        val dbConfig = DatabaseConfig().apply {
                            readOnly = true
                            allowCreate = false
                        }
                        val db = env.openDatabase(null, dbName, dbConfig)
                        val cursor = db.openCursor(null, null)

                        val foundKey = DatabaseEntry()
                        val foundData = DatabaseEntry()

                        var count = 0
                        while (cursor.getNext(foundKey, foundData, LockMode.DEFAULT) == OperationStatus.SUCCESS) {
                            val keyStr = String(foundKey.data, Charsets.UTF_8)
                            val valStr = String(foundData.data, Charsets.UTF_8)
                            sb.append("[$count] Key: $keyStr -> Value: $valStr\n")
                            count++

                            if (count >= 500) {
                                sb.append("... (데이터가 많아 500개까지만 표시합니다) ...\n")
                                break
                            }
                        }
                        cursor.close()
                        db.close()
                    }
                }
                env.close()

                withContext(Dispatchers.Main) {
                    tvStatus.text = "읽기 완료!"
                    tvResult.text = sb.toString()
                }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    tvStatus.text = "오류 발생"
                    tvResult.text = "파일을 열 수 없습니다:\n${e.message}\n\n" +
                            "※ 참고: C/C++ 기반의 구형 Berkeley DB 파일인 경우 Java Edition 라이브러리와 호환되지 않을 수 있습니다."
                }
            }
        }
    }
}
