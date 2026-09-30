package com.worldexecute.viewer

import java.security.SecureRandom

/**
 * The secret behind a shared address.
 *
 * Turning on LAN sharing means opening a socket that anyone on the network can
 * reach, and what is behind it is a file server. The socket alone would be the
 * whole security model. So a shared server also lives under a path segment that
 * is generated at random when sharing is switched on and forgotten when it is
 * switched off: the QR code is the only place the address is written down, and
 * turning sharing off revokes it.
 *
 * Pure string and PRNG logic, no Android — which is what lets the gate in
 * [AssetServer] be unit-tested without a device.
 */
object Share {

    /**
     * Ambiguous glyphs are left out on purpose. This string ends up in a URL a
     * human may end up reading aloud or retyping if the camera will not focus,
     * and `0`/`O` and `1`/`l`/`I` are where that goes wrong.
     */
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /** 32^8 ≈ 1.1e12: far beyond guessing over HTTP on a phone. */
    const val TOKEN_LENGTH = 8

    private val random = SecureRandom()

    /** A fresh token. [from] exists so tests can be deterministic. */
    fun newToken(from: java.util.Random = random): String =
        buildString(TOKEN_LENGTH) {
            repeat(TOKEN_LENGTH) { append(ALPHABET[from.nextInt(ALPHABET.length)]) }
        }

    /** The path segment every request must sit under. "/" when not sharing. */
    fun prefix(token: String?): String =
        if (token.isNullOrBlank()) "/" else "/s/$token/"

    /**
     * Maps a request path through the share prefix, returning the path relative
     * to the document root, or null when the request did not carry the token.
     *
     * Null must be treated as "refuse", never as "fall through to the default":
     * the whole point is that a request without the token reaches no file. The
     * bare `/s/TOKEN` (no trailing slash) is accepted as the root so a
     * hand-typed address still lands on the film rather than a 404.
     */
    fun strip(token: String?, path: String): String? {
        if (token.isNullOrBlank()) return path
        val prefix = prefix(token)
        if (path == prefix.dropLast(1)) return "/"
        if (!path.startsWith(prefix)) return null
        return "/" + path.substring(prefix.length)
    }
}
