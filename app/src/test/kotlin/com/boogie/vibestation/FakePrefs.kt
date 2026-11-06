package com.boogie.vibestation

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk

/**
 * A SharedPreferences stand-in for string values backed by a plain map, so tests can read back what
 * the code under test wrote. [values] is the live storage; edit it to simulate writes from elsewhere.
 */
internal fun fakePrefs(values: MutableMap<String, String> = mutableMapOf()): Pair<SharedPreferences, MutableMap<String, String>> {
    val prefs = mockk<SharedPreferences>()
    val editor = mockk<SharedPreferences.Editor>()
    every { prefs.getString(any(), any()) } answers { values[firstArg()] ?: secondArg() }
    every { prefs.edit() } returns editor
    every { editor.putString(any(), any()) } answers {
        values[firstArg()] = secondArg()
        editor
    }
    every { editor.apply() } returns Unit
    return prefs to values
}
