package io.github.sneedster.resonance

import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class SetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val api = Api(getSharedPreferences("resonance", MODE_PRIVATE))

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 80, 48, 48)
        }

        val title = TextView(this).apply {
            text = "Resonance Setup"
            textSize = 24f
            setPadding(0, 0, 0, 32)
        }

        val serverLabel = TextView(this).apply { text = "Server URL" }
        val serverInput = EditText(this).apply {
            hint = "https://your-server.com"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(api.base)
        }

        val tokenLabel = TextView(this).apply { text = "Session Token" }
        val tokenInput = EditText(this).apply {
            hint = "Paste token from web app Settings"
            inputType = InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val statusText = TextView(this).apply {
            setPadding(0, 24, 0, 0)
            textSize = 14f
        }

        val saveButton = Button(this).apply { text = "Save & Connect" }

        saveButton.setOnClickListener {
            val base = serverInput.text.toString().trim().trimEnd('/')
            val token = tokenInput.text.toString().trim()

            if (base.isEmpty() || token.isEmpty()) {
                statusText.text = "Please enter both fields"
                return@setOnClickListener
            }

            saveButton.isEnabled = false
            statusText.text = "Connecting..."

            lifecycleScope.launch {
                try {
                    val apiWithCreds = Api(getSharedPreferences("resonance", MODE_PRIVATE))
                    apiWithCreds.setBase(base)
                    apiWithCreds.setToken(token)

                    val result = withContext(Dispatchers.IO) {
                        apiWithCreds.json("settings")
                    }
                    val json = JSONObject(result)
                    statusText.text = "Connected! Jukebox: ${if (json.optBoolean("jukeboxMode")) "ON" else "OFF"}"

                    startActivity(android.content.Intent(this@SetupActivity, MainActivity::class.java))
                    finish()
                } catch (e: Exception) {
                    statusText.text = "Connection failed: ${e.message}"
                    saveButton.isEnabled = true
                }
            }
        }

        container.addView(title)
        container.addView(serverLabel)
        container.addView(serverInput)
        container.addView(tokenLabel)
        container.addView(tokenInput)
        container.addView(saveButton)
        container.addView(statusText)

        setContentView(container)
    }
}
