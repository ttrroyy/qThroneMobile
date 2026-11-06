package app.rawline.mobile.route

import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress

/** Validation never resolves a domain or contacts the network. */
object MobileRouteInput {
    fun domains(input: String): List<String> = tokens(input).map { value ->
        val ascii = IDN.toASCII(value.removePrefix("*.").trimEnd('.'), IDN.USE_STD3_ASCII_RULES).lowercase()
        require(ascii.isNotEmpty() && ascii.length <= 253 && ascii.split('.').all {
            it.isNotEmpty() && it.length <= 63 && !it.startsWith('-') && !it.endsWith('-')
        })
        ascii
    }.distinct()

    fun networks(input: String): List<String> = tokens(input).map { value ->
        val parts = value.split('/')
        require(parts.size in 1..2)
        val address = parts[0]
        val bits = if (':' in address) {
            require(address.matches(Regex("[0-9A-Fa-f:.]+")))
            require(InetAddress.getByName(address) is Inet6Address)
            128
        } else {
            val octets = address.split('.')
            require(octets.size == 4 && octets.all { it.matches(Regex("[0-9]{1,3}")) && it.toInt() in 0..255 })
            32
        }
        if (parts.size == 2) require(parts[1].toIntOrNull()?.let { it in 0..bits } == true)
        // The core expects CIDR even when the user enters a single host.
        if (parts.size == 1) "$address/$bits" else "$address/${parts[1].toInt()}"
    }.distinct()

    private fun tokens(input: String): List<String> = input.split(Regex("[\\s,;]+"))
        .filter { it.isNotBlank() }.also { require(it.isNotEmpty()) }
}
