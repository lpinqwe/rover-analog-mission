package com.rover.gateway

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext

/**
 * Thin wrapper over Paho MQTT v3.
 * Self-healing: [ensureConnected] on a timer from GatewayService restarts
 * the connection if it dropped or the initial connect failed (Paho doesn't retry
 * the primary connection and leaves the client in null).
 */
class MqttClient(
    private val brokerUri: String,
    private val clientId: String,
    private val status: (String) -> Unit,
) {
    private var client: MqttClient? = null
    private var user = ""
    private var pass = ""
    private var topics: List<String> = emptyList()
    private val connecting = AtomicBoolean(false)

    var onMessage: ((topic: String, payload: String) -> Unit)? = null
    var onConnectedChange: ((Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    @Volatile var connected = false
        private set

    fun configure(user: String, pass: String) {
        this.user = user
        this.pass = pass
    }

    fun connect(topics: List<String>) {
        this.topics = topics
        ensureConnected()
    }

    fun ensureConnected() {
        val c = client
        if (c?.isConnected == true || connecting.get()) return
        if (c != null) {
            runCatching { c.disconnect() }
            client = null
            connected = false
        }
        if (!connecting.compareAndSet(false, true)) return
        status("MQTT: connecting to $brokerUri...")
        Thread {
            try {
                val opts = MqttConnectOptions().apply {
                    // we reconnect via ensureConnected (Paho doesn't retry the initial failure)
                    isAutomaticReconnect = false
                    isCleanSession = false
                    connectionTimeout = 15
                    keepAliveInterval = 20
                    val scheme = brokerUri.substringBefore("://")
                    if (scheme == "ssl" || scheme == "tls") {
                        socketFactory = SSLContext.getDefault().socketFactory
                    }
                    if (user.isNotBlank()) {
                        userName = user
                        this.password = pass.toCharArray()
                    }
                }
                val c2 = MqttClient(brokerUri, clientId, MemoryPersistence())
                c2.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String) {
                        runCatching {
                            connected = true
                            onConnectedChange?.invoke(true)
                            status("MQTT: connected${if (reconnect) " (reconnect)" else ""}")
                            topics.forEach { t ->
                                runCatching { c2.subscribe(t, 0) }
                            }
                        }
                    }

                    override fun connectionLost(cause: Throwable?) {
                        runCatching {
                            connected = false
                            onConnectedChange?.invoke(false)
                            status("MQTT: connection lost — reconnecting")
                            onError?.invoke("connection lost, reconnecting")
                        }
                    }

                    override fun messageArrived(topic: String, message: MqttMessage) {
                        runCatching { onMessage?.invoke(topic, String(message.payload)) }
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken) {}
                })
                client = c2
                runCatching { c2.connect(opts) }.getOrElse { t ->
                    client = null
                    connected = false
                    status("MQTT: connection error")
                    onError?.invoke("cannot reach broker")
                }
            } catch (t: Throwable) {
                // no background thread should crash the process
                client = null
                connected = false
            } finally {
                connecting.set(false)
            }
        }.start()
    }

    fun publish(topic: String, payload: String, retain: Boolean = false) {
        val c = client ?: return
        if (!c.isConnected) return
        runCatching {
            val m = MqttMessage(payload.toByteArray(Charsets.UTF_8))
            m.qos = 0
            m.isRetained = retain
            c.publish(topic, m)
        }
    }

    fun disconnect() {
        connecting.set(false)
        runCatching { client?.disconnect() }
        client = null
        connected = false
    }
}