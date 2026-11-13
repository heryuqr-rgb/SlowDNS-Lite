package id.litevpn.slowdns

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
            setBackgroundColor(Color.rgb(12, 18, 30))
        }
        fun label(text: String) = TextView(this).apply {
            this.text = text; textSize = 13f; setTextColor(Color.LTGRAY)
            setPadding(2, 12, 2, 4)
        }
        fun field(hint: String, value: String = "") = EditText(this).apply {
            this.hint = hint; setSingleLine(true); setText(value)
            textSize = 15f; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            setPadding(14, 10, 14, 10)
            setBackgroundColor(Color.rgb(29, 39, 56))
        }
        val title = TextView(this).apply {
            text = "SLOWDNS LITE"; textSize = 25f; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER; setPadding(0, 12, 0, 4)
        }
        root.addView(title)
        root.addView(TextView(this).apply {
            text = "SSH over DNS • Manual profile"; textSize = 13f
            setTextColor(Color.rgb(95, 190, 255)); gravity = Gravity.CENTER
            setPadding(0, 0, 0, 18)
        })
        status = TextView(this).apply {
            text = "● DISCONNECTED"; textSize = 14f; setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER; setPadding(0, 10, 0, 12)
        }
        root.addView(status)
        dns = field("DNS Resolver", "1.1.1.1")
        domain = field("Nameserver / Tunnel Domain")
        pubkey = field("Server Public Key")
        sshHost = field("SSH Host")
        sshPort = field("SSH Port", "22")
        sshUser = field("SSH Username")
        sshPass = field("SSH Password")
        listOf("DNS RESOLVER" to dns, "NAMESERVER" to domain, "PUBLIC KEY" to pubkey,
            "SSH HOST" to sshHost, "SSH PORT" to sshPort, "SSH USERNAME" to sshUser,
            "SSH PASSWORD (OPTIONAL)" to sshPass).forEach { (name, input) ->
            root.addView(label(name)); root.addView(input,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 48))
        }
        val connect = Button(this).apply {
            text = "CONNECT"; textSize = 15f
            setOnClickListener {
                log.text = "Profile validation only. Native SLDNS tunnel engine is not integrated yet."
                status.text = "● NOT CONNECTED"
            }
        }
        root.addView(connect, LinearLayout.LayoutParams(-1, 52).apply { topMargin = 20 })
        log = TextView(this).apply {
            text = "Log: Ready"; textSize = 12f; setTextColor(Color.LTGRAY)
            setPadding(4, 14, 4, 4)
        }
        root.addView(log)
        setContentView(ScrollView(this).apply { addView(root) })
    }
}
