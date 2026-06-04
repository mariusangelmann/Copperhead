package com.copperhead.gateway

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.copperhead.gateway.databinding.ActivityLogBinding
import com.copperhead.gateway.service.GatewayService

/**
 * Full-screen, terminal-style log viewer.
 *
 * - Pulls the buffered history from [GatewayService] on attach.
 * - Subscribes for live updates while visible.
 * - Auto-scrolls to bottom UNLESS the user has scrolled up; FAB appears in
 *   that case to jump back to live tail.
 */
class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding
    private val buffer = StringBuilder()
    private var autoScroll = true

    private val logListener: (String) -> Unit = { line ->
        runOnUiThread { appendLine(line) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.logToolbar)
        binding.logToolbar.setNavigationOnClickListener { finish() }

        binding.fabScrollBottom.setOnClickListener {
            autoScroll = true
            scrollToBottom()
            binding.fabScrollBottom.hide()
        }

        binding.fullLogScrollView.viewTreeObserver.addOnScrollChangedListener {
            val sv = binding.fullLogScrollView
            val atBottom = !sv.canScrollVertically(1)
            autoScroll = atBottom
            binding.fabScrollBottom.visibility = if (atBottom) View.GONE else View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
        buffer.clear()
        val service = GatewayService.instance
        if (service != null) {
            service.getLogSnapshot().forEach { buffer.appendLine(it) }
            binding.fullLogText.text = buffer.toString()
            service.addLogListener(logListener)
            binding.fullLogScrollView.post { scrollToBottom() }
        } else {
            binding.fullLogText.text = "Gateway is not running. Start it to see live logs."
        }
    }

    override fun onPause() {
        GatewayService.instance?.removeLogListener(logListener)
        super.onPause()
    }

    private fun appendLine(line: String) {
        buffer.appendLine(line)
        // Trim if huge — keep last ~80k chars.
        if (buffer.length > 120_000) {
            val trimmed = buffer.substring(buffer.length - 80_000)
            buffer.clear()
            buffer.append(trimmed)
        }
        binding.fullLogText.text = buffer.toString()
        if (autoScroll) scrollToBottom()
    }

    private fun scrollToBottom() {
        binding.fullLogScrollView.post {
            binding.fullLogScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_copy -> {
            val clip = getSystemService(ClipboardManager::class.java)
            clip.setPrimaryClip(ClipData.newPlainText("Copperhead log", buffer.toString()))
            Toast.makeText(this, getString(R.string.log_copied), Toast.LENGTH_SHORT).show()
            true
        }
        R.id.action_share -> {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, buffer.toString())
                putExtra(Intent.EXTRA_SUBJECT, "Copperhead Gateway log")
            }
            startActivity(Intent.createChooser(send, getString(R.string.log_share)))
            true
        }
        R.id.action_clear -> {
            buffer.clear()
            binding.fullLogText.text = ""
            GatewayService.instance?.clearLogBuffer()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
}
