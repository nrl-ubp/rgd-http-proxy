#Requires -Version 5.1
# Functions shared by the RGD Flight SQL ODBC scripts. Dot-sourced, not meant to be run on its own.

Set-StrictMode -Version 2.0

# Exit codes, the same for every script so that deployment tools can react to them.
$script:ExitOk = 0
$script:ExitFailure = 1
$script:ExitInvalidSettings = 2
$script:ExitNotAdministrator = 3
$script:ExitDriverInstallFailed = 4
$script:ExitConflict = 5

$script:RequiredSettings = @(
    'DsnName', 'DsnType', 'ServerHost', 'Port', 'UseEncryption', 'TrustedCerts',
    'DisableCertificateVerification', 'DriverName', 'DriverVersion', 'DriverMsi', 'DriverUpgradeCode'
)

# Stops the script with the given exit code and message; caught by Invoke-RgdMain.
function Stop-Rgd {
    param([Parameter(Mandatory)][int]$ExitCode, [Parameter(Mandatory)][string]$Message)
    $exception = New-Object System.InvalidOperationException $Message
    $exception.Data['RgdExitCode'] = $ExitCode
    throw $exception
}

# Runs the body of a script with a transcript, and turns its outcome into an exit code.
function Invoke-RgdMain {
    param([Parameter(Mandatory)][string]$Name, [Parameter(Mandatory)][scriptblock]$Body)

    $logFile = Start-RgdLog -Name $Name
    $exitCode = $script:ExitOk
    try {
        & $Body | Out-Host
        Write-Host ''
        Write-Host 'Done.' -ForegroundColor Green
    } catch {
        Write-Host ''
        Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
        if ($_.Exception.Data.Contains('RgdExitCode')) {
            $exitCode = $_.Exception.Data['RgdExitCode']
        } else {
            $exitCode = $script:ExitFailure
            Write-Host $_.ScriptStackTrace
        }
    } finally {
        if ($logFile) {
            Write-Host "Log: $logFile"
            try { Stop-Transcript | Out-Null } catch { Write-Verbose "Transcript already stopped: $($_.Exception.Message)" }
        }
    }
    return $exitCode
}

# Starts a transcript in the logs folder next to the scripts, or in %TEMP% when that one is read-only.
function Start-RgdLog {
    param([Parameter(Mandatory)][string]$Name)

    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
    foreach ($folder in @((Join-Path $PSScriptRoot 'logs'), (Join-Path ([IO.Path]::GetTempPath()) 'rgd-flightsql-odbc'))) {
        try {
            New-Item -ItemType Directory -Path $folder -Force -ErrorAction Stop | Out-Null
            $file = Join-Path $folder "$Name-$stamp.log"
            Start-Transcript -LiteralPath $file -ErrorAction Stop | Out-Null
            return $file
        } catch {
            continue
        }
    }
    Write-Warning 'Could not start a log file, continuing without one.'
    return $null
}

function Get-RgdLogFolder {
    $folder = Join-Path $PSScriptRoot 'logs'
    if (Test-Path -LiteralPath $folder) { return $folder }
    return [IO.Path]::GetTempPath()
}

# Reads datasource.psd1, then lets the parameters the user actually passed override it.
function Get-RgdSettings {
    param(
        [Parameter(Mandatory)][string]$ConfigFile,
        [Parameter(Mandatory)][hashtable]$BoundParameters
    )

    if (-not (Test-Path -LiteralPath $ConfigFile)) {
        Stop-Rgd $script:ExitInvalidSettings "Settings file not found: $ConfigFile"
    }
    try {
        $settings = Import-PowerShellDataFile -LiteralPath $ConfigFile
    } catch {
        Stop-Rgd $script:ExitInvalidSettings "Settings file $ConfigFile is not valid: $($_.Exception.Message)"
    }
    foreach ($key in $script:RequiredSettings) {
        if (-not $settings.ContainsKey($key)) {
            Stop-Rgd $script:ExitInvalidSettings "Setting '$key' is missing from $ConfigFile"
        }
    }
    foreach ($key in @($BoundParameters.Keys)) {
        if ($script:RequiredSettings -contains $key) {
            $value = $BoundParameters[$key]
            if ($value -is [System.Management.Automation.SwitchParameter]) { $value = [bool]$value }
            $settings[$key] = $value
        }
    }

    $settings.DsnName = "$($settings.DsnName)".Trim()
    $settings.ServerHost = "$($settings.ServerHost)".Trim()
    $settings.TrustedCerts = "$($settings.TrustedCerts)".Trim()
    if (-not $settings.DsnName) { Stop-Rgd $script:ExitInvalidSettings 'The datasource name is empty.' }
    if ($settings.DsnName -match '[\[\]{}(),;?*=!@\\]') {
        Stop-Rgd $script:ExitInvalidSettings "The datasource name '$($settings.DsnName)' holds a character ODBC does not allow: []{}(),;?*=!@\"
    }
    if (@('User', 'System') -notcontains $settings.DsnType) {
        Stop-Rgd $script:ExitInvalidSettings "DsnType must be 'User' or 'System', not '$($settings.DsnType)'."
    }
    $settings.DsnType = (Get-Culture).TextInfo.ToTitleCase("$($settings.DsnType)".ToLowerInvariant())
    $settings.UseEncryption = ConvertTo-RgdBoolean $settings.UseEncryption 'UseEncryption'
    $settings.DisableCertificateVerification = ConvertTo-RgdBoolean $settings.DisableCertificateVerification 'DisableCertificateVerification'
    return $settings
}

