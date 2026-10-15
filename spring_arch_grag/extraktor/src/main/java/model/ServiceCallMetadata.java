package model;

public class ServiceCallMetadata {

    private String serviceName;

    /** HTTP verb of the call site, or null if not statically determinable. */
    private String httpMethod;

    /** Path template of the call site (e.g. "/owners/{}/pets"), or null if not statically determinable. */
    private String path;

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    @Override
    public String toString() {
        return "ServiceCallMetadata{" +
                "serviceName='" + serviceName + '\'' +
                ", httpMethod='" + httpMethod + '\'' +
                ", path='" + path + '\'' +
                '}';
    }
}
