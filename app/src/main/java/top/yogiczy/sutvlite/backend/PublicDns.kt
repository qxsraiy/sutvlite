package top.yogiczy.sutvlite.backend

import android.util.Log
import top.yogiczy.sutvlite.Logx
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * 抗污染的 DNS 解析 (运营商 DNS 常见问题: 污染 / 超时 / 返回空 / NXDOMAIN)
 *
 * 顺序:
 *   1) 系统 DNS (盒子 Wi-Fi / 网线 拿到的 DNS)
 *   2) 公共 DNS 裸 UDP 查询: 114.114.114.114 -> 223.5.5.5 -> 119.29.29.29 -> 8.8.8.8
 *
 * 带 5 分钟内存缓存, 避免每次连接都查一次 (老盒子性能有限)。
 */
object PublicDns : okhttp3.Dns {

    private const val TAG = "PublicDns"
    private const val CACHE_TTL_MS = 5 * 60 * 1000L
    private const val TIMEOUT_MS = 2500

    private val servers = listOf("114.114.114.114", "223.5.5.5", "119.29.29.29", "8.8.8.8")

    private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()

    override fun lookup(hostname: String): List<InetAddress> {
        // 已是 IP 字面量
        runCatching {
            val ip = InetAddress.getByName(hostname)
            if (ip.hostAddress == hostname) return listOf(ip)
        }

        cache[hostname]?.let { (t, list) ->
            if (System.currentTimeMillis() - t < CACHE_TTL_MS) return list
        }

        // 1) 系统 DNS —— 只取 IPv4!
        // 原因: 咪咕 CDN 会把 playurl 时的 client_ip 写进流地址,
        // 而盒子的 IPv6 隐私地址在开机后还会轮换, 导致请求源 IP 与地址里的不一致 -> 661。
        // IPv4 地址稳定, 因此强制走 IPv4。
        val sysAll = runCatching { okhttp3.Dns.SYSTEM.lookup(hostname) }.getOrNull()
        val sys = sysAll?.filterIsInstance<java.net.Inet4Address>()
        if (!sys.isNullOrEmpty()) {
            cache[hostname] = System.currentTimeMillis() to sys
            return sys
        }
        Log.w(TAG, "系统DNS失败, 走公共DNS: $hostname")

        // 2) 公共 DNS 兜底
        for (server in servers) {
            val result = runCatching { queryA(hostname, server) }.getOrNull()
            if (!result.isNullOrEmpty()) {
                Log.i(TAG, "公共DNS($server) 解析成功: $hostname -> ${result.joinToString { it.hostAddress ?: "" }}")
                cache[hostname] = System.currentTimeMillis() to result
                return result
            }
        }

        throw UnknownHostException("$hostname (系统DNS与公共DNS均失败)")
    }

    /**
     * 汇总所有解析器(系统 + 多个公共 DNS)的结果, 去重返回。
     * 用于"某个解析器被污染/返回假地址"时逐个尝试。
     */
    fun resolveAll(hostname: String): List<java.net.InetAddress> {
        // 只用 IPv4 (queryA 本来就是 A 记录; 系统结果里过滤掉 IPv6)
        val out = LinkedHashMap<String, java.net.InetAddress>()
        runCatching {
            okhttp3.Dns.SYSTEM.lookup(hostname)
                .filterIsInstance<java.net.Inet4Address>()
                .forEach { out[it.hostAddress ?: return@forEach] = it }
        }
        for (server in servers) {
            runCatching {
                queryA(hostname, server).forEach { out[it.hostAddress ?: return@forEach] = it }
            }
        }
        Logx.i("DNS", "$hostname -> ${out.keys.joinToString()}")
        return out.values.toList()
    }

    /** 裸 UDP 发一条 A 记录查询并解析应答 */
    private fun queryA(hostname: String, server: String): List<InetAddress> {
        val query = buildQuery(hostname)
        java.net.DatagramSocket().use { socket ->
            socket.soTimeout = TIMEOUT_MS
            val addr = InetAddress.getByName(server)
            socket.send(java.net.DatagramPacket(query, query.size, addr, 53))

            val buf = ByteArray(1500)
            val resp = java.net.DatagramPacket(buf, buf.size)
            socket.receive(resp)
            return parseAnswers(buf, resp.length)
        }
    }

    private fun buildQuery(hostname: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        // ID
        out.write((System.nanoTime() and 0xFFFF).toInt())
        out.write((System.nanoTime() shr 8 and 0xFF).toInt())
        // flags: 0x0100 = RD
        out.write(0x01); out.write(0x00)
        // QDCOUNT=1, ANCOUNT=0, NSCOUNT=0, ARCOUNT=0
        out.write(0x00); out.write(0x01)
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)
        // QNAME
        for (label in hostname.trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        // QTYPE=1(A), QCLASS=1(IN)
        out.write(0x00); out.write(0x01)
        out.write(0x00); out.write(0x01)
        return out.toByteArray()
    }

    private fun parseAnswers(buf: ByteArray, len: Int): List<InetAddress> {
        if (len < 12) return emptyList()
        val qdCount = ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
        val anCount = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
        if (anCount <= 0) return emptyList()

        var pos = 12
        // 跳过问题段
        repeat(qdCount) {
            while (pos < len) {
                val l = buf[pos].toInt() and 0xFF
                if (l == 0) { pos++; break }
                if (l and 0xC0 == 0xC0) { pos += 2; break }
                pos += l + 1
            }
            pos += 4 // QTYPE + QCLASS
        }

        val result = mutableListOf<InetAddress>()
        repeat(anCount) {
            if (pos >= len) return@repeat
            // 跳过 NAME (可能是压缩指针)
            while (pos < len) {
                val l = buf[pos].toInt() and 0xFF
                if (l == 0) { pos++; break }
                if (l and 0xC0 == 0xC0) { pos += 2; break }
                pos += l + 1
            }
            if (pos + 10 > len) return@repeat
            val type = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)
            pos += 8 // TYPE(2) CLASS(2) TTL(4)
            val rdLen = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)
            pos += 2
            if (type == 1 && rdLen == 4 && pos + 4 <= len) {
                val ip = byteArrayOf(buf[pos], buf[pos + 1], buf[pos + 2], buf[pos + 3])
                runCatching { InetAddress.getByAddress(ip) }.getOrNull()?.let { result.add(it) }
            }
            pos += rdLen
        }
        return result
    }

    fun clearCache() = cache.clear()
}