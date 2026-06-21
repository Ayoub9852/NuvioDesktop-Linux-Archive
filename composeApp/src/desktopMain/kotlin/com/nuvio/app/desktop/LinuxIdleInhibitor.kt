package com.nuvio.app.desktop

import java.io.File
import java.util.concurrent.atomic.AtomicReference

internal object LinuxIdleInhibitor {
    private const val APP_NAME = "Nuvio"
    private const val REASON = "Video playback active"
    private val activeInhibition = AtomicReference<Inhibition?>(null)
    private val disabled: Boolean
        get() = System.getenv("NUVIO_DISABLE_IDLE_INHIBIT") == "1" ||
            System.getProperty("nuvio.disableIdleInhibit").equals("true", ignoreCase = true)
    private val isLinux: Boolean
        get() = System.getProperty("os.name").contains("linux", ignoreCase = true)

    fun acquire(reason: String) {
        if (!isLinux) return
        if (disabled) {
            DesktopRuntimeLog.info("IDLE_INHIBIT acquire skipped disabled=true reason=$reason")
            return
        }
        activeInhibition.get()?.let {
            DesktopRuntimeLog.info(
                "IDLE_INHIBIT acquire requested reason=$reason alreadyActive=true backend=${it.backend} cookie=${it.safeCookie()}",
            )
            return
        }

        DesktopRuntimeLog.info("IDLE_INHIBIT acquire requested reason=$reason")
        val inhibition = acquireWithBackends()
        if (inhibition == null) {
            DesktopRuntimeLog.warn("IDLE_INHIBIT acquire failed selectedBackend=none reason=$reason")
            return
        }
        if (activeInhibition.compareAndSet(null, inhibition)) {
            DesktopRuntimeLog.info(
                "IDLE_INHIBIT acquired backend=${inhibition.backend} cookie=${inhibition.safeCookie()} reason=$reason",
            )
        } else {
            inhibition.release()
        }
    }

    fun release(reason: String) {
        if (!isLinux) return
        val inhibition = activeInhibition.getAndSet(null)
        if (inhibition == null) {
            DesktopRuntimeLog.info("IDLE_INHIBIT release requested reason=$reason active=false")
            return
        }
        DesktopRuntimeLog.info(
            "IDLE_INHIBIT release requested reason=$reason backend=${inhibition.backend} cookie=${inhibition.safeCookie()}",
        )
        inhibition.release()
    }

    private fun acquireWithBackends(): Inhibition? {
        acquirePortalInhibit()?.let { return it }
        acquireFreedesktopScreenSaver()?.let { return it }
        acquireKdePowerManagement()?.let { return it }
        acquireGnomeSessionManager()?.let { return it }
        acquireXdgScreensaver()?.let { return it }
        return null
    }

    private fun acquirePortalInhibit(): Inhibition? {
        val gdbus = findExecutable("gdbus") ?: return null.also {
            DesktopRuntimeLog.warn("IDLE_INHIBIT backend=portal unavailable reason=gdbus-not-found")
        }
        val result = runCommand(
            listOf(
                gdbus,
                "call",
                "--session",
                "--dest",
                "org.freedesktop.portal.Desktop",
                "--object-path",
                "/org/freedesktop/portal/desktop",
                "--method",
                "org.freedesktop.portal.Inhibit.Inhibit",
                "",
                "12",
                "{'reason': <'$REASON'>}",
            ),
        )
        val handle = parsePortalHandle(result.output)
        if (result.exitCode == 0 && handle != null) {
            DesktopRuntimeLog.info("IDLE_INHIBIT selected backend=portal handle=$handle")
            return PortalInhibition(gdbus, handle)
        }
        DesktopRuntimeLog.warn("IDLE_INHIBIT backend=portal failed exit=${result.exitCode} output=${result.shortOutput()}")
        return null
    }

