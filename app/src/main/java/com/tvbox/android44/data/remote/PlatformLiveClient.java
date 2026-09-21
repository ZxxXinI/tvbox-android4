package com.tvbox.android44.data.remote;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tvbox.android44.domain.model.PlatformLive;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import okhttp3.Request;

/**
 * 平台直播统一服务客户端（只消费 platform_live_server 接口，不抓网页）：
 * /v1/live/sites、/v1/live/categories、/v1/live/rooms、/v1/live/resolve。
 * 解析对返回形状保持容忍（数组或对象包裹均可）。
 */
public class PlatformLiveClient {

    /** 请求头白名单：只把这些头传给播放器数据源。 */
    public static final Set<String> HEADER_WHITELIST = new HashSet<String>(
            Arrays.asList("user-agent", "referer", "origin"));

    private final String baseUrl;

    public PlatformLiveClient(String baseUrl) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** 去末尾 '/'，只接受 http/https。 */
    public static String normalizeBaseUrl(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            return "";
        }
        return s;
    }

    public List<PlatformLive.Site> sites(@Nullable CancelScope scope) throws IOException {
        if (com.tvbox.android44.BuildConfig.DEBUG && android.os.Build.VERSION.SDK_INT >= 24) {
            android.util.Log.d("TVBOX_PLATFORM", "cleartext permitted="
                    + android.security.NetworkSecurityPolicy.getInstance()
                    .isCleartextTrafficPermitted("20.205.10.127"));
        }
        String body = get("/v1/live/sites", scope);
        if (com.tvbox.android44.BuildConfig.DEBUG) {
            android.util.Log.d("TVBOX_PLATFORM", "sites bodyLength="
                    + (body == null ? -1 : body.length()));
        }
        JsonObject root = parseObjectOrWrap(body);
        JsonArray arr = findArray(root, "sites", "list", "data", "items");
        List<PlatformLive.Site> out = new ArrayList<PlatformLive.Site>();
        if (arr != null) {
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                String id = str(o, "id", "site", "code");
                String name = str(o, "name", "title");
                if (id == null || id.isEmpty()) continue;
                out.add(new PlatformLive.Site(id, name == null ? id : name, str(o, "description", "desc")));
            }
        }
        if (com.tvbox.android44.BuildConfig.DEBUG) {
            android.util.Log.d("TVBOX_PLATFORM", "sites parsed=" + out.size());
        }
        return out;
    }

    public List<PlatformLive.Category> categories(String site, @Nullable String parentId,
                                                   @Nullable CancelScope scope) throws IOException {
        String path = "/v1/live/categories?site=" + MacCmsClient.encode(site);
        if (parentId != null && !parentId.isEmpty()) {
            path += "&parentId=" + MacCmsClient.encode(parentId);
        }
        String body = get(path, scope);
        return parseCategoriesBody(body, parentId);
    }

    /**
     * 解析平台分类响应。服务端会同时返回 parentCategories 和 categories，
     * 且 parentId 查询在部分平台上仍返回完整分类表，因此客户端必须自己分层。
     */
    static List<PlatformLive.Category> parseCategoriesBody(String body, @Nullable String parentId)
            throws IOException {
        JsonObject root = parseObjectOrWrap(body);
        boolean wantsParents = parentId == null || parentId.isEmpty();
        JsonArray arr = wantsParents
                ? findArray(root, "parentCategories", "parents", "parent_cats")
                : findArray(root, "categories", "list", "data", "items", "children");
        if (arr == null || arr.size() == 0) {
            // 没有父分类的平台直接返回子分类；兼容服务端只提供 categories 的旧形状。
            arr = findArray(root, "categories", "list", "data", "items", "children");
        }
        List<PlatformLive.Category> out = new ArrayList<PlatformLive.Category>();
        if (arr != null) {
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                String id = str(o, "id", "categoryId", "cate_id");
                String name = str(o, "name", "title", "cate_name");
                if (id == null || id.isEmpty() || name == null) continue;
                String pid = str(o, "parentId", "parent_id", "pid");
                out.add(new PlatformLive.Category(id, pid == null ? "" : pid, name,
                        str(o, "icon", "iconUrl", "pic", "cover", "coverUrl")));
            }
        }
        if (!wantsParents && parentId != null && !parentId.isEmpty()) {
            boolean hasParentMetadata = false;
            for (PlatformLive.Category category : out) {
                if (category.parentId != null && !category.parentId.isEmpty()) {
                    hasParentMetadata = true;
                    break;
                }
            }
            if (hasParentMetadata) {
                List<PlatformLive.Category> filtered = new ArrayList<PlatformLive.Category>();
                for (PlatformLive.Category category : out) {
                    if (parentId.equals(category.parentId)) {
                        filtered.add(category);
                    }
                }
                return filtered;
            }
        }
        return out;
    }

    public static final class RoomsPage {
        public final List<PlatformLive.Room> rooms = new ArrayList<PlatformLive.Room>();
        public boolean hasMore;
        public int page;
    }

    public RoomsPage rooms(String site, String categoryId, int page,
                           @Nullable CancelScope scope) throws IOException {
        String path = "/v1/live/rooms?site=" + MacCmsClient.encode(site)
                + "&categoryId=" + MacCmsClient.encode(categoryId)
                + "&page=" + page;
        String body = get(path, scope);
        JsonObject root = parseObjectOrWrap(body);
        RoomsPage out = new RoomsPage();
        out.page = page;
        JsonArray arr = findArray(root, "rooms", "list", "data", "items");
        if (arr != null) {
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                String roomId = str(o, "roomId", "room_id", "id", "rid");
                String title = str(o, "title", "roomName", "room_name", "name");
                if (roomId == null || roomId.isEmpty()) continue;
                Boolean living = bool(o, "living", "isLive", "is_live", "showStatus");
                out.rooms.add(new PlatformLive.Room(
                        site, roomId,
                        title == null ? "" : title,
                        str(o, "cover", "coverUrl", "pic", "cover_url", "thumb"),
                        str(o, "anchor", "anchorName", "nickname", "uname"),
                        str(o, "areaName", "area_name", "area", "cate"),
                        intOr(o, -1, "viewers", "views", "online", "hot"),
                        living == null || living));
            }
        }
        if (root != null && root.has("hasMore")) {
            out.hasMore = root.get("hasMore").getAsBoolean();
        } else if (root != null && root.has("total")) {
            try {
                long total = root.get("total").getAsLong();
                out.hasMore = (long) page * 20L < total;
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public PlatformLive.Stream resolve(String site, String roomId, boolean refresh,
                                       @Nullable CancelScope scope) throws IOException {
        String path = "/v1/live/resolve?site=" + MacCmsClient.encode(site)
                + "&roomId=" + MacCmsClient.encode(roomId)
                + "&refresh=" + (refresh ? 1 : 0);
        String body = get(path, scope);
        JsonObject root = parseObjectOrWrap(body);
        if (root == null) {
            throw new IOException("PARSE");
        }
        PlatformLive.Stream stream = new PlatformLive.Stream(
                bool(root, "live", "isLive") == null || Boolean.TRUE.equals(bool(root, "live", "isLive")));
        JsonArray arr = findArray(root, "streams", "candidates", "list", "data", "items", "urls");
        if (arr != null) {
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                String url = str(o, "url", "playUrl", "play_url", "stream", "flv", "hls");
                if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
                    continue;
                }
                String name = str(o, "name", "cdn", "cdnName");
                String protocol = str(o, "protocol", "type");
                String quality = str(o, "quality", "rate", "levelName");
                stream.candidates.add(new PlatformLive.StreamCandidate(
                        name == null || name.isEmpty() ? "默认" : name,
                        protocol == null ? "" : protocol,
                        url, quality == null ? "" : quality));
            }
        }
        if (root.has("headers") && root.get("headers").isJsonObject()) {
            JsonObject h = root.getAsJsonObject("headers");
            for (Map.Entry<String, JsonElement> e : h.entrySet()) {
                if (e.getValue().isJsonPrimitive() && HEADER_WHITELIST.contains(e.getKey().toLowerCase(java.util.Locale.ROOT))) {
                    stream.headers.put(e.getKey(), e.getValue().getAsString());
                }
            }
        }
        if (stream.candidates.isEmpty()) {
            throw new IOException("EMPTY_BODY");
        }
        return stream;
    }

    private String get(String path, @Nullable CancelScope scope) throws IOException {
        Request request = new Request.Builder().url(baseUrl + path).get().build();
        return HttpExecutor.executeForString(HttpClients.withTimeout(12000L), request, scope);
    }

    private static JsonObject parseObjectOrWrap(String body) throws IOException {
        if (body == null) return null;
        String s = body.trim();
        if (s.startsWith("\uFEFF")) s = s.substring(1);
        com.google.gson.stream.JsonReader reader = new com.google.gson.stream.JsonReader(new StringReader(s));
        reader.setLenient(true);
        try {
            JsonElement el = JsonParser.parseReader(reader);
            if (el.isJsonObject()) {
                return el.getAsJsonObject();
            }
            if (el.isJsonArray()) {
                JsonObject wrap = new JsonObject();
                wrap.add("list", el.getAsJsonArray());
                return wrap;
            }
            throw new IOException("PARSE");
        } catch (com.google.gson.JsonSyntaxException e) {
            throw new IOException("PARSE");
        }
    }

    private static JsonArray findArray(JsonObject root, String... keys) {
        if (root == null) return null;
        for (String k : keys) {
            if (root.has(k)) {
                JsonElement el = root.get(k);
                if (el.isJsonArray()) {
                    return el.getAsJsonArray();
                }
                if (el.isJsonObject()) {
                    // 兼容 data.list 两级结构
                    for (String k2 : keys) {
                        if (el.getAsJsonObject().has(k2) && el.getAsJsonObject().get(k2).isJsonArray()) {
                            return el.getAsJsonObject().getAsJsonArray(k2);
                        }
                    }
                }
            }
        }
        return null;
    }

    private static String str(JsonObject o, String... keys) {
        for (String k : keys) {
            if (o.has(k) && o.get(k).isJsonPrimitive()) {
                return o.get(k).getAsString();
            }
        }
        return null;
    }

    private static Boolean bool(JsonObject o, String... keys) {
        for (String k : keys) {
            if (o.has(k) && o.get(k).isJsonPrimitive()) {
                try {
                    return o.get(k).getAsBoolean();
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private static int intOr(JsonObject o, int def, String... keys) {
        for (String k : keys) {
            if (o.has(k) && o.get(k).isJsonPrimitive()) {
                try {
                    return o.get(k).getAsInt();
                } catch (Exception ignored) {
                }
            }
        }
        return def;
    }
}
