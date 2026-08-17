package com.vscodroid.webview

import java.net.URLDecoder

/**
 * The name offered when nothing usable can be read off the download.
 *
 * Extension-less, because reaching here means nothing knew one. The picker
 * creates the file under exactly the name it is given, so an extension invented
 * from the MIME type would be a guess the user cannot see is a guess, while a
 * bare name is visibly incomplete in a field they are already looking at.
 */
internal const val FALLBACK_DOWNLOAD_NAME = "download"

/**
 * Longest name offered to the picker, chosen against the 255-byte limit most
 * filesystems impose rather than against anything Android checks.
 */
private const val MAX_NAME_LENGTH = 200

/** Space is the first printable character; DEL is the one that follows them. */
private const val FIRST_PRINTABLE = 0x20
private const val DELETE_CHAR = 0x7F

private val CONTENT_DISPOSITION_EXTENDED =
    Regex("""filename\*\s*=\s*[^']*'[^']*'([^;]+)""", RegexOption.IGNORE_CASE)

private val CONTENT_DISPOSITION_PLAIN =
    Regex("""filename\s*=\s*"([^"]*)"|filename\s*=\s*([^;"]+)""", RegexOption.IGNORE_CASE)

/**
 * Picks the name a downloaded file is offered to the user under.
 *
 * Four sources, tried in the order of how much each one knows, because on this
 * platform the richest of them is also the one most likely to be absent.
 *
 * [suggested] is the `download` attribute of the anchor the editor clicked,
 * read back out of the page. It is the only source that carries the name the
 * user is expecting, and every other source below is a consolation prize: the
 * editor builds its anchor over a `blob:` URL whose text is a UUID, and a blob
 * has no name to ask for. Whether the attribute reaches the platform download
 * listener at all is not something this code can decide, which is why it is
 * passed in rather than read from [contentDisposition].
 *
 * [contentDisposition] is a real header and is honoured when present, extended
 * form first: `filename*` carries the encoding and survives non-ASCII names,
 * while `filename` is defined over a byte range that does not.
 *
 * The URL is asked twice and the two questions are not the same one. A resource
 * served by the editor's remote-resource endpoint carries the file's POSIX path
 * in a `path` query parameter, so the basename of *that* is the real name;
 * looking at the URL's own last segment there would answer
 * `vscode-remote-resource` for every file in the workspace. Only when there is
 * no `path` parameter does the last path segment get asked, and then only if it
 * looks like a filename, which is what keeps a `blob:` UUID out.
 *
 * Every candidate goes through [safeFileName] and a candidate that does not
 * survive it falls through to the next, rather than being handed on empty. That
 * is the difference between a bad name and no name: the picker shows this in an
 * editable field, so a wrong guess costs the user a retype, while an empty one
 * offers a file that cannot be created.
 */
internal fun downloadFileName(
    url: String,
    contentDisposition: String?,
    suggested: String?,
): String =
    safeFileName(suggested)
        ?: safeFileName(fileNameFromContentDisposition(contentDisposition))
        ?: safeFileName(pathParameterBaseName(url))
        ?: safeFileName(lastPathSegment(url)?.takeIf { it.contains('.') })
        ?: FALLBACK_DOWNLOAD_NAME

/**
 * Reduces a candidate to a name that names one file, or null when nothing is
 * left of it.
 *
 * The separator strip is the part that matters and it runs on every candidate,
 * including the `download` attribute, which is page-controlled text. A name
 * that survived with a separator in it would be handed to the create-document
 * picker as a path, and what a documents provider does with one is the
 * provider's decision rather than this app's. Taking the last segment is also
 * exactly right for the candidates that legitimately arrive as paths, so the
 * defensive reading and the useful one are the same operation.
 *
 * `.` and `..` are refused rather than trimmed, because they survive every
 * character-level rule here and name a directory rather than a file.
 */
internal fun safeFileName(candidate: String?): String? {
    val raw = candidate?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val basename = raw.substringAfterLast('/').substringAfterLast('\\')
    val cleaned = basename
        .filter { it.code >= FIRST_PRINTABLE && it.code != DELETE_CHAR }
        .trim()
        .take(MAX_NAME_LENGTH)
        .trim()
    if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return null
    return cleaned
}

private fun fileNameFromContentDisposition(header: String?): String? {
    if (header.isNullOrEmpty()) return null
    CONTENT_DISPOSITION_EXTENDED.find(header)?.groupValues?.get(1)?.let {
        return percentDecode(it.trim())
    }
    val plain = CONTENT_DISPOSITION_PLAIN.find(header) ?: return null
    return plain.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.trim()
}

/**
 * The basename of the `path` query parameter, which is how the editor's
 * remote-resource endpoint names the file it is serving.
 */
private fun pathParameterBaseName(url: String): String? {
    val query = url.substringBefore('#').substringAfter('?', "")
    if (query.isEmpty()) return null
    val value = query.split('&')
        .firstOrNull { it.startsWith("path=") }
        ?.removePrefix("path=")
        ?: return null
    return percentDecode(value.replace('+', ' '))
}

private fun lastPathSegment(url: String): String? =
    url.substringBefore('#')
        .substringBefore('?')
        .substringAfterLast('/')
        .takeIf { it.isNotEmpty() }
        ?.let { percentDecode(it) }

/**
 * Percent-decoding that answers the input when the input is not valid encoding.
 *
 * A name is a display string rather than a protocol field, so a stray `%` in
 * one is a character and not a syntax error. The decoder throws on it, and the
 * throw would travel out of a naming helper and cancel a download over a
 * filename.
 */
private fun percentDecode(value: String): String =
    try {
        // Percent-escapes only. `URLDecoder` also turns `+` into a space, which
        // is the rule for a query parameter and wrong everywhere else: a path
        // segment and a Content-Disposition filename both carry `+` as itself,
        // so `c++notes.txt` would arrive as `c  notes.txt`. Escaping it first
        // makes the decoder hand it back unchanged, and the one caller that
        // does want the query rule applies it before calling here.
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    } catch (e: IllegalArgumentException) {
        value
    }
