package apoc.spatial;

import apoc.ApocConfiguration;
import apoc.Description;
import apoc.util.JsonUtil;
import apoc.util.Util;
import org.codehaus.jackson.node.ObjectNode;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.logging.Log;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static apoc.util.MapUtil.map;
import static apoc.util.Util.toDouble;
import static apoc.util.Util.toLong;
import static java.lang.String.valueOf;
import static java.lang.System.currentTimeMillis;

public class Geocode {
    public static final int MAX_RESULTS = 100;
    public static final String PREFIX = "spatial.geocode";
    public static final String GEOCODE_PROVIDER_KEY = "provider";

    @Context
    public GraphDatabaseService db;

    @Context
    public GraphDatabaseAPI graph;

    @Context
    public KernelTransaction kernelTransaction;

    @Context
    public Log log;

    interface GeocodeSupplier {
        Stream<GeoCodeResult> geocode(String encodedAddress, long maxResults);
    }

    private static class Throttler {
        private final KernelTransaction kernelTransaction;
        private long throttleInMs;
        private static long lastCallTime = 0L;
        private static long DEFAULT_THROTTLE = 5*1000;  // 5 seconds
        private static long MAX_THROTTLE = 60 * 60 * 1000;  // 1 hour
        private Log log;

        public Throttler(KernelTransaction kernelTransaction, long throttle, Log log) {
            this.kernelTransaction = kernelTransaction;
            this.log = log;

            throttle = Math.min(throttle, MAX_THROTTLE);
            if (throttle < 0) throttle = DEFAULT_THROTTLE;

            this.throttleInMs = throttle;
        }

        private void waitForThrottle() {
            long msSinceLastCall = currentTimeMillis() - lastCallTime;
            while (msSinceLastCall < throttleInMs) {
                try {
                    if (kernelTransaction.getReasonIfTerminated() != null) return;
                    long msToWait = throttleInMs - msSinceLastCall;
                    log.debug("apoc.spatial.geocode: throttling calls to geocode service for " + msToWait + "ms");
                    Thread.sleep(Math.min(msToWait, 1000));
                } catch (InterruptedException e) {
                    // ignore
                }
                msSinceLastCall = currentTimeMillis() - lastCallTime;
            }
            lastCallTime = currentTimeMillis();
        }
    }

    private static class SupplierWithKey implements GeocodeSupplier {
        private static final String[] FORMATTED_KEYS = new String[]{"formatted", "formatted_address", "address", "description", "display_name"};
        private static final String[] LAT_KEYS = new String[]{"lat", "latitude"};
        private static final String[] LNG_KEYS = new String[]{"lng", "longitude", "lon"};
        private Throttler throttler;
        private String configBase;
        private String urlTemplate;
        private Log log;

        public SupplierWithKey(Map<String, Object> config, KernelTransaction kernelTransaction, Log log, String provider) {
            this.configBase = provider;
            this.log = log;

            if (!config.containsKey(configKey("url"))) {
                throw new IllegalArgumentException("Missing 'url' for geocode provider: " + provider);
            }
            urlTemplate = config.get(configKey("url")).toString();
            if (!urlTemplate.contains("PLACE")) throw new IllegalArgumentException("Missing 'PLACE' in url template: " + urlTemplate);

            if (urlTemplate.contains("KEY") && !config.containsKey(configKey("key"))) {
                throw new IllegalArgumentException("Missing 'key' for geocode provider: " + provider);
            }
            String key = config.get(configKey("key")).toString();
            urlTemplate = urlTemplate.replace("KEY", key);

            this.throttler = new Throttler(kernelTransaction, toLong(ApocConfiguration.get(configKey("throttle"), Throttler.DEFAULT_THROTTLE)), log);
        }

