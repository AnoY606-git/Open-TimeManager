package dev.opentimemanager.core.export

import dev.opentimemanager.core.model.*
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class CsvExporterTest {
    private val header = "segment_id,entry_id,project_id,project_name,start_utc,end_utc,zone_id,duration_seconds,note,source\r\n"

    private fun record(projectName: String = "Работа", note: String = "", duration: Long = 1234) = ExportRecord(
        Segment(SegmentId("s"), EntryId("e"), ProjectId("p"), 0, duration),
        projectName, note, ZoneId.of("Europe/Moscow"),
    )

    private fun csv(vararg records: ExportRecord): String = StringBuilder().also {
        CsvExporter.write(records.asSequence(), it)
    }.toString()

    // Переименование/перестановка колонок сломает существующих потребителей формата.
    @Test fun `empty export still contains stable header`() {
        assertEquals(header, csv())
    }

    // Использование локального времени или округление до целых секунд потеряет данные.
    @Test fun `closed segment uses UTC instants zone id and exact seconds`() {
        assertEquals(header + "s,e,p,Работа,1970-01-01T00:00:00Z,1970-01-01T00:00:01.234Z,Europe/Moscow,1.234,,TIMER\r\n", csv(record()))
    }

    @Test fun `commas quotes CR and LF use RFC4180 quoting`() {
        assertEquals(header + "s,e,p,\"Проект, \"\"А\"\"\",1970-01-01T00:00:00Z,1970-01-01T00:00:01Z,Europe/Moscow,1,\"строка\r\nещё\",TIMER\r\n",
            csv(record("Проект, \"А\"", "строка\r\nещё", 1000)))
    }

    @Test fun `spreadsheet formula prefixes receive apostrophe in text cells`() {
        for (prefix in listOf("=", "+", "-", "@", "  =", "\t+")) {
            val row = csv(record(prefix + "SUM(1)", prefix + "note")).removePrefix(header)
            assertTrue(row, row.startsWith("s,e,p,'${prefix}SUM(1),"))
            assertTrue(row, row.endsWith(",'${prefix}note,TIMER\r\n"))
        }
        val ids = ExportRecord(Segment(SegmentId("=s"), EntryId("+e"), ProjectId("@p"), 0, 1000), "ok", "ok", ZoneId.of("UTC"))
        assertTrue(csv(ids).removePrefix(header).startsWith("'=s,'+e,'@p,ok,"))
    }

    @Test fun `decimal seconds are independent of default locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals(header + "s,e,p,Работа,1970-01-01T00:00:00Z,1970-01-01T00:00:01.234Z,Europe/Moscow,1.234,,TIMER\r\n", csv(record()))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun `records retain sequence order and output failures propagate`() {
        val result = csv(record("first"), record("second"))
        assertTrue(result.indexOf(",first,") < result.indexOf(",second,"))
        val output = object : Appendable {
            override fun append(csq: CharSequence?): Appendable = throw java.io.IOException("full")
            override fun append(csq: CharSequence?, start: Int, end: Int): Appendable = append(csq)
            override fun append(c: Char): Appendable = append(c.toString())
        }
        assertThrows(java.io.IOException::class.java) { CsvExporter.write(sequenceOf(record()), output) }
    }

    @Test fun `manual and imported sources retain their canonical names`() {
        assertTrue(csv(record().copy(source = EntrySource.MANUAL)).endsWith(",MANUAL\r\n"))
        assertTrue(csv(record().copy(source = EntrySource.IMPORT)).endsWith(",IMPORT\r\n"))
    }
}
