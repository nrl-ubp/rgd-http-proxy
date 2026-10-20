#Requires -Version 5.1
<#
.SYNOPSIS
    Removes the RGD Flight SQL ODBC datasource and, on request, the Apache Arrow Flight SQL ODBC driver.

.DESCRIPTION
    The driver is kept by default since other datasources may use it; -RemoveDriver uninstalls it too
    (administrator rights are asked for through UAC).
    Exit codes: 0 OK, 1 unexpected failure, 2 invalid settings, 3 administrator rights required,
    4 driver removal failed, 5 the driver is still used by other datasources (use -Force).

.PARAMETER DsnName
    Name of the datasource, from datasource.psd1 by default.
.PARAMETER DsnType
    'User' or 'System', from datasource.psd1 by default.
.PARAMETER RemoveDriver
    Uninstall the driver as well.
.PARAMETER Force
    With -RemoveDriver: uninstall the driver even when other datasources still use it.
.PARAMETER ConfigFile
    Settings file, datasource.psd1 next to this script by default.

.EXAMPLE
    .\Uninstall-RgdFlightSqlDsn.ps1
.EXAMPLE
    .\Uninstall-RgdFlightSqlDsn.ps1 -RemoveDriver
#>
[CmdletBinding()]
param(
    [string]$DsnName,
    [ValidateSet('User', 'System')][string]$DsnType,
    [switch]$RemoveDriver,
    [switch]$Force,
    [string]$ConfigFile = (Join-Path $PSScriptRoot 'datasource.psd1')
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'RgdFlightSqlOdbc.Common.ps1')
$boundParameters = @{} + $PSBoundParameters

# Product codes of the installed driver: through its upgrade code first, its display name otherwise.
function Get-RgdDriverProductCode {
    param([Parameter(Mandatory)][hashtable]$Settings)

    $codes = @()
    try {
        $installer = New-Object -ComObject WindowsInstaller.Installer
        $codes = @($installer.RelatedProducts($Settings.DriverUpgradeCode))
    } catch {
        Write-Verbose "Windows Installer lookup by upgrade code failed: $($_.Exception.Message)"
    }
    if ($codes.Count -gt 0) { return $codes }

    $keys = @(
        'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*',
        'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*'
    )
    return @(Get-ItemProperty -Path $keys -ErrorAction SilentlyContinue |
        Where-Object { $_.PSObject.Properties['DisplayName'] -and $_.DisplayName -like 'Apache*Arrow*Flight*SQL*ODBC*' -and $_.PSChildName -match '^\{[0-9A-Fa-f-]{36}\}$' } |
        ForEach-Object { $_.PSChildName })
}

function Uninstall-RgdDriver {
    param([Parameter(Mandatory)][hashtable]$Settings)

    $others = @(Get-OdbcDsn -DriverName $Settings.DriverName -DsnType 'All' -Platform '64-bit' -ErrorAction SilentlyContinue)
    if ($others.Count -gt 0) {
        $names = ($others | ForEach-Object { "$($_.Name) ($($_.DsnType))" }) -join ', '
        if (-not $Force) {
            Stop-Rgd $script:ExitConflict "The driver is still used by: $names. Run again with -Force to remove it anyway."
        }
        Write-Warning "Removing the driver still used by: $names"
    }

    $codes = @(Get-RgdDriverProductCode -Settings $Settings)
    if ($codes.Count -eq 0) {
        if (Get-RgdInstalledDriver -Name $Settings.DriverName) {
            Stop-Rgd $script:ExitDriverInstallFailed "The driver is registered but its Windows Installer package was not found: remove it from 'Apps & features'."
        }
        Write-Host 'The driver is not installed.'
        return
    }
    foreach ($code in $codes) {
        Write-Host "Uninstalling driver package $code..."
        $msiLog = Join-Path ([IO.Path]::GetTempPath()) ("rgd-flightsql-odbc-uninstall-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log')
        Invoke-RgdMsiexec -Arguments "/x $code /qn /norestart /l*v `"$msiLog`"" -What 'uninstall the ODBC driver'
        Write-Host "Windows Installer log: $msiLog"
    }
    Write-Host 'Driver removed.'
}

$exitCode = Invoke-RgdMain -Name 'uninstall' -Body {
    Assert-RgdWindows64
    $settings = Get-RgdSettings -ConfigFile $ConfigFile -BoundParameters $boundParameters

    if ($settings.DsnType -eq 'System' -and -not (Test-RgdAdministrator)) {
        Stop-Rgd $script:ExitNotAdministrator 'A System DSN can only be removed from an elevated session: run the uninstaller as administrator.'
    }

    if (Get-RgdDsn -Name $settings.DsnName -DsnType $settings.DsnType) {
        Remove-OdbcDsn -Name $settings.DsnName -DsnType $settings.DsnType -Platform '64-bit'
        Write-Host "Datasource '$($settings.DsnName)' ($($settings.DsnType) DSN) removed."
    } else {
        Write-Host "Datasource '$($settings.DsnName)' ($($settings.DsnType) DSN) does not exist, nothing to remove."
    }

    $certificateCopy = Get-RgdCertificateCopyPath -DsnName $settings.DsnName -DsnType $settings.DsnType
    if (Test-Path -LiteralPath $certificateCopy) {
        Remove-Item -LiteralPath $certificateCopy -Force
        Write-Host "Removed $certificateCopy"
    }

    if ($RemoveDriver) { Uninstall-RgdDriver -Settings $settings }
}
exit $exitCode
