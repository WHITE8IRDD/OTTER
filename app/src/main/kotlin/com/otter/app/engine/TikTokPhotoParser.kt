package com.otter.app.engine

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.Locale

/**
 * Extracts TikTok photo-mode slides from the public page hydration payload.
 *
 * TikTok's yt-dlp extractor is the primary route for playable videos. Photo-mode
 * posts are different: their `imagePost.images` payload contains still-image CDN
 * URLs rather than a video format, so a video-only extractor can return no formats
 * even when the post is public. This parser deliberately reads only that imagePost
 * field (never the cover/poster or suggested-post thumbnails) and preserves signed
 * CDN query parameters.
 */
object TikTokPhotoParser {
    private const val MAX_IMAGES = 40
    private const val MAX_JSON_NODES = 40_000

    private val scriptTag = Regex("""(?is)<script\b([^>]*)>(.*?)</script\s*>""")
    private val idAttribute = Regex("""(?is)\bid\s*=\s*["']([^"']+)["']""")
    private val typeAttribute = Regex("""(?is)\btype\s*=\s*["']([^"']+)["']""")
    private val knownPagePayload = setOf(
        "__universal_data_for_rehydration__",
        "sigi_state",
        "sigi-persisted-data"
    )
    private val videoIdPattern = Regex("""/(?:photo|video)/(\d+)""", RegexOption.IGNORE_CASE)
    private val unicodeEscape = Regex("""\\u([0-9a-fA-F]{4})""")

    data class Result(
        val imageUrls: List<String> = emptyList(),
        /** Number of image records in the page, including any whose URL was unreadable. */
        val expectedCount: Int = 0,
        val title: String = "",
        val description: String = "",
        val uploader: String = ""
    ) {
        val isComplete: Boolean get() = imageUrls.isNotEmpty() && imageUrls.size >= expectedCount
        val isPartial: Boolean get() = imageUrls.isNotEmpty() && !isComplete
    }

    /** Parse the public hydration scripts in a TikTok page. */
    fun parseHtml(html: String, pageUrl: String = ""): Result {
        var best = Result()
        for (match in scriptTag.findAll(html)) {
            val attributes = match.groupValues[1]
            val id = idAttribute.find(attributes)?.groupValues?.getOrNull(1)
                ?.lowercase(Locale.ROOT)
            val type = typeAttribute.find(attributes)?.groupValues?.getOrNull(1)
                ?.lowercase(Locale.ROOT)
            if (id !in knownPagePayload && type != "application/json") continue

            val parsed = parseJson(match.groupValues[2], pageUrl)
            if (parsed.imageUrls.size > best.imageUrls.size) best = parsed
            if (best.imageUrls.size >= best.expectedCount && best.expectedCount > 0) return best
        }
        return best
    }

    /** Parse a TikTok hydration JSON object or array. */
    fun parseJson(json: String, pageUrl: String = ""): Result {
        val root: Any = runCatching { JSONObject(json) }.getOrNull()
            ?: runCatching { JSONArray(json) }.getOrNull()
            ?: return Result()
        val targetId = videoIdPattern.find(runCatching { URL(pageUrl).path }.getOrDefault(""))
            ?.groupValues?.getOrNull(1).orEmpty()
        val owner = findImagePostOwner(root, targetId) ?: return Result()
        val imagePost = imagePostObject(owner) ?: return Result()
        val images = imagePost.optJSONArray("images") ?: imagePost.optJSONArray("image_list")
            ?: return Result()

        val urls = LinkedHashSet<String>()
        for (index in 0 until minOf(images.length(), MAX_IMAGES)) {
            val image = images.optJSONObject(index) ?: continue
            imageUrl(image)?.let(urls::add)
        }

        val description = firstNonBlank(
            owner.optString("desc", ""),
            owner.optString("description", ""),
            owner.optString("title", "")
        )
        val author = owner.optJSONObject("authorInfo") ?: owner.optJSONObject("author")
        val uploader = firstNonBlank(
            author?.optString("uniqueId", "").orEmpty(),
            author?.optString("unique_id", "").orEmpty(),
            author?.optString("nickname", "").orEmpty()
        )

        return Result(
            imageUrls = urls.toList(),
            expectedCount = images.length(),
            title = description.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty(),
            description = description,
            uploader = uploader
        )
    }

