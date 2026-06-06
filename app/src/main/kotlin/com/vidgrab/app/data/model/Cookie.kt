package com.vidgrab.app.data.model

data class Cookie(
    val domain: String = "",
    val name: String = "",
    val value: String = "",
    val includeSubdomains: Boolean = true,
    val path: String = "/",
    val secure: Boolean = true,
    val expiry: Long = 0L
) {
    fun toNetscapeCookieString(): String {
        return listOf(
            domain,
            if (includeSubdomains) "TRUE" else "FALSE",
            path,
            if (secure) "TRUE" else "FALSE",
            expiry.toString(),
            name,
            value
        ).joinToString("\u0009")
    }
}
