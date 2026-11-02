package top.bangwu.taildeck

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput

/** Allow, Deny and Reply pressed on an agent's notification. */
class AgentActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val node = intent.getStringExtra(NODE) ?: return
        val pane = intent.getStringExtra(PANE) ?: return
        val hub = TaildeckApp.instance.herdr
        when (intent.action) {
            ALLOW, DENY -> hub.answerFromNotification(node, pane, intent.action == ALLOW, intent.getStringExtra(SCREEN).orEmpty())
            REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REPLY_TEXT)?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) hub.replyFromNotification(node, pane, text)
            }
        }
    }

    companion object {
        const val ALLOW = "top.bangwu.taildeck.agent.ALLOW"
        const val DENY = "top.bangwu.taildeck.agent.DENY"
        const val REPLY = "top.bangwu.taildeck.agent.REPLY"
        const val REPLY_TEXT = "reply"
        const val NODE = "node"
        const val PANE = "pane"
        const val SCREEN = "screen"
    }
}