    /**
     * True only for a photo-mode item, not the video's ordinary cover image.
     *
     * The page shape is `/@user/photo/<id>`, but forwarded links and web shares can
     * carry the `/photo/<id>` part without the `@user` prefix, so the marker is the
     * `/photo/<id>` segment itself. A `/video/` link is never photo mode.
     */
    fun isPhotoModeUrl(url: String): Boolean {
        if (!MediaUrlNormalizer.hostMatches(url, "tiktok.com") &&
            !MediaUrlNormalizer.hostMatches(url, "tiktokv.com")
        ) return false
        val path = runCatching { URL(url).path }.getOrNull() ?: return false
        return Regex("""(?:^|/)photo/\d+(?:/|$)""", RegexOption.IGNORE_CASE).containsMatchIn(path)
    }

    private fun findImagePostOwner(root: Any, targetId: String): JSONObject? {
        val queue = ArrayDeque<Any>()
        queue.add(root)
        var visited = 0
        var firstCandidate: JSONObject? = null

        while (queue.isNotEmpty() && visited++ < MAX_JSON_NODES) {
            when (val node = queue.removeFirst()) {
                is JSONObject -> {
                    if (imagePostObject(node) != null) {
                        if (firstCandidate == null) firstCandidate = node
                        if (targetId.isNotBlank() && nodeMatchesId(node, targetId)) return node
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        node.opt(keys.next())?.let { child ->
                            if (child is JSONObject || child is JSONArray) queue.addLast(child)
                        }
                    }
                }
                is JSONArray -> {
                    for (index in 0 until node.length()) {
                        node.opt(index)?.let { child ->
                            if (child is JSONObject || child is JSONArray) queue.addLast(child)
                        }
                    }
                }
            }
        }
        return firstCandidate
    }

    private fun nodeMatchesId(node: JSONObject, targetId: String): Boolean =
        listOf("id", "aweme_id", "awemeId", "itemId", "item_id")
            .any { key -> node.optString(key, "") == targetId }

    private fun imagePostObject(owner: JSONObject): JSONObject? {
        for (key in listOf("imagePost", "image_post")) {
            when (val post = owner.opt(key)) {
                is JSONObject -> if (post.optJSONArray("images") != null || post.optJSONArray("image_list") != null) return post
                is JSONArray -> return JSONObject().put("images", post)
            }
        }
        return null
    }

    private fun imageUrl(image: JSONObject): String? {
        for (key in listOf("imageURL", "imageUrl", "image_url", "displayImage", "display_image")) {
            val candidate = urlFromValue(image.opt(key))
            if (candidate != null) return candidate
        }
        return null
    }

    private fun urlFromValue(value: Any?): String? {
        val candidates = when (value) {
            is String -> listOf(value)
            is JSONArray -> (0 until value.length()).mapNotNull { index ->
                value.optString(index, "").takeIf { it.isNotBlank() }
            }
            is JSONObject -> {
                val urlArray = value.optJSONArray("urlList") ?: value.optJSONArray("url_list")
                    ?: value.optJSONArray("urls")
                if (urlArray != null) {
                    (0 until urlArray.length()).mapNotNull { index ->
                        urlArray.optString(index, "").takeIf { it.isNotBlank() }
                    }
                } else {
                    listOf("url", "src", "uri").mapNotNull { value.optString(it, "").takeIf(String::isNotBlank) }
                }
            }
            else -> emptyList()
        }
        return candidates.firstNotNullOfOrNull(::normalizeImageUrl)
    }

    private fun normalizeImageUrl(raw: String): String? {
        var decoded = raw.trim()
            .replace("\\/", "/")
            .replace("\\u002F", "/", ignoreCase = true)
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
        decoded = unicodeEscape.replace(decoded) { match ->
            (match.groupValues[1].toIntOrNull(16)?.toChar()?.toString()) ?: match.value
        }
        if (decoded.startsWith("//")) decoded = "https:$decoded"
        val withoutFragment = decoded.substringBefore('#')
        val parsed = runCatching { URL(withoutFragment) }.getOrNull() ?: return null
        if (parsed.protocol != "http" && parsed.protocol != "https") return null
        val host = parsed.host.lowercase(Locale.ROOT)
        if (!host.contains("tiktokcdn") && !host.contains("byteoversea") && !host.contains("ibytedtos")) return null
        return withoutFragment
    }

    private fun firstNonBlank(vararg values: String): String = values.firstOrNull { it.isNotBlank() }.orEmpty()
}
