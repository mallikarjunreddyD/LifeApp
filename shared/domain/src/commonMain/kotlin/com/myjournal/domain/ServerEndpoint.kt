package com.myjournal.domain

/** A validated HTTPS origin. No requests are performed by this configuration type. */
class ServerEndpoint private constructor(val origin: String) {
    companion object {
        private val originPattern = Regex("https://([a-zA-Z0-9.-]{1,253})(?::([0-9]{1,5}))?/?")
        private val labelPattern = Regex("[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?")

        fun parse(value: String): ServerEndpoint {
            val match = requireNotNull(originPattern.matchEntire(value)) { "Enter an HTTPS origin without credentials or a path" }
            val host = match.groupValues[1].lowercase()
            val labels = host.split('.')
            require(labels.size > 1 && labels.all(labelPattern::matches))
            if (labels.last().first().isDigit()) {
                require(labels.size == 4 && labels.all { it.toIntOrNull() in 0..255 && it.toIntOrNull().toString() == it })
            }
            val port = match.groupValues[2].takeIf(String::isNotEmpty)?.toInt()
            require(port == null || port in 1..65535)
            return ServerEndpoint("https://$host" + if (port == null || port == 443) "" else ":$port")
        }
    }
}

sealed interface ServerConnection {
    data object Unconfigured : ServerConnection
    data class Configured(val endpoint: ServerEndpoint) : ServerConnection
}
