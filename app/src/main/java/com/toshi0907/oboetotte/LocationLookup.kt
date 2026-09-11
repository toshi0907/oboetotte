package com.toshi0907.oboetotte

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.os.Build
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

data class GeocodeResult(val name: String, val latitude: Double, val longitude: Double)

/** 入力された住所・場所名を[Geocoder]で座標に変換する。見つからなければnull。 */
suspend fun geocodeAddress(context: Context, query: String): GeocodeResult? {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return null
    val geocoder = Geocoder(context, Locale.getDefault())

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        suspendCancellableCoroutine { cont ->
            geocoder.getFromLocationName(trimmed, 1) { addresses ->
                val first = addresses.firstOrNull()
                if (cont.isActive) {
                    cont.resume(first?.let { GeocodeResult(trimmed, it.latitude, it.longitude) })
                }
            }
        }
    } else {
        withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocationName(trimmed, 1)
            addresses?.firstOrNull()?.let { GeocodeResult(trimmed, it.latitude, it.longitude) }
        }
    }
}

/**
 * 端末の現在地を1回だけ取得する。位置情報権限が無い、または取得できない場合はnull。
 * 呼び出し側で権限確認済みであることを前提とする。
 */
@SuppressLint("MissingPermission")
suspend fun getCurrentLocationResult(context: Context): GeocodeResult? {
    val client = LocationServices.getFusedLocationProviderClient(context)
    val cancellationTokenSource = CancellationTokenSource()
    return suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancellationTokenSource.cancel() }
        try {
            client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cancellationTokenSource.token)
                .addOnSuccessListener { location ->
                    if (cont.isActive) {
                        cont.resume(location?.let { GeocodeResult("現在地", it.latitude, it.longitude) })
                    }
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(null)
                }
        } catch (e: SecurityException) {
            if (cont.isActive) cont.resume(null)
        }
    }
}
