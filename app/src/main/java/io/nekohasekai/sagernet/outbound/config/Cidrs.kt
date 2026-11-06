package app.rawline.mobile.outbound.config

import app.rawline.mobile.outbound.link.Hosts

/** The prefix arithmetic of generate.cpp:249-328 (prefixesOverlap, parsePrefix, prefixContains, subtractPrefix). */
internal object Cidrs {

    /** 4 or 16 address bytes with the host bits cleared, and the prefix length. */
    class Prefix(val addr: ByteArray, val bits: Int)

    /**
     * QHostAddress::parseSubnet: `<ipv6>[/n]` (no length = 128) or `d[.d[.d[.d]]][/n]` with decimal octets, missing
     * trailing octets zero and no length = 8 per given octet; the host bits are cleared. Null when malformed.
     */
    fun parse(text: String): Prefix? {
        val subnet = text.trim()
        if (subnet.isEmpty()) return null
        val slash = subnet.indexOf('/')
        val net = if (slash >= 0) subnet.substring(0, slash) else subnet
        var bits = -1
        if (slash >= 0) bits = decimal(subnet.substring(slash + 1)) ?: return null
        if (net.contains(':')) {
            if (bits > 128) return null
            if (bits < 0) bits = 128
            val addr = Hosts.parseIpv6(net.substringBefore('%')) ?: return null
            clearHostBits(addr, bits)
            return Prefix(addr, bits)
        }
        if (bits > 32) return null
        val parts = net.split('.').toMutableList()
        if (parts.size > 4) return null
        if (parts.last().isEmpty()) parts.removeAt(parts.size - 1)
        if (parts.isEmpty()) return null
        val addr = ByteArray(4)
        for ((i, part) in parts.withIndex()) {
            val octet = decimal(part) ?: return null
            if (octet > 255) return null
            addr[i] = octet.toByte()
        }
        if (bits < 0) bits = 8 * parts.size
        clearHostBits(addr, bits)
        return Prefix(addr, bits)
    }

    /** prefixToString: the address as QHostAddress prints it, then `/bits`. */
    fun format(prefix: Prefix): String {
        val a = prefix.addr
        val address = if (a.size == 4) {
            Hosts.formatIpv4(((a[0].toLong() and 0xFF) shl 24) or ((a[1].toLong() and 0xFF) shl 16) or
                ((a[2].toLong() and 0xFF) shl 8) or (a[3].toLong() and 0xFF))
        } else {
            Hosts.formatIpv6(a)
        }
        return "$address/${prefix.bits}"
    }

    /** prefixContains: same family and [inner] lies inside [outer]. */
    fun contains(outer: Prefix, inner: Prefix): Boolean {
        if (outer.addr.size != inner.addr.size || outer.bits > inner.bits) return false
        for (i in 0 until outer.bits) {
            if (bit(outer.addr, i) != bit(inner.addr, i)) return false
        }
        return true
    }

    /** prefixesOverlap (generate.cpp:249-256): same family and one contains the other; malformed input never overlaps. */
    fun overlap(lhs: String, rhs: String): Boolean {
        val a = parse(lhs) ?: return false
        val b = parse(rhs) ?: return false
        return if (a.bits <= b.bits) contains(a, b) else contains(b, a)
    }

    /**
     * subtractPrefix (generate.cpp:306-328): a range inside [hole] is dropped, a range that holds it is replaced by
     * the sibling prefixes of every level between the two lengths, anything else is kept verbatim.
     */
    fun subtract(ranges: List<String>, hole: String): List<String> {
        val cut = parse(hole) ?: return ranges
        val out = ArrayList<String>()
        for (entry in ranges) {
            val range = parse(entry)
            if (range != null && contains(cut, range)) continue
            if (range == null || !contains(range, cut)) {
                out.add(entry)
                continue
            }
            for (bits in range.bits + 1..cut.bits) {
                val sibling = cut.addr.copyOf()
                flipBit(sibling, bits - 1)
                clearHostBits(sibling, bits)
                out.add(format(Prefix(sibling, bits)))
            }
        }
        return out
    }

    private fun decimal(text: String): Int? = if (text.isNotEmpty() && text.all { it in '0'..'9' }) text.toIntOrNull() else null

    private fun bit(addr: ByteArray, index: Int): Int = (addr[index / 8].toInt() shr (7 - index % 8)) and 1

    private fun flipBit(addr: ByteArray, index: Int) {
        addr[index / 8] = (addr[index / 8].toInt() xor (0x80 ushr (index % 8))).toByte()
    }

    private fun clearHostBits(addr: ByteArray, bits: Int) {
        for (i in bits until addr.size * 8) {
            addr[i / 8] = (addr[i / 8].toInt() and (0x80 ushr (i % 8)).inv()).toByte()
        }
    }
}
