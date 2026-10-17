package com.seethrough.raw.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.seethrough.raw.FloorplanViewModel
import com.seethrough.raw.R
import com.seethrough.raw.WifiScanHub
import com.seethrough.raw.WifiScanState

private val CompanyInk = Color(0xFF28372E)
private val Heading = Color(0xFF758078)
private val RoomLabel = Color(0xFF67736B)
private val HeaderLine = Color(0xFFE4E7E4)
private val RowLine = Color(0xFFF0F1EF)
private val YouColor = Color(0xFF447C70)
private val NamePlate = Color(0xEBFFFFFF)

@Composable
fun FloorplanScreen(vm: FloorplanViewModel = viewModel()) {
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

    val uiRoom by vm.uiRoom
    val problem by vm.problem
    val scan by vm.wifi.state
    val placed = placePeople(listOf(FloorPerson(name = "You", roomNumber = uiRoom)))

    Scaffold(containerColor = Color.White) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxHeight()
                    .fillMaxWidth(),
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp)
                        .padding(top = 12.dp),
                ) {
                    Text(
                        "See/Through",
                        color = CompanyInk,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.7).sp,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                    ScanNotices(
                        scan = scan,
                        problem = problem,
                        onRequestPermissions = { permissionLauncher.launch(permissions) },
                    )
                    FloorplanMapView(placed)
                }
                PeopleList(
                    people = placed,
                    modifier = Modifier.padding(horizontal = 12.dp).padding(top = 20.dp, bottom = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun FloorplanMapView(people: List<PlacedPerson>) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .aspectRatio(FloorplanMap.WIDTH / FloorplanMap.HEIGHT)
            .semantics { contentDescription = "Floorplan showing people in rooms 1 to 5" },
    ) {
        Image(
            painter = painterResource(R.drawable.floorplan),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
        people.forEach { person ->
            val position = person.position ?: return@forEach
            val room = person.roomNumber
            Column(
                Modifier
                    .offset(x = maxWidth * position.x - 36.dp, y = maxHeight * position.y - 22.dp)
                    .width(72.dp)
                    .semantics {
                        contentDescription = if (room == null) {
                            person.name
                        } else {
                            "${person.name}, Room $room"
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(2.dp, YouColor, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("\uD83E\uDDD1\uD83C\uDFFD", fontSize = 18.sp)
                }
                Text(
                    person.name,
                    color = CompanyInk,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(NamePlate)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun PeopleList(people: List<PlacedPerson>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .bottomRule(HeaderLine)
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Name", color = Heading, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text("Room", color = Heading, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        if (people.isEmpty()) {
            Text(
                "No people to show.",
                color = Heading,
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 20.dp),
            )
        }
        people.forEach { person ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 47.dp)
                    .bottomRule(RowLine),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(YouColor),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    person.name,
                    color = CompanyInk,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (person.position != null && person.roomNumber != null) {
                        "Room ${person.roomNumber}"
                    } else {
                        "Unknown"
                    },
                    color = RoomLabel,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun ScanNotices(
    scan: WifiScanState,
    problem: String,
    onRequestPermissions: () -> Unit,
) {
    val context = LocalContext.current
    if (problem.isNotEmpty()) {
        Text(problem, color = RoomLabel, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
    }
    if (scan.session == "idle") return
    if (needsPermission(scan)) {
        TextButton(onClick = onRequestPermissions) {
            Text("Allow Wi-Fi scans", color = YouColor, fontWeight = FontWeight.SemiBold)
        }
    }
    if (!scan.locationEnabled) {
        TextButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }) {
            Text("Turn on location", color = YouColor, fontWeight = FontWeight.SemiBold)
        }
    }
    if (!scan.wifiEnabled) {
        TextButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }) {
            Text("Turn on Wi-Fi", color = YouColor, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun needsPermission(state: WifiScanState): Boolean {
    val nearbyMissing = Build.VERSION.SDK_INT >= 33 && !state.nearbyWifiGranted
    return !state.fineLocationGranted || nearbyMissing
}

private fun Modifier.bottomRule(color: Color): Modifier = drawBehind {
    val stroke = 1.dp.toPx()
    val y = size.height - stroke / 2f
    drawLine(color, Offset(0f, y), Offset(size.width, y), stroke)
}
