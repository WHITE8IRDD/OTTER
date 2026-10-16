package com.otter.app.engine

/**
 * A fetch failure whose text was written for the user, not for the log.
 *
 * The UI classifies a raw extractor error and replaces it with a canned, localised hint — which is
 * right for `ERROR: [youtube] Sign in to confirm you're not a bot` and wrong for a message this app
 * composed itself after already doing the diagnosis (an Instagram post whose slides could not be
 * read, a page that advertises a video it will not hand over). Wrapping those makes the difference
 * explicit instead of relying on the classifier to recognise its own sentences.
 */
class FetchUserMessage(message: String) : IllegalStateException(message)
