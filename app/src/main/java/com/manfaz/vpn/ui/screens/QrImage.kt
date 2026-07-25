package com.manfaz.vpn.ui.screens

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Feature: decode a QR code from an image picked from the gallery (via ML Kit). */
object QrImage {
    suspend fun decode(context: Context, uri: Uri): String? = suspendCancellableCoroutine { cont ->
        try {
            val input = InputImage.fromFilePath(context, uri)
            val scanner = BarcodeScanning.getClient()
            scanner.process(input)
                .addOnSuccessListener { codes ->
                    cont.resume(codes.firstOrNull { it.rawValue != null }?.rawValue)
                }
                .addOnFailureListener { cont.resume(null) }
        } catch (e: Exception) {
            cont.resume(null)
        }
    }
}
