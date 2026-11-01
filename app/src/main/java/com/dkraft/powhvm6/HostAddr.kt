package com.dkraft.powhvm6

/** Normalizes what the user types (or discovery finds) into "host:port". */
object HostAddr {
    const val DEFAULT_PORT = 8088

    /**
     * Accepts "10.0.0.69", "10.0.0.69:8088", "http://10.0.0.69", "http://10.0.0.69:8088/"
     * (any path, whitespace, or http/https scheme is dropped). A missing or invalid port
     * becomes [DEFAULT_PORT]. Returns "" for blank input.
     */
    fun normalize(input: String, defaultPort: Int = DEFAULT_PORT): String {
        var s = input.trim()
        if (s.isEmpty()) return ""
        s = s.replaceFirst(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://"), "")
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringAfterLast('@')
        if (s.isEmpty()) return ""
        val host: String
        var port: Int? = null
        if (s.startsWith("[")) {
            // [ipv6]:port
            host = s.substringBefore(']') + "]"
            port = s.substringAfter("]", "").removePrefix(":").toIntOrNull()
        } else if (s.count { it == ':' } == 1) {
            host = s.substringBefore(':')
            port = s.substringAfter(':').toIntOrNull()
        } else {
            host = s
        }
        if (host.isEmpty() || host == "[]") return ""
        val p = port?.takeIf { it in 1..65535 } ?: defaultPort
        return "$host:$p"
    }

    /** Host part only, without the port. */
    fun hostOnly(addr: String): String {
        val n = normalize(addr)
        return if (n.isEmpty()) "" else n.substringBeforeLast(':')
    }
}
