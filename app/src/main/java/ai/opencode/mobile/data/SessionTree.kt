package ai.opencode.mobile.data

import ai.opencode.mobile.data.remote.Session
import java.util.concurrent.ConcurrentHashMap

/**
 * Child session id -> parent session id, used to attribute events emitted by
 * subagent sessions to the chat the user is looking at. Written by the event
 * collector and the session loader, read from the UI, hence concurrent.
 */
internal class SessionTree {
    private val parentById = ConcurrentHashMap<String, String>()

    fun reset(sessions: List<Session>) {
        parentById.clear()
        sessions.forEach(::record)
    }

    fun record(session: Session) {
        val parentId = session.parentID
        if (parentId != null) parentById[session.id] = parentId else parentById.remove(session.id)
    }

    fun remove(sessionId: String) {
        parentById.remove(sessionId)
    }

    /**
     * True when [itemSessionId] is the target session or one of its descendants.
     * Subagent sessions run under their own id but belong to the open chat.
     */
    fun matches(itemSessionId: String, targetSessionId: String): Boolean =
        itemSessionId == targetSessionId || rootOf(itemSessionId) == targetSessionId

    private fun rootOf(sessionId: String): String {
        var current = sessionId
        val visited = HashSet<String>()
        while (visited.add(current)) {
            val parent = parentById[current] ?: return current
            current = parent
        }
        return current
    }
}
