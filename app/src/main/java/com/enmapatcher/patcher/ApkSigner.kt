package com.enmapatcher.patcher

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ApkSigner(private val keystoreDir: File) {

    companion object {
        private const val KEYSTORE_FILE = "enmapatcher.p12"
        private const val KEY_ALIAS = "enmapatcher"
        private const val KEY_PASS = "enmapatcher"
        private val BC = BouncyCastleProvider.PROVIDER_NAME

        init {

            Security.removeProvider(BC)
            Security.addProvider(BouncyCastleProvider())
        }
    }

    suspend fun sign(inputApk: File, outputApk: File): File = withContext(Dispatchers.IO) {
        val (key, cert) = loadOrCreateKeyPair()
        signV1(inputApk, outputApk, key, cert)
        outputApk
    }

    private fun loadOrCreateKeyPair(): Pair<PrivateKey, X509Certificate> {
        keystoreDir.mkdirs()
        val ksFile = File(keystoreDir, KEYSTORE_FILE)

        if (ksFile.exists()) {
            val ks = KeyStore.getInstance("PKCS12")
            ksFile.inputStream().use { ks.load(it, KEY_PASS.toCharArray()) }
            val key = ks.getKey(KEY_ALIAS, KEY_PASS.toCharArray()) as PrivateKey
            val cert = ks.getCertificate(KEY_ALIAS) as X509Certificate
            return key to cert
        }

        val kpg = KeyPairGenerator.getInstance("RSA", BC)
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()

        val now = System.currentTimeMillis()
        val name = X500Name("CN=EnmaPatcher,O=EnmaPatcher,C=ES")
        val cert = JcaX509CertificateConverter().setProvider(BC).getCertificate(
            JcaX509v3CertificateBuilder(
                name, BigInteger.ONE,
                Date(now - 86_400_000L),
                Date(now + 30L * 365 * 24 * 3600 * 1000),
                name, kp.public,
            ).build(JcaContentSignerBuilder("SHA256withRSA").setProvider(BC).build(kp.private))
        )

        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, KEY_PASS.toCharArray())
        ks.setKeyEntry(KEY_ALIAS, kp.private, KEY_PASS.toCharArray(), arrayOf(cert))
        ksFile.outputStream().use { ks.store(it, KEY_PASS.toCharArray()) }

        return kp.private to cert
    }

    private fun signV1(input: File, output: File, key: PrivateKey, cert: X509Certificate) {
        val md = MessageDigest.getInstance("SHA-256")

        val entryOrder = mutableListOf<String>()
        val digests = LinkedHashMap<String, ByteArray>()
        ZipFile(input).use { zf ->
            for (entry in zf.entries()) {
                if (isSignatureFile(entry.name)) continue
                entryOrder += entry.name
                md.reset()
                zf.getInputStream(entry).use { ins ->
                    val buf = ByteArray(65_536)
                    var n = ins.read(buf)
                    while (n > 0) { md.update(buf, 0, n); n = ins.read(buf) }
                }
                digests[entry.name] = md.digest()
            }
        }

        val manifestSb = StringBuilder()
        manifestSb.append("Manifest-Version: 1.0\r\nCreated-By: EnmaPatcher\r\n\r\n")
        for ((name, digest) in digests) {
            manifestSb.append("Name: $name\r\n")
            manifestSb.append("SHA-256-Digest: ${b64(digest)}\r\n\r\n")
        }
        val manifestBytes = manifestSb.toString().toByteArray(Charsets.UTF_8)

        val certSfSb = StringBuilder()
        certSfSb.append("Signature-Version: 1.0\r\nCreated-By: EnmaPatcher\r\n")
        certSfSb.append("SHA-256-Digest-Manifest: ${b64(sha256(md, manifestBytes))}\r\n\r\n")
        for ((name, digest) in digests) {
            val section = "Name: $name\r\nSHA-256-Digest: ${b64(digest)}\r\n\r\n"
            certSfSb.append("Name: $name\r\n")
            certSfSb.append("SHA-256-Digest: ${b64(sha256(md, section.toByteArray(Charsets.UTF_8)))}\r\n\r\n")
        }
        val certSfBytes = certSfSb.toString().toByteArray(Charsets.UTF_8)

        val certRsaBytes = buildPkcs7(certSfBytes, key, cert)

        ZipOutputStream(output.outputStream().buffered()).use { zos ->
            writeRaw(zos, "META-INF/MANIFEST.MF", manifestBytes)
            writeRaw(zos, "META-INF/CERT.SF", certSfBytes)
            writeRaw(zos, "META-INF/CERT.RSA", certRsaBytes)
            ZipFile(input).use { zf ->
                for (name in entryOrder) {
                    val entry = zf.getEntry(name) ?: continue
                    zos.putNextEntry(ZipEntry(name))
                    zf.getInputStream(entry).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
    }

    private fun buildPkcs7(data: ByteArray, key: PrivateKey, cert: X509Certificate): ByteArray {
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(BC).build(key)
        val digestProv = JcaDigestCalculatorProviderBuilder().setProvider(BC).build()
        val gen = CMSSignedDataGenerator().apply {
            addSignerInfoGenerator(JcaSignerInfoGeneratorBuilder(digestProv).build(signer, cert))
            addCertificates(JcaCertStore(listOf(cert)))
        }
        return gen.generate(CMSProcessableByteArray(data), false).encoded
    }

    private fun sha256(md: MessageDigest, bytes: ByteArray): ByteArray { md.reset(); return md.digest(bytes) }
    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)!!
    private fun writeRaw(zos: ZipOutputStream, name: String, data: ByteArray) {
        zos.putNextEntry(ZipEntry(name)); zos.write(data); zos.closeEntry()
    }
    private fun isSignatureFile(name: String): Boolean {
        val u = name.uppercase()
        return u == "META-INF/MANIFEST.MF" ||
            (u.startsWith("META-INF/") && (u.endsWith(".SF") || u.endsWith(".RSA") || u.endsWith(".DSA") || u.endsWith(".EC")))
    }
}
