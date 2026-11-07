package com.hostpot

import android.content.Context
import android.content.Intent
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

object Termux {
    private const val HOME = "/data/data/com.termux/files/home"
    private const val BASH = "/data/data/com.termux/files/usr/bin/bash"
    private const val TBASH = "#!/data/data/com.termux/files/usr/bin/bash"
    const val PERM = "com.termux.permission.RUN_COMMAND"

    fun run(ctx: Context, cmd: String, background: Boolean = true): Boolean {
        return try {
            val i = Intent("com.termux.RUN_COMMAND")
            i.setClassName("com.termux", "com.termux.app.RunCommandService")
            i.putExtra("com.termux.RUN_COMMAND_PATH", BASH)
            i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", cmd))
            i.putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
            i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
            ctx.startForegroundService(i)
            Logx.add("termux> " + cmd.replace(Regex("--token \\S+"), "--token ***").take(160))
            true
        } catch (e: Exception) {
            Logx.add("termux FAIL: " + e.message)
            false
        }
    }

    fun portOpen(p: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", p), 500); true }
    } catch (e: Exception) { false }

    // Each service runs in a loop script and restarts 3 seconds after a crash.
    private fun script(name: String, body: String): String {
        val f = File(Cfg.runDir(), "$name.sh")
        val pid = File(Cfg.logs(), "$name.pid").absolutePath
        f.writeText("$TBASH\necho \$\$ > '$pid'\ntermux-wake-lock\nwhile true; do\n$body\nsleep 3\ndone\n")
        return f.absolutePath
    }

    // pkill -x matches the process name only, so Termux does not kill itself
    private fun stopCmd(name: String): String {
        val pid = File(Cfg.logs(), "$name.pid").absolutePath
        val kill = when (name) {
            "php" -> "pkill -x php"
            "node" -> "pkill -x node"
            "python" -> "pkill -f '[a]pp\\.py'"
            else -> "pkill -x cloudflared"
        }
        return "{ [ -f '$pid' ] && kill \$(cat '$pid') 2>/dev/null; }; $kill; true"
    }

    fun stop(ctx: Context, name: String) = run(ctx, stopCmd(name))
    fun stopAllBackends(ctx: Context) = run(ctx, listOf("php", "node", "python").joinToString("; ") { stopCmd(it) })

    fun startBackend(ctx: Context, kind: String, site: String): Boolean {
        val dir = Cfg.siteDir(site).absolutePath
        val log = Cfg.logs().absolutePath
        val bp = Cfg.backendPort
        val body = when (kind) {
            "php" -> "( cd '$dir' && php -S 127.0.0.1:$bp ) >> '$log/php.log' 2>&1"
            "node" -> "( cd '$dir' && { [ ! -f package.json ] || [ -d node_modules ] || npm install; } && PORT=$bp node server.js ) >> '$log/node.log' 2>&1"
            "python" -> "( cd '$dir' && PORT=$bp python3 app.py ) >> '$log/py.log' 2>&1"
            else -> return false
        }
        val s = script(kind, body)
        return run(ctx, stopCmd(kind) + "; nohup bash '$s' >/dev/null 2>&1 &")
    }

    fun startTunnel(ctx: Context, mode: String): Boolean {
        val log = File(Cfg.logs(), "tunnel.log").absolutePath
        val body = when (mode) {
            "quick" -> "cloudflared tunnel --url http://127.0.0.1:${Cfg.port} > '$log' 2>&1"
            "token" -> "cloudflared tunnel run --token ${Cfg.token} > '$log' 2>&1"
            else -> return false
        }
        val s = script("tunnel", body)
        return run(ctx, stopCmd("tunnel") + "; nohup bash '$s' >/dev/null 2>&1 &")
    }

    fun install(ctx: Context) = run(ctx, "pkg update -y && pkg install -y php python nodejs cloudflared", false)

    const val SETUP = "mkdir -p ~/.termux && (grep -q '^allow-external-apps' ~/.termux/termux.properties 2>/dev/null || echo 'allow-external-apps=true' >> ~/.termux/termux.properties) && termux-reload-settings && termux-setup-storage"
}
