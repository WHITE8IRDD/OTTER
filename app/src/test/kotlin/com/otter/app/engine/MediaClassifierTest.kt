package com.otter.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a media URL is, and how far up the result list it belongs.
 *
 * The behaviour under test is the one users hit by pasting a video page: the picker answered with
 * dozens of page pictures and no video, because a scraper that can only find `img` tags returns
 * whatever the page decorates itself with. Ranking has to be able to tell "this page's own video"
 * from "an avatar", "a tracking pixel" and "a reduced copy of the poster".
 */
class MediaClassifierTest {

    private fun candidate(
        url: String,
        source: MediaClassifier.Source,
        width: Int = 0,
        height: Int = 0,
        declared: Boolean = false
    ) = MediaClassifier.Candidate(
        url = url,
        kind = MediaClassifier.kindOf(url, source, width, height),
        source = source,
        width = width,
        height = height,
        declaredByPage = declared
    )

    @Test
    fun extensionIsReadFromThePathAndIgnoresTheQueryString() {
        assertEquals("mp4", MediaClassifier.extension("https://cdn.example/v/9/clip.mp4?sig=abc&oe=1"))
        assertEquals("", MediaClassifier.extension("https://cdn.example/v/9/clip"))
        assertEquals("jpg", MediaClassifier.extension("https://cdn.example/a.JPG"))
    }

    @Test
    fun manifestsAreMediaButNeverDirectlyDownloadable() {
        val manifest = "https://cdn.example/live/master.m3u8"

        assertTrue(MediaClassifier.isManifestUrl(manifest))
        assertFalse(
            "a plain GET of a manifest writes a playlist of text, not a video",
            MediaClassifier.isDirectlyDownloadable(manifest)
        )
        assertTrue(MediaClassifier.isManifestUrl("https://cdn.example/v/1.mp4?format=m3u8&sig=x"))
    }

    @Test
    fun pageFurnitureIsRejectedWhateverItLooksLike() {
        listOf(
            "https://static.example/icons/close.svg",
            "https://www.example.com/avatar/user-150x150.jpg",
            "https://www.example.com/tracking/pixel.gif",
            "https://scontent.cdninstagram.com/v/t51.2885-19/profile_pic1.jpg"
        ).forEach { url ->
            assertTrue("decorative: $url", MediaClassifier.isDecorative(url))
            assertEquals(
                "unusable: $url",
                MediaClassifier.MediaKind.UNUSABLE,
                MediaClassifier.kindOf(url, MediaClassifier.Source.IMAGE_TAG)
            )
        }
    }

    @Test
    fun smallCopiesAreThumbnailsButSlidesOfACarouselAreNot() {
        val thumb = "https://cdn.example/i/photo-90x90.jpg"

        assertTrue("a 90px copy is a thumbnail", MediaClassifier.looksLikeThumbnail(thumb))
        assertEquals(
            MediaClassifier.MediaKind.THUMBNAIL,
            MediaClassifier.kindOf(thumb, MediaClassifier.Source.IMAGE_TAG, 90, 90)
        )
        // The same small file listed as a slide of the post itself is content, not decoration.
        assertEquals(
            "a carousel slide is the post's own media even when the source only offers a small copy",
            MediaClassifier.MediaKind.GALLERY_IMAGE,
            MediaClassifier.kindOf(thumb, MediaClassifier.Source.CAROUSEL, 90, 90)
        )
        // A name that says "thumbnail" is enough on its own, whatever size the page claims.
        assertTrue(
            MediaClassifier.looksLikeThumbnail("https://cdn.example/i/clip-thumb.jpg")
        )
    }

    @Test
    fun declaredVideoWinsOverPicturesAndIsPromotedToPrimary() {
        val page = "https://example.org/watch/summer-trip-2024"
        val ranked = MediaClassifier.rank(
            listOf(
                candidate("https://example.org/static/logo.png", MediaClassifier.Source.IMAGE_TAG, 64, 64),
                candidate("https://example.org/img/summer-poster.jpg", MediaClassifier.Source.IMAGE_TAG, 1200, 900),
                candidate("https://example.org/media/summer-trip-2024.mp4", MediaClassifier.Source.DECLARED)
            ),
            pageUrl = page
        )

        assertEquals(
            MediaClassifier.MediaKind.PRIMARY_VIDEO,
            ranked.first().kind
        )
        assertTrue(MediaClassifier.isVideoPrimary(ranked))
        assertEquals("https://example.org/media/summer-trip-2024.mp4", ranked.first().url)
    }

