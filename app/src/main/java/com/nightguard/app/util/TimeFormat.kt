package com.nightguard.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Every timestamp in the app displays in Central Time. Uses the "America/Chicago" IANA
 * zone rather than a fixed UTC-6 offset, so it correctly follows CST/CDT rather than
 * drifting an hour off for half the year.
 */
object TimeFormat {
    private val CENTRAL = TimeZone.getTimeZone("America/Chicago")

    fun dateTime(): SimpleDateFormat = SimpleDateFormat("MMM d, yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = CENTRAL }

    fun shortDateTime(): SimpleDateFormat = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US).apply { timeZone = CENTRAL }

    fun timeOnly(): SimpleDateFormat = SimpleDateFormat("HH:mm zzz", Locale.US).apply { timeZone = CENTRAL }

    fun format(millis: Long, pattern: SimpleDateFormat): String = pattern.format(Date(millis))
}
