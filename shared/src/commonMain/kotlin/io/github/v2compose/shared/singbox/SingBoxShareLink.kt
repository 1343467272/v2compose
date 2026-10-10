package io.github.v2compose.shared.singbox

import io.ktor.http.Url
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * 把常见的「分享链接」解析成 sing-box outbound 配置。
 *
 * 目前支持 vless / vmess / ss / trojan / hysteria2（hy2）。
 * 解析失败返回 null，由调用方决定如何处理。
 */
object SingBoxShareLink {

    fun parse(link: String, index: Int = 0): JsonObject? {
        val trimmed = link.trim()
        if (trimmed.isEmpty()) return null
        return when (trimmed.substringBefore("://", "").lowercase()) {
            "vless" -> parseVless(trimmed, index)
            "vmess" -> parseVmess(trimmed, index)
            "ss" -> parseShadowsocks(trimmed, index)
            "trojan" -> parseTrojan(trimmed, index)
            "hysteria2", "hy2" -> parseHysteria2(trimmed, index)
            else -> null
        }
    }

    // region vless

    private fun parseVless(link: String, index: Int): JsonObject? {
        val url = parseUrl(link) ?: return null
        val uuid = url.user.orEmpty().takeIf { it.isNotBlank() } ?: return null
        val host = url.host.takeIf { it.isNotBlank() } ?: return null
        val port = url.port
        if (port <= 0) return null
        val params = UrlParameters(url)
        return buildJsonObject {
            put("type", "vless")
            put("tag", nodeTag(url.fragment, "vless", index))
            put("server", host)
            put("server_port", port)
            put("uuid", uuid)
            params.value("flow")?.let { put("flow", it) }
            tlsJson(params, params["security"], url.host)?.let { put("tls", it) }
            transportJson(params)?.let { put("transport", it) }
        }
    }

    // endregion

    // region vmess

    private fun parseVmess(link: String, index: Int): JsonObject? {
        val payload = link.substringAfter("://", "")
        val decoded = decodeBase64(payload) ?: return null
        val json = runCatching { Json.parseToJsonElement(decoded.trim()) as? JsonObject }.getOrNull()
            ?: return null
        val host = json.string("add") ?: return null
        val port = json.string("port")?.toIntOrNull() ?: return null
        val uuid = json.string("id") ?: return null
        val name = json.string("ps")
        val network = json.string("net") ?: "tcp"
        val tlsEnabled = json.string("tls")?.lowercase() == "tls"
        val transportType = json.string("type")

        return buildJsonObject {
            put("type", "vmess")
            put("tag", nodeTag(name, "vmess", index))
            put("server", host)
            put("server_port", port)
            put("uuid", uuid)
            json.string("scy")?.takeIf { it.isNotBlank() }?.let { put("security", it) }
            json.string("aid")?.toIntOrNull()?.let { put("alter_id", it) }
            if (tlsEnabled) {
                put("tls", buildJsonObject {
                    put("enabled", true)
                    json.string("sni")?.takeIf { it.isNotBlank() }?.let { put("server_name", it) }
                    json.string("fp")?.takeIf { it.isNotBlank() }?.let { fingerprint ->
                        put("utls", buildJsonObject {
                            put("enabled", true)
                            put("fingerprint", fingerprint)
                        })
                    }
                })
            }
            transportJsonForVmess(network, transportType, json.string("host"), json.string("path"))?.let {
                put("transport", it)
            }
        }
    }

    // endregion

    // region shadowsocks