    private fun acquireFreedesktopScreenSaver(): Inhibition? {
        val dbusSend = findExecutable("dbus-send") ?: return null.also {
            DesktopRuntimeLog.warn("IDLE_INHIBIT backend=freedesktop-screensaver unavailable reason=dbus-send-not-found")
        }
        val paths = listOf("/org/freedesktop/ScreenSaver", "/ScreenSaver")
        for (path in paths) {
            val result = runCommand(
                listOf(
                    dbusSend,
                    "--session",
                    "--print-reply",
                    "--dest=org.freedesktop.ScreenSaver",
                    path,
                    "org.freedesktop.ScreenSaver.Inhibit",
                    "string:$APP_NAME",
                    "string:$REASON",
                ),
            )
            val cookie = parseUint32(result.output)
            if (result.exitCode == 0 && cookie != null) {
                DesktopRuntimeLog.info("IDLE_INHIBIT selected backend=freedesktop-screensaver path=$path cookie=$cookie")
                return DbusCookieInhibition(
                    backend = "freedesktop-screensaver",
                    cookie = cookie,
                    command = listOf(
                        dbusSend,
                        "--session",
                        "--print-reply",
                        "--dest=org.freedesktop.ScreenSaver",
                        path,
                        "org.freedesktop.ScreenSaver.UnInhibit",
                        "uint32:$cookie",
                    ),
                )
            }
            DesktopRuntimeLog.warn(
                "IDLE_INHIBIT backend=freedesktop-screensaver failed path=$path exit=${result.exitCode} output=${result.shortOutput()}",
            )
        }
        return null
    }

    private fun acquireKdePowerManagement(): Inhibition? {
        val dbusSend = findExecutable("dbus-send") ?: return null
        val result = runCommand(
            listOf(
                dbusSend,
                "--session",
                "--print-reply",
                "--dest=org.freedesktop.PowerManagement",
                "/org/freedesktop/PowerManagement/Inhibit",
                "org.freedesktop.PowerManagement.Inhibit.Inhibit",
                "string:$APP_NAME",
                "string:$REASON",
            ),
        )
        val cookie = parseUint32(result.output)
        if (result.exitCode == 0 && cookie != null) {
            DesktopRuntimeLog.info("IDLE_INHIBIT selected backend=kde-power-management cookie=$cookie")
            return DbusCookieInhibition(
                backend = "kde-power-management",
                cookie = cookie,
                command = listOf(
                    dbusSend,
                    "--session",
                    "--print-reply",
                    "--dest=org.freedesktop.PowerManagement",
                    "/org/freedesktop/PowerManagement/Inhibit",
                    "org.freedesktop.PowerManagement.Inhibit.UnInhibit",
                    "uint32:$cookie",
                ),
            )
        }
        DesktopRuntimeLog.warn("IDLE_INHIBIT backend=kde-power-management failed exit=${result.exitCode} output=${result.shortOutput()}")
        return null
    }

    private fun acquireGnomeSessionManager(): Inhibition? {
        val dbusSend = findExecutable("dbus-send") ?: return null
        val result = runCommand(
            listOf(
                dbusSend,
                "--session",
                "--print-reply",
                "--dest=org.gnome.SessionManager",
                "/org/gnome/SessionManager",
                "org.gnome.SessionManager.Inhibit",
                "string:$APP_NAME",
                "uint32:0",
                "string:$REASON",
                "uint32:8",
            ),
        )
        val cookie = parseUint32(result.output)
        if (result.exitCode == 0 && cookie != null) {
            DesktopRuntimeLog.info("IDLE_INHIBIT selected backend=gnome-session-manager cookie=$cookie")
            return DbusCookieInhibition(
                backend = "gnome-session-manager",
                cookie = cookie,
                command = listOf(
                    dbusSend,
                    "--session",
                    "--print-reply",
                    "--dest=org.gnome.SessionManager",
                    "/org/gnome/SessionManager",
                    "org.gnome.SessionManager.Uninhibit",
                    "uint32:$cookie",
                ),
            )
        }
        DesktopRuntimeLog.warn("IDLE_INHIBIT backend=gnome-session-manager failed exit=${result.exitCode} output=${result.shortOutput()}")
        return null
    }

