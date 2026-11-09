package com.ubp.rgd.proxy.tds.protocol;

/**
 * Constants of the Tabular Data Stream protocol, see [MS-TDS] section 2.2.
 */
public final class Tds {

    private Tds() {
    }

    // -------------------------------------------------------------------------------------------------
    // Packet header (2.2.3.1)
    // -------------------------------------------------------------------------------------------------

    /** Length of the packet header. */
    public static final int HEADER_LENGTH = 8;

    /** Packet size before the login negotiates another one. */
    public static final int DEFAULT_PACKET_SIZE = 4096;

    /** Largest packet size SQL Server accepts. */
    public static final int MAX_PACKET_SIZE = 32767;

    public static final int PKT_SQL_BATCH = 0x01;
    public static final int PKT_RPC = 0x03;
    public static final int PKT_REPLY = 0x04;
    public static final int PKT_ATTENTION = 0x06;
    public static final int PKT_BULK_LOAD = 0x07;
    public static final int PKT_FEDAUTH_TOKEN = 0x08;
    public static final int PKT_TRANSACTION_MANAGER = 0x0E;
    public static final int PKT_LOGIN7 = 0x10;
    public static final int PKT_SSPI = 0x11;
    public static final int PKT_PRELOGIN = 0x12;

    public static final int STATUS_EOM = 0x01;
    public static final int STATUS_IGNORE = 0x02;
    public static final int STATUS_RESETCONNECTION = 0x08;
    public static final int STATUS_RESETCONNECTION_SKIPTRAN = 0x10;

    // -------------------------------------------------------------------------------------------------
    // TDS versions (2.2.6.4)
    // -------------------------------------------------------------------------------------------------

    public static final int VERSION_7_4 = 0x74000004;
    public static final int VERSION_8_0 = 0x08000000;

    /** The ALPN protocol of a TDS 8.0 strict connection. */
    public static final String ALPN_TDS_8 = "tds/8.0";

    /** First byte of a TLS record: a client opening a TDS 8.0 strict connection starts with it. */
    public static final int TLS_HANDSHAKE_RECORD = 0x16;

    // -------------------------------------------------------------------------------------------------
    // PRELOGIN (2.2.6.5)
    // -------------------------------------------------------------------------------------------------

    public static final int PL_VERSION = 0x00;
    public static final int PL_ENCRYPTION = 0x01;
    public static final int PL_INSTOPT = 0x02;
    public static final int PL_THREADID = 0x03;
    public static final int PL_MARS = 0x04;
    public static final int PL_TRACEID = 0x05;
    public static final int PL_FEDAUTHREQUIRED = 0x06;
    public static final int PL_NONCEOPT = 0x07;
    public static final int PL_TERMINATOR = 0xFF;

    public static final int ENCRYPT_OFF = 0x00;
    public static final int ENCRYPT_ON = 0x01;
    public static final int ENCRYPT_NOT_SUP = 0x02;
    public static final int ENCRYPT_REQ = 0x03;
    public static final int ENCRYPT_CLIENT_CERT = 0x80;

    // -------------------------------------------------------------------------------------------------
    // LOGIN7 (2.2.6.4)
    // -------------------------------------------------------------------------------------------------

    /** OptionFlags2: integrated security, the login carries an SSPI blob. */
    public static final int LOGIN_INT_SECURITY = 0x80;

    /** OptionFlags3: the Extension field points to the feature extensions. */
    public static final int LOGIN_EXTENSION = 0x10;

    public static final int FEATURE_SESSIONRECOVERY = 0x01;
    public static final int FEATURE_FEDAUTH = 0x02;
    public static final int FEATURE_COLUMNENCRYPTION = 0x04;
    public static final int FEATURE_GLOBALTRANSACTIONS = 0x05;
    public static final int FEATURE_AZURESQLSUPPORT = 0x08;
    public static final int FEATURE_DATACLASSIFICATION = 0x09;
    public static final int FEATURE_UTF8_SUPPORT = 0x0A;
    public static final int FEATURE_TERMINATOR = 0xFF;

    // -------------------------------------------------------------------------------------------------
    // Tokens (2.2.7)
    // -------------------------------------------------------------------------------------------------

    public static final int TOKEN_ALTMETADATA = 0x88;
    public static final int TOKEN_ALTROW = 0xD3;
    public static final int TOKEN_COLINFO = 0xA5;
    public static final int TOKEN_COLMETADATA = 0x81;
    public static final int TOKEN_DATACLASSIFICATION = 0xA3;
    public static final int TOKEN_DONE = 0xFD;
    public static final int TOKEN_DONEPROC = 0xFE;
    public static final int TOKEN_DONEINPROC = 0xFF;
    public static final int TOKEN_ENVCHANGE = 0xE3;
    public static final int TOKEN_ERROR = 0xAA;
    public static final int TOKEN_FEATUREEXTACK = 0xAE;
    public static final int TOKEN_FEDAUTHINFO = 0xEE;
    public static final int TOKEN_INFO = 0xAB;
    public static final int TOKEN_LOGINACK = 0xAD;
    public static final int TOKEN_NBCROW = 0xD2;
    public static final int TOKEN_ORDER = 0xA9;
    public static final int TOKEN_RETURNSTATUS = 0x79;
    public static final int TOKEN_RETURNVALUE = 0xAC;
    public static final int TOKEN_ROW = 0xD1;
    public static final int TOKEN_SESSIONSTATE = 0xE4;
    public static final int TOKEN_SSPI = 0xED;
    public static final int TOKEN_TABNAME = 0xA4;

