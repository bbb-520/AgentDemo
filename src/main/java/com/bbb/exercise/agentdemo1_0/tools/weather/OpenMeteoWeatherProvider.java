package com.bbb.exercise.agentdemo1_0.tools.weather;

import tools.jackson.databind.JsonNode;
import com.bbb.exercise.agentdemo1_0.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.Map;


/**
 * 首选天气数据源：Open-Meteo（免密钥）。
 *
 * <p>两步调用：城市名 →（geocoding）经纬度 →（forecast）当前实况，
 * 再把 WMO 天气码与风向角度转成中文描述返回。
 */
@Slf4j
@Component
@Order(1)
public class OpenMeteoWeatherProvider implements WeatherProvider {

    private static final String GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search";
    private static final String FORECAST_URL = "https://api.open-meteo.com/v1/forecast";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** WMO Weather interpretation codes → 中文描述 */
    private static final Map<Integer, String> WMO_CODES = Map.<Integer, String>ofEntries(
            Map.entry(0, "晴"),
            Map.entry(1, "大致晴朗"),
            Map.entry(2, "局部多云"),
            Map.entry(3, "阴"),
            Map.entry(45, "雾"),
            Map.entry(48, "冻雾"),
            Map.entry(51, "小毛毛雨"),
            Map.entry(53, "毛毛雨"),
            Map.entry(55, "大毛毛雨"),
            Map.entry(56, "冻毛毛雨"),
            Map.entry(57, "强冻毛毛雨"),
            Map.entry(61, "小雨"),
            Map.entry(63, "中雨"),
            Map.entry(65, "大雨"),
            Map.entry(66, "冻雨"),
            Map.entry(67, "强冻雨"),
            Map.entry(71, "小雪"),
            Map.entry(73, "中雪"),
            Map.entry(75, "大雪"),
            Map.entry(77, "雪粒"),
            Map.entry(80, "小阵雨"),
            Map.entry(81, "阵雨"),
            Map.entry(82, "强阵雨"),
            Map.entry(85, "小阵雪"),
            Map.entry(86, "大阵雪"),
            Map.entry(95, "雷暴"),
            Map.entry(96, "雷暴伴小冰雹"),
            Map.entry(99, "雷暴伴大冰雹"));

    private static final String[] WIND_DIRECTIONS =
            {"北", "东北", "东", "东南", "南", "西南", "西", "西北"};

    private final WebClient webClient;

    public OpenMeteoWeatherProvider(@Qualifier("openMeteoWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public String name() {
        return "Open-Meteo";
    }

    /**
     * 查询流程：地理编码拿坐标 → 请求实况 → 格式化中文描述。
     * 任一步数据缺失都抛异常，由 {@link WeatherService} 降级到下一个数据源。
     */
    @Override
    public String fetchWeather(String city) {
        JsonNode location = geocode(city);
        double lat = location.path("latitude").asDouble();
        double lon = location.path("longitude").asDouble();
        String resolvedName = location.path("name").asText(city);
        String admin = location.path("admin1").asText("");
        String country = location.path("country").asText("");

        JsonNode current = forecast(lat, lon);
        return format(resolvedName, admin, country, current);
    }

    /** 城市名 → 经纬度（取第一个匹配结果） */
    private JsonNode geocode(String city) {
        // 注意：必须以 java.net.URI 形式交给 WebClient。
        // 传 String 会被当作 URI 模板二次编码（%E5%8C%97 → %25E5...），
        // 中文城市名到服务端变成乱码，导致「未找到城市」。
        URI uri = UriComponentsBuilder.fromUriString(GEOCODING_URL)
                .queryParam("name", city)
                .queryParam("count", 1)
                .queryParam("language", "zh")
                .queryParam("format", "json")
                .build()
                .encode()
                .toUri();
        JsonNode response = get(uri);
        JsonNode results = response == null ? null : response.path("results");
        if (results == null || !results.isArray() || results.isEmpty()) {
            throw new IllegalStateException("Open-Meteo 地理编码未找到城市: " + city);
        }
        return results.get(0);
    }

    /** 经纬度 → 当前实况 */
    private JsonNode forecast(double lat, double lon) {
        URI uri = UriComponentsBuilder.fromUriString(FORECAST_URL)
                .queryParam("latitude", lat)
                .queryParam("longitude", lon)
                .queryParam("current", "temperature_2m,relative_humidity_2m,apparent_temperature,"
                        + "weather_code,wind_speed_10m,wind_direction_10m")
                .queryParam("timezone", "auto")
                .build()
                .toUri();
        JsonNode response = get(uri);
        JsonNode current = response == null ? null : response.path("current");
        if (current == null || current.isMissingNode() || current.isNull()) {
            throw new IllegalStateException("Open-Meteo 未返回实况数据");
        }
        return current;
    }

    private JsonNode get(URI uri) {
        log.debug("[weather] Open-Meteo 请求: {}", uri);
        return webClient.get()
                .uri(uri)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(TIMEOUT);
    }

    /** 组装面向模型的中文天气描述 */
    private String format(String name, String admin, String country, JsonNode current) {
        StringBuilder place = new StringBuilder(name);
        if (!StringUtils.isBlank(admin) && !admin.equals(name)) {
            place.append("（").append(admin);
            if (!StringUtils.isBlank(country)) {
                place.append("，").append(country);
            }
            place.append("）");
        } else if (!StringUtils.isBlank(country)) {
            place.append("（").append(country).append("）");
        }

        int code = current.path("weather_code").asInt(-1);
        String weather = WMO_CODES.getOrDefault(code, "天气码 " + code);
        double temp = current.path("temperature_2m").asDouble();
        double feels = current.path("apparent_temperature").asDouble();
        int humidity = current.path("relative_humidity_2m").asInt();
        double windSpeed = current.path("wind_speed_10m").asDouble();
        int windDeg = current.path("wind_direction_10m").asInt();

        return String.format(
                "%s 当前天气：%s。气温 %.1f℃（体感 %.1f℃），相对湿度 %d%%，风速 %.1f km/h，风向%s。",
                place, weather, temp, feels, humidity, windSpeed, windDirection(windDeg));
    }

    /** 风向角度 → 八方位中文 */
    private static String windDirection(int degrees) {
        int idx = (int) Math.round(((degrees % 360) + 360) % 360 / 45.0) % 8;
        return WIND_DIRECTIONS[idx] + "风";
    }
}
