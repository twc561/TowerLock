package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.TowerDbEntry
import com.example.telephony.CellModel
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    cell: CellModel,
    userLat: Double?,
    userLon: Double?,
    towerLat: Double?,
    towerLon: Double?,
    allTowers: List<TowerDbEntry>
) {
    val context = LocalContext.current
    var selectedTower by remember { mutableStateOf<TowerDbEntry?>(null) }
    var isBottomSheetOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
    ) {
        AndroidView(
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                    
                    // Dark Mode styling via ColorMatrix
                    val darkMatrix = floatArrayOf(
                        -0.8f, 0f, 0f, 0f, 255f, // R
                        0f, -0.8f, 0f, 0f, 255f, // G
                        0f, 0f, -0.8f, 0f, 255f, // B
                        0f, 0f, 0f, 1f, 0f        // A
                    )
                    overlayManager.tilesOverlay.setColorFilter(ColorMatrixColorFilter(darkMatrix))
                    
                    // Default Zoom and position (SF or user location)
                    controller.setZoom(15.5)
                    val startPoint = if (userLat != null && userLon != null) {
                        GeoPoint(userLat, userLon)
                    } else {
                        GeoPoint(37.7749, -122.4194)
                    }
                    controller.setCenter(startPoint)
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { mapView ->
                mapView.overlays.clear()

                // 1. Add User Location Marker
                if (userLat != null && userLon != null) {
                    val userPoint = GeoPoint(userLat, userLon)
                    val userMarker = Marker(mapView).apply {
                        position = userPoint
                        title = "Your Position"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        // Standard red marker or similar
                    }
                    mapView.overlays.add(userMarker)
                }

                // 2. Add Estimated Tower Position Marker
                if (towerLat != null && towerLon != null) {
                    val towerPoint = GeoPoint(towerLat, towerLon)
                    val towerMarker = Marker(mapView).apply {
                        position = towerPoint
                        title = "Estimated Tower"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        setOnMarkerClickListener { _, _ ->
                            selectedTower = TowerDbEntry(
                                radio = if (cell.tech.contains("5G")) "NR" else "LTE",
                                mcc = cell.mcc ?: "310",
                                mnc = cell.mnc ?: "260",
                                area = cell.tac,
                                cid = cell.cellId,
                                lat = towerLat,
                                lon = towerLon,
                                range = cell.distanceEstimateMeters.toInt(),
                                address = "Serving tower near user"
                            )
                            isBottomSheetOpen = true
                            true
                        }
                    }
                    mapView.overlays.add(towerMarker)

                    // 3. Draw Dotted Polyline user <-> tower
                    if (userLat != null && userLon != null) {
                        val polyline = Polyline().apply {
                            addPoint(GeoPoint(userLat, userLon))
                            addPoint(towerPoint)
                            outlinePaint.color = android.graphics.Color.parseColor("#38BDF8")
                            outlinePaint.strokeWidth = 4f
                            outlinePaint.strokeCap = Paint.Cap.ROUND
                            outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f)
                        }
                        mapView.overlays.add(polyline)
                    }

                    // 4. Draw TA Concentric Distance Ring Overlay around Tower
                    if (cell.distanceEstimateMeters > 0) {
                        val circlePoints = ArrayList<GeoPoint>()
                        val radiusMeters = cell.distanceEstimateMeters
                        for (i in 0 until 360 step 5) {
                            val radialPoint = GeoPoint(towerLat, towerLon).destinationPoint(radiusMeters, i.toDouble())
                            circlePoints.add(radialPoint)
                        }
                        val taRing = Polygon().apply {
                            points = circlePoints
                            fillPaint.color = android.graphics.Color.parseColor("#20EC4899") // Semi-transparent pink
                            outlinePaint.color = android.graphics.Color.parseColor("#EC4899")
                            outlinePaint.strokeWidth = 2f
                        }
                        mapView.overlays.add(taRing)
                    }
                }

                // 5. Render All Logged / Known Towers
                allTowers.forEach { tower ->
                    // Exclude serving cell if already drawn
                    if (tower.lat != towerLat || tower.lon != towerLon) {
                        val loggedTowerMarker = Marker(mapView).apply {
                            position = GeoPoint(tower.lat, tower.lon)
                            title = "${tower.radio} Tower (${tower.cid})"
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            setOnMarkerClickListener { _, _ ->
                                selectedTower = tower
                                isBottomSheetOpen = true
                                true
                            }
                        }
                        mapView.overlays.add(loggedTowerMarker)
                    }
                }

                mapView.invalidate()
            }
        )

        // Floating layer toggle or information banner
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1E293B).copy(alpha = 0.9f))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = if (towerLat != null) "Viewing serving & ${allTowers.size} logged towers" else "Awaiting cell tower lock...",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium
            )
        }

        // Bottom Sheet for Marker Details
        if (isBottomSheetOpen && selectedTower != null) {
            ModalBottomSheet(
                onDismissRequest = { isBottomSheetOpen = false },
                containerColor = Color(0xFF1E293B),
                dragHandle = { BottomSheetDefaults.DragHandle(color = Color(0xFF475569)) }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                ) {
                    Text(
                        text = "${selectedTower!!.radio} cell tower coordinates",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = selectedTower!!.address ?: "No geocoded address resolved",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color(0xFF94A3B8),
                        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                    )

                    Divider(color = Color(0xFF334155), modifier = Modifier.padding(bottom = 16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        DetailItem(label = "Lat/Lon", value = String.format(Locale.US, "%.5f, %.5f", selectedTower!!.lat, selectedTower!!.lon))
                        DetailItem(label = "Range Accuracy", value = "±${selectedTower!!.range} m")
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        DetailItem(label = "Cell ID (CID/gNB)", value = "${selectedTower!!.cid}")
                        DetailItem(label = "MCC-MNC", value = "${selectedTower!!.mcc}-${selectedTower!!.mnc}")
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("coords", "${selectedTower!!.lat}, ${selectedTower!!.lon}")
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Coordinates copied to clipboard!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF475569))
                        ) {
                            Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Copy Coords")
                        }

                        Button(
                            onClick = {
                                val uri = "google.navigation:q=${selectedTower!!.lat},${selectedTower!!.lon}"
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                                    setPackage("com.google.android.apps.maps")
                                }
                                if (intent.resolveActivity(context.packageManager) != null) {
                                    context.startActivity(intent)
                                } else {
                                    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=${selectedTower!!.lat},${selectedTower!!.lon}"))
                                    context.startActivity(webIntent)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                        ) {
                            Icon(imageVector = Icons.Default.Navigation, contentDescription = "Navigate")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Navigate")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DetailItem(label: String, value: String) {
    Column {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = Color.White, fontWeight = FontWeight.Bold)
    }
}
