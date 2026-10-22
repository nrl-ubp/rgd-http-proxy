RGD Flight SQL - ODBC datasource for Windows
=============================================

Package version ${project.version}

This package creates an ODBC datasource named "${odbc.dsn.name}" that reaches the RGD Flight SQL
server (${odbc.dsn.host}, port ${odbc.dsn.port}) from any 64-bit ODBC application: 64-bit Excel,
Power BI, Access, scripts...

It installs, when needed, the Apache Arrow Flight SQL ODBC Driver ${odbc.driver.version} found in
the driver folder (Apache License 2.0, see driver\LICENSE.txt and driver\NOTICE.txt).


Requirements
------------

- Windows 10 / Windows Server 2016 or later, 64-bit.
- 64-bit applications only. The driver exists in 64-bit only: 32-bit Office cannot use it.
- Administrator rights the first time only, to install the driver. Windows asks for them.
- Network access to ${odbc.dsn.host}, port ${odbc.dsn.port}.
- A user name and password for the database behind the RGD proxy.


Installation
------------

1. Unzip the package into any folder.
2. Optionally edit datasource.psd1 (Notepad) to change the defaults.
3. Double-click install.cmd.
   - the driver is installed if it is missing or older (Windows asks for administrator rights);
   - the datasource is created for the current Windows user (a "User DSN").
4. Double-click test.cmd and enter the database user name and password: it connects, runs a query
   and lists a few tables.

Then, in the application, choose the ODBC datasource "${odbc.dsn.name}" and enter the database user
name and password. They are never stored in the datasource.

The datasource can be inspected in "ODBC Data Sources (64-bit)" (odbcad32.exe in
C:\Windows\System32).

The installer can be run again at any time: it changes nothing when everything is in place.


Options
-------

Any setting of datasource.psd1 can be given on the command line, from a command prompt opened in
the package folder:

    install.cmd -ServerHost otherserver.example.com -Port 32010 -Force
    install.cmd -DsnName "RGD Flight SQL Test" -ServerHost testserver.example.com
    install.cmd -DsnType System                (run the command prompt as administrator)
    install.cmd -TrustedCerts C:\certs\company-root-ca.pem
    install.cmd -UseEncryption false           (only if the server does not use TLS)
    install.cmd -?                             (full help)

    -Force            replaces an existing datasource of the same name that has other settings.
    -DsnType System   creates the datasource for every user of the machine.
    -TrustedCerts     a PEM file of the certificate authority to trust, when the server certificate
                      is not issued by an authority Windows already trusts. The file is copied
                      under %LOCALAPPDATA%\RGD\FlightSqlOdbc (or %ProgramData% for a System DSN).
    -DisableCertificateVerification
                      accepts any server certificate. For troubleshooting only.

    test.cmd -Query "SELECT COUNT(*) FROM PERSON"
    uninstall.cmd                              removes the datasource, keeps the driver
    uninstall.cmd -RemoveDriver                removes the datasource and the driver


Connection string
-----------------

Applications that take a connection string rather than a datasource name can use:

    DSN=${odbc.dsn.name};UID=<user>;PWD=<password>

or, without any datasource:

    Driver={${odbc.driver.name}};HOST=${odbc.dsn.host};PORT=${odbc.dsn.port};useEncryption=${odbc.dsn.use-encryption};UID=<user>;PWD=<password>


Automated deployment
--------------------

The scripts return an exit code: 0 OK, 1 failure, 2 invalid settings, 3 administrator rights
required, 4 driver installation failed, 5 the datasource exists with other settings (use -Force).
A log of every run is written to the logs folder (or %TEMP%\rgd-flightsql-odbc when the package
folder is read-only).

When a deployment tool runs install.cmd without parameters, set RGD_NO_PAUSE=1 first, or call
the PowerShell script directly:

    powershell.exe -NoProfile -ExecutionPolicy Bypass -File Install-RgdFlightSqlDsn.ps1 -DsnType System

The driver alone can also be installed silently by an administrator:

    msiexec /i driver\${odbc.driver.msi} /qn /norestart


Troubleshooting
---------------

- "Data source name not found": the application is 32-bit, or the datasource was created for
  another Windows user (User DSN). Use a 64-bit application, or install with -DsnType System.
- TLS / certificate / handshake errors: the server certificate must name the host of the
  datasource exactly (${odbc.dsn.host}) and be issued by an authority this machine trusts. Use
  -TrustedCerts for a private authority.
- The connection fails or hangs although the host is right: the datasource and the server must
  agree on encryption. useEncryption=true needs TLS enabled on the server
  (proxy.flight-sql.tls.enabled=true); a server without TLS needs -UseEncryption false.
- Authentication errors: the user name and password are those of the database behind the proxy.
- The logs folder holds the log of every run, and driver-install-*.log the Windows Installer log.
