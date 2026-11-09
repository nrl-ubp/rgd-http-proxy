package com.ubp.rgd.proxy.tds.protocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A LOGIN7 message, see [MS-TDS] 2.2.6.4.
 * <p>
 * The gateway decodes it to re-encode it for the database server: the variable fields are kept as
 * their raw bytes, so that the password, obfuscated by the client, is relayed without ever being
 * decoded.
 */
public final class Login7 {

    /** Length of the fixed part, for TDS 7.2 and later. */
    public static final int FIXED_LENGTH = 94;

    private static final int OFFSET_TDS_VERSION = 4;
    private static final int OFFSET_PACKET_SIZE = 8;
    private static final int OFFSET_OPTION_FLAGS2 = 25;
    private static final int OFFSET_OPTION_FLAGS3 = 27;
    private static final int OFFSET_SSPI_LONG = 90;

    /** The variable fields, in the order of their offset/length pairs. */
    public enum Field {
        HOST_NAME(36, true), USER_NAME(40, true), PASSWORD(44, true), APP_NAME(48, true),
        SERVER_NAME(52, true), EXTENSION(56, false), CLIENT_INTERFACE(60, true), LANGUAGE(64, true),
        DATABASE(68, true), SSPI(78, false), ATTACH_DB_FILE(82, true), CHANGE_PASSWORD(86, true);

        final int offset;
        /** Whether the length counts characters (2 bytes) rather than bytes. */
        final boolean characters;

        Field(int offset, boolean characters) {
            this.offset = offset;
            this.characters = characters;
        }
    }

    /** A feature extension: its id and data. */
    public record Feature(int id, byte[] data) {
    }

    private final byte[] fixed;
    private final byte[][] fields = new byte[Field.values().length][];
    private final List<Feature> features = new ArrayList<>();
    private boolean hasFeatures;

    private Login7(byte[] fixed) {
        this.fixed = fixed;
    }

    public static Login7 parse(byte[] payload) throws TdsProtocolException {
        if (payload.length < FIXED_LENGTH) {
            throw new TdsProtocolException("LOGIN7 message too short");
        }
        Login7 login = new Login7(Arrays.copyOf(payload, FIXED_LENGTH));
        if (Integer.compareUnsigned(login.tdsVersion(), 0x72000000) < 0 && login.tdsVersion() != Tds.VERSION_8_0) {
            throw new TdsProtocolException(String.format("TDS version 0x%08X is not supported, 7.2 or later is required",
                    login.tdsVersion()));
        }
        for (Field field : Field.values()) {
            int offset = login.ushort(field.offset);
            int length = login.ushort(field.offset + 2);
            if (field == Field.SSPI && length == 0xFFFF) {
                length = login.int32(OFFSET_SSPI_LONG);
            }
            int bytes = field.characters ? length * 2 : length;
            if (offset + bytes > payload.length || offset < 0) {
                throw new TdsProtocolException("LOGIN7 field " + field + " out of the message");
            }
            login.fields[field.ordinal()] = Arrays.copyOfRange(payload, offset, offset + bytes);
        }
        if ((login.fixed[OFFSET_OPTION_FLAGS3] & Tds.LOGIN_EXTENSION) != 0) {
            byte[] extension = login.field(Field.EXTENSION);
            if (extension.length >= 4) {
                int featureOffset = (extension[0] & 0xFF) | (extension[1] & 0xFF) << 8
                        | (extension[2] & 0xFF) << 16 | (extension[3] & 0xFF) << 24;
                login.parseFeatures(payload, featureOffset);
            }
        }
        return login;
    }

    private void parseFeatures(byte[] payload, int offset) throws TdsProtocolException {
        hasFeatures = true;
        int position = offset;
        while (true) {
            if (position >= payload.length) {
                throw new TdsProtocolException("Truncated LOGIN7 feature extension");
            }
            int id = payload[position] & 0xFF;
            if (id == Tds.FEATURE_TERMINATOR) {
                return;
            }
            if (position + 5 > payload.length) {
                throw new TdsProtocolException("Truncated LOGIN7 feature extension");
            }
            int length = (payload[position + 1] & 0xFF) | (payload[position + 2] & 0xFF) << 8
                    | (payload[position + 3] & 0xFF) << 16 | (payload[position + 4] & 0xFF) << 24;
            if (length < 0 || position + 5 + length > payload.length) {
                throw new TdsProtocolException("LOGIN7 feature extension out of the message");
            }
            features.add(new Feature(id, Arrays.copyOfRange(payload, position + 5, position + 5 + length)));
            position += 5 + length;
        }
    }

    public int tdsVersion() {
        return int32(OFFSET_TDS_VERSION);
    }

    public void setTdsVersion(int version) {
        setInt32(OFFSET_TDS_VERSION, version);
    }

