package org.armman.sakhi.data.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ApiFileLoggingInterceptorTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private lateinit var server: MockWebServer
  private lateinit var logFile: File
  private lateinit var client: OkHttpClient

  private val gson = Gson()
  private val jsonMediaType = "application/json".toMediaType()

  @Before
  fun setUp() {
    server = MockWebServer().apply { start() }
    logFile = File(tempFolder.newFolder("debug-logs"), "api-calls.jsonl")
    client = OkHttpClient.Builder()
      .addInterceptor(ApiFileLoggingInterceptor(logFile))
      .build()
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  private fun loggedLines(): List<JsonObject> =
    logFile.readLines().filter { it.isNotBlank() }.map { JsonParser.parseString(it).asJsonObject }

  @Test
  fun `logs url, request payload, and response body for a login-style call`() {
    server.enqueue(
      MockResponse().setResponseCode(200).setBody(
        """{"success":true,"data":{"accessToken":"tok-abc","refreshToken":"ref-xyz"}}""",
      ),
    )
    val request = Request.Builder()
      .url(server.url("/api/v1/auth/login"))
      .post("""{"username":"test.sakhi","password":"hunter2"}""".toRequestBody(jsonMediaType))
      .build()

    client.newCall(request).execute().close()

    val entry = loggedLines().single()
    assertEquals(server.url("/api/v1/auth/login").toString(), entry["url"].asString)
    assertEquals("POST", entry["method"].asString)
    assertEquals(200, entry["statusCode"].asInt)

    val loggedRequest = entry["requestBody"].asJsonObject
    assertEquals("test.sakhi", loggedRequest["username"].asString)
    assertEquals("***REDACTED***", loggedRequest["password"].asString)

    val loggedResponseData = entry["responseBody"].asJsonObject["data"].asJsonObject
    assertEquals("***REDACTED***", loggedResponseData["accessToken"].asString)
    assertEquals("***REDACTED***", loggedResponseData["refreshToken"].asString)
  }

  @Test
  fun `logs null request body for a GET with no body`() {
    server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"1"}"""))
    val request = Request.Builder().url(server.url("/api/v1/me")).build()

    client.newCall(request).execute().close()

    val entry = loggedLines().single()
    // Gson's default serializer omits null fields rather than writing `null`, so a body-less
    // request means the "requestBody" key is simply absent.
    assertNull(entry["requestBody"])
    assertEquals("1", entry["responseBody"].asJsonObject["id"].asString)
  }

  @Test
  fun `redacts sensitive fields nested inside the body`() {
    server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
    val request = Request.Builder()
      .url(server.url("/api/v1/otp/verify"))
      .post("""{"data":{"otp":"123456","username":"test.sakhi"}}""".toRequestBody(jsonMediaType))
      .build()

    client.newCall(request).execute().close()

    val nested = loggedLines().single()["requestBody"].asJsonObject["data"].asJsonObject
    assertEquals("***REDACTED***", nested["otp"].asString)
    assertEquals("test.sakhi", nested["username"].asString)
  }

  @Test
  fun `falls back to raw string for a non-JSON body without crashing`() {
    server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))
    val request = Request.Builder()
      .url(server.url("/api/v1/upload"))
      .post("plain text payload".toRequestBody("text/plain".toMediaType()))
      .build()

    client.newCall(request).execute().close()

    val entry = loggedLines().single()
    assertEquals("plain text payload", entry["requestBody"].asString)
    assertEquals("not json", entry["responseBody"].asString)
  }

  @Test
  fun `logs an error entry instead of crashing when the network call fails`() {
    server.shutdown() // No listener left, so the call fails with IOException.
    val request = Request.Builder().url("http://127.0.0.1:1/api/v1/me").build()

    try {
      client.newCall(request).execute()
    } catch (e: IOException) {
      // Expected — the interceptor rethrows after logging.
    }

    val entry = loggedLines().single()
    assertNull(entry["responseBody"])
    assertTrue(entry.has("error"))
  }

  @Test
  fun `rotates the log file once it exceeds the size cap`() {
    // Pre-fill the file past the 5MB cap so the next write triggers rotation.
    logFile.parentFile?.mkdirs()
    logFile.writeBytes(ByteArray(6 * 1024 * 1024))
    server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

    client.newCall(Request.Builder().url(server.url("/api/v1/me")).build()).execute().close()

    // File was deleted and rewritten with just the new entry, not appended to the 6MB blob.
    assertEquals(1, loggedLines().size)
  }

  @Test
  fun `truncates bodies larger than the per-body cap without crashing`() {
    val hugeValue = "x".repeat(100 * 1024)
    server.enqueue(MockResponse().setResponseCode(200).setBody("""{"value":"$hugeValue"}"""))
    val request = Request.Builder()
      .url(server.url("/api/v1/upload"))
      .post("""{"value":"$hugeValue"}""".toRequestBody(jsonMediaType))
      .build()

    client.newCall(request).execute().close()

    // Truncation happens on the raw string before JSON parsing, so an oversized body simply
    // falls back to a (truncated) raw string instead of parsed JSON — the assertion here is just
    // that logging completes without throwing and produces exactly one line.
    assertEquals(1, loggedLines().size)
  }
}
