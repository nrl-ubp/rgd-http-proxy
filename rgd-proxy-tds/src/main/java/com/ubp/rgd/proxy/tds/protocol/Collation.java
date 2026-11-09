package com.ubp.rgd.proxy.tds.protocol;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The COLLATION of a character column, see [MS-TDS] 2.2.5.1.2: it gives the code page the values of
 * the {@code char}, {@code varchar} and {@code text} columns are encoded with.
 * <p>
 * A SQL collation names its code page through its sort id. A Windows collation (sort id 0) through the
 * locale id of its language. A collation flagged UTF-8 ({@code _UTF8} collations of SQL Server 2019)
 * encodes in UTF-8 whatever its language.
 *
 * @param info   the 4 first bytes: LCID (20 bits), the comparison flags and the version
 * @param sortId the 5th byte
 */
public record Collation(int info, int sortId) {

    /** Length of a collation on the wire. */
    public static final int LENGTH = 5;

    private static final int LCID_MASK = 0xFFFF;
    private static final int UTF8_FLAG = 1 << 26;

    private static final Map<Integer, Integer> LCIDS = new HashMap<>();
    private static final Map<Integer, Integer> SORT_IDS = new HashMap<>();
    private static final Set<Integer> UNICODE_ONLY = new HashSet<>();
    private static final Map<Integer, Charset> CHARSETS = new HashMap<>();

    static {
        codePage(1256, LCIDS,
                0x401, 0x420, 0x429, 0x480, 0x48c, 0x801, 0xc01, 0x1001, 0x1401, 0x1801, 0x1c01, 0x2001,
                0x2401, 0x2801, 0x2c01, 0x3001, 0x3401, 0x3801, 0x3c01, 0x4001);
        codePage(1251, LCIDS,
                0x402, 0x419, 0x422, 0x423, 0x428, 0x42f, 0x43f, 0x440, 0x444, 0x450, 0x46d, 0x485,
                0x82c, 0x843, 0x850, 0xc1a, 0x1c1a, 0x201a);
        codePage(1252, LCIDS,
                0x403, 0x406, 0x407, 0x409, 0x40a, 0x40b, 0x40c, 0x40f, 0x410, 0x413, 0x414, 0x416,
                0x417, 0x41d, 0x421, 0x42b, 0x42d, 0x42e, 0x432, 0x434, 0x435, 0x436, 0x437, 0x438,
                0x43b, 0x43e, 0x441, 0x452, 0x456, 0x45d, 0x45e, 0x462, 0x464, 0x468, 0x46a, 0x46b,
                0x46c, 0x46e, 0x46f, 0x470, 0x478, 0x47a, 0x47c, 0x47e, 0x482, 0x483, 0x484, 0x486,
                0x487, 0x488, 0x807, 0x809, 0x80a, 0x80c, 0x810, 0x813, 0x814, 0x816, 0x81d, 0x82e,
                0x83b, 0x83c, 0x83e, 0x85d, 0x85f, 0x86b, 0xc07, 0xc09, 0xc0a, 0xc0c, 0xc3b, 0xc6b,
                0x1007, 0x1009, 0x100a, 0x100c, 0x103b, 0x1407, 0x1409, 0x140a, 0x140c, 0x143b, 0x1809, 0x180a,
                0x180c, 0x183b, 0x1c09, 0x1c0a, 0x1c3b, 0x2009, 0x200a, 0x203b, 0x2409, 0x240a, 0x243b, 0x2809,
                0x280a, 0x2c09, 0x2c0a, 0x3009, 0x300a, 0x3409, 0x340a, 0x380a, 0x3c0a, 0x4009, 0x400a, 0x4409,
                0x440a, 0x4809, 0x480a, 0x4c0a, 0x500a, 0x540a);
        codePage(950, LCIDS,
                0x404, 0xc04, 0x1404);
        codePage(1250, LCIDS,
                0x405, 0x40e, 0x415, 0x418, 0x41a, 0x41b, 0x41c, 0x424, 0x442, 0x81a, 0x101a, 0x141a,
                0x181a);
        codePage(1253, LCIDS,
                0x408);
        codePage(1255, LCIDS,
                0x40d);
        codePage(932, LCIDS,
                0x411);
        codePage(949, LCIDS,
                0x412);
        codePage(874, LCIDS,
                0x41e);
        codePage(1254, LCIDS,
                0x41f, 0x42c, 0x443);
        codePage(1257, LCIDS,
                0x425, 0x426, 0x427, 0x827);
        codePage(1258, LCIDS,
                0x42a);
        codePage(936, LCIDS,
                0x804, 0x1004);
        unicodeOnly(
                0x439, 0x43a, 0x445, 0x446, 0x447, 0x448, 0x449, 0x44a, 0x44b, 0x44c, 0x44d, 0x44e,
                0x44f, 0x451, 0x453, 0x454, 0x457, 0x45a, 0x45b, 0x461, 0x463, 0x465, 0x481, 0x845);
        codePage(437, SORT_IDS,
                30, 31, 32, 33, 34, 35);
        codePage(850, SORT_IDS,
                40, 41, 42, 43, 44, 45, 49, 55, 56, 57, 58, 59, 60, 61);
        codePage(1252, SORT_IDS,
                50, 51, 52, 53, 54, 71, 72, 73, 74, 75, 183, 184, 185, 186, 210, 211,
                212, 213, 214, 215, 216, 217);
        codePage(1250, SORT_IDS,
                80, 81, 82, 83, 84, 85, 86, 87, 88, 89, 90, 91, 92, 93, 94, 95,
                96, 97, 98);
        codePage(1251, SORT_IDS,
                104, 105, 106, 107, 108);
        codePage(1253, SORT_IDS,
                112, 113, 114, 120, 121, 122, 124);
        codePage(1254, SORT_IDS,
                128, 129, 130);
        codePage(1255, SORT_IDS,
                136, 137, 138);
        codePage(1256, SORT_IDS,
                144, 145, 146);
        codePage(1257, SORT_IDS,
                152, 153, 154, 155, 156, 157, 158, 159, 160);
        codePage(932, SORT_IDS,
                192, 193, 200);
        codePage(949, SORT_IDS,
                194, 195, 201);
        codePage(950, SORT_IDS,
                196, 197, 202);
        codePage(936, SORT_IDS,
                198, 199, 203);
        codePage(874, SORT_IDS,
                204, 205, 206);
    }