    public int packetSize() {
        return int32(OFFSET_PACKET_SIZE);
    }

    public boolean integratedSecurity() {
        return (fixed[OFFSET_OPTION_FLAGS2] & Tds.LOGIN_INT_SECURITY) != 0;
    }

    public void setIntegratedSecurity(boolean value) {
        fixed[OFFSET_OPTION_FLAGS2] = (byte) (value
                ? fixed[OFFSET_OPTION_FLAGS2] | Tds.LOGIN_INT_SECURITY
                : fixed[OFFSET_OPTION_FLAGS2] & ~Tds.LOGIN_INT_SECURITY);
    }

    public byte[] field(Field field) {
        return fields[field.ordinal()];
    }

    /** @return a character field, never the password */
    public String text(Field field) {
        if (field == Field.PASSWORD || field == Field.CHANGE_PASSWORD) {
            throw new IllegalArgumentException("The passwords are never decoded");
        }
        return new String(field(field), StandardCharsets.UTF_16LE);
    }

    public void setText(Field field, String value) {
        fields[field.ordinal()] = value.getBytes(StandardCharsets.UTF_16LE);
    }

    public void setField(Field field, byte[] value) {
        fields[field.ordinal()] = value;
    }

    public List<Feature> features() {
        return features;
    }

    /** Keep only the feature extensions whose id is listed. */
    public List<Feature> keepFeatures(java.util.Set<Integer> kept) {
        List<Feature> removed = features.stream().filter(f -> !kept.contains(f.id())).toList();
        features.removeIf(f -> !kept.contains(f.id()));
        return removed;
    }

    public byte[] encode() {
        byte[] head = fixed.clone();
        TdsBuffer data = new TdsBuffer(512);
        int position = FIXED_LENGTH;
        boolean extension = hasFeatures;
        int extensionOffsetPosition = -1;
        for (Field field : Field.values()) {
            byte[] value = field(field);
            if (field == Field.EXTENSION) {
                value = extension ? new byte[4] : new byte[0];
                extensionOffsetPosition = extension ? position : -1;
            }
            int length = field.characters ? value.length / 2 : value.length;
            putUShort(head, field.offset, value.length == 0 ? (field == Field.SSPI ? 0 : position) : position);
            if (field == Field.SSPI && length >= 0xFFFF) {
                putUShort(head, field.offset + 2, 0xFFFF);
                putInt(head, OFFSET_SSPI_LONG, length);
            } else {
                putUShort(head, field.offset + 2, length);
                if (field == Field.SSPI) {
                    putInt(head, OFFSET_SSPI_LONG, 0);
                }
            }
            data.writeBytes(value);
            position += value.length;
        }
        byte[] body = data.toByteArray();
        if (extension) {
            int featureOffset = position;
            int relative = extensionOffsetPosition - FIXED_LENGTH;
            putInt(body, relative, featureOffset);
            TdsBuffer featureData = new TdsBuffer(64);
            for (Feature feature : features) {
                featureData.writeByte(feature.id()).writeInt(feature.data().length).writeBytes(feature.data());
            }
            featureData.writeByte(Tds.FEATURE_TERMINATOR);
            byte[] encodedFeatures = featureData.toByteArray();
            byte[] joined = Arrays.copyOf(body, body.length + encodedFeatures.length);
            System.arraycopy(encodedFeatures, 0, joined, body.length, encodedFeatures.length);
            body = joined;
            head[OFFSET_OPTION_FLAGS3] |= Tds.LOGIN_EXTENSION;
        } else {
            head[OFFSET_OPTION_FLAGS3] &= ~Tds.LOGIN_EXTENSION;
        }
        putInt(head, 0, FIXED_LENGTH + body.length);
        byte[] message = Arrays.copyOf(head, FIXED_LENGTH + body.length);
        System.arraycopy(body, 0, message, FIXED_LENGTH, body.length);
        return message;
    }

    private int ushort(int offset) {
        return (fixed[offset] & 0xFF) | (fixed[offset + 1] & 0xFF) << 8;
    }

    private int int32(int offset) {
        return (fixed[offset] & 0xFF) | (fixed[offset + 1] & 0xFF) << 8
                | (fixed[offset + 2] & 0xFF) << 16 | (fixed[offset + 3] & 0xFF) << 24;
    }

    private void setInt32(int offset, int value) {
        putInt(fixed, offset, value);
    }

    private static void putUShort(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
    }

    private static void putInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }

    /** Obfuscate a password the way a client does, see [MS-TDS] 2.2.6.4: nibbles swapped, then XOR 0xA5. */
    public static byte[] obfuscatePassword(String password) {
        byte[] bytes = password.getBytes(StandardCharsets.UTF_16LE);
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            bytes[i] = (byte) ((((value & 0x0F) << 4) | (value >>> 4)) ^ 0xA5);
        }
        return bytes;
    }
}
