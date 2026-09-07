package app.lia.android.backend

/**
 * URL normalising and the transport-security policy for the home server
 * (plan 3.1), matching the desktop's rules exactly.
 *
 * Android permits cleartext app-wide (a `<domain-config>` cannot express an IP
 * range, and Tailscale hands out 100.64.0.0/10 literals), so this class is the
 * real gate: plain ws:// is allowed ONLY to a private host.
 */
object WsUrl {

    data class Parsed(val url: String, val scheme: String, val host: String, val port: Int)

    const val DEFAULT_PORT = 9090

    /** Accepts `host`, `host:port`, `ws://`, `wss://`, `http://`, `https://`. */
    fun normalize(input: String): Parsed? {
        var text = input.trim()
        if (text.isEmpty()) return null
        var scheme = "ws"
        val schemeSplit = text.indexOf("://")
        if (schemeSplit > 0) {
            scheme = when (text.substring(0, schemeSplit).lowercase()) {
                "ws", "http" -> "ws"
                "wss", "https" -> "wss"
                else -> return null
            }
            text = text.substring(schemeSplit + 3)
        }
        text = text.trimEnd('/')
        if (text.isEmpty()) return null
        // Strip any path or query - the server speaks at the root.
        text = text.substringBefore('/').substringBefore('?')
        var host = text
        var port = if (scheme == "wss") 443 else DEFAULT_PORT
        if (text.startsWith("[")) {                       // [ipv6]:port
            val close = text.indexOf(']')
            if (close < 0) return null
            host = text.substring(0, close + 1)
            val rest = text.substring(close + 1)
            if (rest.startsWith(":")) port = rest.drop(1).toIntOrNull() ?: return null
        } else if (text.count { it == ':' } == 1) {
            val parts = text.split(':')
            host = parts[0]
            port = parts[1].toIntOrNull() ?: return null
        }
        if (host.isEmpty()) return null
        return Parsed("$scheme://$host:$port", scheme, host, port)
    }

    /** A bind the desktop allows without a token; anywhere else one is required. */
    fun isLoopback(rawHost: String): Boolean {
        val host = rawHost.trim().trim('[', ']').lowercase()
        return host == "localhost" || host == "::1" || host.startsWith("127.")
    }

    /**
     * Loopback, RFC1918, link-local, Tailscale's CGNAT range, and the .local /
     * .ts.net name suffixes. Anything else is "public" for this purpose.
     */
    fun isPrivateHost(rawHost: String): Boolean {
        val host = rawHost.trim().trim('[', ']').lowercase()
        if (host.isEmpty()) return false
        if (host == "localhost" || host == "::1" || host.endsWith(".localhost")) return true
        if (host.endsWith(".local") || host.endsWith(".ts.net")) return true
        val octets = host.split('.')
        if (octets.size == 4 && octets.all { it.toIntOrNull() in 0..255 }) {
            val a = octets[0].toInt()
            val b = octets[1].toInt()
            return when {
                a == 127 -> true                       // loopback
                a == 10 -> true                        // RFC1918
                a == 172 && b in 16..31 -> true        // RFC1918
                a == 192 && b == 168 -> true           // RFC1918
                a == 169 && b == 254 -> true           // link-local
                a == 100 && b in 64..127 -> true       // CGNAT / Tailscale
                else -> false
            }
        }
        // fc00::/7 unique-local and fe80::/10 link-local
        if (host.contains(':')) {
            return host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe8") ||
                host.startsWith("fe9") || host.startsWith("fea") || host.startsWith("feb")
        }
        return false
    }

    sealed interface Policy {
        data class Allowed(val parsed: Parsed) : Policy
        data class Insecure(val parsed: Parsed, val warning: String) : Policy
        data class Refused(val reason: String) : Policy
    }

    fun check(input: String, allowInsecure: Boolean = false): Policy {
        val parsed = normalize(input)
            ?: return Policy.Refused("That does not look like a server address.")
        if (parsed.scheme == "wss") return Policy.Allowed(parsed)
        if (isPrivateHost(parsed.host)) return Policy.Allowed(parsed)
        val message = "Refusing plaintext ws:// to public host ${parsed.host} - " +
            "use wss://, or reach the server over Tailscale."
        return if (allowInsecure) {
            Policy.Insecure(parsed, "INSECURE: $message")
        } else {
            Policy.Refused(message)
        }
    }
}
