package dev.opentimemanager.app.system

import android.app.KeyguardManager
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import dev.opentimemanager.app.R
import dev.opentimemanager.core.model.TrackingState
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Документированный клиентский протокол OS3. Его наличие не равно допуску приложения Xiaomi.
 * https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131
 * Шаблоны: раскрытый №13, широкий остров №6; отдельной полосы с двумя кнопками здесь нет. */
internal fun hyperIslandProtocolAvailable(context: Context): Boolean {
    if (Build.MANUFACTURER.lowercase(Locale.ROOT) !in setOf("xiaomi", "redmi", "poco")) return false
    return try {
        Settings.System.getInt(context.contentResolver, "notification_focus_protocol", 0) >= 3
    } catch (_: Exception) { false }
}

// Наблюдатель процесса владеет подпиской, запрет общий для ВСЕХ renderer, включая
// обновления из приложения. Он не сохраняется в настройки или резервные копии.
@Volatile private var screenEventBlocksIsland = false

internal fun updateHyperIslandScreenState(action: String?) {
    when (action) {
        Intent.ACTION_SCREEN_OFF -> screenEventBlocksIsland = true
        Intent.ACTION_USER_PRESENT -> screenEventBlocksIsland = false
        // SCREEN_ON не доказывает разблокировку: Keyguard на OEM может обновляться позднее.
    }
}

internal fun hyperIslandContentVisible(context: Context): Boolean = try {
    val keyguard = context.getSystemService(KeyguardManager::class.java)
    !screenEventBlocksIsland && hyperIslandProtocolAvailable(context) &&
        context.getSystemService(PowerManager::class.java).isInteractive &&
        !keyguard.isDeviceLocked && !keyguard.isKeyguardLocked
} catch (_: Exception) { false }

/** Не читает проекты, Room, сеть или идентификаторы сессии. Получает только готовый безопасный текст.
 * OEM не документирует применение publicVersion к JSON, поэтому на заблокированном экране
 * эти extras не публикуются; наблюдатель пересобирает уведомление по событиям экрана. */
internal fun hyperIslandExtras(
    context: Context,
    state: TrackingState,
    title: String,
    dailyText: String?,
    elapsedMillis: Long,
    entryStartedAtUtcMillis: Long?,
    nowWallMillis: Long,
    actions: List<Notification.Action>,
): Bundle? {
    if (state != TrackingState.RUNNING && state != TrackingState.PAUSED) return null
    // Дедлайн всей записи, а не суммы работы: пауза/перепубликация его не продлевает.
    val started = entryStartedAtUtcMillis ?: return null
    val age = try { Math.subtractExact(nowWallMillis, started) } catch (_: ArithmeticException) { return null }
    if (age !in 0 until 43_200_000L) return null
    val remaining = 43_200_000L - age
    // timeout имеет точность минут; не округляем вверх за предел жизненного цикла.
    if (remaining < 60_000) return null
    if (elapsedMillis !in 0 until 43_200_000L || nowWallMillis < elapsedMillis || actions.size != 2) return null
    val running = state == TrackingState.RUNNING
    val clock = JSONObject().put("timerType", 1).put("timerWhen", nowWallMillis - elapsedMillis)
        .put("timerSystemCurrent", nowWallMillis)
    val frozen = DateUtils.formatElapsedTime(elapsedMillis / 1000)
    val digits = JSONObject().put("showHighlightColor", false).apply {
        if (running) put("timerInfo", clock) else put("digit", frozen)
    }
    val icon = "miui.focus.pic_timer"
    val highlight = JSONObject().put("picFunction", icon).put("content", dailyText ?: title).apply {
            if (dailyText != null) put("subContent", title)
            if (running) put("timerInfo", clock) else put("title", frozen)
        }
    val actionData = Bundle()
    val definitions = JSONArray()
    actions.forEachIndexed { index, action ->
        val key = "miui.focus.action_$index"
        val resource = if (index == 1) R.drawable.ic_island_stop else if (running) R.drawable.ic_island_pause else R.drawable.ic_island_resume
        actionData.putParcelable(key, Notification.Action.Builder(Icon.createWithResource(context, resource), action.title, action.actionIntent).build())
        definitions.put(JSONObject().put("type", 0).put("action", key))
    }
    val params = JSONObject().put("protocol", 1).put("business", "work_time")
        // Локальная политика уже проверила opt-in/dismissal. Новый entry и явное
        // повторное включение могут использовать отменённый постоянный ID уведомления.
        .put("updatable", true).put("reopen", "reopen")
        .put("enableFloat", false).put("islandFirstFloat", false)
        .put("filterWhenNoPermission", false).put("timeout", remaining / 60_000)
        // AOD не получает счётчик или дневной итог даже при нестандартном поведении OEM.
        .put("aodTitle", title).put("highlightInfo", highlight).put("actions", definitions)
        .put("param_island", JSONObject().put("islandProperty", 1).put("islandOrder", false)
            .put("islandTimeout", remaining / 1_000)
            .put("bigIslandArea", JSONObject()
                .put("imageTextInfoLeft", JSONObject().put("type", 1)
                    .put("picInfo", JSONObject().put("type", 1).put("pic", icon)))
                .put("sameWidthDigitInfo", digits))
            .put("smallIslandArea", JSONObject().put("picInfo", JSONObject().put("type", 1).put("pic", icon))))
    val json = JSONObject().put("param_v2", params).toString()
    if (json.toByteArray(Charsets.UTF_8).size > 3_072) return null
    return Bundle().apply {
        putString("miui.focus.param", json)
        putBundle("miui.focus.actions", actionData)
        putBundle("miui.focus.pics", Bundle().apply {
            putParcelable(icon, Icon.createWithResource(context, if (running) R.drawable.ic_island_timer else R.drawable.ic_island_pause))
        })
    }
}