    /** COLMETADATA count telling the client to reuse the metadata it already has. */
    public static final int NO_METADATA = 0xFFFF;

    public static final int DONE_FINAL = 0x0000;
    public static final int DONE_MORE = 0x0001;
    public static final int DONE_ERROR = 0x0002;
    public static final int DONE_ATTN = 0x0020;

    public static final int ENV_PACKET_SIZE = 4;
    public static final int ENV_ROUTING = 20;

    // -------------------------------------------------------------------------------------------------
    // RPC (2.2.6.6)
    // -------------------------------------------------------------------------------------------------

    /** RPC option flag asking the server not to send the metadata of the results. */
    public static final int RPC_NO_METADATA = 0x0002;
    /** RPC option flag asking the server to reuse metadata it already sent. */
    public static final int RPC_REUSE_METADATA = 0x0004;

    /** Batch separator between the RPC requests of a message (TDS 7.2 and later). */
    public static final int RPC_BATCH_FLAG = 0xFF;
    /** "No exec" separator between the RPC requests of a message. */
    public static final int RPC_NO_EXEC_FLAG = 0xFE;

    /** NameLenProcID announcing a well known procedure id rather than a name. */
    public static final int RPC_PROC_ID = 0xFFFF;

    public static final int PROC_CURSOROPEN = 2;
    public static final int PROC_CURSORPREPARE = 3;
    public static final int PROC_CURSORPREPEXEC = 5;
    public static final int PROC_EXECUTESQL = 10;
    public static final int PROC_PREPARE = 11;
    public static final int PROC_PREPEXEC = 13;

    // -------------------------------------------------------------------------------------------------
    // Data types (2.2.5.4)
    // -------------------------------------------------------------------------------------------------

    public static final int NULLTYPE = 0x1F;
    public static final int INT1 = 0x30;
    public static final int BIT = 0x32;
    public static final int INT2 = 0x34;
    public static final int INT4 = 0x38;
    public static final int DATETIM4 = 0x3A;
    public static final int FLT4 = 0x3B;
    public static final int MONEY = 0x3C;
    public static final int DATETIME = 0x3D;
    public static final int FLT8 = 0x3E;
    public static final int MONEY4 = 0x7A;
    public static final int INT8 = 0x7F;

    public static final int GUID = 0x24;
    public static final int INTN = 0x26;
    public static final int DECIMAL = 0x37;
    public static final int NUMERIC = 0x3F;
    public static final int BITN = 0x68;
    public static final int DECIMALN = 0x6A;
    public static final int NUMERICN = 0x6C;
    public static final int FLTN = 0x6D;
    public static final int MONEYN = 0x6E;
    public static final int DATETIMN = 0x6F;
    public static final int DATEN = 0x28;
    public static final int TIMEN = 0x29;
    public static final int DATETIME2N = 0x2A;
    public static final int DATETIMEOFFSETN = 0x2B;
    public static final int CHAR = 0x2F;
    public static final int VARCHAR = 0x27;
    public static final int BINARY = 0x2D;
    public static final int VARBINARY = 0x25;

    public static final int BIGVARBINARY = 0xA5;
    public static final int BIGVARCHAR = 0xA7;
    public static final int BIGBINARY = 0xAD;
    public static final int BIGCHAR = 0xAF;
    public static final int NVARCHAR = 0xE7;
    public static final int NCHAR = 0xEF;
    public static final int XML = 0xF1;
    public static final int UDT = 0xF0;
    public static final int TVP = 0xF3;
    public static final int JSON = 0xF4;
    public static final int VECTOR = 0xF5;

    public static final int TEXT = 0x23;
    public static final int IMAGE = 0x22;
    public static final int NTEXT = 0x63;
    public static final int SSVARIANT = 0x62;

    /** Length announcing a {@code max} type, whose values are sent as PLP. */
    public static final int MAX_LENGTH = 0xFFFF;

    /** USHORTLEN length of a NULL value. */
    public static final int USHORT_NULL = 0xFFFF;

    /** PLP length of a NULL value. */
    public static final long PLP_NULL = 0xFFFFFFFFFFFFFFFFL;

    /** PLP length of a value whose total length is not announced. */
    public static final long PLP_UNKNOWN = 0xFFFFFFFFFFFFFFFEL;

    /** Largest length, in bytes, of a non {@code max} character column. */
    public static final int MAX_SHORT_LENGTH = 8000;
}