    private fun parseShadowsocks(link: String, index: Int): JsonObject? {
        val body = link.substringAfter("://", "").substringBefore('#')
        val name = link.substringAfter('#', "").takeIf { it.isNotBlank() }
        val withoutQuery = body.substringBefore('?')

        val credentials: String
        val serverPart: String
        val atIndex = withoutQuery.lastIndexOf('@')
        if (atIndex >= 0) {
            credentials = decodeBase64(withoutQuery.substring(0, atIndex))
                ?: percentDecode(withoutQuery.substring(0, atIndex))
            serverPart = withoutQuery.substring(atIndex + 1)
        } else {
            val decoded = decodeBase64(withoutQuery) ?: return null
            val splitIndex = decoded.lastIndexOf('@')
            if (splitIndex < 0) return null
            credentials = decoded.substring(0, splitIndex)
            serverPart = decoded.substring(splitIndex + 1)
        }
        val separator = credentials.indexOf(':')
        if (separator <= 0) return null
        val method = credentials.substring(0, separator)
        val password = credentials.substring(separator + 1)
        val (host, port) = splitHostPort(serverPart) ?: return null

        return buildJsonObject {
            put("type", "shadowsocks")
            put("tag", nodeTag(name, "shadowsocks", index))
            put("server", host)
            put("server_port", port)
            put("method", method)
            put("password", password)
        }
    }

    // endregion

    // region trojan

    private fun parseTrojan(link: String, index: Int): JsonObject? {
        val url = parseUrl(link) ?: return null
        val password = url.user.orEmpty().takeIf { it.isNotBlank() } ?: return null
        val host = url.host.takeIf { it.isNotBlank() } ?: return null
        val port = url.port
        if (port <= 0) return null
        val params = UrlParameters(url)
        return buildJsonObject {
            put("type", "trojan")
            put("tag", nodeTag(url.fragment, "trojan", index))
            put("server", host)
            put("server_port", port)
            put("password", password)
            tlsJson(params, params["security"] ?: "tls", host)?.let { put("tls", it) }
            transportJson(params)?.let { put("transport", it) }
        }
    }

    // endregion

    // region hysteria2

    private fun parseHysteria2(link: String, index: Int): JsonObject? {
        val url = parseUrl(link) ?: return null
        val host = url.host.takeIf { it.isNotBlank() } ?: return null
        val port = url.port
        if (port <= 0) return null
        val params = UrlParameters(url)
        val password = url.user.orEmpty().ifBlank { params.value("password").orEmpty() }
        return buildJsonObject {
            put("type", "hysteria2")
            put("tag", nodeTag(url.fragment, "hysteria2", index))
            put("server", host)
            put("server_port", port)
            if (password.isNotBlank()) put("password", password)
            put("tls", buildJsonObject {
                put("enabled", true)
                params.value("sni")?.let { put("server_name", it) }
                if (params.isTrue("insecure")) put("insecure", true)
                if (params.isTrue("allowInsecure")) put("insecure", true)
            })
            val obfs = params.value("obfs")
            if (!obfs.isNullOrBlank() && obfs != "none") {
                put("obfs", buildJsonObject {
                    put("type", obfs)
                    params.value("obfs-password")?.let { put("password", it) }
                })
            }
        }
    }

    // endregion

    // region helpers

    private fun parseUrl(link: String): Url? = runCatching { Url(link) }.getOrNull()

    private fun tlsJson(params: UrlParameters, security: String?, host: String): JsonObject? {
        val securityValue = security?.lowercase().orEmpty()
        if (securityValue != "tls" && securityValue != "reality" && securityValue != "xtls") {
            return null
        }
        val sni = params.value("sni") ?: params.value("peer") ?: host
        return buildJsonObject {
            put("enabled", true)
            put("server_name", sni)
            if (params.isTrue("allowInsecure") || params.isTrue("insecure")) {
                put("insecure", true)
            }
            params.value("alpn")?.let { alpn ->
                put("alpn", buildJsonArray { alpn.split(',').forEach { add(JsonPrimitive(it)) } })
            }
            val fingerprint = params.value("fp")
            if (!fingerprint.isNullOrBlank()) {
                put("utls", buildJsonObject {
                    put("enabled", true)
                    put("fingerprint", fingerprint)
                })
            }
            if (securityValue == "reality") {
                put("reality", buildJsonObject {
                    put("enabled", true)
                    params.value("pbk")?.let { put("public_key", it) }
                    params.value("sid")?.let { put("short_id", it) }
                })
            }
        }
    }

