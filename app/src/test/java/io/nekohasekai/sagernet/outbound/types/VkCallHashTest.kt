package app.rawline.mobile.outbound.types

import app.rawline.mobile.outbound.json.JsonInput
import org.junit.Assert.assertEquals
import org.junit.Test

class VkCallHashTest {
    private val hash = "abcDEF123_-xyz789"

    @Test fun callLinksBecomeHashesLikeTheOriginalApps() {
        for (link in listOf(
            "https://vk.com/call/join/$hash", "http://m.vk.ru/call/join/$hash", "HTTPS://VK.RU/call/join/$hash",
            "vk.com/call/join/$hash", "m.vk.com/call/join/$hash/", "https://vk.com/call/join/$hash?from=chat#x", "  $hash  ",
        )) assertEquals(link, hash, VkCallHash.strip(link))
    }

    @Test fun profilesAcceptFullCallLinks() {
        val qwdtt = Qwdtt()
        qwdtt.parseFromJson(JsonInput.parseObject("""{"type":"qwdtt","peer":"192.0.2.1:56000","password":"p","hashes":["vk.com/call/join/$hash/","https://m.vk.ru/call/join/${hash}2?x=1"]}"""))
        assertEquals(listOf(hash, hash + "2"), qwdtt.hashes)
        val csqtt = Csqtt()
        csqtt.parseFromJson(JsonInput.parseObject("""{"type":"csqtt","server":"192.0.2.1","server_port":46000,"password":"p","hashes":["https://vk.com/call/join/$hash#a"]}"""))
        assertEquals(listOf(hash), csqtt.hashes)
    }
}