        @SuppressWarnings("unchecked")
        public Stream<GeoCodeResult> geocode(String address, long maxResults) {
            throttler.waitForThrottle();
            String url = urlTemplate.replace("PLACE", Util.encodeUrlComponent(address));
            log.info("apoc.spatial.geocode: " + url);
            Object value = loadJsonTryTwice(url, log);
            if (value instanceof List) {
                return findResults((List<Map<String, Object>>) value, maxResults);
            } else if (value instanceof Map) {
                Map data = (Map) value;
                Object results = data.get("results");
                if (results instanceof List) {
                    List<Map<String, Object>> resultList = (List<Map<String, Object>>) results;
                    if (resultList.size() == 0) {
                        if (data.containsKey("status")) {
                            Object status = data.get("status");
                            if (status instanceof String) {
                                if (status.toString().equals("OVER_QUERY_LIMIT")) {
                                    throw new RuntimeException(this.configBase + ": " + status.toString());
                                }
                            } else if (status instanceof Map) {
                                Map statusMap = (Map) status;
                                if (statusMap.containsKey("message") && statusMap.get("message").toString().equals("quota exceeded")) {
                                    throw new RuntimeException(this.configBase + ": " + statusMap.get("message").toString());
                                }
                            }
                        }
                    }
                    return findResults(resultList, maxResults);
                }
            }
            throw new RuntimeException("Can't parse geocoding results " + value);
        }

        @SuppressWarnings("unchecked")
        private Stream<GeoCodeResult> findResults(List<Map<String, Object>> results, long maxResults) {
            return results.stream().limit(maxResults).map(data -> {
                String description = findFirstEntry(data, FORMATTED_KEYS);
                Map<String,Object> location = (Map<String,Object>) data.get("geometry");
                if (location.containsKey("location")) {
                    location = (Map<String,Object>) location.get("location");
                }
                String lat = findFirstEntry(location, LAT_KEYS);
                String lng = findFirstEntry(location, LNG_KEYS);
                return new GeoCodeResult(toDouble(lat), toDouble(lng), description, data);
            });
        }

        private String findFirstEntry(Map<String, Object> map, String[] keys) {
            for (String key : keys) {
                if (map.containsKey(key)) {
                    return valueOf(map.get(key));
                }
            }
            return "";
        }

        private String configKey(String name) {
            return configBase + "." + name;
        }

    }

    private static class OSMSupplier implements GeocodeSupplier {
        public static final String OSM_URL = "http://nominatim.openstreetmap.org/search.php?format=json&q=";
        private Throttler throttler;
        private Log log;

        public OSMSupplier(Map<String, Object> config,KernelTransaction kernelTransaction, Log log) {
            this.throttler = new Throttler(kernelTransaction, toLong(config.getOrDefault("osm.throttle", Throttler.DEFAULT_THROTTLE)), log);
            this.log = log;
        }

        @SuppressWarnings("unchecked")
        public Stream<GeoCodeResult> geocode(String address, long maxResults) {
            throttler.waitForThrottle();
            String url = OSM_URL + Util.encodeUrlComponent(address);
            log.info("apoc.spatial.geocode: " + url);
            Object value = loadJsonTryTwice(url, log);
            if (value instanceof List) {
                return ((List<Map<String, Object>>) value).stream().limit(maxResults).map(data ->
                        new GeoCodeResult(toDouble(data.get("lat")), toDouble(data.get("lon")), valueOf(data.get("display_name")), data));
            }
            throw new RuntimeException("Can't parse geocoding results " + value);
        }
    }

    class GoogleSupplier implements GeocodeSupplier {
        private final Throttler throttler;
        private String baseUrl;
        private Log log;

        public GoogleSupplier(Map<String, Object> config, KernelTransaction kernelTransaction, Log log) {
            this.throttler = new Throttler(kernelTransaction, toLong(config.getOrDefault("google.throttle", Throttler.DEFAULT_THROTTLE)), log);
            this.baseUrl = String.format("https://maps.googleapis.com/maps/api/geocode/json?%s&address=", credentials(config));
            this.log = log;
        }

