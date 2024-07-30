package io.vertx.core.net;

import io.vertx.core.json.JsonObject;

/**
 * Converter and mapper for {@link io.vertx.core.net.OpenSSLEngineOptions}.
 * NOTE: This class has been automatically generated from the {@link io.vertx.core.net.OpenSSLEngineOptions} original class using Vert.x codegen.
 */
public class OpenSSLEngineOptionsConverter {


   static void fromJson(Iterable<java.util.Map.Entry<String, Object>> json, OpenSSLEngineOptions obj) {
    for (java.util.Map.Entry<String, Object> member : json) {
      switch (member.getKey()) {
        case "sessionCacheEnabled":
          if (member.getValue() instanceof Boolean) {
            obj.setSessionCacheEnabled((Boolean)member.getValue());
          }
          break;
        case "useWorkerThread":
          if (member.getValue() instanceof Boolean) {
            obj.setUseWorkerThread((Boolean)member.getValue());
          }
          break;
      }
    }
  }

   static void toJson(OpenSSLEngineOptions obj, JsonObject json) {
    toJson(obj, json.getMap());
  }

   static void toJson(OpenSSLEngineOptions obj, java.util.Map<String, Object> json) {
    json.put("sessionCacheEnabled", obj.isSessionCacheEnabled());
    json.put("useWorkerThread", obj.getUseWorkerThread());
  }
}
