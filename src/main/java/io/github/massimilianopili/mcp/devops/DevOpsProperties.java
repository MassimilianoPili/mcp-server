package io.github.massimilianopili.mcp.devops;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mcp.devops")
public class DevOpsProperties {

    private String organization;
    private String project;
    private String team;
    private String pat;
    private String apiVersion = "7.1";

    public String getOrganization() { return organization; }
    public void setOrganization(String organization) { this.organization = organization; }

    public String getProject() { return project; }
    public void setProject(String project) { this.project = project; }

    public String getTeam() { return team; }
    public void setTeam(String team) { this.team = team; }

    public String getPat() { return pat; }
    public void setPat(String pat) { this.pat = pat; }

    public String getApiVersion() { return apiVersion; }
    public void setApiVersion(String apiVersion) { this.apiVersion = apiVersion; }

    /** Base URL: https://dev.azure.com/{organization}/{project} */
    public String getBaseUrl() {
        return "https://dev.azure.com/" + organization + "/" + project;
    }

    /** Base URL per API team-scoped: https://dev.azure.com/{org}/{project}/{team} */
    public String getTeamBaseUrl() {
        return getBaseUrl() + "/" + team;
    }
}
