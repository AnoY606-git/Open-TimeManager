package dev.opentimemanager.core.export

import dev.opentimemanager.core.model.Segment
import dev.opentimemanager.core.model.EntrySource
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

data class ExportRecord(val segment: Segment, val projectName: String, val note: String, val zoneId: ZoneId, val source: EntrySource = EntrySource.TIMER)

object CsvExporter {
    fun write(records: Sequence<ExportRecord>, output: Appendable) {
        output.append("segment_id,entry_id,project_id,project_name,start_utc,end_utc,zone_id,duration_seconds,note,source\r\n")
        for (record in records) {
            val segment = record.segment
            val cells = listOf(
                segment.id.value, segment.entryId.value, segment.projectId.value, record.projectName,
                Instant.ofEpochMilli(segment.startUtcMillis).toString(),
                Instant.ofEpochMilli(segment.endUtcMillis).toString(), record.zoneId.id,
                BigDecimal.valueOf(segment.durationMillis, 3).stripTrailingZeros().toPlainString(), record.note, record.source.name,
            )
            output.append(cells.joinToString(",", transform = ::cell)).append("\r\n")
        }
    }

    private fun cell(value: String): String {
        // Табуляция/пробелы перед формулой тоже могут интерпретироваться таблицами.
        val safe = if (value.firstOrNull { !it.isWhitespace() } in listOf('=', '+', '-', '@')) "'$value" else value
        return if (safe.any { it == ',' || it == '"' || it == '\r' || it == '\n' })
            "\"${safe.replace("\"", "\"\"")}\"" else safe
    }
}
