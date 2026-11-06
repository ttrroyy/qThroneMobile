package app.rawline.mobile.bg.proto

import app.rawline.mobile.outbound.config.GeneratorSettings
import org.junit.Assert.*
import org.junit.Test

class DnsDiagnosticsTest {
    @Test fun effectiveDnsLabelsDoNotExposeResolverSecrets() {
        val settings=GeneratorSettings(directDns="https://secret-user:secret-pass@private.example/secret-path?token=secret-token",
            remoteDns="https://8.8.8.8/dns-query")
        assertEquals("Effective DNS: source=subscription; direct=https/custom; remote=https/google; underlying=system",
            DnsDiagnostics.summary(settings,true))
        assertEquals("system",DnsDiagnostics.describe("localhost"))
        assertEquals("udp/cloudflare",DnsDiagnostics.describe("1.1.1.1"))
    }
}
