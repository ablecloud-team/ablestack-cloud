package com.cloud.api.response;
import org.apache.cloudstack.api.BaseResponse;
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;
import com.google.gson.JsonObject;
public class ConfigurationOperationWireSketch {
  static class Response extends BaseResponse {
    @SerializedName("id") @Param(description="operation id") String id="design-operation";
    @SerializedName("requestid") @Param(description="idempotency request") String requestId="design-request";
    @SerializedName("state") @Param(description="operation state") String state="ROLLED_BACK";
    @SerializedName("originalfailure") @Param(description="sanitized immutable first failure") JsonObject first=new JsonObject();
    @SerializedName("rollback") @Param(description="separate rollback result") JsonObject rollback=new JsonObject();
    Response(){setObjectName("configurationoperation");setResponseName("getstorageserviceconfigurationoperationresponse");first.addProperty("code","VERIFY_FAILED");first.addProperty("phase","VERIFYING");rollback.addProperty("state","SUCCEEDED");}
  }
  public static void main(String[] args)throws Exception{
    String wire=ApiResponseSerializer.toSerializedString(new Response(),"json");
    com.google.gson.JsonObject j=com.google.gson.JsonParser.parseString(wire).getAsJsonObject();
    if(!j.getAsJsonObject("getstorageserviceconfigurationoperationresponse").getAsJsonObject("configurationoperation").get("state").getAsString().equals("ROLLED_BACK"))throw new AssertionError(wire);
    System.out.println(wire);
  }
}
