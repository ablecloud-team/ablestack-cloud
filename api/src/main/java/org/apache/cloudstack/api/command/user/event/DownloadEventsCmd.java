package org.apache.cloudstack.api.command.user.event;

import com.cloud.event.Event;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.user.Account;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.EventResponse;
import org.apache.cloudstack.api.response.SuccessResponse;
import org.apache.cloudstack.context.CallContext;

import java.util.Date;
import java.util.List;

@APICommand(name = "downloadEvents", description = "Download one or more events.", responseObject = SuccessResponse.class, entityType = {Event.class},
        requestHasSensitiveInfo = false, responseHasSensitiveInfo = false)
public class DownloadEventsCmd extends BaseCmd {

    private static final String s_name = "downloadeventsresponse";

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.IDS,
            type = BaseCmd.CommandType.LIST,
            collectionType = BaseCmd.CommandType.UUID,
            entityType = EventResponse.class,
            description = "the IDs of the events")
    private List<Long> ids;

    @Parameter(name = ApiConstants.END_DATE, type = BaseCmd.CommandType.DATE, description = "end date range to archive events"
            + " (including) this date (use format \"yyyy-MM-dd\" or the new format \"yyyy-MM-ddThh:mm:ss\")")
    private Date endDate;

    @Parameter(name = ApiConstants.START_DATE, type = BaseCmd.CommandType.DATE, description = "start date range to archive events"
            + " (including) this date (use format \"yyyy-MM-dd\" or the new format \"yyyy-MM-ddThh:mm:ss\")")
    private Date startDate;

    @Parameter(name = ApiConstants.TYPE, type = BaseCmd.CommandType.STRING, description = "archive by event type")
    private String type;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public List<Long> getIds() {
        return ids;
    }

    public Date getEndDate() {
        return endDate;
    }

    public Date getStartDate() {
        return startDate;
    }

    public String getType() {
        return type;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public String getCommandName() {
        return s_name;
    }

    @Override
    public long getEntityOwnerId() {
        Account account = CallContext.current().getCallingAccount();
        if (account != null) {
            return account.getId();
        }
        return Account.ACCOUNT_ID_SYSTEM;
    }

    @Override
    public void execute() {
        if (ids == null && type == null && endDate == null) {
            throw new InvalidParameterValueException("either ids, type or enddate must be specified");
        } else if (startDate != null && endDate == null) {
            throw new InvalidParameterValueException("enddate must be specified with startdate parameter");
        }
        boolean result = _mgr.downloadEvents(this);
        if (result) {
            SuccessResponse response = new SuccessResponse(getCommandName());
            setResponseObject(response);
        } else {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Unable to archive Events, one or more parameters has invalid values");
        }
    }
}