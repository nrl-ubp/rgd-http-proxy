package ch.regdata.rps.engine.client;

public class Evidence {
    private String name;
    private String value;
    private Integer hashCode;

    public Evidence(String name, String value) {
        setName(name);
        setValue(value);
    }

    public Evidence() {

    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
        this.hashCode = null;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
        this.hashCode = null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Evidence evidence = (Evidence) o;

        if (!name.equals(evidence.name)) return false;
        return value.equals(evidence.value);
    }

    @Override
    public int hashCode() {
        if (hashCode == null) {
            hashCode = name.hashCode();
            hashCode = 31 * hashCode + value.hashCode();
        }
        return hashCode;
    }

}
