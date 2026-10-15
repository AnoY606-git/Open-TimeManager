package dev.opentimemanager.app.system

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.opentimemanager.app.tracking.*
import dev.opentimemanager.core.model.*

/** URI входит в identity PendingIntent: старый Stop не заменяется новым target через extras. */
object TrackingActions {
    const val START = "dev.opentimemanager.action.START"
    const val PAUSE = "dev.opentimemanager.action.PAUSE"
    const val RESUME = "dev.opentimemanager.action.RESUME"
    const val STOP = "dev.opentimemanager.action.STOP"

    fun pendingIntent(context: Context, command: TrackingCommand): PendingIntent {
        val intent = Intent(context, TrackingCommandReceiver::class.java)
        val revision: Long
        val id: String
        when (command) {
            is TrackingCommand.Start -> { intent.action = START; revision = command.expectedRevision; id = command.projectId.value }
            is TrackingCommand.Pause -> { intent.action = PAUSE; revision = command.target.expectedRevision; id = command.target.entryId.value }
            is TrackingCommand.Resume -> { intent.action = RESUME; revision = command.target.expectedRevision; id = command.target.entryId.value }
            is TrackingCommand.Stop -> { intent.action = STOP; revision = command.target.expectedRevision; id = command.target.entryId.value }
            is TrackingCommand.Switch -> error("Switch requires project selection in the app")
            is TrackingCommand.ResolveReview -> error("Review resolution requires explicit confirmation in the app")
        }
        intent.data = Uri.Builder().scheme("otm").authority("tracking").appendPath(intent.action!!).appendPath(id).appendPath(revision.toString()).appendPath(command.commandId.value).build()
        intent.putExtra("id", id).putExtra("revision", revision).putExtra("commandId", command.commandId.value)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    fun decode(intent: Intent): TrackingCommand? {
        val id = intent.getStringExtra("id")?.takeIf { it.isNotBlank() && it.length <= 256 } ?: return null
        val commandId = intent.getStringExtra("commandId")?.takeIf { it.isNotBlank() && it.length <= 1024 } ?: return null
        val revision = intent.getLongExtra("revision", -1).takeIf { it >= 0 } ?: return null
        val target = CommandTarget(EntryId(id), revision, CommandId(commandId))
        return when (intent.action) {
            START -> TrackingCommand.Start(CommandId(commandId), ProjectId(id), revision)
            PAUSE -> TrackingCommand.Pause(target)
            RESUME -> TrackingCommand.Resume(target)
            STOP -> TrackingCommand.Stop(target)
            else -> null
        }
    }

    fun primary(snapshot: AppSnapshot): TrackingCommand? {
        val s = snapshot.session
        val target = s.entryId?.let { CommandTarget(it, s.revision, CommandId("system:${it.value}:${s.revision}:${s.state}")) }
        return when (s.state) {
            TrackingState.IDLE -> snapshot.projects.firstOrNull { it.archivedAtUtcMillis == null }?.let {
                TrackingCommand.Start(CommandId("system:start:${s.revision}:${it.id}"), ProjectId(it.id), s.revision)
            }
            TrackingState.RUNNING -> TrackingCommand.Pause(target!!)
            TrackingState.PAUSED -> TrackingCommand.Resume(target!!)
            TrackingState.NEEDS_REVIEW -> null
        }
    }

    fun stop(snapshot: AppSnapshot): TrackingCommand.Stop = snapshot.session.let {
        TrackingCommand.Stop(CommandTarget(it.entryId!!, it.revision, CommandId("system:stop:${it.entryId!!.value}:${it.revision}")))
    }

    fun openApp(context: Context): PendingIntent? = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
        PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
    }
}
