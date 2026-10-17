package com.seethrough.raw.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.seethrough.raw.RawReadoutViewModel
import com.seethrough.raw.TrainingReadout
import com.seethrough.raw.TrainingRow
import com.seethrough.raw.WifiScanHub
import com.seethrough.raw.WifiScanState
import java.util.Locale

@Composable
fun RawReadoutScreen(vm: RawReadoutViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        vm.wifi.onPermissionsUpdated()
    }
    val permissions = remember { WifiScanHub.requiredPermissions() }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> vm.start()
                Lifecycle.Event.ON_PAUSE -> vm.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            vm.start()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.stop()
        }
    }

    LaunchedEffect(Unit) {
        if (permissions.any { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            permissionLauncher.launch(permissions)
        }
    }

    val readout by vm.training.readout
    val scan by vm.wifi.state

    Scaffold(containerColor = Ink) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Header() }
            item {
                TrainingCard(
                    readout = readout,
                    onLabel = vm::labelRoom,
                    onUpload = vm::upload,
                )
            }
            item {
                VectorCard(
                    readout = readout,
                    scan = scan,
                    onRequestPermissions = { permissionLauncher.launch(permissions) },
                )
            }
        }
    }
}

@Composable
private fun Header() {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text(
            "SEE THROUGH",
            color = TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 2.4.sp,
        )
        Text(
            "Wi-Fi training",
            color = AccelAccent,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            "${Build.MANUFACTURER} ${Build.MODEL}  ·  Android ${Build.VERSION.RELEASE}  ·  API ${Build.VERSION.SDK_INT}",
            color = Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun TrainingCard(
    readout: TrainingReadout,
    onLabel: (Int) -> Unit,
    onUpload: () -> Unit,
) {
    ReadoutCard(WifiAccent) {
        StreamHeader("Rooms")
        Text(
            "Tap the room you are in. That saves the current signal vector with that label. A new access point adds a row. Upload pads every saved vector with zeros so they are the same length, then stores the file.",
            color = Dim,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (room in 1..5) {
                RoomButton(
                    room = room,
                    count = readout.roomCounts[room - 1],
                    selected = readout.lastRoom == room,
                    onClick = { onLabel(room) },
                )
            }
        }
        Text(
            "${readout.sampleCount} samples  ·  ${readout.rows.size} rows",
            color = Muted,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (readout.message.isNotEmpty()) {
            Text(
                readout.message,
                color = WifiAccent,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Button(
            onClick = onUpload,
            enabled = readout.sampleCount > 0,
            modifier = Modifier.padding(top = 12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = WifiAccent,
                contentColor = Color(0xFF2A1016),
                disabledContainerColor = SurfaceAlt,
                disabledContentColor = Dim,
            ),
        ) {
            Text("Upload")
        }
    }
}

@Composable
private fun RowScope.RoomButton(room: Int, count: Int, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) WifiAccent else SurfaceAlt
    val foreground = if (selected) Color(0xFF2A1016) else TextPrimary
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            room.toString(),
            color = foreground,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            count.toString(),
            color = if (selected) foreground else Muted,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun VectorCard(
    readout: TrainingReadout,
    scan: WifiScanState,
    onRequestPermissions: () -> Unit,
) {
    val context = LocalContext.current
    ReadoutCard(AccelAccent) {
        StreamHeader("Vector")
        Text(
            "Each row is an access point. The number is its strength from 1 to 1000. A dash means it is not in the latest scan, so a label saved now records 0 there.",
            color = Dim,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        Text(
            statusLine(scan),
            color = AccelAccent,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (needsPermission(scan)) {
            Button(
                onClick = onRequestPermissions,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccelAccent,
                    contentColor = Color(0xFF07110F),
                ),
            ) {
                Text("Allow Wi-Fi scans")
            }
            TextButton(onClick = { openAppSettings(context) }) {
                Text("App settings", color = Muted)
            }
        }
        if (!scan.locationEnabled) {
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }) {
                Text("Turn on location", color = AccelAccent)
            }
        }
        if (!scan.wifiEnabled) {
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }) {
                Text("Turn on Wi-Fi", color = AccelAccent)
            }
        }
        if (readout.rows.isEmpty()) {
            Text(
                "Rows appear as access points are discovered.",
                color = Dim,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            readout.rows.forEach { row ->
                VectorRow(row)
            }
        }
    }
}

@Composable
private fun VectorRow(row: TrainingRow) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            row.index.toString().padStart(2, '0'),
            color = Dim,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.width(28.dp),
        )
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(row.name, color = TextPrimary, fontSize = 15.sp, maxLines = 1)
            Text(row.bssid, color = Dim, fontSize = 11.sp, maxLines = 1)
        }
        Text(
            row.signal?.toString() ?: "—",
            color = if (row.signal == null) Dim else AccelAccent,
            fontFamily = FontFamily.Monospace,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ReadoutCard(accent: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Surface)
            .border(1.dp, Line, RoundedCornerShape(18.dp))
            .drawBehind {
                drawRect(
                    color = accent,
                    size = Size(width = 3.dp.toPx(), height = size.height),
                )
            }
            .padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        content = content,
    )
}

@Composable
private fun StreamHeader(title: String) {
    Text(
        title.uppercase(Locale.US),
        color = Muted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.1.sp,
    )
}

private fun statusLine(state: WifiScanState): String {
    val clock = if (state.lastEventClock.isBlank()) "" else "  ·  ${state.lastEventClock}"
    return state.session + clock
}

private fun needsPermission(state: WifiScanState): Boolean {
    val nearbyMissing = Build.VERSION.SDK_INT >= 33 && !state.nearbyWifiGranted
    return !state.fineLocationGranted || nearbyMissing
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
    }
    context.startActivity(intent)
}
