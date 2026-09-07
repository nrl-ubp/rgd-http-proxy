package com.ubp.rgd.proxy.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.callback.*;

public class MockingCallbackHandler implements CallbackHandler {
    private static final Logger LOG = LoggerFactory.getLogger(MockingCallbackHandler.class);

    private String user;
    private char[] password;

    public void setUser(String user) {
        this.user = user;
    }

    public void setPassword(char[] password) {
        this.password = password;
    }

    @Override
    public void handle(Callback[] callbacks) throws UnsupportedCallbackException {
        for (Callback callback : callbacks) {
            switch (callback) {
                case TextOutputCallback tocb ->
                    // display a message according to a specified type
                        LOG.debug("{} : {}", tocb.getMessageType(), tocb.getMessage());
                case NameCallback ncb -> {
                    LOG.debug("Name callback. Set user name.");
                    ncb.setName(this.user);
                }
                case PasswordCallback pcb -> {
                    LOG.debug("Password callback. Setting the password for callback.");
                    pcb.setPassword(this.password);
                }
                case null, default -> throw new UnsupportedCallbackException(callback, "Unrecognized Callback");
            }
        }
    }
}
