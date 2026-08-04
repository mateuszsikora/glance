package com.glance.content

import java.time.DayOfWeek

/**
 * Day-of-week helpers shared by the settings screen, the remote panel and the text format.
 * An empty day set always means "every day" and is rendered without a prefix.
 */
object WeekDays {
    /** Monday first, matching [DayOfWeek] ordering. */
    val ALL: List<DayOfWeek> = DayOfWeek.values().toList()

    /** Selecting the whole week is the same as selecting nothing, and stores as "daily". */
    fun normalize(days: Set<DayOfWeek>): Set<DayOfWeek> =
        if (days.size == ALL.size) emptySet() else days

    fun shortName(day: DayOfWeek): String =
        day.name.lowercase().replaceFirstChar(Char::uppercase).take(SHORT_NAME_LENGTH)

    /**
     * Parses a day specification such as `Mon-Fri`, `Sat,Sun`, `weekend` or an empty string.
     * Returns null when the specification cannot be understood, and an empty set when it covers
     * the whole week.
     */
    fun parse(spec: String): Set<DayOfWeek>? {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) return emptySet()

        val days = sortedSetOf<DayOfWeek>()
        trimmed.split(',').forEach { part ->
            val token = part.trim()
            if (token.isEmpty()) return null
            days.addAll(parseAlias(token) ?: parseRange(token) ?: return null)
        }
        return normalize(days)
    }

    /** Renders [days] as a prefix, collapsing consecutive days into ranges. Empty means daily. */
    fun format(days: Set<DayOfWeek>): String {
        if (days.isEmpty() || days.size == ALL.size) return ""

        val runs = mutableListOf<MutableList<DayOfWeek>>()
        ALL.filter { it in days }.forEach { day ->
            val current = runs.lastOrNull()
            if (current != null && current.last().plus(1L) == day) {
                current.add(day)
            } else {
                runs.add(mutableListOf(day))
            }
        }
        return runs.joinToString(",") { run ->
            when (run.size) {
                1 -> shortName(run.first())
                2 -> "${shortName(run.first())},${shortName(run.last())}"
                else -> "${shortName(run.first())}-${shortName(run.last())}"
            }
        }
    }

    private fun parseAlias(token: String): List<DayOfWeek>? = when (token.lowercase()) {
        "daily", "everyday" -> ALL
        "weekdays" -> ALL - WEEKEND
        "weekend", "weekends" -> WEEKEND.toList()
        else -> null
    }

    private fun parseRange(token: String): List<DayOfWeek>? {
        val bounds = token.split('-')
        if (bounds.size > 2) return null
        val first = parseDay(bounds.first()) ?: return null
        if (bounds.size == 1) return listOf(first)
        val last = parseDay(bounds[1]) ?: return null
        // Ranges may wrap around the end of the week, e.g. Fri-Mon.
        val span = Math.floorMod(last.value - first.value, ALL.size)
        return (0..span).map { first.plus(it.toLong()) }
    }

    private fun parseDay(token: String): DayOfWeek? {
        val normalized = token.trim().lowercase()
        if (normalized.length < SHORT_NAME_LENGTH) return null
        return ALL.firstOrNull { it.name.lowercase().startsWith(normalized) }
    }

    private const val SHORT_NAME_LENGTH = 3
    private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
}
