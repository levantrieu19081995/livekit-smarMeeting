package vn.cusc.meetingservice.model.enums;

public enum MinutesTemplateCode {
    CUSC_STANDARD(
        "Biên bản họp CUSC",
        "Mẫu biên bản họp CUSC - BM07.40/PM/CUSC",
        "minutes/cusc-standard"
    );

    private final String name;
    private final String description;
    private final String templateName;

    MinutesTemplateCode(
        String name,
        String description,
        String templateName
    ) {
        this.name = name;
        this.description = description;
        this.templateName = templateName;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getTemplateName() {
        return templateName;
    }
}
