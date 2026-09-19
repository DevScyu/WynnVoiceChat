package wynnvoicechat.mod.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import org.junit.jupiter.api.Test;

class VoiceClientPinTest {
    @Test
    void bundledCertificateIsTheRelaysAndBuildsAPinnedContext() throws Exception {
        assertNotNull(VoiceClient.pinnedContext(null), "relay.pem is bundled and parses");
        X509Certificate cert;
        try (InputStream pem = VoiceClient.class.getResourceAsStream("/relay.pem")) {
            cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(pem);
        }
        assertEquals("CN=relay.wynnvoicechat.com", cert.getSubjectX500Principal().getName());
        assertTrue(cert.getSubjectAlternativeNames().toString().contains("relay.wynnvoicechat.com"));
        cert.checkValidity();
    }

    @Test
    void missingOverridePathFailsLoudly() {
        org.junit.jupiter.api.Assertions.assertThrows(java.io.UncheckedIOException.class, () -> VoiceClient.pinnedContext("/nowhere/relay.pem"));
    }
}
