#Requires -Version 5.1
<#
.SYNOPSIS
    Installs the Apache Arrow Flight SQL ODBC driver when needed, and creates the ODBC datasource of the
    RGD Flight SQL server.

.DESCRIPTION
    1. Installs the bundled 64-bit driver (driver\*.msi) when it is missing or older than the bundled one.
       Only this step needs administrator rights: Windows asks for them through UAC.
    2. Creates the ODBC datasource (DSN) pointing to the Flight SQL server, as the current user.

    Defaults come from datasource.psd1, next to this script; any parameter given overrides them.
    No user name or password is stored in the datasource: every application supplies its own when it
    connects.

    Exit codes: 0 OK, 1 unexpected failure, 2 invalid settings, 3 administrator rights required,
    4 driver installation failed, 5 the datasource already exists with other settings (use -Force).

.PARAMETER DsnName
    Name of the datasource.
.PARAMETER ServerHost
    Host name of the Flight SQL server. With TLS, it must match the name in the server certificate.
.PARAMETER Port
    Port of the Flight SQL server.
.PARAMETER DsnType
    'User' (current user only) or 'System' (every user of the machine; run as administrator).
.PARAMETER UseEncryption
    'true' to connect with TLS, 'false' for a plaintext connection.
.PARAMETER TrustedCerts
    PEM file of the CA certificate(s) to trust instead of the Windows certificate store. It is copied
    under %LOCALAPPDATA% (User DSN) or %ProgramData% (System DSN), so the package folder can be deleted.
.PARAMETER DisableCertificateVerification
    Accept any server certificate. For troubleshooting only.
.PARAMETER SkipDriverInstall
    Do not install the driver; fail when it is not installed.
.PARAMETER Force
    Replace a datasource of the same name that has other settings.
.PARAMETER ConfigFile
    Settings file, datasource.psd1 next to this script by default.

.EXAMPLE
    .\Install-RgdFlightSqlDsn.ps1
.EXAMPLE
    .\Install-RgdFlightSqlDsn.ps1 -ServerHost flightsql.example.com -Port 32010 -Force
.EXAMPLE
    .\Install-RgdFlightSqlDsn.ps1 -DsnType System -TrustedCerts C:\certs\corporate-root-ca.pem
#>
[CmdletBinding()]
param(
    [string]$DsnName,
    [string]$ServerHost,
    [ValidateRange(1, 65535)][int]$Port,
    [ValidateSet('User', 'System')][string]$DsnType,
    [ValidateSet('true', 'false')][string]$UseEncryption,
    [string]$TrustedCerts,
    [switch]$DisableCertificateVerification,
    [switch]$SkipDriverInstall,
    [switch]$Force,
    [string]$ConfigFile = (Join-Path $PSScriptRoot 'datasource.psd1')
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'RgdFlightSqlOdbc.Common.ps1')
$boundParameters = @{} + $PSBoundParameters

