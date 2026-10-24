package com.cloud.api.response;
import org.apache.cloudstack.api.BaseResponse;
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;
import com.google.gson.*;
public class ConfigurationPreflightWireSketch {
 static class Response extends BaseResponse {
  @SerializedName("status") @Param(description="preflight decision") String status="READY";
  @SerializedName("revision") @Param(description="configuration revision") Long revision=2L;
  @SerializedName("fingerprint") @Param(description="public impact fingerprint") String fingerprint="design-fingerprint";
  @SerializedName("observedat") @Param(description="measurement time") java.util.Date observed=java.util.Date.from(java.time.Instant.parse("2026-10-24T08:00:00Z"));
  @SerializedName("cancellationsupported") @Param(description="actual cancellation support") Boolean cancel=false;
  @SerializedName("checks") @Param(description="bounded public checks") JsonArray checks=new JsonArray();
  Response(){setObjectName("configurationpreflight");setResponseName("preflightstorageserviceconfigurationresponse");JsonObject c=new JsonObject();c.addProperty("code","MEMORY_AVAILABLE");c.addProperty("blocking",false);c.addProperty("availablebytes",3777335296L);c.addProperty("requiredbytes",218251608L);checks.add(c);}
 }
 public static void main(String[] args)throws Exception{
  String wire=ApiResponseSerializer.toSerializedString(new Response(),"json");
  JsonObject root=JsonParser.parseString(wire).getAsJsonObject().getAsJsonObject("preflightstorageserviceconfigurationresponse");
  JsonObject p=root.getAsJsonObject("configurationpreflight");
  if(!p.get("status").getAsString().equals("READY")||p.get("revision").getAsLong()!=2||!p.get("checks").isJsonArray()||p.get("cancellationsupported").getAsBoolean())throw new AssertionError(wire);
  System.out.println(wire);
 }
}