    private fun transportJson(params: UrlParameters): JsonObject? {
        val type = params.value("type") ?: return null
        return transportJsonForVmess(
            network = type,
            transportType = params.value("headerType"),
            host = params.value("host"),
            path = params.value("path"),
            serviceName = params.value("serviceName"),
        )
    }

    private fun transportJsonForVmess(
        network: String,
        transportType: String?,
        host: String?,
        path: String?,
        serviceName: String? = null,
    ): JsonObject? {
        return when (network.lowercase()) {
            "ws" -> buildJsonObject {
                put("type", "ws")
                put("path", path?.takeIf { it.isNotBlank() } ?: "/")
                host?.takeIf { it.isNotBlank() }?.let {
                    put("headers", buildJsonObject { put("Host", it) })
                }
            }

            "grpc" -> buildJsonObject {
                put("type", "grpc")
                val service = serviceName?.takeIf { it.isNotBlank() }
                    ?: path?.takeIf { it.isNotBlank() }
                service?.let { put("service_name", it) }
            }

            "http", "h2" -> buildJsonObject {
                put("type", "http")
                host?.takeIf { it.isNotBlank() }?.let {
                    put("host", buildJsonArray { add(JsonPrimitive(it)) })
                }
                path?.takeIf { it.isNotBlank() }?.let { put("path", it) }
            }

            "quic" -> buildJsonObject { put("type", "quic") }

            "httpupgrade" -> buildJsonObject {
                put("type", "httpupgrade")
                put("path", path?.takeIf { it.isNotBlank() } ?: "/")
                host?.takeIf { it.isNotBlank() }?.let { put("host", it) }
            }

            // tcp / raw 等不需要 transport，headerType 为 http 时忽略（旧式伪装很少用到）
            else -> if (transportType?.lowercase() == "http") {
                buildJsonObject { put("type", "http") }
            } else {
                null
            }
        }
    }

    private fun nodeTag(rawName: String?, prefix: String, index: Int): String {
        val name = rawName?.let { percentDecode(it) }?.trim().orEmpty()
        return name.ifBlank { "$prefix-$index" }
    }

    private fun splitHostPort(value: String): Pair<String, Int>? {
        val text = value.trim().removePrefix("[").trimEnd()
        val separator = text.lastIndexOf(':')
        if (separator <= 0) return null
        val host = text.substring(0, separator).trim().trim('[', ']')
        val port = text.substring(separator + 1).trim().substringBefore('/').toIntOrNull() ?: return null
        if (host.isBlank() || port <= 0) return null
        return host to port
    }

    internal fun decodeBase64(raw: String): String? {
        val normalized = raw.trim()
            .replace('-', '+')
            .replace('_', '/')
            .filterNot { it.isWhitespace() }
        if (normalized.isEmpty()) return null
        val padded = when (normalized.length % 4) {
            0 -> normalized
            2 -> normalized + "=="
            3 -> normalized + "="
            else -> return null
        }
        return runCatching { Base64.decode(padded).decodeToString() }.getOrNull()
    }

    internal fun percentDecode(value: String): String {
        if ('%' !in value && '+' !in value) return value
        val bytes = ArrayList<Byte>(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '%' && index + 2 < value.length) {
                val code = value.substring(index + 1, index + 3).toIntOrNull(16)
                if (code != null) {
                    bytes.add(code.toByte())
                    index += 3
                    continue
                }
            }
            if (char == '+') {
                bytes.add(' '.code.toByte())
            } else {
                bytes.addAll(char.toString().encodeToByteArray().toList())
            }
            index++
        }
        return bytes.toByteArray().decodeToString()
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content.isNotBlank() }?.content

    private class UrlParameters(private val url: Url) {
        operator fun get(key: String): String? = value(key)

        fun value(key: String): String? = url.parameters[key]?.takeIf { it.isNotBlank() }

        fun isTrue(key: String): Boolean = value(key)?.lowercase() in setOf("1", "true", "yes")
    }

    // endregion
}
