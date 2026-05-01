package com.nostr.torinos.ui.components

private val webUrlRegex = Regex("""https?://\S+""")

fun extractWebUrls(content: String): List<String> =
    webUrlRegex.findAll(content)
        .map { it.value.trimUrlBoundary() }
        .filter { it.isNotBlank() }
        .distinct()
        .toList()

private fun String.trimUrlBoundary(): String =
    trimEnd('.', ',', ';', ':', ')', ']', '}', '>', '"', '\'')
