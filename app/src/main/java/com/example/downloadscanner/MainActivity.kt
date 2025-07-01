package com.example.downloadscanner
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothSocket
import android.content.*
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.bluetooth.BluetoothManager
import android.os.Handler
import android.graphics.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import androidx.core.view.WindowCompat
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import java.io.*
import java.util.UUID
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Alignment
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import android.provider.DocumentsContract
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.content.res.Resources
import android.content.Context
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavGraph.Companion.findStartDestination
import android.widget.LinearLayout
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.core.app.ActivityCompat
import android.graphics.Color as AndroidColor
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.bluetooth.le.*
import android.content.Intent
import android.app.AlertDialog
import android.provider.Settings

sealed class NavigationItem(
    val route: String,
    val title: String,
    val icon: ImageVector
) {
    object Home : NavigationItem("home", "Home", Icons.Default.Home)
    object History : NavigationItem("history", "History", Icons.Default.History)
    object Settings : NavigationItem("settings", "Settings", Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {

    private companion object {
        const val PRINTABLE_WIDTH_MM = 48f
        const val DPI = 203
        const val MM_TO_INCH = 25.4f
        val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val SAFETY_SCALE = 0.95f
        const val REQUEST_ENABLE_BT = 1234
    }private var bluetoothLeScanner: BluetoothLeScanner? = null
    private val _isScanning = mutableStateOf(false)
    val isScanning: Boolean
        get() = _isScanning.value

    private val bluetoothAdapter: BluetoothAdapter?
        get() {
            val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            return manager?.adapter
        }
    private val _scannedDevices = mutableStateListOf<BluetoothDevice>()
    private val scannedDevices: List<BluetoothDevice> get() = _scannedDevices.toList()
    private var selectedPdfUri by mutableStateOf<Uri?>(null)
    private var isPdfSelected by mutableStateOf(false)
    private var connectedSocket by mutableStateOf<BluetoothSocket?>(null)
    private var autoPrintEnabled by mutableStateOf(false)
    private val printedDocuments = mutableStateListOf<String>()
    private val paperWidthPixels by lazy {
        (PRINTABLE_WIDTH_MM / MM_TO_INCH * DPI * SAFETY_SCALE).toInt()
    }
    val fileList = mutableListOf<String>()
    val adapter = PdfListAdapter(fileList)

    private var contentObserver: ContentObserver? = null
    private val processedPdfs = mutableSetOf<String>()
    private var isReceiverRegistered = false
    private var selectedDirectoryUri: Uri? = null
    private var selectedDirectoryDisplayName by mutableStateOf<String?>(null)

    val downloadsUri: Uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
    val REQUEST_CODE_OPEN_DIRECTORY = 1001

    private var scanJob: Job? = null
    private val processedFiles = mutableSetOf<String>()
    private val historyList = mutableStateListOf<String>()



    fun openDirectoryPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        startActivityForResult(intent, REQUEST_CODE_OPEN_DIRECTORY)
    }
    private fun getDisplayName(uri: Uri): String? {
        return try {
            // First try DocumentsContract
            DocumentsContract.getDocumentId(uri)?.let { docId ->
                if (docId.contains(":")) {
                    docId.split(":")[1] // Typically the actual folder name is after the colon
                } else {
                    docId
                }
            } ?: run {
                // Fallback to content resolver query
                contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(0)
                    } else {
                        uri.lastPathSegment ?: "Selected Folder"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error getting display name", e)
            uri.lastPathSegment ?: "Selected Folder"
        }
    }
    class PdfListAdapter(private val files: List<String>) : RecyclerView.Adapter<PdfListAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val fileNameTextView: TextView = view.findViewById(android.R.id.text1) // Using system resource
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            // Create view programmatically instead of using layout inflation
            val view = TextView(parent.context).apply {
                setPadding(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 16.dpToPx())
                textSize = 16f
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.fileNameTextView.text = files[position]
        }

        override fun getItemCount() = files.size

        private fun Int.dpToPx(): Int = (this * Resources.getSystem().displayMetrics.density).toInt()
    }

    private val requiredPermissions: Array<String>
        get() {
            return arrayOf("dummy_permission") // Not used anymore
        }

    private fun checkSelfPermissions(): Boolean {
        return true // Always return true
    }
    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            selectedPdfUri = it
            isPdfSelected = true
            showToast("PDF selected")
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Skip permission check and start scanning anyway
        startBluetoothScanning()
    }
          override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            PrinterApp(
                scannedDevices = scannedDevices,
                isScanning = isScanning,
                isPdfSelected = isPdfSelected,
                isConnected = connectedSocket != null,
                autoPrintEnabled = autoPrintEnabled,
                printedDocuments = printedDocuments,
                onSelectPdf = { filePickerLauncher.launch("application/pdf") },
                onStartScanning = { startBluetoothScanning() },
                onDeviceSelected = { connectToPrinter(it) },

                onPrintPdf = {
                    selectedPdfUri?.let { uri ->
                        lifecycleScope.launch {
                            val success = printPdf(uri)
                            if (success) {
                                // Add to history
                                printedDocuments.add(uri.lastPathSegment ?: "Printed Document")
                            }
                        }
                    } ?: showToast("No PDF selected")
                },

                fileList = fileList,
                onToggleAutoPrint = ::handleAutoPrintToggle
            )
        }
        if (!checkSelfPermissions()) {
            requestPermissionsLauncher.launch(requiredPermissions)
        } else {

        }
    }
    override fun onPause() {
        super.onPause()
        stopBluetoothScanning()
    }
    override fun onDestroy() {
        super.onDestroy()
        cleanupResources()
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_OPEN_DIRECTORY && resultCode == Activity.RESULT_OK) {
            data?.data?.also { uri ->
                // Persist permission
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )

                    // Update both URI and display name
                    selectedDirectoryUri = uri
                    selectedDirectoryDisplayName = getDisplayName(uri) ?: "Selected Folder"

                    // Log for debugging
                    Log.d("DirectorySelection", "Selected: $uri, Name: $selectedDirectoryDisplayName")

                    // Start monitoring if auto-print is enabled
                    if (autoPrintEnabled) {
                        startFileMonitoring()
                    }
                } catch (e: Exception) {
                    Log.e("DirectorySelection", "Error taking persistable permission", e)
                    showToast("Failed to save folder selection")
                }
            }
        }
    }


    private fun handleAutoPrintToggle(enabled: Boolean) {
        autoPrintEnabled = enabled

        if (enabled) {
            if (selectedDirectoryUri == null) {
                showToast("Please select a directory first")
                openDirectoryPicker()
            } else {
                startFileMonitoring()
                showToast("Auto-print enabled")

            }
        } else {
            stopFileMonitoring()
            showToast("Auto-print disabled")
        }


    }


    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                Log.d("BluetoothScan", "Found device: ${device.name} - ${device.address}")
                val alreadyExists = _scannedDevices.any { it.address == device.address }
                if (!alreadyExists) {
                    runOnUiThread {
                        _scannedDevices.add(device)
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onBatchScanResults(results: List<ScanResult>?) {
            results?.forEach { result ->
                result.device?.let { device ->
                    Log.d("BluetoothScan", "Batch found: ${device.name}")
                    val alreadyExists = _scannedDevices.any { it.address == device.address }
                    if (!alreadyExists) {
                        runOnUiThread {
                            _scannedDevices.add(device)
                        }
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BluetoothScan", "Scan failed with code $errorCode")
            showToast("Scan failed: $errorCode")
        }
    }
    @SuppressLint("MissingPermission")
    private fun startBluetoothScanning() {
        val adapter = bluetoothAdapter ?: return

        if (!adapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            return
        }

        try {
            _scannedDevices.clear()
            _isScanning.value = true
            bluetoothLeScanner = adapter.bluetoothLeScanner
            bluetoothLeScanner?.startScan(listOf(), getScanSettings(), scanCallback)
            showToast("Scanning for printers...")
            Handler(Looper.getMainLooper()).postDelayed({
                stopBluetoothScanning()
            }, 6000) // 6 seconds = 6000 ms

        } catch (e: SecurityException) {
            Log.e("BluetoothScan", "Security exception", e)
            showToast("Permission denied during scan")
        } catch (e: Exception) {
            Log.e("BluetoothScan", "Unexpected error", e)
            showToast("Error starting scan")
        }
    }
    private fun getScanSettings(): ScanSettings {
        return ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
    }
    @SuppressLint("MissingPermission")
    private fun stopBluetoothScanning() {
        if (_isScanning.value) {
            bluetoothLeScanner?.stopScan(scanCallback)
            _isScanning.value = false
        }
    }

    private fun checkBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_SCAN) &&
                    hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            true
        }
    }

    private fun connectToPrinter(device: BluetoothDevice) {
                try {
            bluetoothAdapter?.cancelDiscovery()
            val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket.connect()
            connectedSocket = socket
            showToast("Connected to ${device.name ?: "printer"}")
        } catch (e: SecurityException) {
            showToast("Connection permission denied")
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleAlternativeConnection(device: BluetoothDevice, e: IOException) {
        connectedSocket = null
        showToast("Connection failed: ${e.message}")
        try {
            device::class.java.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                .invoke(device, 1)?.let {
                    (it as BluetoothSocket).connect()
                    connectedSocket = it
                }
        } catch (ex: Exception) {
            showToast("Alternative connection failed: ${ex.message}")
        }
    }
    private val pollingHandler = Handler(Looper.getMainLooper())
    private var pollingRunnable: Runnable? = null
    private fun startFileMonitoring() {

        if (pollingRunnable != null) return

        pollingRunnable = object : Runnable {
            override fun run() {
                if (autoPrintEnabled) {
                    selectedDirectoryUri?.let { uri ->
                        lifecycleScope.launch {
                            scanSAFDirectory(uri)

                        }
                    }
                }
                pollingHandler.postDelayed(this, 3000) // Scan every 3 seconds

            }
        }
        pollingHandler.post(pollingRunnable!!)
    }

    private fun stopFileMonitoring() {
        pollingRunnable?.let {
            pollingHandler.removeCallbacks(it)
            pollingRunnable = null
        }
    }




    suspend fun scanSAFDirectory(uri: Uri) {
//    withContext(Dispatchers.Main) {
//        showToast("hello")
//    }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            uri,
            DocumentsContract.getTreeDocumentId(uri)
        )

        val cursor = contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        ) ?: return

        val receiptPattern = Regex("""receipt-\d{1,2}-\d{4}\.pdf""", RegexOption.IGNORE_CASE)

        cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(0)
                val documentId = it.getString(1)
                val mimeType = it.getString(2)

                if (mimeType == "application/pdf" &&
                    receiptPattern.matches(name) &&
                    !processedPdfs.contains(name)
                ) {
                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(uri, documentId)

                    val printSuccess = withContext(Dispatchers.Main) {
                        printPdf(documentUri)
                    }

                    if (printSuccess) {
                        processedPdfs.add(name)

                        withContext(Dispatchers.Main) {
                            addToHistory(name)
                            Log.d("History", "Added to history: $name, Current history: $historyList")
                        }

                        deleteDocument(documentUri)
                    } else {
                        Log.d("Printing", "Print failed for $name - not deleting")
                    }
                }
            }
        }
    }

    fun deleteDocument(uri: Uri): Boolean {
        return try {
            DocumentsContract.deleteDocument(contentResolver, uri)
            Log.d("SAF", "Deleted: $uri")
            true
        } catch (e: Exception) {
            Log.e("SAF", "Failed to delete document", e)
            false
        }
    }

    private fun addToHistory(name: String) {
        historyList.add(0, name)
        if (historyList.size > 100) {
            historyList.removeAt(historyList.size - 1)
        }
    }



    // Query MediaStore for files in the Downloads folder
    private fun queryMediaStore() {
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.RELATIVE_PATH
        )

        val uri = MediaStore.Files.getContentUri("external")
        val cursor = contentResolver.query(
            uri,
            projection,
            null,  // No selection
            null,
            "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
        )

        cursor?.use {
            val nameIndex = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val pathIndex = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH)

            while (it.moveToNext()) {
                val name = it.getString(nameIndex)
                val path = it.getString(pathIndex)

                // Only include files in Downloads with specific extensions
                if (path.contains("Download", ignoreCase = true)) {
                    if ((name.endsWith(".pdf", true) ||
                                name.endsWith(".docx", true) ||
                                name.endsWith(".txt", true) ||
                                name.endsWith(".jpg", true) ||
                                name.endsWith(".png", true) ||
                                name.endsWith(".mp3", true) ||
                                name.endsWith(".mp4", true)) &&
                        !fileList.contains(name)
                    ) {
                        fileList.add(name)
                        Log.d("MediaStore", "Found: $name")
                    }
                }
            }
        }

        // Log the final list
        Log.d("queryMediaStore", "Final file list: $fileList")
    }
    private fun findPdfUriByName(fileName: String): Uri? {
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(fileName)

        contentResolver.query(downloadsUri, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                return Uri.withAppendedPath(downloadsUri, id.toString())
            }
        }
        return null
    }

    suspend fun printPdf(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        if (connectedSocket == null) {
            withContext(Dispatchers.Main) { showToast("Not connected to printer") }
            return@withContext false
        }

        return@withContext try {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                PdfRenderer(pfd).use { pdfRenderer ->
                    connectedSocket?.outputStream?.let { outputStream ->
                        initializePrinter(outputStream)

                        for (i in 0 until pdfRenderer.pageCount) {
                            pdfRenderer.openPage(i).use { page ->
                                val bitmap = Bitmap.createBitmap(
                                    page.width,
                                    page.height,
                                    Bitmap.Config.ARGB_8888
                                )
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                                val scaledBitmap = scaleBitmapToPrintableWidth(bitmap)
                                val printCommands = convertBitmapToEscPos(scaledBitmap)
                                outputStream.write(printCommands)

                                if (i < pdfRenderer.pageCount - 1) {
                                    outputStream.write(byteArrayOf(0x0A, 0x0A))
                                }
                            }
                        }

                        outputStream.write(byteArrayOf(0x0A, 0x0A, 0x0A, 0x0A))
                        outputStream.write(byteArrayOf(0x1D, 0x56, 0x41, 0x00))
                        outputStream.flush()

                        true // Return true if everything succeeded
                    } ?: false // Return false if outputStream is null
                }
            } ?: false // Return false if file descriptor couldn't be opened
        } catch (e: Exception) {
            Log.e("PrintPDF", "Error printing PDF", e)
            withContext(Dispatchers.Main) {
                showToast("Print failed: ${e.localizedMessage}")
            }
            false // Return false on any exception
        }
    }


    private fun initializePrinter(outputStream: OutputStream) {
        outputStream.write(byteArrayOf(0x1B, 0x40))
        outputStream.write(byteArrayOf(0x1D, 0x4C, 0x00, 0x00))
        outputStream.write(byteArrayOf(
            0x1D, 0x57,
            (paperWidthPixels % 256).toByte(),
            (paperWidthPixels / 256).toByte()
        ))
        outputStream.write(byteArrayOf(0x1B, 0x45, 0x00))
        outputStream.write(byteArrayOf(0x1B, 0x21, 0x00))
        outputStream.write(byteArrayOf(0x1B, 0x33, 0x00))
        outputStream.write(byteArrayOf(0x1C, 0x2E))
    }
    private fun scaleBitmapToPrintableWidth(original: Bitmap): Bitmap {
        val scaleFactor = paperWidthPixels.toFloat() / original.width.toFloat()
        val scaledHeight = (original.height * scaleFactor).toInt()

        val matrix = Matrix().apply { postScale(scaleFactor, scaleFactor) }
        val scaledBitmap = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)

        val output = Bitmap.createBitmap(paperWidthPixels, scaledHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output).apply {
            drawColor(android.graphics.Color.WHITE) // Use android.graphics.Color here
            drawBitmap(scaledBitmap, 0f, 0f, null)
        }

        return convertToBlackAndWhite(output)
    }

    private fun convertToBlackAndWhite(bitmap: Bitmap): Bitmap {
        val bwBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        for (i in pixels.indices) {
            val r = AndroidColor.red(pixels[i]) // lowercase 'red' from android.graphics.Color
            val g = AndroidColor.green(pixels[i])
            val b = AndroidColor.blue(pixels[i])
            val luminance = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            pixels[i] = if (luminance < 160) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }

        bwBitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return bwBitmap
    }

    private fun convertBitmapToEscPos(bitmap: Bitmap): ByteArray {
        return ByteArrayOutputStream().apply {
            write(byteArrayOf(0x1D, 0x76, 0x30, 0x00))
            val widthBytes = (bitmap.width + 7) / 8
            write(
                byteArrayOf(
                    (widthBytes % 256).toByte(),
                    (widthBytes / 256).toByte(),
                    (bitmap.height % 256).toByte(),
                    (bitmap.height / 256).toByte()
                )
            )

            for (y in 0 until bitmap.height) {
                for (xByte in 0 until widthBytes) {
                    var byte = 0
                    for (bit in 0..7) {
                        val x = xByte * 8 + bit
                        if (x < bitmap.width && bitmap.getPixel(x, y) == android.graphics.Color.BLACK) {
                            byte = byte or (0x80 shr bit)
                        }
                    }
                    write(byte)
                }
            }
        }.toByteArray()
    }

    private fun cleanupResources() {
        stopFileMonitoring()
        try {
            connectedSocket?.close()
        } catch (e: IOException) {
            Log.e("MainActivity", "Socket close error", e)
        }
        try {
            bluetoothAdapter?.cancelDiscovery()
        } catch (e: SecurityException) {
            Log.e("MainActivity", "Bluetooth discovery cancel error", e)
        }

    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    @Composable
    fun PrinterApp(
        scannedDevices: List<BluetoothDevice>,
        isScanning: Boolean,
        isPdfSelected: Boolean,
        isConnected: Boolean,
        autoPrintEnabled: Boolean,
        printedDocuments: List<String>,
        onSelectPdf: () -> Unit,
        onStartScanning: () -> Unit,
        onDeviceSelected: (BluetoothDevice) -> Unit,
        onPrintPdf: () -> Unit,
        fileList: List<String>,
        onToggleAutoPrint: (Boolean) -> Unit
    ) {
        val navController = rememberNavController()
        val context = LocalContext.current
        Scaffold(
            bottomBar = { BottomNavigationBar(navController) }
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                NavigationHost(
                    navController = navController,
                    scannedDevices = scannedDevices,
                    isScanning = isScanning,
                    isPdfSelected = isPdfSelected,
                    isConnected = isConnected,
                    autoPrintEnabled = autoPrintEnabled,
                    printedDocuments = printedDocuments,
                    onSelectPdf = onSelectPdf,
                    onStartScanning = onStartScanning,
                    onDeviceSelected = onDeviceSelected,
                    onPrintPdf = onPrintPdf,
                    onToggleAutoPrint = onToggleAutoPrint,
                    fileList = fileList,
                    selectedDirectoryUri = selectedDirectoryUri,
                    selectedDirectoryDisplayName = selectedDirectoryDisplayName,
                    onSelectDirectory = {
                        (context as MainActivity).openDirectoryPicker()
                    }
                )
            }
        }
    }

    @Composable
    fun NavigationHost(
        navController: NavHostController,
        scannedDevices: List<BluetoothDevice>,
        isScanning: Boolean,
        isPdfSelected: Boolean,
        isConnected: Boolean,
        autoPrintEnabled: Boolean,
        printedDocuments: List<String>,
        onSelectPdf: () -> Unit,
        onStartScanning: () -> Unit,
        onDeviceSelected: (BluetoothDevice) -> Unit,
        onPrintPdf: () -> Unit,
        onToggleAutoPrint: (Boolean) -> Unit,
        fileList: List<String>,
        selectedDirectoryUri: Uri?,
        selectedDirectoryDisplayName: String?,
        onSelectDirectory: () -> Unit
    ) {
        NavHost(navController = navController, startDestination = "home") {
            composable("home") {
                PrinterHomeScreen(
                    scannedDevices = scannedDevices,
                    isScanning = isScanning,
                    isPdfSelected = isPdfSelected,
                    isConnected = isConnected,
                    onSelectPdf = onSelectPdf,
                    onStartScanning = onStartScanning,
                    onDeviceSelected = onDeviceSelected,
                    onPrintPdf = onPrintPdf
                )
            }
            composable("settings") {
                SettingsScreen(
                    autoPrintEnabled = autoPrintEnabled,
                    fileList = fileList,
                    onToggleAutoPrint = onToggleAutoPrint,
                    selectedDirectoryUri = selectedDirectoryUri,
                    selectedDirectoryDisplayName = selectedDirectoryDisplayName,
                    onSelectDirectory = onSelectDirectory
                )
            }
            composable("history") {
                PrintHistoryScreen(historyList = historyList)
            }
        }
    }
    @Composable
    fun PrinterHomeScreen(
        scannedDevices: List<BluetoothDevice>,
        isScanning: Boolean,
        isPdfSelected: Boolean,
        isConnected: Boolean,
        onSelectPdf: () -> Unit,
        onStartScanning: () -> Unit,
        onDeviceSelected: (BluetoothDevice) -> Unit,
        onPrintPdf: () -> Unit
    ) {
        val context = LocalContext.current
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Skip permission check on Android 10
        }
        Box(modifier = Modifier.fillMaxSize()) {
            // Background image layer
            Image(
                painter = painterResource(id = R.drawable.app_background),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            // Optional: dark overlay for better text visibility
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f))
            )

            // Foreground UI content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(72.dp))

                // Title
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Bluetooth Receipt",
                        fontSize = 45.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Printing App",
                        fontSize = 45.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(60.dp))

                // Buttons and rest (same as before)
                Button(
                    onClick = onSelectPdf,
                    modifier = Modifier.fillMaxWidth(0.8f).height(60.dp)
                ) {
                    Text("Select PDF")
                }

                Spacer(modifier = Modifier.height(8.dp))

                val printButtonEnabled = isPdfSelected && isConnected
                val buttonColor = if (printButtonEnabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) // slightly faded
                }

                Button(
                    onClick = onPrintPdf,
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(60.dp),
                    enabled = printButtonEnabled,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = buttonColor,
                        disabledContainerColor = buttonColor // make disabled color same but faded
                    )
                ) {
                    Text(
                        if (isConnected) "Print PDF" else "Connect printer first",
                        color = Color.White // ensure text is visible on the button
                    )
                }


                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        requestPermissionsLauncher.launch(arrayOf(
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ))
                    },
                    modifier = Modifier.fillMaxWidth(0.8f).height(60.dp),
                    enabled = !isScanning
                ) {
                    Text(if (isScanning) "Scanning..." else "Scan Printers")
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text("Available Printers:", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (isConnected) "Connected" else "Disconnected",
                    color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )

                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                ) {
                    items(scannedDevices) { device ->
                        val deviceName = when {
                            !hasPermission -> "Permission Required"
                            device.name != null -> device.name
                            else -> "Unknown Device (${device.address})"
                        }

                        Text(
                            text = deviceName,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onDeviceSelected(device) }
                                .padding(16.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Divider()
                    }
                }
            }
        }
    }

    @Composable
    fun PrintHistoryScreen(historyList: List<String>) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Background Image
            Image(
                painter = painterResource(id = R.drawable.history),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            // Optional: dark overlay for readability
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f))
            )

            // Foreground content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Text(
                    text = "Print History",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                if (historyList.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No documents printed yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(historyList) { document ->
                            Text(
                                text = document,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White
                            )
                            Divider(color = Color.White.copy(alpha = 0.6f))
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun BottomNavigationBar(navController: NavHostController) {
        val items = listOf(
            NavigationItem.Home,
            NavigationItem.History,
            NavigationItem.Settings
        )

        NavigationBar(
            containerColor = MaterialTheme.colorScheme.primary // Set the background color
        ) {
            items.forEach { item ->
                NavigationBarItem(
                    icon = { Icon(item.icon, contentDescription = item.title) },
                    label = { Text(item.title) },
                    selected = navController.currentDestination?.route == item.route,
                    onClick = {
                        navController.navigate(item.route) {
                            launchSingleTop = true
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            restoreState = true
                        }
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = Color.White,
                        unselectedIconColor = Color.LightGray,
                        unselectedTextColor = Color.LightGray,
                        indicatorColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }
    }

    @Composable
    fun SettingsScreen(
        autoPrintEnabled: Boolean,
        fileList: List<String>,
        onToggleAutoPrint: (Boolean) -> Unit,
        selectedDirectoryUri: Uri?,
        selectedDirectoryDisplayName: String?,
        onSelectDirectory: () -> Unit
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Background image
            Image(
                painter = painterResource(id = R.drawable.settings),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // Dark overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
            )

            // Foreground content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Top
            ) {
                Spacer(modifier = Modifier.height(24.dp))

                // Title with icon
                Row(

                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings Icon",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(30.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Settings",
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Auto-Print Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            "Enable Auto-Print",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontSize = 25.sp
                        )
                        Text(
                            "Scans selected folder every 3 seconds",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White
                        )
                    }
                    Switch(
                        checked = autoPrintEnabled,
                        onCheckedChange = onToggleAutoPrint
                    )
                }

                if (autoPrintEnabled) {
                    // Select Folder Button
                    Button(
                        onClick = onSelectDirectory,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Text("Select Scan Folder")
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Folder display
                    Text("Current scan folder:", style = MaterialTheme.typography.bodySmall, color = Color.White)
                    Spacer(modifier = Modifier.height(4.dp))
                    when {
                        selectedDirectoryUri != null && selectedDirectoryDisplayName != null -> {
                            Text(
                                selectedDirectoryDisplayName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        selectedDirectoryUri != null -> {
                            Text(
                                "Selected folder (name not available)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White
                            )
                        }
                        else -> {
                            Text(
                                "No folder selected",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