function Assert-RgdServerSettings {
    param([Parameter(Mandatory)][hashtable]$Settings)

    if (-not $Settings.ServerHost) { Stop-Rgd $script:ExitInvalidSettings 'The server host is empty.' }
    $port = 0
    if (-not [int]::TryParse("$($Settings.Port)", [ref]$port) -or $port -lt 1 -or $port -gt 65535) {
        Stop-Rgd $script:ExitInvalidSettings "The port must be a number between 1 and 65535, not '$($Settings.Port)'."
    }
    $Settings.Port = $port
}

function ConvertTo-RgdBoolean {
    param($Value, [string]$Name)

    if ($Value -is [bool]) { return $Value }
    switch -Regex ("$Value".Trim()) {
        '^(?i:true|1|yes)$' { return $true }
        '^(?i:false|0|no|)$' { return $false }
    }
    Stop-Rgd $script:ExitInvalidSettings "$Name must be 'true' or 'false', not '$Value'."
}

function Test-RgdAdministrator {
    $principal = New-Object Security.Principal.WindowsPrincipal ([Security.Principal.WindowsIdentity]::GetCurrent())
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Assert-RgdWindows64 {
    if (-not [Environment]::Is64BitOperatingSystem) {
        Stop-Rgd $script:ExitFailure 'The Apache Arrow Flight SQL ODBC driver exists for 64-bit Windows only.'
    }
    if (-not (Get-Command -Name Add-OdbcDsn -ErrorAction SilentlyContinue)) {
        Stop-Rgd $script:ExitFailure 'The Windows ODBC cmdlets (Wdac module) are not available: Windows 8 / Server 2012 or later is required.'
    }
}

# The 64-bit driver registered under the given name, with the version of its DLL, or $null.
function Get-RgdInstalledDriver {
    param([Parameter(Mandatory)][string]$Name)

    $driver = Get-OdbcDriver -Name $Name -Platform '64-bit' -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $driver) { return $null }

    $path = $null
    $version = $null
    if ($driver.Attribute -and $driver.Attribute.ContainsKey('Driver')) {
        $path = [Environment]::ExpandEnvironmentVariables($driver.Attribute['Driver'])
    }
    if ($path -and (Test-Path -LiteralPath $path)) {
        $info = (Get-Item -LiteralPath $path).VersionInfo
        $version = New-Object Version $info.FileMajorPart, $info.FileMinorPart, $info.FileBuildPart
    }
    return [pscustomobject]@{ Name = $driver.Name; Path = $path; Version = $version }
}

function Get-RgdDsn {
    param([Parameter(Mandatory)][string]$Name, [Parameter(Mandatory)][string]$DsnType)

    return Get-OdbcDsn -Name $Name -DsnType $DsnType -Platform '64-bit' -ErrorAction SilentlyContinue |
        Select-Object -First 1
}

# Where the installer keeps a copy of the trusted certificates, so that the datasource does not depend
# on the folder the package was unzipped into.
function Get-RgdCertificateCopyPath {
    param([Parameter(Mandatory)][string]$DsnName, [Parameter(Mandatory)][string]$DsnType)

    if ($DsnType -eq 'System') { $root = $env:ProgramData } else { $root = $env:LOCALAPPDATA }
    $safeName = $DsnName -replace '[^A-Za-z0-9._-]', '_'
    return Join-Path $root "RGD\FlightSqlOdbc\$safeName-trusted-certs.pem"
}

# Runs msiexec, elevated through UAC when the current process is not, and waits for it.
function Invoke-RgdMsiexec {
    param([Parameter(Mandatory)][string]$Arguments, [Parameter(Mandatory)][string]$What)

    $start = @{ FilePath = 'msiexec.exe'; ArgumentList = $Arguments; Wait = $true; PassThru = $true }
    if (-not (Test-RgdAdministrator)) {
        Write-Host "Administrator rights are needed to $What, Windows will ask for them."
        $start.Verb = 'RunAs'
    }
    try {
        $process = Start-Process @start
    } catch {
        Stop-Rgd $script:ExitDriverInstallFailed "Could not $What (elevation refused or msiexec not started): $($_.Exception.Message)"
    }
    switch ($process.ExitCode) {
        0 { return }
        1641 { Write-Warning 'Windows Installer has started a restart of the machine.'; return }
        3010 { Write-Warning 'Windows Installer asks for a restart of the machine to complete.'; return }
        1602 { Stop-Rgd $script:ExitDriverInstallFailed "Could not $What`: cancelled by the user." }
        1618 { Stop-Rgd $script:ExitDriverInstallFailed "Could not $What`: another installation is in progress, try again later." }
        default { Stop-Rgd $script:ExitDriverInstallFailed "Could not $What`: msiexec exit code $($process.ExitCode)." }
    }
}

# An ODBC connection string value, enclosed in braces when it holds a character the syntax reserves.
function ConvertTo-RgdOdbcValue {
    param([string]$Value)

    if ($Value -match '[;{}=\s]' -or $Value.StartsWith('{')) {
        return '{' + $Value.Replace('}', '}}') + '}'
    }
    return $Value
}