    private fun acquireXdgScreensaver(): Inhibition? {
        val xdgScreensaver = findExecutable("xdg-screensaver") ?: return null.also {
            DesktopRuntimeLog.warn("IDLE_INHIBIT backend=xdg-screensaver unavailable reason=xdg-screensaver-not-found")
        }
        val xdotool = findExecutable("xdotool") ?: return null.also {
            DesktopRuntimeLog.warn("IDLE_INHIBIT backend=xdg-screensaver unavailable reason=xdotool-not-found")
        }
        val windowIdResult = runCommand(listOf(xdotool, "getactivewindow"))
        val windowId = windowIdResult.output.trim().takeIf { it.all(Char::isDigit) }
        if (windowIdResult.exitCode != 0 || windowId == null) {
            DesktopRuntimeLog.warn(
                "IDLE_INHIBIT backend=xdg-screensaver failed reason=no-window-id exit=${windowIdResult.exitCode} output=${windowIdResult.shortOutput()}",
            )
            return null
        }
        val result = runCommand(listOf(xdgScreensaver, "suspend", windowId))
        if (result.exitCode == 0) {
            DesktopRuntimeLog.info("IDLE_INHIBIT selected backend=xdg-screensaver windowId=$windowId")
            return XdgScreensaverInhibition(xdgScreensaver, windowId)
        }
        DesktopRuntimeLog.warn("IDLE_INHIBIT backend=xdg-screensaver failed exit=${result.exitCode} output=${result.shortOutput()}")
        return null
    }

    private fun parseUint32(output: String): Long? {
        val match = Regex("""uint32\s+(\d+)""").find(output) ?: return null
        return match.groupValues.getOrNull(1)?.toLongOrNull()
    }

    private fun parsePortalHandle(output: String): String? {
        val match = Regex("""objectpath\s+'([^']+)'""").find(output) ?: return null
        return match.groupValues.getOrNull(1)?.takeIf { it.startsWith("/org/freedesktop/portal/desktop/request/") }
    }

    private fun findExecutable(name: String): String? {
        val path = System.getenv("PATH").orEmpty()
        return path.split(File.pathSeparator)
            .asSequence()
            .filter(String::isNotBlank)
            .map { File(it, name) }
            .firstOrNull { it.isFile && it.canExecute() }
            ?.absolutePath
    }

    private fun runCommand(command: List<String>): CommandResult =
        try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            CommandResult(process.waitFor(), output)
        } catch (error: Throwable) {
            CommandResult(-1, error.message ?: error.javaClass.name)
        }

    private data class CommandResult(val exitCode: Int, val output: String) {
        fun shortOutput(): String = output.replace('\n', ' ').take(240)
    }

    private interface Inhibition {
        val backend: String
        fun release()
        fun safeCookie(): String
    }

    private data class DbusCookieInhibition(
        override val backend: String,
        val cookie: Long,
        val command: List<String>,
    ) : Inhibition {
        override fun release() {
            val result = runCommand(command)
            if (result.exitCode == 0) {
                DesktopRuntimeLog.info("IDLE_INHIBIT released backend=$backend cookie=$cookie")
            } else {
                DesktopRuntimeLog.warn("IDLE_INHIBIT release failed backend=$backend cookie=$cookie exit=${result.exitCode} output=${result.shortOutput()}")
            }
        }

        override fun safeCookie(): String = cookie.toString()
    }

    private data class PortalInhibition(
        val gdbus: String,
        val handle: String,
    ) : Inhibition {
        override val backend: String = "portal"

        override fun release() {
            val result = runCommand(
                listOf(
                    gdbus,
                    "call",
                    "--session",
                    "--dest",
                    "org.freedesktop.portal.Desktop",
                    "--object-path",
                    handle,
                    "--method",
                    "org.freedesktop.portal.Request.Close",
                ),
            )
            if (result.exitCode == 0) {
                DesktopRuntimeLog.info("IDLE_INHIBIT released backend=$backend handle=$handle")
            } else {
                DesktopRuntimeLog.warn("IDLE_INHIBIT release failed backend=$backend handle=$handle exit=${result.exitCode} output=${result.shortOutput()}")
            }
        }

        override fun safeCookie(): String = handle
    }

    private data class XdgScreensaverInhibition(
        val xdgScreensaver: String,
        val windowId: String,
    ) : Inhibition {
        override val backend: String = "xdg-screensaver"

        override fun release() {
            val result = runCommand(listOf(xdgScreensaver, "resume", windowId))
            if (result.exitCode == 0) {
                DesktopRuntimeLog.info("IDLE_INHIBIT released backend=$backend windowId=$windowId")
            } else {
                DesktopRuntimeLog.warn("IDLE_INHIBIT release failed backend=$backend windowId=$windowId exit=${result.exitCode} output=${result.shortOutput()}")
            }
        }

        override fun safeCookie(): String = "window:$windowId"
    }
}
