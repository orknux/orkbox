package io.mszymanski.orknux.orkbox.session

import io.mszymanski.orknux.orkbox.model.PublishedPort
import io.mszymanski.orknux.orkbox.runtime.Tunnel
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * A session's forwarded ports: for each, a listener on a random host port that
 * carries every connection into the session's port through a [Tunnel].
 *
 * A listener rather than `docker run --publish`, for two reasons. A port can be
 * opened on a session already running, which publishing cannot do - it is fixed
 * when the container starts. And it works the same on Docker Desktop, where the
 * host cannot reach a container's address, as on Linux: the tunnel goes
 * through `docker exec`, which every Docker setup has.
 *
 * What that costs is a `docker exec` per connection - a few hundred
 * milliseconds to open, then the bytes at full speed - which a person opening
 * a dev server does not notice and a load test would.
 */
class PortForwarder(private val host: String) {
    private val log = LoggerFactory.getLogger(PortForwarder::class.java)
    private val listeners = ConcurrentHashMap<Int, Listener>()

    private class Listener(val server: ServerSocket, val published: PublishedPort) {
        val connections: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    }

    fun list(): List<PublishedPort> = listeners.values.map { it.published }.sortedBy { it.container }

    fun find(port: Int): PublishedPort? = listeners[port]?.published

    /** Opens a listener for [port], or answers the one already open. */
    @Synchronized
    fun open(port: Int, connect: suspend (Int) -> Tunnel): PublishedPort {
        listeners[port]?.let { return it.published }
        val server = ServerSocket(0, 50, InetAddress.getByName(host))
        val listener = Listener(server, PublishedPort(port, host, server.localPort))
        listeners[port] = listener
        Thread.ofVirtual().name("orkbox-forward-$port").start {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                listener.connections += client
                Thread.ofVirtual().start { carry(listener, client, port, connect) }
            }
        }
        return listener.published
    }

    /** Closes [port]'s listener and every connection through it; false when it was not open. */
    fun close(port: Int): Boolean {
        val listener = listeners.remove(port) ?: return false
        runCatching { listener.server.close() }
        listener.connections.forEach { runCatching { it.close() } }
        return true
    }

    fun closeAll() = listeners.keys.toList().forEach(::close)

    private fun carry(listener: Listener, client: Socket, port: Int, connect: suspend (Int) -> Tunnel) {
        val tunnel = try {
            runBlocking { connect(port) }
        } catch (e: Exception) {
            log.debug("Could not reach port {} in the session", port, e)
            listener.connections -= client
            runCatching { client.close() }
            return
        }
        try {
            // Each direction until its end, then its end is passed on: a request
            // is finished when the client stops writing, not when it disconnects.
            val inward = Thread.ofVirtual().start {
                pump(client.getInputStream(), tunnel.output)
                tunnel.closeOutput()
            }
            pump(tunnel.input, client.getOutputStream())
            runCatching { client.shutdownOutput() }
            inward.join(1000)
        } finally {
            tunnel.close()
            listener.connections -= client
            runCatching { client.close() }
        }
    }

    private fun pump(from: InputStream, to: OutputStream) {
        val buffer = ByteArray(16 * 1024)
        runCatching {
            while (true) {
                val read = from.read(buffer)
                if (read < 0) break
                to.write(buffer, 0, read)
                to.flush()
            }
        }
    }
}
