package com.ubp.rgd.proxy.security;


import org.ietf.jgss.*;
import org.jboss.logging.Logger;

import java.util.concurrent.Callable;

public class KerberosValidateCallable implements Callable<GSSCredential> {
    private static final Logger LOGGER = Logger.getLogger(KerberosValidateCallable.class.getName());

    private final byte[] kerberosTicket;

    public KerberosValidateCallable(byte[] kerberosTicket) {
        this.kerberosTicket = kerberosTicket;
        if (kerberosTicket == null || kerberosTicket.length == 0) {
            LOGGER.warn("New kerberos validation action with null or empty kerberos ticket !");
        }
    }

    @Override
    public GSSCredential call() throws Exception {
        GSSContext context = GSSManager.getInstance().createContext((GSSCredential) null);

        while (!context.isEstablished()) {
            context.acceptSecContext(kerberosTicket, 0, kerberosTicket.length);
            if (context.getSrcName() == null) {
                throw new Exception("GSSContext name of the context initiator is null");
            }
        }

        return context.getDelegCred();
    }

    public static byte[] tweakJdkRegression(byte[] token) {
        LOGGER.info("tweakJdkRegression...");
        //    	Due to regression in 8u40/8u45 described in
        //    	https://bugs.openjdk.java.net/browse/JDK-8078439
        //    	try to tweak token package if it looks like it has
        //    	OID's in wrong order
        //
        //      0000: 60 82 06 5C 06 06 2B 06   01 05 05 02 A0 82 06 50
        //      0010: 30 82 06 4C A0 30 30 2E  |06 09 2A 86 48 82 F7 12
        //      0020: 01 02 02|06 09 2A 86 48   86 F7 12 01 02 02 06|0A
        //      0030: 2B 06 01 04 01 82 37 02   02 1E 06 0A 2B 06 01 04
        //      0040: 01 82 37 02 02 0A A2 82   06 16 04 82 06 12 60 82
        //
        //    	In above package first token is in position 24 and second
        //    	in 35 with both having size 11.
        //
        //    	We simple check if we have these two in this order and swap
        //
        //    	Below code would create two arrays, lets just create that
        //    	manually because it doesn't change
        //      Oid GSS_KRB5_MECH_OID = new Oid("1.2.840.113554.1.2.2");
        //      Oid MS_KRB5_MECH_OID = new Oid("1.2.840.48018.1.2.2");
        //		byte[] der1 = GSS_KRB5_MECH_OID.getDER();
        //		byte[] der2 = MS_KRB5_MECH_OID.getDER();

        //		0000: 06 09 2A 86 48 86 F7 12   01 02 02
        //		0000: 06 09 2A 86 48 82 F7 12   01 02 02

        if (token == null || token.length < 48) {
            return token;
        }
        LOGGER.info("tweakJdkRegression: token length... " + token.length);

        int[] toCheck = new int[] { 0x06, 0x09, 0x2A, 0x86, 0x48, 0x82, 0xF7, 0x12, 0x01, 0x02, 0x02, 0x06, 0x09, 0x2A,
                0x86, 0x48, 0x86, 0xF7, 0x12, 0x01, 0x02, 0x02 };

        for (int i = 0; i < 22; i++) {
            if ((byte) toCheck[i] != token[i + 24]) {
                return token;
            }
        }

        byte[] nt = new byte[token.length];
        System.arraycopy(token, 0, nt, 0, 24);
        System.arraycopy(token, 35, nt, 24, 11);
        System.arraycopy(token, 24, nt, 35, 11);
        System.arraycopy(token, 46, nt, 46, token.length - 24 - 11 - 11);
        return nt;
    }
}
