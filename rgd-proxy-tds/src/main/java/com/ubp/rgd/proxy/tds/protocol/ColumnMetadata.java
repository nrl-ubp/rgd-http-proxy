package com.ubp.rgd.proxy.tds.protocol;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A column of a COLMETADATA token, see [MS-TDS] 2.2.7.4.
 *
 * @param userType  the user type id
 * @param flags     the column flags (nullable, identity...)
 * @param typeInfo  its type
 * @param tableName the parts of the table name, for a {@code text}, {@code ntext} or {@code image}
 *                  column only, otherwise {@code null}
 * @param name      the column name, its alias when the query gives one
 */
public record ColumnMetadata(int userType, int flags, TypeInfo typeInfo, List<String> tableName, String name) {

    public static ColumnMetadata read(TdsReader reader) throws IOException {
        int userType = reader.readInt();
        int flags = reader.readUShort();
        TypeInfo typeInfo = TypeInfo.read(reader);
        List<String> tableName = null;
        if (typeInfo.hasTableName()) {
            int parts = reader.readByte();
            tableName = new ArrayList<>(parts);
            for (int i = 0; i < parts; i++) {
                tableName.add(reader.readUsVarchar());
            }
        }
        return new ColumnMetadata(userType, flags, typeInfo, tableName, reader.readBVarchar());
    }

    public void write(TdsBuffer buffer) {
        buffer.writeInt(userType).writeUShort(flags);
        typeInfo.write(buffer);
        if (tableName != null) {
            buffer.writeByte(tableName.size());
            tableName.forEach(buffer::writeUsVarchar);
        }
        buffer.writeBVarchar(name);
    }

    /** The same column, announced with another type. */
    public ColumnMetadata withType(TypeInfo type) {
        return new ColumnMetadata(userType, flags, type, tableName, name);
    }

    /** @return the last part of the table name, or {@code null} */
    public String table() {
        return tableName == null || tableName.isEmpty() ? null : tableName.get(tableName.size() - 1);
    }
}
