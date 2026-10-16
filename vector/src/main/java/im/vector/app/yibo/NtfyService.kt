package im.vector.app.yibo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class NtfyService : Service() {

    companion object {
        private const val TAG = "YiBoNtfy"
        private const val CHANNEL_FG = "yibo_fg"
        private const val CHANNEL_MSG = "yibo_msg"
        private const val FG_ID = 9001
        private const val SSE_URL = "https://ntfy.yibo.help/team-chat/sse"
        private const val TOKEN = "tk_j0pjl2mngq4n9v7ssylz7tjrex565"

        fun start(context: Context) {
            val i = Intent(context, NtfyService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    private var counter = 0

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(FG_ID, buildFgNotification())
        if (!running) {
            running = true
            thread = Thread { loop() }.also {
                it.isDaemon = true
                it.start()
            }
        }
        return START_STICKY
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_FG, "YiBo 后台服务", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MSG, "聊天消息", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            }
        )
    }

    private fun buildFgNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_FG)
            .setContentTitle("YiBo Chat")
            .setContentText("正在接收消息")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    private fun loop() {
        while (running) {
            var conn: HttpURLConnection? = null
            var reader: BufferedReader? = null
            try {
                conn = (URL(SSE_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Authorization", "Bearer $TOKEN")
                    setRequestProperty("Accept", "text/event-stream")
                    setRequestProperty("Accept-Encoding", "identity")
                    connectTimeout = 15_000
                    readTimeout = 0
                    connect()
                }
                if (conn.responseCode != 200) {
                    Thread.sleep(10_000)
                    continue
                }
                reader = BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8))
                val buf = StringBuilder()
                while (running) {
                    val line = reader.readLine() ?: break
                    when {
                        line.startsWith("data:") -> buf.append(line.substring(5).trim())
                        line.isEmpty() && buf.isNotEmpty() -> {
                            handle(buf.toString())
                            buf.setLength(0)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "SSE error: ${e.message}")
                try { Thread.sleep(5_000) } catch (_: InterruptedException) { break }
            } finally {
                try { reader?.close() } catch (_: Exception) {}
                conn?.disconnect()
            }
        }
    }

    private fun handle(data: String) {
        try {
            val obj = JSONObject(data)
            if (obj.optString("event") != "message") return
            val title = obj.optString("title", "YiBo Chat")
            val msg = obj.optString("message", "")
            if (msg.isEmpty()) return
            notify(title, msg)
        } catch (e: Exception) {
            Log.w(TAG, "parse: ${e.message}")
        }
    }

    private fun notify(title: String, msg: String) {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pi = PendingIntent.getActivity(
            this, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL_MSG)
            .setContentTitle(title)
            .setContentText(msg)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(1000 + (counter++ % 1000), n)
    }

    override fun onDestroy() {
        running = false
        thread?.interrupt()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
