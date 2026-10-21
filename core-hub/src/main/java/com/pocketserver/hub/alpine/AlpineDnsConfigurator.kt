package com.pocketserver.hub.alpine

import android.content.Context
import android.net.ConnectivityManager
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress

/**
 * Dynamic DNS configurator neutralizing the notorious Android 5-second Musl DNS timeout trap.
 *
 * Background:
 *   Musl libc queries IPv4 (A) and IPv6 (AAAA) records concurrently over the same UDP socket.
 *   Android's internal `netd` daemon and mobile cellular carriers frequently drop simultaneous
 *   AAAA packets, causing Musl to stall for its hardcoded 5.0-second timeout before fallback.
 *
 * Solution:
 *   1. Dynamically writes `$STORE_DIR/alpine/etc/resolv.conf` with `options timeout:1 attempts:2`
 *      and active upstream nameservers extracted from Android's `ConnectivityManager`.
 *   2. Supplies `--dns-result-order=ipv4first` and localized network environment flags for runtimes.
 */
class AlpineDnsConfigurator(
    val sysrootEtcDir: File,
    private val context: Context? = null
) {

    companion object {
        val FALLBACK_DNS_SERVERS = listOf(
            "1.1.1.1", // Cloudflare Primary
            "8.8.8.8", // Google Primary
            "1.0.0.1", // Cloudflare Secondary
            "8.8.4.4"  // Google Secondary
        )

        const val DNS_OPTIONS = "options timeout:1 attempts:2 rotate ndots:1"
    }

    init {
        sysrootEtcDir.mkdirs()
    }

    /**
     * Resolves the active DNS servers from Android ConnectivityManager, falling back to public DNS.
     */
    fun resolveActiveDnsServers(): List<String> {
        val detectedServers = mutableListOf<String>()

        if (context != null) {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val activeNetwork = cm?.activeNetwork
                if (activeNetwork != null) {
                    val linkProps = cm.getLinkProperties(activeNetwork)
                    if (linkProps != null) {
                        for (dns in linkProps.dnsServers) {
                            val hostAddress = dns.hostAddress
                            if (!hostAddress.isNullOrBlank()) {
                                detectedServers.add(hostAddress)
                            }
                        }
                    }
                }
            } catch (ignored: Exception) {}
        }

        return if (detectedServers.isNotEmpty()) detectedServers else FALLBACK_DNS_SERVERS
    }

    /**
     * Generates standard POSIX `resolv.conf` text content.
     */
    fun generateResolvConf(dnsServers: List<String> = resolveActiveDnsServers()): String {
        return buildString {
            append("# Generated dynamically by PocketServer AlpineDnsConfigurator\n")
            append("# Mitigates Android netd concurrent A/AAAA packet drop\n\n")
            for (dns in dnsServers) {
                append("nameserver $dns\n")
            }
            append("\n")
            append("$DNS_OPTIONS\n")
        }
    }

    /**
     * Atomically writes `$sysrootEtcDir/resolv.conf`.
     */
    fun writeResolvConf(dnsServers: List<String> = resolveActiveDnsServers()): File {
        val targetFile = File(sysrootEtcDir, "resolv.conf")
        val tempFile = File(sysrootEtcDir, ".resolv.conf.tmp-${System.nanoTime()}")

        val content = generateResolvConf(dnsServers)

        FileOutputStream(tempFile).use { fos ->
            fos.write(content.toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }

        if (!tempFile.renameTo(targetFile)) {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
        }

        targetFile.setReadable(true, false)
        return targetFile
    }

    /**
     * Runtime execution arguments eliminating cellular DNS stalls on Node.js / Bun.
     */
    fun getNodeDnsArguments(): List<String> {
        return listOf("--dns-result-order=ipv4first")
    }

    /**
     * Environment variables ensuring robust IPv4 resolution in Python and shell environments.
     */
    fun getNetworkEnvironmentVariables(): Map<String, String> {
        return mapOf(
            "RES_OPTIONS" to "timeout:1 attempts:2",
            "CURL_RESOLV_OPT" to "ipv4"
        )
    }
}
