package com.kiki.spotifymixer.auth

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Lightweight loopback HTTP server on 127.0.0.1:8888 that catches Spotify's OAuth callback.
 * Matches the exact redirect_uri registered for the Mac app: http://127.0.0.1:8888/callback
 */
class SpotifyAuthServer(private val port: Int = 8888) {

    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isRunning = false

    suspend fun startListening(
        onCodeReceived: (code: String) -> Unit,
        onError: (message: String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            stop() // ensure any previous socket is closed
            // Bind to all local interfaces with backlog of 50
            serverSocket = ServerSocket(port, 50).apply {
                soTimeout = 900_000 // 15 minutes timeout to allow time for email 2FA verification
            }
            isRunning = true
            Log.d("SpotifyAuthServer", "Auth server listening on port $port with 15min timeout")

            while (isRunning) {
                try {
                    val clientSocket: Socket = serverSocket?.accept() ?: break
                    // Handle client on IO thread
                    handleClient(clientSocket, onCodeReceived, onError)
                    // If we successfully received a code, we can stop
                    if (!isRunning) break
                } catch (e: SocketTimeoutException) {
                    if (isRunning) {
                        Log.w("SpotifyAuthServer", "Auth server timed out waiting for callback.")
                        onError("El inicio de sesión expiró después de 15 minutos. Por favor intenta de nuevo.")
                    }
                    break
                }
            }
        } catch (e: Exception) {
            Log.e("SpotifyAuthServer", "Failed to start or maintain auth server", e)
            onError("No se pudo iniciar el servidor local de autenticación: ${e.message}")
        } finally {
            stop()
        }
    }

    private fun handleClient(
        clientSocket: Socket,
        onCodeReceived: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
            val output: OutputStream = clientSocket.getOutputStream()

            val requestLine = reader.readLine() ?: ""
            Log.d("SpotifyAuthServer", "Received request: $requestLine")

            // Handle Chrome / modern browser Private Network Access (PNA) preflight
            if (requestLine.startsWith("OPTIONS")) {
                val preflightHeader = "HTTP/1.1 204 No Content\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Access-Control-Allow-Methods: GET, OPTIONS\r\n" +
                        "Access-Control-Allow-Headers: *\r\n" +
                        "Access-Control-Allow-Private-Network: true\r\n" +
                        "Connection: close\r\n\r\n"
                output.write(preflightHeader.toByteArray(Charsets.UTF_8))
                output.flush()
                return
            }

            // Expected format: GET /callback?code=... HTTP/1.1 or GET /callback?error=...
            if (requestLine.startsWith("GET /callback") || requestLine.startsWith("GET /?code=")) {
                val queryString = requestLine.substringAfter("?", "").substringBefore(" ")
                val params = queryString.split("&").associate { param ->
                    val parts = param.split("=", limit = 2)
                    if (parts.size == 2) parts[0] to parts[1] else parts[0] to ""
                }

                val code = params["code"]
                val error = params["error"]

                val htmlResponse: String
                if (!code.isNullOrBlank()) {
                    htmlResponse = buildSuccessHtml()
                    sendHttpResponse(output, 200, "OK", htmlResponse)
                    isRunning = false
                    onCodeReceived(code)
                } else {
                    val errMsg = error ?: "Acceso cancelado por el usuario"
                    htmlResponse = buildErrorHtml(errMsg)
                    sendHttpResponse(output, 400, "Bad Request", htmlResponse)
                    onError(errMsg)
                }
            } else {
                sendHttpResponse(output, 404, "Not Found", "Not Found")
            }
        } catch (e: Exception) {
            Log.e("SpotifyAuthServer", "Error handling client callback", e)
            onError("Error procesando respuesta: ${e.message}")
        } finally {
            try { clientSocket.close() } catch (_: Exception) {}
        }
    }

    private fun sendHttpResponse(output: OutputStream, statusCode: Int, statusText: String, body: String) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $statusCode $statusText\r\n" +
                "Content-Type: text/html; charset=UTF-8\r\n" +
                "Content-Length: ${bodyBytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Private-Network: true\r\n" +
                "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bodyBytes)
        output.flush()
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
    }

    private fun buildSuccessHtml(): String = """
        <!DOCTYPE html>
        <html lang="es">
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>¡Conectado a Spotify!</title>
            <style>
                body {
                    background-color: #121212;
                    color: #FFFFFF;
                    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    min-height: 100vh;
                    margin: 0;
                    padding: 24px;
                    box-sizing: border-box;
                    text-align: center;
                }
                .card {
                    background-color: #181818;
                    border: 1px solid #282828;
                    border-radius: 20px;
                    padding: 36px 28px;
                    max-width: 380px;
                    box-shadow: 0 12px 32px rgba(0, 0, 0, 0.6);
                }
                .icon {
                    font-size: 54px;
                    margin-bottom: 16px;
                }
                h1 {
                    color: #1DB954;
                    font-size: 24px;
                    margin: 0 0 12px 0;
                }
                p {
                    color: #B3B3B3;
                    font-size: 15px;
                    line-height: 1.5;
                    margin: 0 0 24px 0;
                }
                .btn {
                    display: inline-block;
                    background-color: #1DB954;
                    color: #000000;
                    font-weight: bold;
                    padding: 14px 32px;
                    border-radius: 30px;
                    text-decoration: none;
                    font-size: 16px;
                    transition: transform 0.15s ease;
                }
                .btn:active {
                    transform: scale(0.97);
                }
            </style>
        </head>
        <body>
            <div class="card">
                <div class="icon">💚</div>
                <h1>¡Inicio de sesión exitoso!</h1>
                <p>Tu cuenta de Spotify se ha sincronizado correctamente.<br><br>Ya puedes regresar a <b>Kiki's Spotify Mixer</b> para disfrutar de tus canciones.</p>
                <a class="btn" href="kikispotifymixer://callback">Abrir la App</a>
            </div>
            <script>
                setTimeout(function() {
                    window.location.href = "kikispotifymixer://callback";
                }, 1000);
            </script>
        </body>
        </html>
    """.trimIndent()

    private fun buildErrorHtml(error: String): String = """
        <!DOCTYPE html>
        <html lang="es">
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Error de Autenticación</title>
            <style>
                body {
                    background-color: #121212;
                    color: #FFFFFF;
                    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    min-height: 100vh;
                    margin: 0;
                    padding: 24px;
                    box-sizing: border-box;
                    text-align: center;
                }
                .card {
                    background-color: #181818;
                    border: 1px solid #FF5555;
                    border-radius: 20px;
                    padding: 36px 28px;
                    max-width: 380px;
                }
                h1 { color: #FF5555; font-size: 22px; margin-bottom: 12px; }
                p { color: #B3B3B3; font-size: 14px; line-height: 1.5; }
            </style>
        </head>
        <body>
            <div class="card">
                <h1>No se pudo iniciar sesión</h1>
                <p>$error</p>
                <p>Por favor vuelve a la app e intenta nuevamente.</p>
            </div>
        </body>
        </html>
    """.trimIndent()
}
