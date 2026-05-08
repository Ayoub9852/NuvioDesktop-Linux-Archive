package com.nuvio.app.features.notifications

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.desktop.DesktopPreferences
import com.nuvio.app.desktop.DesktopRuntimeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.Locale

internal actual object EpisodeReleaseNotificationsStorage {
    private const val preferencesName = "nuvio_episode_release_notifications"
    private const val payloadKey = "episode_release_notifications_payload"

    actual fun loadPayload(): String? =
        DesktopPreferences.getString(preferencesName, ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        DesktopPreferences.putString(preferencesName, ProfileScopedKey.of(payloadKey), payload)
    }
}

internal actual object EpisodeReleaseNotificationPlatform {
    // Win32 toasts need a Start Menu shortcut whose AppUserModelID matches CreateToastNotifier(appId).
    // Installers should create it, but portable/dev builds can create a per-user shortcut here.
    private const val toastAppUserModelId = "Nuvio.Desktop"

    private const val toastTitleEnv = "NUVIO_TOAST_TITLE"
    private const val toastBodyEnv = "NUVIO_TOAST_BODY"
    private const val toastDeepLinkEnv = "NUVIO_TOAST_DEEP_LINK"
    private const val toastReleaseDateEnv = "NUVIO_TOAST_RELEASE_DATE"
    private const val toastRequestIdEnv = "NUVIO_TOAST_REQUEST_ID"
    private const val toastAppIdEnv = "NUVIO_TOAST_AUMID"
    private const val toastExeEnv = "NUVIO_TOAST_EXE"

    actual suspend fun notificationsAuthorized(): Boolean = withContext(Dispatchers.IO) {
        if (!isWindows()) return@withContext false
        runPowerShell(notificationProbeScript, toastSetupEnvironment())
            .onFailure { DesktopRuntimeLog.warn("desktop notifications probe failed: ${it.message}") }
            .isSuccess
    }

    actual suspend fun requestAuthorization(): Boolean = withContext(Dispatchers.IO) {
        if (!isWindows()) return@withContext false

        // Toast "permission" on Win32 desktop isn't exposed as a clean API to prompt the user.
        // Instead of treating "CreateToastNotifier()" as permission, we attempt the path we use for
        // real notifications (PowerShell -> WinRT -> Show) and only fail hard if that fails.
        val granted = runCatching { notificationsAuthorized() }.getOrElse { false }
        if (granted) return@withContext true

        val dummyRequest = EpisodeReleaseNotificationRequest(
            requestId = "episode-release-auth-probe",
            notificationTitle = "Nuvio",
            notificationBody = "Notifications are ready.",
            releaseDateIso = "2099-01-01",
            deepLinkUrl = "",
            backdropUrl = null,
        )

        runPowerShell(
            script = showToastScript,
            environment = buildToastEnvironment(dummyRequest),
        ).onFailure { DesktopRuntimeLog.warn("desktop notification auth toast probe failed: ${it.message}") }
            .isSuccess
    }

    actual suspend fun scheduleEpisodeReleaseNotifications(requests: List<EpisodeReleaseNotificationRequest>) {
        withContext(Dispatchers.IO) {
            if (!isWindows()) return@withContext
            clearScheduledEpisodeReleaseNotifications()

            requests
                .filter { request -> scheduledNotificationTime(request.releaseDateIso) != null }
                .forEach { request ->
                    runPowerShell(
                        script = scheduleToastScript,
                        environment = buildToastEnvironment(request),
                    ).onFailure { error ->
                        DesktopRuntimeLog.warn("desktop notification schedule failed id=${request.requestId}: ${error.message}")
                    }
                }
        }
    }

    actual suspend fun clearScheduledEpisodeReleaseNotifications() {
        withContext(Dispatchers.IO) {
            if (!isWindows()) return@withContext
            runPowerShell(clearScheduledToastsScript)
                .onFailure { DesktopRuntimeLog.warn("desktop notification clear failed: ${it.message}") }
        }
    }

    actual suspend fun showTestNotification(request: EpisodeReleaseNotificationRequest) {
        withContext(Dispatchers.IO) {
            if (!isWindows()) return@withContext
            runPowerShell(
                script = showToastScript,
                environment = buildToastEnvironment(request),
            ).onFailure { error ->
                DesktopRuntimeLog.warn("desktop test notification failed id=${request.requestId}: ${error.message}")
                throw error
            }
        }
    }

    private fun buildToastEnvironment(request: EpisodeReleaseNotificationRequest): Map<String, String> = buildMap {
        putAll(toastSetupEnvironment())
        put(toastTitleEnv, request.notificationTitle)
        put(toastBodyEnv, request.notificationBody)
        put(toastDeepLinkEnv, request.deepLinkUrl)
        put(toastReleaseDateEnv, request.releaseDateIso)
        put(toastRequestIdEnv, request.requestId)
    }

    private fun toastSetupEnvironment(): Map<String, String> = buildMap {
        put(toastAppIdEnv, toastAppUserModelId)
        put(toastExeEnv, currentExecutablePath())
    }

    private fun currentExecutablePath(): String =
        ProcessHandle.current().info().command().orElse(System.getProperty("java.home") + "\\bin\\java.exe")

    private fun scheduledNotificationTime(releaseDateIso: String): Instant? {
        val date = try {
            LocalDate.parse(releaseDateIso)
        } catch (_: DateTimeParseException) {
            return null
        }
        val scheduledInstant = date
            .atTime(EpisodeReleaseNotificationHour, EpisodeReleaseNotificationMinute)
            .atZone(ZoneId.systemDefault())
            .toInstant()
        return scheduledInstant.takeIf { it.isAfter(Instant.now()) }
    }

    private fun runPowerShell(
        script: String,
        environment: Map<String, String> = emptyMap(),
    ): Result<String> = runCatching {
        val encodedScript = Base64.getEncoder()
            .encodeToString(script.trimIndent().replace("\n", "\r\n").toByteArray(StandardCharsets.UTF_16LE))
        val process = ProcessBuilder(
            "powershell.exe",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-EncodedCommand",
            encodedScript,
        )
            .apply {
                redirectErrorStream(true)
                environment().putAll(environment)
            }
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            error("powershell exit=$exitCode output=$output")
        }
        output
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name")?.lowercase(Locale.US)?.contains("windows") == true

    private val notificationProbeScript = """
        ${'$'}ErrorActionPreference = 'Stop'
        Add-Type -AssemblyName System.Runtime.WindowsRuntime | Out-Null
        ${ensureToastShortcutScript()}
        ${'$'}notifier = [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier(${'$'}env:$toastAppIdEnv)
        if (${ '$' }null -eq ${ '$' }notifier) {
            throw 'CreateToastNotifier returned null'
        }
        Write-Output 'ok'
    """

    private val clearScheduledToastsScript = """
        ${'$'}ErrorActionPreference = 'Stop'
        Add-Type -AssemblyName System.Runtime.WindowsRuntime | Out-Null
        ${ensureToastShortcutScript()}
        ${'$'}notifier = [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier(${'$'}env:$toastAppIdEnv)
        foreach (${ '$' }scheduled in ${ '$' }notifier.GetScheduledToastNotifications()) {
            ${ '$' }notifier.RemoveFromSchedule(${ '$' }scheduled)
        }
        Write-Output 'cleared'
    """

    private val showToastScript = """
        ${'$'}ErrorActionPreference = 'Stop'
        Add-Type -AssemblyName System.Runtime.WindowsRuntime | Out-Null
        ${ensureToastShortcutScript()}
        function Escape-Xml([string]${'$'}value) {
            if ([string]::IsNullOrEmpty(${'$'}value)) { return '' }
            return [System.Security.SecurityElement]::Escape(${'$'}value)
        }
        ${'$'}title = Escape-Xml ${'$'}env:$toastTitleEnv
        ${'$'}body = Escape-Xml ${'$'}env:$toastBodyEnv
        ${'$'}deepLink = Escape-Xml ${'$'}env:$toastDeepLinkEnv
        ${'$'}actions = ''
        if (-not [string]::IsNullOrWhiteSpace(${'$'}deepLink)) {
            ${'$'}actions = "<actions><action content='Open' arguments='${'$'}deepLink' activationType='protocol'/></actions>"
        }
        ${'$'}xml = "<toast><visual><binding template='ToastGeneric'><text>${'$'}title</text><text>${'$'}body</text></binding></visual>${'$'}actions</toast>"
        ${'$'}doc = New-Object Windows.Data.Xml.Dom.XmlDocument
        ${'$'}doc.LoadXml(${'$'}xml)
        ${'$'}toast = New-Object Windows.UI.Notifications.ToastNotification ${'$'}doc
        ${'$'}notifier = [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier(${'$'}env:$toastAppIdEnv)
        ${'$'}notifier.Show(${'$'}toast)
        Write-Output 'shown'
    """

    private val scheduleToastScript = """
        ${'$'}ErrorActionPreference = 'Stop'
        Add-Type -AssemblyName System.Runtime.WindowsRuntime | Out-Null
        ${ensureToastShortcutScript()}
        function Escape-Xml([string]${'$'}value) {
            if ([string]::IsNullOrEmpty(${'$'}value)) { return '' }
            return [System.Security.SecurityElement]::Escape(${'$'}value)
        }
        ${'$'}releaseDate = ${'$'}env:$toastReleaseDateEnv
        ${'$'}parsedDate = [datetime]::ParseExact(${'$'}releaseDate, 'yyyy-MM-dd', [System.Globalization.CultureInfo]::InvariantCulture)
        ${'$'}offset = [TimeZoneInfo]::Local.GetUtcOffset(${'$'}parsedDate)
        ${'$'}scheduledAt = [datetimeoffset]::new(${'$'}parsedDate.Year, ${'$'}parsedDate.Month, ${'$'}parsedDate.Day, $EpisodeReleaseNotificationHour, $EpisodeReleaseNotificationMinute, 0, ${'$'}offset)
        if (${'$'}scheduledAt -le [datetimeoffset]::Now) {
            Write-Output 'skipped-past'
            exit 0
        }
        ${'$'}title = Escape-Xml ${'$'}env:$toastTitleEnv
        ${'$'}body = Escape-Xml ${'$'}env:$toastBodyEnv
        ${'$'}deepLink = Escape-Xml ${'$'}env:$toastDeepLinkEnv
        ${'$'}requestId = Escape-Xml ${'$'}env:$toastRequestIdEnv
        ${'$'}actions = ''
        if (-not [string]::IsNullOrWhiteSpace(${'$'}deepLink)) {
            ${'$'}actions = "<actions><action content='Open' arguments='${'$'}deepLink' activationType='protocol'/></actions>"
        }
        ${'$'}xml = "<toast launch='${'$'}requestId'><visual><binding template='ToastGeneric'><text>${'$'}title</text><text>${'$'}body</text></binding></visual>${'$'}actions</toast>"
        ${'$'}doc = New-Object Windows.Data.Xml.Dom.XmlDocument
        ${'$'}doc.LoadXml(${'$'}xml)
        ${'$'}scheduledToast = New-Object Windows.UI.Notifications.ScheduledToastNotification ${'$'}doc, ${'$'}scheduledAt
        ${'$'}notifier = [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier(${'$'}env:$toastAppIdEnv)
        ${'$'}notifier.AddToSchedule(${'$'}scheduledToast)
        Write-Output 'scheduled'
    """

    private fun ensureToastShortcutScript(): String = """
        function Ensure-NuvioToastShortcut {
            ${'$'}appId = ${'$'}env:$toastAppIdEnv
            ${'$'}exePath = ${'$'}env:$toastExeEnv
            if ([string]::IsNullOrWhiteSpace(${'$'}appId)) { throw 'missing Nuvio toast AppUserModelID' }
            if ([string]::IsNullOrWhiteSpace(${'$'}exePath) -or -not (Test-Path -LiteralPath ${'$'}exePath)) {
                throw "missing Nuvio toast executable path: ${'$'}exePath"
            }

            ${'$'}programs = [Environment]::GetFolderPath('Programs')
            ${'$'}nuvioDir = Join-Path ${'$'}programs 'Nuvio'
            ${'$'}shortcutPath = Join-Path ${'$'}nuvioDir 'Nuvio.lnk'
            New-Item -ItemType Directory -Force -Path ${'$'}nuvioDir | Out-Null

            ${'$'}source = @'
using System;
using System.Runtime.InteropServices;
using System.Text;

[ComImport, Guid("00021401-0000-0000-C000-000000000046")]
public class CShellLink {}

[ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("000214F9-0000-0000-C000-000000000046")]
public interface IShellLinkW {
    void GetPath([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszFile, int cchMaxPath, IntPtr pfd, uint fFlags);
    void GetIDList(out IntPtr ppidl);
    void SetIDList(IntPtr pidl);
    void GetDescription([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszName, int cchMaxName);
    void SetDescription([MarshalAs(UnmanagedType.LPWStr)] string pszName);
    void GetWorkingDirectory([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszDir, int cchMaxPath);
    void SetWorkingDirectory([MarshalAs(UnmanagedType.LPWStr)] string pszDir);
    void GetArguments([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszArgs, int cchMaxPath);
    void SetArguments([MarshalAs(UnmanagedType.LPWStr)] string pszArgs);
    void GetHotkey(out short pwHotkey);
    void SetHotkey(short wHotkey);
    void GetShowCmd(out int piShowCmd);
    void SetShowCmd(int iShowCmd);
    void GetIconLocation([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszIconPath, int cchIconPath, out int piIcon);
    void SetIconLocation([MarshalAs(UnmanagedType.LPWStr)] string pszIconPath, int iIcon);
    void SetRelativePath([MarshalAs(UnmanagedType.LPWStr)] string pszPathRel, uint dwReserved);
    void Resolve(IntPtr hwnd, uint fFlags);
    void SetPath([MarshalAs(UnmanagedType.LPWStr)] string pszFile);
}

[ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("0000010b-0000-0000-C000-000000000046")]
public interface IPersistFile {
    void GetClassID(out Guid pClassID);
    void IsDirty();
    void Load([MarshalAs(UnmanagedType.LPWStr)] string pszFileName, uint dwMode);
    void Save([MarshalAs(UnmanagedType.LPWStr)] string pszFileName, bool fRemember);
    void SaveCompleted([MarshalAs(UnmanagedType.LPWStr)] string pszFileName);
    void GetCurFile([MarshalAs(UnmanagedType.LPWStr)] out string ppszFileName);
}

[ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("00000138-0000-0000-C000-000000000046")]
public interface IPropertyStore {
    void GetCount(out uint cProps);
    void GetAt(uint iProp, out PROPERTYKEY pkey);
    void GetValue(ref PROPERTYKEY key, out PROPVARIANT pv);
    void SetValue(ref PROPERTYKEY key, ref PROPVARIANT pv);
    void Commit();
}

[StructLayout(LayoutKind.Sequential, Pack = 4)]
public struct PROPERTYKEY {
    public Guid fmtid;
    public uint pid;
}

[StructLayout(LayoutKind.Sequential)]
public struct PROPVARIANT {
    public ushort vt;
    public ushort wReserved1;
    public ushort wReserved2;
    public ushort wReserved3;
    public IntPtr p;
}

public static class NuvioToastShortcut {
    public static void Create(string shortcutPath, string exePath, string appId) {
        IShellLinkW link = (IShellLinkW)new CShellLink();
        link.SetPath(exePath);
        link.SetWorkingDirectory(System.IO.Path.GetDirectoryName(exePath));
        link.SetDescription("Nuvio");
        link.SetIconLocation(exePath, 0);

        IPropertyStore propertyStore = (IPropertyStore)link;
        PROPERTYKEY appIdKey = new PROPERTYKEY {
            fmtid = new Guid("9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3"),
            pid = 5
        };
        PROPVARIANT appIdValue = new PROPVARIANT {
            vt = 31,
            p = Marshal.StringToCoTaskMemUni(appId)
        };
        try {
            propertyStore.SetValue(ref appIdKey, ref appIdValue);
            propertyStore.Commit();
        } finally {
            if (appIdValue.p != IntPtr.Zero) Marshal.FreeCoTaskMem(appIdValue.p);
        }

        IPersistFile file = (IPersistFile)link;
        file.Save(shortcutPath, true);
    }
}
'@
            if (-not ('NuvioToastShortcut' -as [type])) {
                Add-Type -TypeDefinition ${'$'}source -Language CSharp
            }
            [NuvioToastShortcut]::Create(${'$'}shortcutPath, ${'$'}exePath, ${'$'}appId)
            return ${'$'}shortcutPath
        }
        Ensure-NuvioToastShortcut | Out-Null
    """
}

internal actual object EpisodeReleaseNotificationsClock {
    actual fun isoDateFromEpochMs(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toString()
}
