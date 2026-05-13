package com.meher.btthermalprint

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.res.ColorStateList
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val devices = linkedMapOf<String, BluetoothDevice>()
    private val deviceLabels = mutableListOf<String>()
    private lateinit var listAdapter: ArrayAdapter<String>
    private lateinit var statusText: TextView
    private lateinit var printButton: Button
    private lateinit var scanButton: Button
    private lateinit var paperGroup: RadioGroup
    private val isPrinting = AtomicBoolean(false)
    private var selectedAddress: String? = null
    private var sharedDocuments: List<SharedDocument> = emptyList()
    private var receiverRegistered = false

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.getParcelableExtraCompat<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    addDevice(device, bonded = false)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    scanButton.isEnabled = true
                    if (devices.isEmpty()) {
                        setStatus("No Bluetooth printers found. Pair the printer in Android settings, then scan again.")
                    } else {
                        setStatus("Select a printer.")
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedAddress = getPreferences(MODE_PRIVATE).getString(KEY_PRINTER_ADDRESS, null)
        sharedDocuments = SharedDocument.fromIntent(this, intent)
        buildUi()
        registerDiscoveryReceiver()
        ensurePermissionsThenLoadDevices()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedDocuments = SharedDocument.fromIntent(this, intent)
        refreshPrintState()
    }

    override fun onDestroy() {
        runCatching { bluetoothAdapter?.cancelDiscovery() }
        if (receiverRegistered) unregisterReceiver(discoveryReceiver)
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
            setPadding(dp(20), dp(18), dp(20), dp(16))
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val left: Int
            val top: Int
            val right: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val systemInsets = insets.getInsets(WindowInsets.Type.systemBars())
                left = systemInsets.left
                top = systemInsets.top
                right = systemInsets.right
                bottom = systemInsets.bottom
            } else {
                @Suppress("DEPRECATION")
                left = insets.systemWindowInsetLeft
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                right = insets.systemWindowInsetRight
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            view.setPadding(
                dp(20) + left,
                dp(18) + top,
                dp(20) + right,
                dp(16) + bottom,
            )
            insets
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        }
        window.statusBarColor = COLOR_BACKGROUND
        window.navigationBarColor = COLOR_BACKGROUND

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(18))
        }

        val appMark = TextView(this).apply {
            text = "BT"
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = rounded(COLOR_PRIMARY, dp(14))
        }
        header.addView(appMark, LinearLayout.LayoutParams(dp(48), dp(48)))

        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        titleBlock.addView(TextView(this).apply {
            text = "BT Thermal Print"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT)
        })
        titleBlock.addView(TextView(this).apply {
            text = "Bluetooth receipt printer"
            textSize = 13f
            setTextColor(COLOR_MUTED)
        })
        header.addView(titleBlock, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(Color.WHITE, dp(16), COLOR_STROKE, 1)
        }
        statusCard.addView(TextView(this).apply {
            text = "Status"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_PRIMARY)
        })
        statusText = TextView(this).apply {
            textSize = 15f
            setTextColor(COLOR_TEXT_SECONDARY)
            setPadding(0, dp(4), 0, 0)
        }
        statusCard.addView(statusText)
        root.addView(statusCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).withMargins(bottom = dp(14)))

        paperGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, 0, 0, dp(12))
            addView(RadioButton(context).apply {
                id = PAPER_58
                text = "58mm"
                isChecked = true
                buttonTintList = ColorStateList.valueOf(COLOR_PRIMARY)
                setTextColor(COLOR_TEXT)
            })
            addView(RadioButton(context).apply {
                id = PAPER_80
                text = "80mm"
                buttonTintList = ColorStateList.valueOf(COLOR_PRIMARY)
                setTextColor(COLOR_TEXT)
            })
        }
        root.addView(paperGroup)

        scanButton = Button(this).apply {
            text = "Scan printers"
            isAllCaps = false
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = ripple(COLOR_PRIMARY, dp(14))
            minHeight = dp(52)
            setOnClickListener { startDiscovery() }
        }
        root.addView(scanButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(52),
        ).withMargins(bottom = dp(14)))

        val printerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(Color.WHITE, dp(18), COLOR_STROKE, 1)
        }
        printerPanel.addView(TextView(this).apply {
            text = "Printers"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT)
            setPadding(dp(4), 0, dp(4), dp(8))
        })

        listAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_single_choice, deviceLabels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return (super.getView(position, convertView, parent) as TextView).apply {
                    textSize = 14f
                    setTextColor(COLOR_TEXT_SECONDARY)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = ripple(0xFFF8FAFC.toInt(), dp(12))
                }
            }
        }
        val list = ListView(this).apply {
            choiceMode = ListView.CHOICE_MODE_SINGLE
            adapter = listAdapter
            divider = null
            setPadding(0, 0, 0, 0)
            clipToPadding = false
            background = null
            onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
                selectedAddress = devices.keys.elementAt(position)
                getPreferences(MODE_PRIVATE).edit().putString(KEY_PRINTER_ADDRESS, selectedAddress).apply()
                refreshPrintState()
            }
        }
        printerPanel.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(printerPanel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            .withMargins(bottom = dp(14)))

        printButton = Button(this).apply {
            text = "Print shared document"
            isAllCaps = false
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = ripple(0xFF16A34A.toInt(), dp(14))
            minHeight = dp(52)
            setOnClickListener { printSharedDocuments() }
        }
        root.addView(printButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(52),
        ))

        val footer = TextView(this).apply {
            text = "Tip: pair the printer in Android Bluetooth settings if connection fails."
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(COLOR_MUTED)
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(footer)

        setContentView(root)
        refreshPrintState()
    }

    private fun registerDiscoveryReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(discoveryReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(discoveryReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun ensurePermissionsThenLoadDevices() {
        val missing = requiredPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            loadBondedDevices()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            loadBondedDevices()
        } else {
            setStatus("Bluetooth permission is required to find and print to thermal printers.")
            refreshPrintState()
        }
    }

    @SuppressLint("MissingPermission")
    private fun loadBondedDevices() {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            setStatus("Bluetooth is not available on this device.")
            return
        }
        if (!adapter.isEnabled) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            setStatus("Turn on Bluetooth, then return to this app.")
            return
        }

        adapter.bondedDevices.forEach { addDevice(it, bonded = true) }
        if (devices.isEmpty()) setStatus("No paired printers yet. Tap Scan printers or pair one in Android settings.")
        refreshPrintState()
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        ensurePermissionsThenLoadDevices()
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            setStatus("Turn on Bluetooth first.")
            return
        }
        scanButton.isEnabled = false
        setStatus("Scanning for nearby Bluetooth printers...")
        adapter.cancelDiscovery()
        adapter.startDiscovery()
    }

    @SuppressLint("MissingPermission")
    private fun addDevice(device: BluetoothDevice, bonded: Boolean) {
        val address = device.address ?: return
        devices[address] = device
        val name = device.name?.takeIf { it.isNotBlank() } ?: "Unknown printer"
        val suffix = if (bonded || device.bondState == BluetoothDevice.BOND_BONDED) "paired" else "found"
        val label = "$name\n$address ($suffix)"
        val index = devices.keys.indexOf(address)
        if (index >= 0 && index < deviceLabels.size) {
            deviceLabels[index] = label
        } else {
            deviceLabels += label
        }
        listAdapter.notifyDataSetChanged()
        refreshPrintState()
    }

    private fun refreshPrintState() {
        val hasShare = sharedDocuments.isNotEmpty()
        val hasPrinter = selectedAddress != null && devices.containsKey(selectedAddress)
        printButton.visibility = if (hasShare) View.VISIBLE else View.GONE
        printButton.isEnabled = hasShare && hasPrinter && !isPrinting.get()
        val docText = if (hasShare) "${sharedDocuments.size} shared item(s) ready." else "Open this app from Android share to print a document."
        val printerText = selectedAddress?.let { " Selected: $it" } ?: " Select a printer."
        setStatus(docText + printerText)
    }

    private fun printSharedDocuments() {
        val address = selectedAddress
        val device = if (address == null) null else devices[address]
        val adapter = bluetoothAdapter
        if (adapter == null || device == null || sharedDocuments.isEmpty()) {
            refreshPrintState()
            return
        }
        if (!isPrinting.compareAndSet(false, true)) return
        printButton.isEnabled = false
        setStatus("Preparing document for printing...")

        val paperWidthPx = if (paperGroup.checkedRadioButtonId == PAPER_80) 576 else 384
        thread(name = "printer-worker") {
            val result = runCatching {
                BluetoothPrinter(device).use { printer ->
                    printer.connect(adapter)
                    sharedDocuments.flatMap { it.toEscPosJobs(this, paperWidthPx) }.forEach { job ->
                        printer.write(job)
                        Thread.sleep(250)
                    }
                }
            }

            runOnUiThread {
                isPrinting.set(false)
                printButton.isEnabled = true
                result
                    .onSuccess { setStatus("Print job sent.") }
                    .onFailure {
                        setStatus("Print failed: ${it.message ?: it.javaClass.simpleName}")
                        Toast.makeText(this, "Print failed", Toast.LENGTH_LONG).show()
                    }
            }
        }
    }

    private fun setStatus(message: String) {
        statusText.text = message
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int, strokeColor: Int? = null, strokeWidth: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (strokeColor != null && strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }
    }

    private fun ripple(color: Int, radius: Int): RippleDrawable {
        return RippleDrawable(
            ColorStateList.valueOf(0x1F2563EB.toInt()),
            rounded(color, radius),
            rounded(Color.WHITE, radius),
        )
    }

    private fun LinearLayout.LayoutParams.withMargins(
        left: Int = leftMargin,
        top: Int = topMargin,
        right: Int = rightMargin,
        bottom: Int = bottomMargin,
    ): LinearLayout.LayoutParams = apply {
        setMargins(left, top, right, bottom)
    }

    private fun requiredPermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private companion object {
        const val REQUEST_PERMISSIONS = 10
        const val KEY_PRINTER_ADDRESS = "printer_address"
        const val PAPER_58 = 58
        const val PAPER_80 = 80
        const val COLOR_BACKGROUND = 0xFFF4F7FB.toInt()
        const val COLOR_PRIMARY = 0xFF2563EB.toInt()
        const val COLOR_TEXT = 0xFF111827.toInt()
        const val COLOR_TEXT_SECONDARY = 0xFF374151.toInt()
        const val COLOR_MUTED = 0xFF64748B.toInt()
        const val COLOR_STROKE = 0xFFE2E8F0.toInt()
    }
}

@Suppress("DEPRECATION")
private inline fun <reified T : android.os.Parcelable> Intent.getParcelableExtraCompat(name: String): T? {
    return if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(name, T::class.java) else getParcelableExtra(name)
}
