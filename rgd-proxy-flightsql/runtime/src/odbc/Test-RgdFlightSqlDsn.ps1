#Requires -Version 5.1
<#
.SYNOPSIS
    Checks that the RGD Flight SQL ODBC datasource works: connects with a user name and password,
    runs a query and reads the metadata ODBC tools rely on.

.DESCRIPTION
    The password is asked for interactively (never shown, never stored) unless -Credential is given.
    Exit codes: 0 OK, 1 the check failed, 2 invalid settings.

.PARAMETER DsnName
    Name of the datasource, from datasource.psd1 by default.
.PARAMETER Credential
    Database user name and password. Asked for when not given.
.PARAMETER Query
    The query to run, 'SELECT 1' by default.
.PARAMETER MaxTables
    How many tables to list.
.PARAMETER ConfigFile
    Settings file, datasource.psd1 next to this script by default.

.EXAMPLE
    .\Test-RgdFlightSqlDsn.ps1
.EXAMPLE
    .\Test-RgdFlightSqlDsn.ps1 -Credential (Get-Credential) -Query 'SELECT COUNT(*) FROM PERSON'
#>
[CmdletBinding()]
param(
    [string]$DsnName,
    [System.Management.Automation.PSCredential]$Credential,
    [string]$Query = 'SELECT 1',
    [ValidateRange(0, 1000)][int]$MaxTables = 10,
    [string]$ConfigFile = (Join-Path $PSScriptRoot 'datasource.psd1')
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'RgdFlightSqlOdbc.Common.ps1')
$boundParameters = @{} + $PSBoundParameters

function Write-RgdHints {
    param([Parameter(Mandatory)][hashtable]$Settings, [string]$Message)

    Write-Host ''
    Write-Host 'Things to check:' -ForegroundColor Yellow
    if ($Message -match 'Data source name not found|IM002') {
        Write-Host "  - The datasource '$($Settings.DsnName)' does not exist for this user / in 64-bit: run install.cmd."
    }
    if ($Settings.UseEncryption) {
        Write-Host '  - The datasource uses TLS: the server must have proxy.flight-sql.tls.enabled=true.'
        Write-Host "  - The server certificate must name '$($Settings.ServerHost)' and be issued by a CA this machine"
        Write-Host '    trusts (or the one given with -TrustedCerts at installation).'
    } else {
        Write-Host '  - The datasource is plaintext: the server must have proxy.flight-sql.tls.enabled=false.'
    }
    Write-Host "  - The server $($Settings.ServerHost):$($Settings.Port) must be reachable from this machine (firewall, proxy)."
    Write-Host '  - The user name and password are those of the database behind the RGD proxy.'
}

$exitCode = Invoke-RgdMain -Name 'test' -Body {
    if (-not [Environment]::Is64BitProcess) {
        Stop-Rgd $script:ExitFailure 'This check must run in 64-bit PowerShell, the only one that sees the 64-bit datasource. Use test.cmd.'
    }
    $settings = Get-RgdSettings -ConfigFile $ConfigFile -BoundParameters $boundParameters

    $dsn = Get-RgdDsn -Name $settings.DsnName -DsnType 'User'
    if (-not $dsn) { $dsn = Get-RgdDsn -Name $settings.DsnName -DsnType 'System' }
    if (-not $dsn) {
        Stop-Rgd $script:ExitFailure "Datasource '$($settings.DsnName)' not found (64-bit, User or System): run install.cmd first."
    }
    # Hints describe the datasource as installed, not the defaults of the package.
    if ($dsn.Attribute.ContainsKey('HOST')) { $settings.ServerHost = $dsn.Attribute['HOST'] }
    if ($dsn.Attribute.ContainsKey('PORT')) { $settings.Port = $dsn.Attribute['PORT'] }
    if ($dsn.Attribute.ContainsKey('useEncryption')) {
        $settings.UseEncryption = ConvertTo-RgdBoolean $dsn.Attribute['useEncryption'] 'useEncryption'
    } else {
        $settings.UseEncryption = $true
    }
    Write-Host "Datasource : $($settings.DsnName) ($($dsn.DsnType) DSN, driver '$($dsn.DriverName)')"
    Write-Host "Server     : $($settings.ServerHost):$($settings.Port), encryption $($settings.UseEncryption)"

    if (-not $Credential) {
        $Credential = Get-Credential -Message "Database user name and password for '$($settings.DsnName)'"
        if (-not $Credential) { Stop-Rgd $script:ExitFailure 'No credentials given.' }
    }
    $network = $Credential.GetNetworkCredential()
    $connectionString = 'DSN=' + (ConvertTo-RgdOdbcValue $settings.DsnName) +
        ';UID=' + (ConvertTo-RgdOdbcValue $network.UserName) +
        ';PWD=' + (ConvertTo-RgdOdbcValue $network.Password)

    $connection = New-Object System.Data.Odbc.OdbcConnection $connectionString
    try {
        try {
            $connection.Open()
        } catch {
            $message = $_.Exception.Message
            Write-RgdHints -Settings $settings -Message $message
            Stop-Rgd $script:ExitFailure "Connection failed: $message"
        }
        Write-Host "Connected  : driver $($connection.Driver), server version $($connection.ServerVersion)"

        $command = $connection.CreateCommand()
        $command.CommandText = $Query
        $result = $command.ExecuteScalar()
        Write-Host "Query      : $Query -> $result"

        $types = $connection.GetSchema('DataTypes')
        Write-Host "Data types : $($types.Rows.Count) ($((@($types.Rows | Select-Object -First 8 | ForEach-Object { $_['TypeName'] })) -join ', '), ...)"

        if ($MaxTables -gt 0) {
            $tables = $connection.GetSchema('Tables')
            Write-Host "Tables     : $($tables.Rows.Count) visible"
            foreach ($row in @($tables.Rows | Select-Object -First $MaxTables)) {
                $schema = $row['TABLE_SCHEM']
                if ($schema -is [DBNull]) { $schema = '' } else { $schema = "$schema." }
                Write-Host "  $schema$($row['TABLE_NAME']) ($($row['TABLE_TYPE']))"
            }
        }
    } finally {
        $connection.Dispose()
    }
    Write-Host ''
    Write-Host "Datasource '$($settings.DsnName)' works." -ForegroundColor Green
}
exit $exitCode
