package com.hermes.assistant

import android.content.Context
import android.provider.ContactsContract

/**
 * Looks up a phone number from a spoken contact name, e.g. "dad", "mom", "rohan".
 * Requires READ_CONTACTS permission (checked by the caller before use).
 */
object ContactResolver {

    data class Match(val displayName: String, val phoneNumber: String)

    fun findBestMatch(context: Context, spokenName: String): Match? {
        val needle = spokenName.trim().lowercase()
        if (needle.isEmpty()) return null

        val resolver = context.contentResolver
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        var exact: Match? = null
        var partial: Match? = null

        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx) ?: continue
                val number = cursor.getString(numIdx) ?: continue
                val lowerName = name.lowercase()

                if (lowerName == needle && exact == null) {
                    exact = Match(name, number)
                } else if (lowerName.contains(needle) && partial == null) {
                    partial = Match(name, number)
                }
            }
        }

        return exact ?: partial
    }
}
