package com.myjournal.server

import com.myjournal.domain.ServerEndpoint
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.postgresql.ds.PGSimpleDataSource
import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    require(args.isEmpty() || (args.size == 1 && args[0] in setOf("init-owner", "enroll-owner-authenticator", "rotate-owner-authenticator"))) { "Usage: lifeapp-server [init-owner|enroll-owner-authenticator|rotate-owner-authenticator]" }
    val database = Database(PGSimpleDataSource().apply {
        setURL(requireNotNull(System.getenv("LIFEAPP_DB_URL")) { "Set LIFEAPP_DB_URL" })
        user = requireNotNull(System.getenv("LIFEAPP_DB_USER")) { "Set LIFEAPP_DB_USER" }
        password = Files.readString(Path.of(requireNotNull(System.getenv("LIFEAPP_DB_PASSWORD_FILE")) {
            "Set LIFEAPP_DB_PASSWORD_FILE"
        })).trimEnd('\r', '\n')
        connectTimeout = 5
        socketTimeout = 15
    })
    database.migrate()
    val authentication = Authentication(database, totpPath = System.getenv("LIFEAPP_OWNER_TOTP_FILE")?.let(Path::of))
    if (args.isNotEmpty()) {
        val console = requireNotNull(System.console()) { "Owner initialization requires an interactive terminal." }
        val username = requireNotNull(console.readLine("Owner username: "))
        if (args[0] != "init-owner") {
            val rotate = args[0] == "rotate-owner-authenticator"
            val newUsername = if (rotate) username else requireNotNull(console.readLine("New owner username: "))
            val code = requireNotNull(console.readPassword("Authenticator code: "))
            try {
                val recovery = authentication.enrollOwner(username, newUsername, code.concatToString(), rotate)
                console.printf("Owner renamed and authenticator enabled. Store these one-time recovery codes offline:%n")
                recovery.forEach { console.printf("%s%n", it) }
            } finally {
                code.fill('\u0000')
            }
            return
        }
        val secret = requireNotNull(console.readPassword("Owner password: "))
        try {
            authentication.initializeOwner(username, secret)
            console.printf("Owner initialized.%n")
        } finally {
            secret.fill('\u0000')
        }
        return
    }
    val origin = ServerEndpoint.parse(requireNotNull(System.getenv("LIFEAPP_HTTPS_ORIGIN")) {
        "Set LIFEAPP_HTTPS_ORIGIN to the canonical HTTPS origin"
    }).origin
    val host = System.getenv("LIFEAPP_LISTEN_HOST") ?: "127.0.0.1"
    val port = System.getenv("LIFEAPP_PORT")?.toInt() ?: 8080
    require(port in 1..65535)
    embeddedServer(Netty, host = host, port = port) {
        lifeApp(database, authentication, origin)
    }.start(wait = true)
}