    private static void codePage(int codePage, Map<Integer, Integer> table, int... ids) {
        for (int id : ids) {
            table.put(id, codePage);
        }
    }

    private static void unicodeOnly(int... lcids) {
        for (int lcid : lcids) {
            UNICODE_ONLY.add(lcid);
        }
    }

    public static Collation read(TdsReader reader) throws java.io.IOException {
        return new Collation(reader.readInt(), reader.readByte());
    }

    public void write(TdsBuffer buffer) {
        buffer.writeInt(info).writeByte(sortId);
    }

    public int lcid() {
        return info & 0xFFFFF;
    }

    public boolean isUtf8() {
        return (info & UTF8_FLAG) != 0;
    }

    /**
     * The Windows code page of the collation.
     *
     * @return the code page, 65001 for UTF-8, or 0 for a Unicode-only language, whose non Unicode
     *         columns cannot hold anything but ASCII
     */
    public int codePage() {
        if (isUtf8()) {
            return 65001;
        }
        if (sortId != 0) {
            Integer codePage = SORT_IDS.get(sortId);
            if (codePage != null) {
                return codePage;
            }
        }
        int lcid = info & LCID_MASK;
        if (UNICODE_ONLY.contains(lcid)) {
            return 0;
        }
        return LCIDS.getOrDefault(lcid, 1252);
    }

    /** @return the charset of the values of the column; windows-1252 when the code page is unknown */
    public Charset charset() {
        int codePage = codePage();
        synchronized (CHARSETS) {
            return CHARSETS.computeIfAbsent(codePage, Collation::lookup);
        }
    }

    private static Charset lookup(int codePage) {
        String name = switch (codePage) {
            case 65001 -> "UTF-8";
            case 0 -> "US-ASCII";
            case 437 -> "IBM437";
            case 850 -> "IBM850";
            case 874 -> "x-windows-874";
            case 932 -> "windows-31j";
            case 936 -> "GBK";
            case 949 -> "x-windows-949";
            case 950 -> "x-windows-950";
            default -> "windows-" + codePage;
        };
        try {
            return Charset.forName(name);
        } catch (RuntimeException e) {
            return Charset.isSupported("windows-1252") ? Charset.forName("windows-1252") : StandardCharsets.ISO_8859_1;
        }
    }
}
