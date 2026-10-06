package ch.regdata.rps.engine.client.mapping;

public class RPSMapping {

    public static final RPSMapping Empty = new RPSMapping();

    private String className;
    private String propertyName;

    public RPSMapping(String className, String propertyName) {
        this.className = className;
        this.propertyName = propertyName;
    }

    private RPSMapping() {
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getPropertyName() {
        return propertyName;
    }

    public void setPropertyName(String propertyName) {
        this.propertyName = propertyName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        RPSMapping that = (RPSMapping) o;

        if (!className.equals(that.className)) return false;
        return propertyName.equals(that.propertyName);
    }

    @Override
    public int hashCode() {
        int result = className.hashCode();
        result = 31 * result + propertyName.hashCode();
        return result;
    }

}
