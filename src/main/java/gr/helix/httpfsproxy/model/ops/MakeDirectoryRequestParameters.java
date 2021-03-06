package gr.helix.httpfsproxy.model.ops;

import javax.validation.constraints.Pattern;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@lombok.Value
@lombok.EqualsAndHashCode(callSuper = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MakeDirectoryRequestParameters extends BaseRequestParameters
{
    @JsonProperty("permission")
    @Pattern(regexp = "^[0-7][0-7][0-7]$")
    String permission;
    
    @JsonCreator
    public static MakeDirectoryRequestParameters of(@JsonProperty("permission") String permission)
    {
        return new MakeDirectoryRequestParameters(permission);
    }
}
