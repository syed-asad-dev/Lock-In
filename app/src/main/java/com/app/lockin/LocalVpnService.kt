package com.app.lockin

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "LocalVpnService"
private const val DNS_TAG = "DnsInterceptor"
private const val NOTIFICATION_CHANNEL_ID = "lockin_vpn_channel"
private const val NOTIFICATION_ID = 1001

class LocalVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private val dnsExecutor: ExecutorService = Executors.newFixedThreadPool(4)

    private val dynamicInstallReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (action == Intent.ACTION_PACKAGE_ADDED) {
                val packageName = intent.data?.schemeSpecificPart
                if (!packageName.isNullOrBlank()) {
                    Log.i(TAG, "Dynamic receiver: Package installed: $packageName")
                    AppBlocklistManager.blockPackageIfNeeded(context, packageName)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate: Registering dynamic package install receiver.")
        val filter = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply {
            addDataScheme("package")
        }
        registerReceiver(dynamicInstallReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand invoked. Starting DNS interceptor.")
        startForegroundNotification()
        establishVpnInterface()
        startVpnLoop()
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "VPN Status",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Lock-In DNS Interceptor Active"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setSmallIcon(R.drawable.ic_lock_lock)
            .setContentTitle("Lock-In VPN")
            .setContentText("DNS Interceptor Intercepting Port 53")
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun establishVpnInterface() {
        if (vpnInterface != null) return
        try {
            val builder = Builder()
                .setSession("Lock-In VPN")
                .addAddress("10.0.0.2", 32)
                .addDnsServer("10.0.0.2")
                // NOTE: We NO LONGER add 0.0.0.0/0 to capture EVERYTHING.
                // This prevents the "Dead Internet" issue.
            
            val pm = packageManager
            val installedPackages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(0)
            }

            var allowedCount = 0
            installedPackages.forEach { packageInfo ->
                // Check if this app is a browser or untrusted app
                if (!NetworkQuarantineManager.isPackageTrusted(packageInfo)) {
                    try {
                        // FORCE only untrusted apps into the tunnel
                        builder.addAllowedApplication(packageInfo.packageName)
                        allowedCount++
                    } catch (e: Exception) {
                        // Package might have been uninstalled
                    }
                }
            }

            // Only add routes if there are apps to capture
            if (allowedCount > 0) {
                builder.addRoute("0.0.0.0", 0)
                builder.addRoute("::", 0)
            }

            vpnInterface = builder.establish()
            Log.i(TAG, "VPN established. Black-hole tunnel active for $allowedCount untrusted apps.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN: ${e.message}", e)
        }
    }

    private fun startVpnLoop() {
        if (vpnThread != null) return
        vpnThread = Thread {
            val fd = vpnInterface?.fileDescriptor ?: return@Thread
            val input = FileInputStream(fd)
            val output = FileOutputStream(fd)
            val buffer = ByteBuffer.allocate(32768)

            try {
                while (!Thread.interrupted()) {
                    val length = input.read(buffer.array())
                    if (length > 0) {
                        // Create a copy of the packet for processing
                        val packet = buffer.array().copyOf(length)
                        handlePacket(packet, length, output)
                        buffer.clear()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "VPN loop error: ${e.message}", e)
            }
        }.apply {
            name = "VpnLoopThread"
            start()
        }
    }

    private fun handlePacket(packet: ByteArray, length: Int, output: FileOutputStream) {
        if (length < 20) return
        val version = (packet[0].toInt() shr 4) and 0x0F
        if (version != 4) return

        val ihl = (packet[0].toInt() and 0x0F) * 4
        val protocol = packet[9].toInt()

        if (protocol == 17) { // UDP
            val udpOffset = ihl
            if (length < udpOffset + 8) return

            val destPort = ((packet[udpOffset + 2].toInt() and 0xFF) shl 8) or (packet[udpOffset + 3].toInt() and 0xFF)
            if (destPort == 53) {
                processDnsQuery(packet, length, udpOffset, output)
            }
        }
    }

    private fun processDnsQuery(packet: ByteArray, length: Int, udpOffset: Int, output: FileOutputStream) {
        val dnsOffset = udpOffset + 8
        val dnsData = packet.copyOfRange(dnsOffset, length)
        val domain = parseDnsDomain(dnsData)
        Log.i(DNS_TAG, "Intercepted DNS query for: $domain")

        dnsExecutor.execute {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                protect(socket)
                val serverAddress = InetAddress.getByName("8.8.8.8")
                val queryPacket = DatagramPacket(dnsData, dnsData.size, serverAddress, 53)
                socket.send(queryPacket)

                val responseBuffer = ByteArray(4096)
                val datagramResponse = DatagramPacket(responseBuffer, responseBuffer.size)
                socket.soTimeout = 5000
                socket.receive(datagramResponse)

                val responseData = datagramResponse.data.copyOf(datagramResponse.length)
                val fullResponse = constructUdpResponse(packet, udpOffset, responseData)
                
                synchronized(output) {
                    try {
                        output.write(fullResponse)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to write DNS response to tun: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(DNS_TAG, "Forwarding failed for $domain: ${e.message}")
            } finally {
                socket?.close()
            }
        }
    }

    private fun parseDnsDomain(dnsData: ByteArray): String {
        val sb = StringBuilder()
        var pos = 12 // Skip DNS header
        try {
            while (pos < dnsData.size) {
                val len = dnsData[pos].toInt() and 0xFF
                if (len == 0) break
                if ((len and 0xC0) == 0xC0) {
                    sb.append("[ptr]")
                    break
                }
                if (sb.isNotEmpty()) sb.append(".")
                pos++
                if (pos + len > dnsData.size) break
                sb.append(String(dnsData, pos, len))
                pos += len
            }
        } catch (e: Exception) {
            return "parse_err"
        }
        return sb.toString()
    }

    private fun constructUdpResponse(originalPacket: ByteArray, udpOffset: Int, dnsResponse: ByteArray): ByteArray {
        val ipHeaderLen = udpOffset
        val newTotalLen = ipHeaderLen + 8 + dnsResponse.size
        val response = ByteArray(newTotalLen)

        // Copy original IP header
        System.arraycopy(originalPacket, 0, response, 0, ipHeaderLen)
        
        // Swap Source and Destination IPs
        System.arraycopy(originalPacket, 12, response, 16, 4) // Source -> Destination
        System.arraycopy(originalPacket, 16, response, 12, 4) // Destination -> Source
        
        // Update IP Total Length
        response[2] = (newTotalLen shr 8).toByte()
        response[3] = (newTotalLen and 0xFF).toByte()
        
        // Recalculate IP Checksum
        response[10] = 0
        response[11] = 0
        val ipChecksum = calculateChecksum(response, 0, ipHeaderLen)
        response[10] = (ipChecksum shr 8).toByte()
        response[11] = (ipChecksum and 0xFF).toByte()

        // Construct UDP Header
        // Swap Source and Destination Ports
        val srcPort = ((originalPacket[udpOffset + 2].toInt() and 0xFF) shl 8) or (originalPacket[udpOffset + 3].toInt() and 0xFF)
        val destPort = ((originalPacket[udpOffset].toInt() and 0xFF) shl 8) or (originalPacket[udpOffset + 1].toInt() and 0xFF)
        
        response[udpOffset] = (srcPort shr 8).toByte()
        response[udpOffset + 1] = (srcPort and 0xFF).toByte()
        response[udpOffset + 2] = (destPort shr 8).toByte()
        response[udpOffset + 3] = (destPort and 0xFF).toByte()
        
        val udpLen = 8 + dnsResponse.size
        response[udpOffset + 4] = (udpLen shr 8).toByte()
        response[udpOffset + 5] = (udpLen and 0xFF).toByte()
        
        // UDP Checksum (set to 0 for simplicity, which is valid for IPv4/UDP)
        response[udpOffset + 6] = 0
        response[udpOffset + 7] = 0

        // Copy DNS payload
        System.arraycopy(dnsResponse, 0, response, udpOffset + 8, dnsResponse.size)
        
        return response
    }

    private fun calculateChecksum(buf: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        var len = length
        while (len > 1) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
            len -= 2
        }
        if (len > 0) {
            sum += (buf[i].toInt() and 0xFF) shl 8
        }
        while ((sum shr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFF)
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN connection revoked.")
        cleanup()
        super.onRevoke()
    }

    override fun onDestroy() {
        Log.i(TAG, "LocalVpnService onDestroy.")
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        try {
            unregisterReceiver(dynamicInstallReceiver)
            Log.i(TAG, "Dynamic receiver unregistered.")
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering dynamic receiver: ${e.message}")
        }
        vpnThread?.interrupt()
        vpnThread = null
        dnsExecutor.shutdownNow()
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface: ${e.message}")
        }
        vpnInterface = null
    }
}
