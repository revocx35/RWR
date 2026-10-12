package io.github.revocx35.rwr.server

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException

class ServerException(message: String) : IOException(message)

/** Blocking REST calls to the RWR server; call off the main thread. */
object ServerApi {
    private val JSON = "application/json".toMediaType()

    fun pair(link: PairingLink, deviceName: String): ServerAccount {
        val body = JSONObject().put("code", link.code).put("name", deviceName).toString().toRequestBody(JSON)
        val json = call(PinnedTls.client(link.pin), Request.Builder().url("${link.baseUrl}/api/v1/pair").post(body).build())
        return ServerAccount(
            baseUrl = link.baseUrl,
            pin = link.pin,
            deviceId = json.getLong("device_id"),
            deviceName = json.getString("name"),
            canConfigure = json.getBoolean("can_configure"),
            token = json.getString("token"),
        )
    }

    fun deviceInfo(a: ServerAccount): JSONObject = call(a, "/api/v1/device")

    fun unpair(a: ServerAccount) {
        call(a, "/api/v1/device") { delete() }
    }

    fun getConfig(a: ServerAccount): JSONObject = call(a, "/api/v1/config")

    fun putConfig(a: ServerAccount, changes: JSONObject): JSONObject =
        call(a, "/api/v1/config") { put(changes.toString().toRequestBody(JSON)) }

    fun testImmich(a: ServerAccount): Pair<Boolean, String> {
        val json = call(a, "/api/v1/config/immich-test") { post(ByteArray(0).toRequestBody(JSON)) }
        return json.optBoolean("ok") to json.optString("message")
    }

    /** Phone-side detection with Immich as destination: the server forwards the file to Immich. */
    fun uploadMedia(a: ServerAccount, file: File, video: Boolean, takenAt: Long, onlookers: Int, mode: String) {
        val media = (if (video) "video/mp4" else "image/jpeg").toMediaType()
        val body: RequestBody = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("kind", if (video) "video" else "photo")
            .addFormDataPart("taken_at", takenAt.toString())
            .addFormDataPart("onlookers", onlookers.toString())
            .addFormDataPart("mode", mode)
            .addFormDataPart("file", file.name, file.asRequestBody(media))
            .build()
        call(a, "/api/v1/media", readTimeoutS = 120) { post(body) }
    }

    private fun call(
        a: ServerAccount, path: String, readTimeoutS: Long = 30, build: Request.Builder.() -> Unit = {},
    ): JSONObject {
        val req = Request.Builder().url(a.baseUrl + path).header("Authorization", "Bearer ${a.token}").apply(build).build()
        return call(PinnedTls.client(a.pin, readTimeoutS), req)
    }

    private fun call(client: okhttp3.OkHttpClient, req: Request): JSONObject =
        client.newCall(req).execute().use { resp -> parse(resp) }

    private fun parse(resp: Response): JSONObject {
        val text = resp.body.string()
        val json = runCatching { JSONObject(text) }.getOrNull()
        if (!resp.isSuccessful) {
            throw ServerException(json?.optString("error")?.takeIf { it.isNotEmpty() } ?: "server replied HTTP ${resp.code}")
        }
        return json ?: throw ServerException("unexpected reply from server")
    }
}