function Install-RgdDriver {
    param([Parameter(Mandatory)][hashtable]$Settings)

    $bundled = [Version]$Settings.DriverVersion
    $installed = Get-RgdInstalledDriver -Name $Settings.DriverName
    if ($installed) {
        Write-Host "Driver '$($installed.Name)' found: version $($installed.Version), $($installed.Path)"
        if ($installed.Version -and $installed.Version -ge $bundled) { return }
    } else {
        Write-Host "Driver '$($Settings.DriverName)' is not installed."
    }

    if ($SkipDriverInstall) {
        if ($installed) {
            Write-Warning "The installed driver is older than the bundled $bundled, kept because of -SkipDriverInstall."
            return
        }
        Stop-Rgd $script:ExitFailure "The driver is not installed and -SkipDriverInstall was given."
    }

    $msi = Join-Path $PSScriptRoot "driver\$($Settings.DriverMsi)"
    if (-not (Test-Path -LiteralPath $msi)) {
        Stop-Rgd $script:ExitFailure "The driver package is missing from the installation folder: $msi"
    }

    # The elevated msiexec may not see the drive the package was unzipped to (mapped network drives
    # are per logon session), so it is given a local copy.
    $work = Join-Path ([IO.Path]::GetTempPath()) ("rgd-flightsql-odbc-" + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $work -Force | Out-Null
    try {
        $localMsi = Join-Path $work (Split-Path -Leaf $msi)
        $msiLog = Join-Path $work 'driver-install.log'
        Copy-Item -LiteralPath $msi -Destination $localMsi
        Write-Host "Installing driver $bundled from $(Split-Path -Leaf $msi)..."
        try {
            Invoke-RgdMsiexec -Arguments "/i `"$localMsi`" /qn /norestart /l*v `"$msiLog`"" -What 'install the ODBC driver'
        } finally {
            if (Test-Path -LiteralPath $msiLog) {
                $keptLog = Join-Path (Get-RgdLogFolder) ("driver-install-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log')
                Copy-Item -LiteralPath $msiLog -Destination $keptLog -ErrorAction SilentlyContinue
                Write-Host "Windows Installer log: $keptLog"
            }
        }
    } finally {
        Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
    }

    $installed = Get-RgdInstalledDriver -Name $Settings.DriverName
    if (-not $installed) {
        Stop-Rgd $script:ExitDriverInstallFailed "The driver installation ended but '$($Settings.DriverName)' is not registered."
    }
    Write-Host "Driver installed: version $($installed.Version), $($installed.Path)"
}

# The DSN properties for these settings; the certificate file is the copy the DSN will point to.
function Get-RgdDsnProperties {
    param([Parameter(Mandatory)][hashtable]$Settings, [string]$CertificateCopy)

    $useSystemTrustStore = 'true'
    # The driver ignores trustedCerts while the Windows store is in use.
    if ($CertificateCopy) { $useSystemTrustStore = 'false' }
    $properties = [ordered]@{
        HOST                = $Settings.ServerHost
        PORT                = "$($Settings.Port)"
        useEncryption       = "$($Settings.UseEncryption)".ToLowerInvariant()
        useSystemTrustStore = $useSystemTrustStore
    }
    if ($CertificateCopy) { $properties.trustedCerts = $CertificateCopy }
    if ($Settings.DisableCertificateVerification) { $properties.disableCertificateVerification = 'true' }
    return $properties
}

# True when the existing DSN has exactly the managed properties wanted, and uses the right driver.
function Test-RgdDsnUpToDate {
    param($Existing, [Parameter(Mandatory)][System.Collections.IDictionary]$Wanted, [Parameter(Mandatory)][string]$DriverName)

    if ($Existing.DriverName -ne $DriverName) { return $false }
    $actual = @{}
    foreach ($key in $Existing.Attribute.Keys) { $actual[$key] = $Existing.Attribute[$key] }
    foreach ($key in @('HOST', 'PORT', 'useEncryption', 'useSystemTrustStore', 'trustedCerts', 'disableCertificateVerification')) {
        $wantedValue = if ($Wanted.Contains($key)) { "$($Wanted[$key])" } else { '' }
        $actualValue = if ($actual.ContainsKey($key)) { "$($actual[$key])" } else { '' }
        if ($wantedValue -ne $actualValue) { return $false }
    }
    return $true
}

function Install-RgdCertificates {
    param([Parameter(Mandatory)][hashtable]$Settings)

    if (-not $Settings.TrustedCerts) { return $null }
    $source = $Settings.TrustedCerts
    if (-not [IO.Path]::IsPathRooted($source)) { $source = Join-Path (Get-Location) $source }
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
        Stop-Rgd $script:ExitInvalidSettings "Trusted certificates file not found: $source"
    }
    $content = Get-Content -LiteralPath $source -Raw
    if ($content -notmatch '-----BEGIN CERTIFICATE-----') {
        Stop-Rgd $script:ExitInvalidSettings "$source is not a PEM file holding certificates (-----BEGIN CERTIFICATE-----)."
    }
    if (-not $Settings.UseEncryption) {
        Write-Warning 'Trusted certificates are given but encryption is off: they will not be used.'
    }
    $copy = Get-RgdCertificateCopyPath -DsnName $Settings.DsnName -DsnType $Settings.DsnType
    if ((Resolve-Path -LiteralPath $source).ProviderPath -ne $copy) {
        New-Item -ItemType Directory -Path (Split-Path -Parent $copy) -Force | Out-Null
        Copy-Item -LiteralPath $source -Destination $copy -Force
    }
    Write-Host "Trusted certificates: $copy"
    return $copy
}

$exitCode = Invoke-RgdMain -Name 'install' -Body {
    Assert-RgdWindows64
    $settings = Get-RgdSettings -ConfigFile $ConfigFile -BoundParameters $boundParameters
    Assert-RgdServerSettings -Settings $settings

    Write-Host "Datasource : $($settings.DsnName) ($($settings.DsnType) DSN, 64-bit)"
    Write-Host "Server     : $($settings.ServerHost):$($settings.Port)"
    Write-Host "Encryption : $($settings.UseEncryption)"
    Write-Host ''

    if ($settings.DsnType -eq 'System' -and -not (Test-RgdAdministrator)) {
        Stop-Rgd $script:ExitNotAdministrator 'A System DSN can only be created from an elevated session: run the installer as administrator, or use -DsnType User.'
    }
    if (-not $settings.UseEncryption) {
        Write-Warning 'Encryption is off: user names, passwords and data travel in clear text on the network.'
    }
    if ($settings.DisableCertificateVerification) {
        Write-Warning 'Certificate verification is disabled: any server can impersonate the RGD server. Use for troubleshooting only.'
    }

    Install-RgdDriver -Settings $settings

    $certificateCopy = Install-RgdCertificates -Settings $settings
    $wanted = Get-RgdDsnProperties -Settings $settings -CertificateCopy $certificateCopy
    $existing = Get-RgdDsn -Name $settings.DsnName -DsnType $settings.DsnType
    if ($existing) {
        if (Test-RgdDsnUpToDate -Existing $existing -Wanted $wanted -DriverName $settings.DriverName) {
            Write-Host "Datasource '$($settings.DsnName)' already exists with these settings, nothing to change."
            return
        }
        if (-not $Force) {
            Stop-Rgd $script:ExitConflict "Datasource '$($settings.DsnName)' already exists with other settings. Run again with -Force to replace it."
        }
        Write-Host "Replacing datasource '$($settings.DsnName)'..."
        Remove-OdbcDsn -Name $settings.DsnName -DsnType $settings.DsnType -Platform '64-bit'
    } else {
        Write-Host "Creating datasource '$($settings.DsnName)'..."
    }

    $values = @($wanted.Keys | ForEach-Object { "$_=$($wanted[$_])" })
    Add-OdbcDsn -Name $settings.DsnName -DriverName $settings.DriverName -DsnType $settings.DsnType `
        -Platform '64-bit' -SetPropertyValue $values

    $created = Get-RgdDsn -Name $settings.DsnName -DsnType $settings.DsnType
    if (-not $created) {
        Stop-Rgd $script:ExitFailure "Datasource '$($settings.DsnName)' was not found after its creation."
    }
    foreach ($key in $wanted.Keys) { Write-Host ("  {0,-31}= {1}" -f $key, $wanted[$key]) }
    Write-Host ''
    Write-Host "Datasource '$($settings.DsnName)' is ready. Applications connect to it with their own user name and password,"
    Write-Host "e.g. DSN=$($settings.DsnName);UID=<user>;PWD=<password>. Check it with test.cmd."
    Write-Host 'Only 64-bit applications can use it (64-bit Office, Power BI, ...): 32-bit Office cannot.'
}
exit $exitCode
