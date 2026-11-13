package id.litevpn.slowdns

import android.app.Activity
import android.net.VpnService
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory

class MainActivity : Activity() {
    private lateinit var configInput: EditText
    private lateinit var dns: EditText
    private lateinit var domain: EditText
    private lateinit var pubkey: EditText
    private lateinit var sshHost: EditText
    private lateinit var sshPort: EditText
    private lateinit var sshUser: EditText
    private lateinit var sshPass: EditText
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var traffic: TextView
    @Volatile private var dnsttProcess: Process? = null
    @Volatile private var stopping = false
    @Volatile private var sshSession: Session? = null

    private val bg = Color.rgb(246, 246, 246)
    private val panel = Color.rgb(232, 232, 232)
    private val accent = Color.rgb(42, 45, 48)
    private val ink = Color.rgb(35, 38, 42)
    private val muted = Color.rgb(112, 116, 121)
    private val localTunnel = "127.0.0.1:7000"
    private val vpnRequestCode = 7401
    private var pendingConnect = false

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int = 10): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun appendLog(message: String) {
        runOnUiThread {
            if (::log.isInitialized) {
                log.text = (log.text.toString() + "\n" + message).takeLast(5000)
            }
        }
    }

    private fun stopEngine() {
        try { stopService(Intent(this, SlowDnsVpnService::class.java)) } catch (_: Exception) { }
        stopping = true
        val session = sshSession
        sshSession = null
        try { session?.disconnect() } catch (_: Exception) { }
        val process = dnsttProcess
        dnsttProcess = null
        if (process != null) {
            process.destroy()
            Thread {
                try {
                    if (!process.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly()
                    }
                } catch (_: Exception) { }
            }.start()
        }
        if (::status.isInitialized) {
            status.text = "●  DISCONNECTED"
            status.setTextColor(Color.LTGRAY)
        }
        if (::log.isInitialized) appendLog("DNS engine stopped.")
    }

    private fun startSshOverTunnel() {
        val username = sshUser.text.toString().trim()
        val password = sshPass.text.toString()
        if (username.isBlank()) {
            appendLog("SSH username is required.")
            return
        }
        Thread {
            try {
                appendLog("Waiting for local DNS tunnel socket $localTunnel ...")
                val jsch = JSch()
                val session = jsch.getSession(username, sshHost.text.toString().trim().ifBlank { "slowdns-local" }, 22)
                session.setPassword(password)
                session.setConfig("StrictHostKeyChecking", "no")
                session.setConfig("PreferredAuthentications", "password,keyboard-interactive")
                session.setSocketFactory(object : SocketFactory {
                    override fun createSocket(host: String, port: Int): Socket =
                        Socket("127.0.0.1", 7000)
                    override fun getInputStream(socket: Socket) = socket.getInputStream()
                    override fun getOutputStream(socket: Socket) = socket.getOutputStream()
                })
                session.connect(20000)
                sshSession = session
                appendLog("SSH authentication successful through DNS tunnel.")
                runOnUiThread {
                    status.text = "●  SSH CONNECTED (TUNNEL)"
                    status.setTextColor(Color.rgb(90, 210, 150))
                }
                try {
                    val vpnIntent = Intent(this@MainActivity, SlowDnsVpnService::class.java)
                        .setAction(SlowDnsVpnService.ACTION_START)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        startForegroundService(vpnIntent)
                    } else {
                        startService(vpnIntent)
                    }
                    appendLog("SSH is up. Starting Android VPN interface now.")
                } catch (e: Exception) {
                    appendLog("SSH connected, but VPN service failed: ${e.message}")
                    runOnUiThread {
                        status.text = "●  SSH OPEN / VPN SERVICE ERROR"
                        status.setTextColor(Color.rgb(255, 150, 90))
                    }
                }
            } catch (e: Exception) {
                appendLog("SSH connection failed: ${e.message}")
                runOnUiThread {
                    status.text = "●  DNS OPEN / SSH FAILED"
                    status.setTextColor(Color.rgb(255, 170, 90))
                }
            }
        }.start()
    }

    private fun importDnsConfig() {
        try {
            val raw = configInput.text.toString().trim()
            require(raw.startsWith("dns://", ignoreCase = true)) { "Config must start with dns://" }
            val parts = raw.removePrefix("dns://").removePrefix("DNS://").split("#")
            require(parts.size >= 2) { "Config format is incomplete." }

            val endpoint = parts[0].substringBefore("/")
            val endpointHost = endpoint.substringBeforeLast(":", endpoint)
            val endpointPort = endpoint.substringAfterLast(":", "22")
            require(endpointHost.isNotBlank()) { "SSH host is missing." }

            val tunnelParts = parts[1].split(":")
            require(tunnelParts.size >= 3) { "Nameserver, public key and DNS resolver are required." }
            val ns = tunnelParts[0].trim()
            val key = tunnelParts[1].trim()
            val resolver = tunnelParts.drop(2).joinToString(":").trim()
            require(key.matches(Regex("(?i)^[0-9a-f]{64}$"))) { "Public key must contain 64 hex characters." }
            require(ns.isNotBlank() && resolver.isNotBlank()) { "Nameserver or DNS resolver is empty." }

            var username = ""
            var password = ""
            if (parts.size >= 3) {
                val credentials = parts[2].substringAfter("@", parts[2])
                username = credentials.substringBefore(":", "").trim()
                password = credentials.substringAfter(":", "").trim()
                if (username == "null") username = ""
            }

            domain.setText(ns)
            pubkey.setText(key)
            dns.setText(resolver)
            sshHost.setText(endpointHost)
            sshPort.setText(endpointPort)
            if (username.isNotBlank()) sshUser.setText(username)
            if (password.isNotBlank()) sshPass.setText(password)
            appendLog("Config imported. Review SSH host/port and credentials before connecting.")
            Toast.makeText(this, "DNS config imported", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            appendLog("Config import failed: ${e.message}")
            Toast.makeText(this, "Config tidak valid: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startEngine() {
        val resolver = dns.text.toString().trim().let {
            if (it.contains(":")) it else "$it:53"
        }
        val tunnelDomain = domain.text.toString().trim().trimEnd('.')
        val key = pubkey.text.toString().trim().replace(" ", "")
        val missing = when {
            resolver.isBlank() -> "DNS resolver is required."
            tunnelDomain.isBlank() -> "Nameserver / tunnel domain is required."
            !key.matches(Regex("(?i)^[0-9a-f]{64}$")) -> "Public key must be 64 hexadecimal characters."
            else -> null
        }
        if (missing != null) {
            status.text = "●  PROFILE INCOMPLETE"
            status.setTextColor(Color.rgb(255, 190, 90))
            appendLog(missing)
            return
        }
        if (dnsttProcess?.isAlive == true) {
            appendLog("DNS engine is already running.")
            return
        }

        val binary = java.io.File(applicationInfo.nativeLibraryDir, "libdnstt.so")
        if (!binary.exists()) {
            status.text = "●  ENGINE MISSING"
            status.setTextColor(Color.rgb(255, 100, 100))
            appendLog("Bundled ARM64 DNS engine was not found in the APK.")
            return
        }

        stopping = false
        try {
            val command = listOf(
                binary.absolutePath, "-udp", resolver, "-pubkey", key,
                tunnelDomain, localTunnel
            )
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            dnsttProcess = process
            status.text = "●  DNS ENGINE STARTING"
            status.setTextColor(Color.rgb(255, 190, 90))
            appendLog("Starting DNS tunnel engine on $localTunnel via $resolver.")
            Thread {
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            appendLog(line)
                            if (line.contains("begin session", ignoreCase = true)) {
                                runOnUiThread {
                                    status.text = "●  DNS TUNNEL SESSION OPEN"
                                    status.setTextColor(Color.rgb(90, 210, 150))
                                }
                                if (sshSession?.isConnected != true) {
                                    appendLog("DNS session is open; starting SSH handshake through local tunnel.")
                                    startSshOverTunnel()
                                }
                            }
                            if (line.contains("operation not permitted", ignoreCase = true)) {
                                runOnUiThread {
                                    status.text = "●  DNS UDP BLOCKED"
                                    status.setTextColor(Color.rgb(255, 150, 90))
                                }
                                appendLog("UDP resolver traffic was denied by Android/network. Check another active VPN or try a different DNS resolver/network.")
                            }
                        }
                    }
                    val exit = process.waitFor()
                    if (!stopping) {
                        appendLog("DNS engine exited with code $exit.")
                        runOnUiThread {
                            status.text = "●  DNS ENGINE STOPPED"
                            status.setTextColor(Color.rgb(255, 150, 90))
                        }
                    }
                } catch (e: Exception) {
                    appendLog("Engine monitor: ${e.message}")
                } finally {
                    if (dnsttProcess === process) dnsttProcess = null
                }
            }.start()
        } catch (e: Exception) {
            dnsttProcess = null
            status.text = "●  ENGINE ERROR"
            status.setTextColor(Color.rgb(255, 100, 100))
            appendLog("Cannot start DNS engine: ${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fun card(color: Int = Color.WHITE, radius: Int = 18, stroke: Int = Color.TRANSPARENT): GradientDrawable =
            GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(radius).toFloat()
                if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
            }
        fun label(text: String, size: Float = 14f, color: Int = ink, bold: Boolean = false) =
            TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(color)
                if (bold) setTypeface(null, Typeface.BOLD)
            }
        fun button(text: String, fill: Int, textColor: Int = Color.WHITE): Button =
            Button(this).apply {
                this.text = text
                textSize = 15f
                isAllCaps = false
                setTextColor(textColor)
                background = card(fill, 15, if (fill == Color.WHITE) Color.LTGRAY else fill)
                stateListAnimator = null
                minHeight = dp(54)
            }
        fun field(hintText: String, value: String = "", password: Boolean = false): EditText =
            EditText(this).apply {
                hint = hintText
                setSingleLine(true)
                setText(value)
                textSize = 14f
                setTextColor(ink)
                setHintTextColor(muted)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = card(Color.WHITE, 10, Color.LTGRAY)
                if (password) inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }

        val scrollRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
        }
        scrollRoot.addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(18))
        }
        val shield = label("⬟", 34f, ink, true)
        header.addView(shield, LinearLayout.LayoutParams(dp(52), dp(54)))
        val brandCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brandCol.addView(label("SlowDNS", 25f, ink, true))
        brandCol.addView(label("Koneksi DNS terenkripsi", 14f, muted))
        header.addView(brandCol, LinearLayout.LayoutParams(0, -2, 1f))
        val settingsIcon = button("⚙", Color.TRANSPARENT, ink)
        settingsIcon.background = card(Color.TRANSPARENT, 12)
        header.addView(settingsIcon, LinearLayout.LayoutParams(dp(54), dp(48)))
        content.addView(header)

        status = label("●  Belum terhubung", 14f, muted)
        status.gravity = Gravity.CENTER
        status.setPadding(0, dp(4), 0, dp(16))
        content.addView(status)

        val homePage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val configCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(18))
            background = card(Color.rgb(55, 58, 61), 18, Color.rgb(205, 205, 205))
        }
        configCard.addView(label("KONFIGURASI SLOWDNS", 14f, Color.WHITE, true))
        configInput = EditText(this).apply {
            hint = "dns://server.example:443#ns.example.net\\npayload: public-key:8.8.8.8"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            textSize = 14f
            minLines = 2
            maxLines = 4
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = card(Color.rgb(38, 40, 43), 14, Color.rgb(70, 72, 75))
        }
        configCard.addView(configInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(104)
        ).apply { topMargin = dp(12) })
        val importButton = button("IMPORT CONFIG", Color.rgb(70, 73, 76))
        importButton.setOnClickListener { importDnsConfig() }
        configCard.addView(importButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(42)
        ).apply { topMargin = dp(8) })
        homePage.addView(configCard)

        val optionNames = listOf(
            "Auto Ping", "UDPGW", "DNS", "Block Ads",
            "Speed Meter", "Auto Reconnect", "WakeLock", "Compression"
        )
        val optionsGrid = GridLayout(this).apply { columnCount = 2 }
        val savedOptions = getSharedPreferences("slowdns_options", MODE_PRIVATE)
        optionNames.forEach { name ->
            val check = CheckBox(this).apply {
                text = name
                textSize = 14f
                setTextColor(ink)
                buttonTintList = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(Color.rgb(104, 164, 35), Color.rgb(95, 99, 103))
                )
                isChecked = savedOptions.getBoolean(name, name == "DNS")
                setOnCheckedChangeListener { _, checked ->
                    savedOptions.edit().putBoolean(name, checked).apply()
                    appendLog("$name ${if (checked) "aktif" else "nonaktif"} (opsi disimpan).")
                }
            }
            optionsGrid.addView(check, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(58)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(2), dp(4), dp(2), dp(4))
            })
        }
        homePage.addView(optionsGrid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        val profileCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = card(Color.rgb(242, 242, 242), 18, Color.rgb(220, 220, 220))
        }
        profileCard.addView(label("Profil Server", 13f, muted, true))
        val profileRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val previous = button("‹", Color.rgb(225, 225, 225), ink)
        val profileName = label("SlowDNS Profile 1", 17f, ink, true)
        profileName.gravity = Gravity.CENTER
        val next = button("›", Color.rgb(225, 225, 225), ink)
        val copy = button("▢", Color.TRANSPARENT, muted)
        copy.background = card(Color.TRANSPARENT, 8)
        previous.setOnClickListener { appendLog("Profil sebelumnya belum dibuat; simpan beberapa profil pada pembaruan berikutnya.") }
        next.setOnClickListener { appendLog("Profil berikutnya belum dibuat; simpan beberapa profil pada pembaruan berikutnya.") }
        copy.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("SlowDNS config", configInput.text))
            Toast.makeText(this, "Config disalin", Toast.LENGTH_SHORT).show()
        }
        profileRow.addView(previous, LinearLayout.LayoutParams(dp(48), dp(48)))
        profileRow.addView(profileName, LinearLayout.LayoutParams(0, -2, 1f))
        profileRow.addView(next, LinearLayout.LayoutParams(dp(48), dp(48)))
        profileRow.addView(copy, LinearLayout.LayoutParams(dp(48), dp(48)))
        profileCard.addView(profileRow)
        val count = label("1 dari 9", 12f, muted)
        count.gravity = Gravity.CENTER
        profileCard.addView(count)
        homePage.addView(profileCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })

        val connect = button("HUBUNGKAN", Color.rgb(49, 52, 55))
        connect.textSize = 18f
        connect.setTypeface(null, Typeface.BOLD)
        homePage.addView(connect, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(76)
        ).apply { topMargin = dp(18) })
        connect.setOnClickListener {
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) {
                pendingConnect = true
                appendLog("Meminta izin VPN Android.")
                startActivityForResult(prepareIntent, vpnRequestCode)
            } else {
                appendLog("Izin VPN Android sudah diberikan.")
                startEngine()
            }
        }
        homePage.addView(label("●  Belum terhubung", 14f, muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(8))
        })
        content.addView(homePage)

        // Internal parsed fields are retained for the tunnel engine, but the
        // previous manual-entry screen is removed from the user interface.
        dns = field("DNS Resolver", "1.1.1.1")
        domain = field("Nameserver / Tunnel Domain")
        pubkey = field("Server Public Key (64 hex)")
        sshHost = field("SSH Host")
        sshPort = field("SSH Port", "22")
        sshUser = field("SSH Username")
        sshPass = field("SSH Password", password = true)

        val settingsPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        settingsPage.addView(label("Pengaturan", 22f, ink, true))
        settingsPage.addView(label(
            "SlowDNS Lite menggunakan satu konfigurasi dns://. Tempel konfigurasi di Beranda, lalu pilih IMPORT CONFIG.",
            14f, muted
        ).apply { setPadding(0, dp(12), 0, dp(16)) })
        val reset = button("HAPUS KONFIGURASI", Color.rgb(225, 225, 225), ink)
        reset.setOnClickListener {
            configInput.setText("")
            dns.setText("1.1.1.1")
            domain.setText("")
            pubkey.setText("")
            sshHost.setText("")
            sshPort.setText("22")
            sshUser.setText("")
            sshPass.setText("")
            getSharedPreferences("slowdns_profile", MODE_PRIVATE).edit().clear().apply()
            Toast.makeText(this, "Konfigurasi dihapus", Toast.LENGTH_SHORT).show()
        }
        settingsPage.addView(reset)
        settingsPage.addView(label(
            "Versi UI baru · profil DNS tunggal · SSH over DNS",
            12f, muted
        ).apply { setPadding(0, dp(18), 0, 0) })
        content.addView(settingsPage)

        val logsPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        logsPage.addView(label("Log koneksi", 20f, ink, true))
        log = label("Log: Ready", 12f, ink)
        log.setPadding(dp(8), dp(12), dp(8), dp(12))
        log.background = card(Color.WHITE, 12, Color.LTGRAY)
        logsPage.addView(log)
        val clearLog = button("HAPUS LOG", Color.rgb(225, 225, 225), ink)
        clearLog.setOnClickListener { log.text = "Log: Ready" }
        logsPage.addView(clearLog)
        content.addView(logsPage)

        traffic = label("TRAFFIC   ↑ 0 B/s     ↓ 0 B/s", 12f, muted)
        val saved = getSharedPreferences("slowdns_profile", MODE_PRIVATE)
        configInput.setText(saved.getString("config", ""))
        dns.setText(saved.getString("dns", "1.1.1.1"))
        domain.setText(saved.getString("domain", ""))
        pubkey.setText(saved.getString("pubkey", ""))
        sshHost.setText(saved.getString("sshHost", ""))
        sshPort.setText(saved.getString("sshPort", "22"))
        sshUser.setText(saved.getString("sshUser", ""))

        fun showPage(page: String) {
            homePage.visibility = if (page == "home") View.VISIBLE else View.GONE
            settingsPage.visibility = if (page == "settings") View.VISIBLE else View.GONE
            logsPage.visibility = if (page == "logs") View.VISIBLE else View.GONE
        }
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(7), dp(8), dp(7))
            setBackgroundColor(Color.WHITE)
        }
        fun navItem(text: String, page: String) {
            val item = TextView(this).apply {
                this.text = text
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(if (page == "home") ink else muted)
                setPadding(dp(4), dp(8), dp(4), dp(8))
                setOnClickListener { showPage(page) }
            }
            nav.addView(item, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        settingsIcon.setOnClickListener { showPage("settings") }
        navItem("⌂\\nBeranda", "home")
        navItem("▤\\nLog", "logs")
        navItem("⚙\\nPengaturan", "settings")
        scrollRoot.addView(nav, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(62)
        ))

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            addView(content, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ))
            addView(nav)
        }
        window.decorView.setBackgroundColor(bg)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContentView(outer)
    }

    override fun onDestroy() {
        stopEngine()
        super.onDestroy()
    }
}
