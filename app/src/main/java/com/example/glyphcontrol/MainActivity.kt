package com.example.glyphcontrol

import android.content.ComponentName
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphException
import com.nothing.ketchum.GlyphFrame
import com.nothing.ketchum.GlyphManager
import okhttp3.*
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private val TAG = "GlyphControl"
    private var mGM: GlyphManager? = null
    private var mCallback: GlyphManager.Callback? = null

    private lateinit var tvStatus: TextView
    private lateinit var etWsUrl: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var loginLayout: LinearLayout
    private lateinit var mainLayout: LinearLayout
    
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private var isDarkMode = true

    override fun onCreate(savedInstanceState: Bundle?) {
        // Enforce initial dark theme
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        etWsUrl = findViewById(R.id.etWsUrl)
        etUser = findViewById(R.id.etUsername)
        etPass = findViewById(R.id.etPassword)
        loginLayout = findViewById(R.id.loginLayout)
        mainLayout = findViewById(R.id.mainLayout)
        
        initGlyphService()
        
        findViewById<Button>(R.id.btnLogin).setOnClickListener {
            val user = etUser.text.toString()
            val pass = etPass.text.toString()
            val url = etWsUrl.text.toString()
            
            if (user == "karanrana" && pass == "karanrana") {
                if (url.isNotEmpty()) {
                    loginLayout.visibility = View.GONE
                    mainLayout.visibility = View.VISIBLE
                    connectWebSocket(url)
                } else {
                    Toast.makeText(this, "Enter WebSocket URL", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Invalid credentials", Toast.LENGTH_SHORT).show()
            }
        }
        
        findViewById<Button>(R.id.btnTheme).setOnClickListener {
            isDarkMode = !isDarkMode
            if (isDarkMode) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
        }
    }

    private fun initGlyphService() {
        mCallback = object : GlyphManager.Callback {
            override fun onServiceConnected(componentName: ComponentName) {
                Log.i(TAG, "Glyph Service Connected")
                runOnUiThread { tvStatus.text = "STATUS: SDK CONNECTED" }
                mGM?.let { gm ->
                    if (Common.is20111()) gm.register(Glyph.DEVICE_20111)
                    if (Common.is22111()) gm.register(Glyph.DEVICE_22111)
                    if (Common.is23111()) gm.register(Glyph.DEVICE_23111)
                    if (Common.is23113()) gm.register(Glyph.DEVICE_23113)
                    if (Common.is24111()) gm.register(Glyph.DEVICE_24111)
                    try {
                        gm.openSession()
                    } catch (e: GlyphException) {
                        Log.e(TAG, "Error opening session: \${e.message}")
                    }
                }
            }

            override fun onServiceDisconnected(componentName: ComponentName) {
                Log.i(TAG, "Glyph Service Disconnected")
                runOnUiThread { tvStatus.text = "STATUS: SDK DISCONNECTED" }
                mGM?.closeSession()
            }
        }
        mGM = GlyphManager.getInstance(applicationContext)
        mGM?.init(mCallback)
    }
    
    private fun connectWebSocket(url: String) {
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread { tvStatus.text = "STATUS: WEBSOCKET CONNECTED" }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runOnUiThread {
                    try {
                        val json = JSONObject(text)
                        val activeZones = json.getJSONArray("active_zones")
                        val progress = json.getInt("progress")
                        
                        val zones = mutableListOf<String>()
                        for (i in 0 until activeZones.length()) {
                            zones.add(activeZones.getString(i))
                        }
                        
                        updateGlyphs(zones, progress)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing state", e)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread { tvStatus.text = "STATUS: WEBSOCKET ERROR" }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread { tvStatus.text = "STATUS: WEBSOCKET CLOSED" }
            }
        })
    }
    
    private fun updateGlyphs(activeZones: List<String>, progress: Int) {
        val gm = mGM ?: return
        
        if (activeZones.isEmpty() && progress == 0) {
            gm.turnOff()
            return
        }
        
        try {
            val builder = gm.glyphFrameBuilder
            
            // Rebuild the composite frame
            if (activeZones.contains("A1")) builder.buildChannelA()
            if (activeZones.contains("B1")) builder.buildChannelB()
            if (activeZones.contains("C")) builder.buildChannelC()
            if (activeZones.contains("E1")) builder.buildChannelE()
            // If D1 is in activeZones but no progress, just turn it on fully.
            // If progress is there, we use displayProgressAndToggle anyway.
            if (activeZones.contains("D1")) builder.buildChannelD()
            
            val frame = builder.build()
            
            if (progress > 0) {
                gm.displayProgressAndToggle(frame, progress, false)
            } else {
                gm.toggle(frame)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing glyph update", e)
        }
    }

    override fun onDestroy() {
        webSocket?.close(1000, "App destroyed")
        try {
            mGM?.closeSession()
        } catch (e: GlyphException) {
            Log.e(TAG, "Error closing session: \${e.message}")
        }
        mGM?.unInit()
        super.onDestroy()
    }
}
