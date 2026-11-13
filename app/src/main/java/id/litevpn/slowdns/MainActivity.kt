package id.litevpn.slowdns

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

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

    private val bg = Color.rgb(12, 18, 30)
    private val panel = Color.rgb(29, 39, 56)
    private val accent = Color.rgb(55, 153, 235)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int = 10): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
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
        pubkey = field("Server Public Key")
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
            val missing = when {
                dns.text.isNullOrBlank() -> "DNS resolver is required."
                domain.text.isNullOrBlank() -> "Nameserver / tunnel domain is required."
                pubkey.text.isNullOrBlank() -> "Server public key is required."
                sshHost.text.isNullOrBlank() -> "SSH host is required."
                sshUser.text.isNullOrBlank() -> "SSH username is required."
                sshPort.text.toString().toIntOrNull() !in 1..65535 -> "SSH port must be between 1 and 65535."
                else -> null
            }
            if (missing != null) {
                log.text = "Log: $missing"
                status.text = "●  PROFILE INCOMPLETE"
                status.setTextColor(Color.rgb(255, 190, 90))
            } else {
                log.text = "Log: Profile looks valid. SLDNS tunnel engine is not integrated yet."
                status.text = "●  ENGINE NOT INSTALLED"
                status.setTextColor(Color.rgb(255, 190, 90))
            }
        }
        val disconnect = actionButton("DISCONNECT", Color.rgb(70, 80, 98))
        disconnect.setOnClickListener {
            status.text = "●  DISCONNECTED"
            status.setTextColor(Color.LTGRAY)
            log.text = "Log: Disconnected (no active tunnel)."
        }
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
            log.text = "Log: Profile saved. Re-enter SSH password when needed."
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
            addView(root, ScrollView.LayoutParams(
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
}
