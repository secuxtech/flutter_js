package io.abner.flutter_js

import android.os.Build
import android.util.Log
import androidx.annotation.NonNull
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import android.os.Looper
import android.os.Handler
import fi.iki.elonen.NanoHTTPD
// import kotlinx.coroutines.Dispatchers
// import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

data class MethodChannelResult(val success: Boolean, val data: Any? = null)

/** FlutterJsPlugin */
class FlutterJsPlugin : FlutterPlugin, MethodCallHandler {
    private var applicationContext: android.content.Context? = null
    private var methodChannel: MethodChannel? = null
    val flutterJsServer = FlutterJsServer()
    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        onAttachedToEngine(flutterPluginBinding.applicationContext, flutterPluginBinding.binaryMessenger)
    }

    private fun onAttachedToEngine(applicationContext: android.content.Context, messenger: BinaryMessenger) {
        this.applicationContext = applicationContext
        methodChannel = MethodChannel(messenger, "io.abner.flutter_js")
        methodChannel!!.setMethodCallHandler(this)
    }

    companion object {
        var jsEngineMap = mutableMapOf<Int, JSEngine>()
    }

//    suspend fun invokeMethod(jsEngine: JSEngine, method: String, arguments: Any, callback: (result: MethodChannelResult) -> Unit): MethodChannelResult {
//        println(">>> send n2f : cmd - $method")
//        methodChannel?.invokeMethod(method, arguments, object : MethodChannel.Result{
//            override fun notImplemented() {
//                Log.d("NormalMethodHandler", "notImplemented")
//                callback.invoke(MethodChannelResult(false, "notImplemented"))
//            }
//
//            override fun error(errorCode: String?, errorMessage: String?, errorDetails: Any?) {
//                Log.d("NormalMethodHandler", "error $errorMessage $ errorDetails")
//                callback.invoke(MethodChannelResult(false, "error $errorMessage $ errorDetails"))
//            }
//
//            override fun success(result: Any?) {
//                Log.d("NormalMethodHandler", "success")
//                callback.invoke(MethodChannelResult(true, result))
//            }
//        })
//    }


    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        if (call.method == "getPlatformVersion") {
            result.success("Android ${android.os.Build.VERSION.RELEASE}")
        } else if (call.method == "initEngine") {
            try {
                Log.d("FlutterJS", call.arguments.toString())
                val engineId = call.arguments as Int
                jsEngineMap[engineId] = JSEngine(applicationContext!!)
                if (!flutterJsServer.isAlive) {
                    flutterJsServer.start()
                }
                Log.i("FLUTTERJS", "SERVER IS ALIVE: ${flutterJsServer.isAlive}")
                Log.i("FLUTTERJS", "PORT of Running JsBridge Service: ${flutterJsServer.listeningPort}")
                result.success(mapOf(
                        "engineId" to engineId,
                        "httpPort" to flutterJsServer.listeningPort,
                        "httpPassword" to flutterJsServer.password
                ))
            } catch (e: Exception) {
                Log.e("FlutterJS", "Error initializing engine: " + e.message, e)
                result.error("INIT_ERROR", "Error initializing engine: ${e.message}", e.toString())
            }
        } else if (call.method == "evaluate") {
            Thread {
                try {
                    val args = call.arguments as Map<String, Any>
                    val engineId = args["engineId"] as Int
                    val command = args["command"] as String
                    val jsResult = jsEngineMap[engineId]!!.eval(command)

                    Handler(Looper.getMainLooper()).post {
                        result.success(jsResult.toString())
                    }
                } catch (e: Exception) {
                    Handler(Looper.getMainLooper()).post {
                        result.error("JS_EVAL_ERROR", e.message, e)
                    }
                }
            }.start()
        } else if (call.method == "registerChannel") {
            val args = call.arguments as Map<String, Any>
            val engineId = args["engineId"] as Int
            val channelName = args["channelName"] as String
            val engine = jsEngineMap[engineId]
            if (engine != null) {
                engine.registerChannel(channelName) { msg ->
                    Handler(Looper.getMainLooper()).post {
                        methodChannel!!.invokeMethod("sendMessage", listOf(engineId, channelName, msg))
                    }
                    msg
                }
                result.success(true)
            } else {
                result.error("ENGINE_NOT_FOUND", "Engine not found", null)
            }
        } else if (call.method == "close") {
            val engineId = call.arguments as Int
            if (jsEngineMap.containsKey(engineId)) {
                try {
                    jsEngineMap[engineId]!!.release()
                    jsEngineMap.remove(engineId)
                } catch (e: Exception) {
                    jsEngineMap.remove(engineId)
                }
            }
            result.success(null)
        } else {
            result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        // jsEngineMap.forEach { engine -> engine.value.release() }
    }
}

// suspend fun MethodChannel.invokeAsync(method: String, arguments: Any?): Any? =
//         withContext(Dispatchers.Main) {
//             suspendCoroutine<Any?> { continuation ->
//                 invokeMethod(method, arguments, object : MethodChannel.Result {

//                     override fun notImplemented() {
//                         continuation.resumeWithException(NotImplementedError("$method , $arguments"))
//                     }

//                     override fun error(errorCode: String?, errorMessage: String?, errorDetails: Any?) {
//                         continuation.resumeWithException(Exception("$errorCode , $errorMessage , $errorDetails"))
//                     }

//                     override fun success(result: Any?) {
//                         continuation.resume(result)
//                     }
//                 })
//             }

//         }

// TODO: Compare with server in Ktor + Netty: https://diamantidis.github.io/2019/11/10/running-an-http-server-on-an-android-app
//       and SUN HttpServer https://medium.com/hacktive-devs/creating-a-local-http-server-on-android-49831fbad9ca
//                          https://gist.github.com/joewalnes/4bf3ac8abc143225fe2c75592d314840
//                          https://github.com/sonuauti/Android-Web-Server/tree/master/AndroidWebServer
//       Another Kotlin Option: https://github.com/weeChanc/AndroidHttpServer   
class FlutterJsServer() : NanoHTTPD(0) {
    val password: String

    init {
        val genUUID = UUID.randomUUID().toString()
        password =  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Base64.getUrlEncoder().encodeToString(genUUID.byteInputStream().readBytes())
        } else {
            URLEncoder.encode(genUUID, "UTF-8")
        }
    }

    override fun serve(session: IHTTPSession): Response {
        try {
            val bodyMap = mutableMapOf<String, String>()
            session.parseBody(bodyMap)
            val engineId = (session.parms["id"] ?: "0").toIntOrNull() ?: 0
            val passwordParam = session.parms["password"]

            if (passwordParam.isNullOrBlank() || (passwordParam ?: "") != password) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Unauthorized")
            }
            val code = bodyMap["postData"]
            val evalResult = FlutterJsPlugin.jsEngineMap[engineId]!!.eval(code!!)
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, evalResult.toString())
        } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "ERROR: ${e.message}")
        }
    }

}