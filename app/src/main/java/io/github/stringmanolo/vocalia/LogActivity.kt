package io.github.stringmanolo.vocalia

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.github.stringmanolo.vocalia.util.VocaliaLogger

class LogActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        val logEditText = findViewById<EditText>(R.id.logEditText)
        val copyButton = findViewById<Button>(R.id.copyLogsButton)
        val clearButton = findViewById<Button>(R.id.clearLogsButton)

        refreshLogs(logEditText)

        copyButton.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Vocalia Logs", VocaliaLogger.getAllLogs())
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Logs copiados al portapapeles", Toast.LENGTH_SHORT).show()
        }

        clearButton.setOnClickListener {
            VocaliaLogger.clear()
            refreshLogs(logEditText)
        }
    }

    private fun refreshLogs(editText: EditText) {
        editText.setText(VocaliaLogger.getAllLogs())
    }
}
