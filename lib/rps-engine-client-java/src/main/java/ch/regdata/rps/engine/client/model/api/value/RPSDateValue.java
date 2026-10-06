package ch.regdata.rps.engine.client.model.api.value;

import ch.regdata.rps.engine.client.mapping.RPSMapping;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class RPSDateValue extends RPSValueBase implements IRPSValue<Date> {

    SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd");   // Default date format
    private Date original;
    private Date transformed;

    public RPSDateValue(RPSMapping mapping, Date originalValue) {
        super(mapping);
        this.original = originalValue;
    }

    public void setDateFormat(String format) {
        this.format = new SimpleDateFormat(format);
    }

    public String getValue() {
        if (original != null) {
            return format.format(original);
        } else {
            return null;
        }
    }

    public void setValue(String value) {
        try {
            setTransformed(format.parse(value));
        } catch (ParseException ex) {
            setTransformed(null);
        }
    }

    public Date getOriginal() {
        return original;
    }

    public void setOriginal(Date original) {
        this.original = original;
    }

    public Date getTransformed() {
        return transformed;
    }

    public void setTransformed(Date transformed) {
        this.transformed = transformed;
    }
}
