package com.example.locationservice

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

object ApiManager {

    private const val BASE_URL = "http://thiagoglaser.ddns.net:50080/Session/"
    private val client = OkHttpClient()

    fun startSession(deviceId: String) {
        val json = "{\"device_id\":\"$deviceId\"}"
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(BASE_URL + "start-session")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("ApiManager", "Failed to start session", e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    Log.e("ApiManager", "Failed to start session: ${response.code}")
                }
            }
        })
    }

    fun endSession(deviceId: String) {
        val json = "{\"device_id\":\"$deviceId\"}"
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(BASE_URL + "end-session")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("ApiManager", "Failed to end session", e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    Log.e("ApiManager", "Failed to end session: ${response.code}")
                }
            }
        })
    }
}