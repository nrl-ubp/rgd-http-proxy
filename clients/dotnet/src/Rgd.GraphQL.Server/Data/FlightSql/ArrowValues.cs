using Apache.Arrow;

namespace Rgd.GraphQL.Server.Data.FlightSql;

/// <summary>Reads the value of an Arrow array cell as a CLR value.</summary>
internal static class ArrowValues
{
    public static object? Get(IArrowArray array, int index)
    {
        if (array.IsNull(index))
        {
            return null;
        }

        return array switch
        {
            StringArray a => a.GetString(index),
            LargeStringArray a => a.GetString(index),
            StringViewArray a => a.GetString(index),
            BooleanArray a => a.GetValue(index),
            Int8Array a => a.GetValue(index),
            Int16Array a => a.GetValue(index),
            Int32Array a => a.GetValue(index),
            Int64Array a => a.GetValue(index),
            UInt8Array a => a.GetValue(index),
            UInt16Array a => a.GetValue(index),
            UInt32Array a => a.GetValue(index),
            UInt64Array a => a.GetValue(index),
            HalfFloatArray a => (float?)a.GetValue(index),
            FloatArray a => a.GetValue(index),
            DoubleArray a => a.GetValue(index),
            Decimal32Array a => a.GetValue(index),
            Decimal64Array a => a.GetValue(index),
            Decimal128Array a => a.GetValue(index),
            Decimal256Array a => a.GetValue(index),
            Date32Array a => a.GetDateOnly(index),
            Date64Array a => a.GetDateOnly(index),
            TimestampArray a => a.GetTimestamp(index),
            Time32Array a => a.GetTime(index),
            Time64Array a => a.GetTime(index),
            BinaryArray a => a.GetBytes(index).ToArray(),
            _ => throw new NotSupportedException($"The Arrow type {array.Data.DataType.Name} is not supported."),
        };
    }
}
