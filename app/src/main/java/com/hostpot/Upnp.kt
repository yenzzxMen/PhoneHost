package com.hostpot

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL

// Automatic router port forwarding (UPnP). UPnP must be enabled on the router.
object Upnp {
    private class Ctl(val url: String, val type: String)

    fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .filter { it is Inet4Address && !it.isLoopbackAddress }.map { it.hostAddress ?: "" }
            .firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.") }
    } catch (e: Exception) { null }

    private fun find(): Ctl? {
        val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n"
        var loc: String? = null
        DatagramSocket().use { s ->
            s.soTimeout = 3500
            val b = msg.toByteArray()
            s.send(DatagramPacket(b, b.size, InetAddress.getByName("239.255.255.250"), 1900))
            val buf = ByteArray(2048)
            try {
                while (loc == null) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val t = String(p.data, 0, p.length)
                    loc = Regex("(?im)^location:\\s*(\\S+)").find(t)?.groupValues?.get(1)
                }
            } catch (e: Exception) { }
        }
        val l = loc ?: return null
        val c = URL(l).openConnection() as HttpURLConnection
        c.connectTimeout = 4000; c.readTimeout = 4000
        val xml = c.inputStream.bufferedReader().readText()
        val m = Regex("<serviceType>(urn:[^<]*WAN(?:IP|PPP)Connection:\\d)</serviceType>\\s*<serviceId>[^<]*</serviceId>\\s*<controlURL>([^<]+)</controlURL>", RegexOption.DOT_MATCHES_ALL).find(xml) ?: return null
        return Ctl(URL(URL(l), m.groupValues[2]).toString(), m.groupValues[1])
    }

    private fun soap(c: Ctl, action: String, args: String): String {
        val body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:$action xmlns:u=\"${c.type}\">$args</u:$action></s:Body></s:Envelope>"
        val h = URL(c.url).openConnection() as HttpURLConnection
        h.requestMethod = "POST"; h.doOutput = true; h.connectTimeout = 4000; h.readTimeout = 6000
        h.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        h.setRequestProperty("SOAPAction", "\"${c.type}#$action\"")
        h.outputStream.use { it.write(body.toByteArray()) }
        return if (h.responseCode in 200..299) h.inputStream.bufferedReader().readText()
        else throw Exception("router said HTTP ${h.responseCode}")
    }

    fun open(port: Int): String {
        return try {
            val ip = localIp() ?: return "Not connected to Wi-Fi (no local IP)."
            val c = find() ?: return "No UPnP router found. Enable UPnP on the router or forward port $port manually."
            fun add(lease: Int) = soap(c, "AddPortMapping", "<NewRemoteHost></NewRemoteHost><NewExternalPort>$port</NewExternalPort><NewProtocol>TCP</NewProtocol><NewInternalPort>$port</NewInternalPort><NewInternalClient>$ip</NewInternalClient><NewEnabled>1</NewEnabled><NewPortMappingDescription>HostPot</NewPortMappingDescription><NewLeaseDuration>$lease</NewLeaseDuration>")
            try { add(0) } catch (e: Exception) { add(86400) }
            val ext = try { Regex("<NewExternalIPAddress>([^<]*)").find(soap(c, "GetExternalIPAddress", ""))?.groupValues?.get(1) ?: "?" } catch (e: Exception) { "?" }
            "OK: router port $port -> $ip:$port forwarded.\nRouter external IP: $ext\nTest: http://$ext:$port"
        } catch (e: Exception) { "UPnP fail: ${e.message}" }
    }

    fun close(port: Int): String {
        return try {
            val c = find() ?: return "No UPnP router found."
            soap(c, "DeletePortMapping", "<NewRemoteHost></NewRemoteHost><NewExternalPort>$port</NewExternalPort><NewProtocol>TCP</NewProtocol>")
            "Port $port forwarding closed."
        } catch (e: Exception) { "UPnP fail: ${e.message}" }
    }
}
