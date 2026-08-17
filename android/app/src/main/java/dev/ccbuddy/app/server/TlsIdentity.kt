package dev.ccbuddy.app.server

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit

private const val KEYSTORE_FILE = "tls_identity.p12"
private const val ALIAS = "cc-buddy"
private const val PREFS_NAME = "buddy_tls"
private const val KEY_PASSWORD = "keystore_password"

class TlsIdentity(val keyStore: KeyStore, val alias: String, val password: CharArray) {

    companion object {
        fun loadOrCreate(context: Context): TlsIdentity {
            // Android ships its own crippled provider also named "BC" --
            // addProvider() is a no-op if a same-named provider already
            // exists, so it must be removed first or every BC call
            // silently resolves to Android's stub (missing algorithms
            // like SHA256withECDSA) instead of the real implementation.
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)

            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            val keystoreFile = File(context.filesDir, KEYSTORE_FILE)
            val keyStore = KeyStore.getInstance("PKCS12")
            val storedPassword = prefs.getString(KEY_PASSWORD, null)

            if (keystoreFile.exists() && storedPassword != null) {
                val password = storedPassword.toCharArray()
                FileInputStream(keystoreFile).use { keyStore.load(it, password) }
                return TlsIdentity(keyStore, ALIAS, password)
            }

            val password = generatePassword()
            val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

            val now = Date()
            val notAfter = Date(now.time + TimeUnit.DAYS.toMillis(365L * 50))
            val subject = X500Name("CN=cc-buddy")
            val serial = BigInteger(64, SecureRandom())

            val certBuilder = JcaX509v3CertificateBuilder(subject, serial, now, notAfter, subject, keyPair.public)
            val signer = JcaContentSignerBuilder("SHA256withECDSA").setProvider("BC").build(keyPair.private)
            val cert: X509Certificate = JcaX509CertificateConverter().setProvider("BC")
                .getCertificate(certBuilder.build(signer))

            keyStore.load(null, null)
            keyStore.setKeyEntry(ALIAS, keyPair.private, password, arrayOf(cert))
            FileOutputStream(keystoreFile).use { keyStore.store(it, password) }
            prefs.edit().putString(KEY_PASSWORD, String(password)).apply()

            return TlsIdentity(keyStore, ALIAS, password)
        }

        private fun generatePassword(): CharArray {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }.toCharArray()
        }
    }
}