        private String credentials(Map<String, Object> config) {
            if (config.containsKey("google.client") && config.containsKey("google.signature")) {
                return "client=" + config.get("google.client") + "&signature=" + config.get("google.signature");
            } else if (config.containsKey("google.key")) {
                return "key=" + config.get("google.key");
            } else {
                return "auth=free"; // throw new RuntimeException("apoc.spatial.geocode: No google client or key specified in neo4j.conf config file");
            }
        }

        @SuppressWarnings("unchecked")
        public Stream<GeoCodeResult> geocode(String address, long maxResults) {
            if (address.length() < 1) {
                return Stream.empty();
            }
            throttler.waitForThrottle();
            String url = baseUrl + Util.encodeUrlComponent(address);
            log.info("apoc.spatial.geocode: " + url);
            Object value = loadJsonTryTwice(url, log);
            if (value instanceof Map) {
                Map valueMap = (Map) value;
                Object results = valueMap.get("results");
                if (results instanceof List) {
                    if (((List) results).size() == 0) {
                        if (valueMap.containsKey("error_message")) {
                            throw new RuntimeException("Google: " + valueMap.get("error_message").toString());
                        }
                    }
                    return ((List<Map<String, Object>>) results).stream().limit(maxResults).map(data -> {
                        Map location = (Map) ((Map) data.get("geometry")).get("location");
                        return new GeoCodeResult(toDouble(location.get("lat")), toDouble(location.get("lng")), valueOf(data.get("formatted_address")), data);
                    });
                }
            }
            throw new RuntimeException("Can't parse geocoding results " + value);
        }
    }

    private static Object loadJsonTryTwice(@Name("url") String url, Log log) {
        try {
            return JsonUtil.loadJson(url);
        } catch (Exception e) {
            log.info("Failed to load JSON: " + e.getMessage());
            try {
                return JsonUtil.loadJson(url);
            } catch (Exception e2) {
                log.info("Failed to load JSON a second time, returning an empty object");
                return emptyResults;
            }
        }
    }

    private static Object emptyResults;

    static {
        try {
            emptyResults = JsonUtil.OBJECT_MAPPER.readValue("{\"results\":[]}", Object.class);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    ;

    private GeocodeSupplier getSupplier() {
        Map<String, Object> activeConfig = ApocConfiguration.get(PREFIX);
        if (activeConfig.containsKey(GEOCODE_PROVIDER_KEY)) {
            String supplier = activeConfig.get(GEOCODE_PROVIDER_KEY).toString().toLowerCase();
            switch (supplier) {
                case "google" : return new GoogleSupplier(activeConfig, kernelTransaction, log);
                case "osm" : return new OSMSupplier(activeConfig,kernelTransaction, log);
                default: return new SupplierWithKey(activeConfig, kernelTransaction, log, supplier);
            }
        }
        return new OSMSupplier(activeConfig,kernelTransaction, log);
    }

    @Procedure
    @Description("apoc.spatial.geocodeOnce('address') YIELD location, latitude, longitude, description, osmData - look up geographic location of address from openstreetmap geocoding service")
    public Stream<GeoCodeResult> geocodeOnce(@Name("location") String address) throws UnsupportedEncodingException {
        return geocode(address, 1L);
    }

    @Procedure
    @Description("apoc.spatial.geocode('address') YIELD location, latitude, longitude, description, osmData - look up geographic location of address from openstreetmap geocoding service")
    public Stream<GeoCodeResult> geocode(@Name("location") String address, @Name("maxResults") long maxResults) {
        return getSupplier().geocode(address, maxResults == 0 ? MAX_RESULTS : Math.min(Math.max(maxResults,1), MAX_RESULTS));
    }

    public static class GeoCodeResult {
        public final Map<String, Object> location;
        public final Map<String, Object> data;
        public final Double latitude;
        public final Double longitude;
        public final String description;

        public GeoCodeResult(Double latitude, Double longitude, String description, Map<String, Object> data) {
            this.data = data;
            this.latitude = latitude;
            this.longitude = longitude;
            this.description = description;
            this.location = map("latitude", latitude, "longitude", longitude, "description", description);
        }
    }
}
