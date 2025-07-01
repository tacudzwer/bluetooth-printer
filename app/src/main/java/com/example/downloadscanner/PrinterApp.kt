package com.example.downloadscanner

import android.bluetooth.BluetoothDevice
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File

@Composable
fun PrinterApp(
    scannedDevices: List<BluetoothDevice>,
    isScanning: Boolean,
    isPdfSelected: Boolean,
    isConnected: Boolean,
    isPrinting: Boolean,
    connectedDevice: BluetoothDevice?,
    getDeviceName: (BluetoothDevice?) -> String,
    onSelectPdf: () -> Unit,
    onStartScanning: () -> Unit,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onPrintPdf: () -> Unit,
    autoPrintEnabled: Boolean,
    printedDocuments: List<String>,
    selectedDirUri: Uri?,
    fileList: List<File>,
    onToggleAutoPrint: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        if (isConnected && connectedDevice != null) {
            Text(
                text = "Connected to: ${getDeviceName(connectedDevice)}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        Button(
            onClick = onSelectPdf,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isPrinting
        ) {
            Text("Select PDF")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onPrintPdf,
            modifier = Modifier.fillMaxWidth(),
            enabled = isPdfSelected && isConnected && !isPrinting
        ) {
            Text(
                when {
                    isPrinting -> "Printing..."
                    !isConnected -> "Connect printer first"
                    else -> "Print PDF"
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onStartScanning,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isScanning && !isPrinting
        ) {
            Text(if (isScanning) "Scanning..." else "Scan Printers")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Auto Print")
            Switch(
                checked = autoPrintEnabled,
                onCheckedChange = onToggleAutoPrint,
                enabled = !isPrinting
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Available Printers:",
            style = MaterialTheme.typography.titleMedium
        )

        if (scannedDevices.isEmpty()) {
            Text(
                text = if (isScanning) "Searching for printers..." else "No printers found",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(scannedDevices) { device ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable(
                                enabled = !isPrinting,
                                onClick = { onDeviceSelected(device) }
                            ),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isConnected && connectedDevice?.address == device.address) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                    ) {
                        Text(
                            text = getDeviceName(device),
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (fileList.isNotEmpty()) {
            Text("Auto-print Files:", style = MaterialTheme.typography.titleMedium)
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(fileList) { file ->
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (printedDocuments.isNotEmpty()) {
            Text("Printed Documents:", style = MaterialTheme.typography.titleMedium)
            printedDocuments.forEach {
                Text(text = it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
