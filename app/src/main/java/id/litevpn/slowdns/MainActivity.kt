package id.litevpn.slowdns

import android.app.Activity
import android.net.VpnService
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

    private val bg = Color.rgb(12, 18, 30)
    private val panel = Color.rgb(29, 39, 56)
    private val accent = Color.rgb(55, 153, 235)
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
            } catch (e: Exception) {
                appendLog("SSH connection failed: ${e.message}")
                runOnUiThread {
                    status.text = "●  DNS OPEN / SSH FAILED"
                    status.setTextColor(Color.rgb(255, 170, 90))
                }
            }
        }.start()
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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(24))
            setBackgroundColor(bg)
        }

        val title = TextView(this).apply {
            text = "SLOWDNS LITE"
            textSize = 27f
            letterSpacing = 0.04f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.NORMAL)
            setPadding(0, dp(8), 0, dp(3))
        }
        root.addView(title)

        root.addView(TextView(this).apply {
            text = "SSH over DNS  •  Manual profile"
            textSize = 14f
            setTextColor(Color.rgb(105, 190, 255))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(18))
        })

        status = TextView(this).apply {
            text = "●  DISCONNECTED"
            textSize = 15f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(18))
        }
        root.addView(status)

        fun section(text: String) {
            root.addView(TextView(this).apply {
                this.text = text
                textSize = 12f
                letterSpacing = 0.06f
                setTextColor(Color.rgb(190, 202, 220))
                setPadding(dp(2), dp(11), dp(2), dp(5))
            })
        }

        fun field(hintText: String, value: String = "", password: Boolean = false): EditText {
            return EditText(this).apply {
                hint = hintText
                setSingleLine(true)
                setText(value)
                textSize = 15f
                setTextColor(Color.WHITE)
                setHintTextColor(Color.rgb(135, 146, 164))
                setPadding(dp(13), dp(9), dp(13), dp(9))
                background = rounded(panel, 7)
                if (password) inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        }

        dns = field("1.1.1.1", "1.1.1.1")
        domain = field("Nameserver / Tunnel Domain")
        pubkey = field("Server Public Key (64 hex)")
        sshHost = field("SSH Host")
        sshPort = field("22", "22")
        sshUser = field("SSH Username")
        sshPass = field("SSH Password", password = true)

        val fields = listOf(
            "DNS RESOLVER" to dns,
            "NAMESERVER" to domain,
            "PUBLIC KEY" to pubkey,
            "SSH HOST" to sshHost,
            "SSH PORT" to sshPort,
            "SSH USERNAME" to sshUser,
            "SSH PASSWORD (OPTIONAL)" to sshPass
        )
        fields.forEach { (caption, input) ->
            section(caption)
            root.addView(input, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)
            ))
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(4))
        }

        fun actionButton(text: String, color: Int): Button = Button(this).apply {
            this.text = text
            textSize = 13f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(color, 9)
            minHeight = dp(48)
            minimumHeight = dp(48)
            stateListAnimator = null
        }

        val connect = actionButton("CONNECT", accent)
        connect.setOnClickListener {
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) {
                pendingConnect = true
                appendLog("Requesting Android VPN permission.")
                startActivityForResult(prepareIntent, vpnRequestCode)
            } else {
                appendLog("Android VPN permission already granted.")
                startEngine()
            }
        }
        val disconnect = actionButton("DISCONNECT", Color.rgb(70, 80, 98))
        disconnect.setOnClickListener { stopEngine() }
        buttonRow.addView(connect, LinearLayout.LayoutParams(0, dp(50), 1f).apply {
            marginEnd = dp(6)
        })
        buttonRow.addView(disconnect, LinearLayout.LayoutParams(0, dp(50), 1f).apply {
            marginStart = dp(6)
        })
        root.addView(buttonRow)

        val save = actionButton("SAVE PROFILE", Color.rgb(39, 112, 91))
        save.setOnClickListener {
            getSharedPreferences("slowdns_profile", MODE_PRIVATE).edit()
                .putString("dns", dns.text.toString())
                .putString("domain", domain.text.toString())
                .putString("pubkey", pubkey.text.toString())
                .putString("sshHost", sshHost.text.toString())
                .putString("sshPort", sshPort.text.toString())
                .putString("sshUser", sshUser.text.toString())
                .apply()
            appendLog("Profile saved. Re-enter SSH password when needed.")
        }
        root.addView(save, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(46)
        ).apply { topMargin = dp(8) })

        traffic = TextView(this).apply {
            text = "TRAFFIC   ↑ 0 B/s     ↓ 0 B/s"
            textSize = 13f
            setTextColor(Color.rgb(170, 185, 205))
            background = rounded(panel, 8)
            setPadding(dp(12), dp(13), dp(12), dp(13))
        }
        root.addView(traffic, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) })

        log = TextView(this).apply {
            text = "Log: Ready"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(dp(3), dp(14), dp(3), dp(8))
        }
        root.addView(log)

        val saved = getSharedPreferences("slowdns_profile", MODE_PRIVATE)
        dns.setText(saved.getString("dns", "1.1.1.1"))
        domain.setText(saved.getString("domain", ""))
        pubkey.setText(saved.getString("pubkey", ""))
        sshHost.setText(saved.getString("sshHost", ""))
        sshPort.setText(saved.getString("sshPort", "22"))
        sshUser.setText(saved.getString("sshUser", ""))

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            clipToPadding = false
            addView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        window.decorView.setBackgroundColor(bg)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0
        setContentView(scroll)
    }

    override fun onDestroy() {
        stopEngine()
        super.onDestroy()
    }
}
