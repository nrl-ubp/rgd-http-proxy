# Defaults used by Install-RgdFlightSqlDsn.ps1, Test-RgdFlightSqlDsn.ps1 and Uninstall-RgdFlightSqlDsn.ps1.
# Edit the values below before installing, or override any of them on the command line, e.g.
#   install.cmd -ServerHost myserver.example.com -Port 32010
@{
    # Name of the ODBC datasource, as shown in the ODBC Data Source Administrator (64-bit).
    DsnName                        = '${odbc.dsn.name}'

    # 'User' (only the current Windows user, no administrator rights needed for the datasource itself)
    # or 'System' (every user of the machine, the installer must then be run as administrator).
    DsnType                        = 'User'

    # The Flight SQL server exposed by the RGD proxy.
    ServerHost                     = '${odbc.dsn.host}'
    Port                           = ${odbc.dsn.port}

    # 'true' to connect with TLS (the server must have proxy.flight-sql.tls.enabled=true), 'false' otherwise.
    UseEncryption                  = '${odbc.dsn.use-encryption}'

    # Optional PEM file holding the CA certificate(s) to trust instead of the Windows certificate store.
    # Leave empty when the server certificate is issued by a CA already trusted by Windows.
    TrustedCerts                   = ''

    # 'true' only for troubleshooting: accepts any server certificate.
    DisableCertificateVerification = 'false'

    # The bundled driver. Do not change unless the driver\ folder is changed accordingly.
    DriverName                     = '${odbc.driver.name}'
    DriverVersion                  = '${odbc.driver.version}'
    DriverMsi                      = '${odbc.driver.msi}'
    DriverUpgradeCode              = '${odbc.driver.upgrade-code}'
}