    @Test
    fun aManifestIsNotPromotedToThePrimaryKind() {
        val ranked = MediaClassifier.rank(
            listOf(
                candidate("https://cdn.example/stream/master.m3u8", MediaClassifier.Source.MANIFEST_LINK),
                candidate("https://cdn.example/img/still.jpg", MediaClassifier.Source.IMAGE_TAG, 1280, 720)
            )
        )

        assertEquals(MediaClassifier.MediaKind.MANIFEST, ranked.first().kind)
        assertFalse(MediaClassifier.isVideoPrimary(ranked))
    }

    @Test
    fun duplicatesThatDifferOnlyByDeliveryEdgeAreMerged() {
        val ranked = MediaClassifier.rank(
            listOf(
                candidate("https://cdn.example/v/clip.mp4?sig=one&oe=1", MediaClassifier.Source.VIDEO_TAG),
                candidate("https://cdn.example/v/clip.mp4?sig=two&oe=9", MediaClassifier.Source.VIDEO_TAG)
            )
        )

        assertEquals(1, ranked.size)
        assertEquals("https://cdn.example/v/clip.mp4?sig=one&oe=1", ranked.first().url)
    }

    @Test
    fun equalScoresKeepTheOrderThePageListedThemIn() {
        val ranked = MediaClassifier.rank(
            listOf(
                "https://cdn.example/album/one.jpg",
                "https://cdn.example/album/two.jpg",
                "https://cdn.example/album/three.jpg"
            ).map { candidate(it, MediaClassifier.Source.CAROUSEL, 1080, 1080) }
        )

        assertEquals(
            listOf(
                "https://cdn.example/album/one.jpg",
                "https://cdn.example/album/two.jpg",
                "https://cdn.example/album/three.jpg"
            ),
            ranked.map { it.url }
        )
    }

    @Test
    fun unusableCandidatesAreDroppedAndAPageOfPicturesIsNotAVideo() {
        val ranked = MediaClassifier.rank(
            listOf(
                candidate("https://cdn.example/icons/arrow.svg", MediaClassifier.Source.RAW),
                candidate("https://cdn.example/album/beach.jpg", MediaClassifier.Source.CAROUSEL, 1080, 1350)
            )
        )

        assertEquals(1, ranked.size)
        assertFalse(MediaClassifier.isVideoPrimary(ranked))
    }

    @Test
    fun aPathThatRepeatsThePageSlugIsTreatedAsThePagesOwnMedia() {
        val media = candidate("https://cdn.example/files/summer-trip-2024.mp4", MediaClassifier.Source.RAW)
        val matching = MediaClassifier.score(media, pageUrl = "https://example.org/watch/summer-trip-2024")
        val unrelated = MediaClassifier.score(media, pageUrl = "https://example.org/watch/something-else")

        assertTrue(
            "the page's own file name should out-rank an unrelated one ($matching vs $unrelated)",
            matching > unrelated
        )
    }

    @Test
    fun audioAndVideoAreToldApartByTheirOwnExtensions() {
        assertEquals(
            MediaClassifier.MediaKind.AUDIO,
            MediaClassifier.kindOf("https://cdn.example/track.mp3", MediaClassifier.Source.STRUCTURED)
        )
        assertEquals(
            MediaClassifier.MediaKind.VIDEO,
            MediaClassifier.kindOf("https://cdn.example/clip.webm", MediaClassifier.Source.STRUCTURED)
        )
        assertEquals(
            MediaClassifier.MediaKind.UNUSABLE,
            MediaClassifier.kindOf("https://cdn.example/page.html", MediaClassifier.Source.RAW)
        )
    }

    @Test
    fun identityIsHostAndPathOnly() {
        assertEquals(
            MediaClassifier.identityOf("https://CDN.example.org/v/clip.mp4?sig=1"),
            MediaClassifier.identityOf("https://cdn.example.org/v/clip.mp4?sig=2")
        )
    }
}
