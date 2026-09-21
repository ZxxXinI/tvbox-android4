package com.tvbox.android44.common;

/**
 * 所有时间与容量常量的单一来源（对应规则文档中的行为基线）。
 * 禁止在页面中复制这些魔法数字。
 */
public final class AppConstants {

    private AppConstants() {
    }

    // ===== 搜索 / 详情并发与超时 =====
    /** 搜索最大并行来源数，硬约束（文档 01/06）。 */
    public static final int SEARCH_MAX_CONCURRENT = 3;
    /** 单来源搜索超时（毫秒）。 */
    public static final long SEARCH_TIMEOUT_MS = 3000;
    /** 详情补线单来源完整匹配流程超时（毫秒）。 */
    public static final long DETAIL_SUPPLEMENT_TIMEOUT_MS = 4000;
    /** 来源失败冷却（毫秒）。 */
    public static final long SOURCE_COOLDOWN_MS = 2 * 60 * 1000L;
    /** 搜索输入长度上限。 */
    public static final int SEARCH_QUERY_MAX_LENGTH = 60;

    // ===== 缓存 =====
    /** 分类/列表/搜索内存缓存 TTL 与容量。 */
    public static final long LIST_CACHE_TTL_MS = 5 * 60 * 1000L;
    public static final int LIST_CACHE_MAX_ENTRIES = 80;
    /** 分类表缓存 TTL（ac=list，变化少，可长于列表）。 */
    public static final long CATEGORY_CACHE_TTL_MS = 30 * 60 * 1000L;
    /** 主来源详情缓存 TTL 与容量。 */
    public static final long DETAIL_CACHE_TTL_MS = 30 * 60 * 1000L;
    public static final int DETAIL_CACHE_MAX_ENTRIES = 60;
    /** 豆瓣成功/失败缓存 TTL。 */
    public static final long DOUBAN_SUCCESS_TTL_MS = 20 * 60 * 1000L;
    public static final long DOUBAN_FAILURE_TTL_MS = 6 * 60 * 1000L;
    public static final int DOUBAN_PAGE_SIZE = 20;

    // ===== 历史与线路健康 =====
    /** 历史最大条数。 */
    public static final int HISTORY_MAX = 100;
    /** 健康记录保留天数与最大条数。 */
    public static final int HEALTH_KEEP_DAYS = 30;
    public static final int HEALTH_MAX_ENTRIES = 300;
    /** 历史进度节流保存间隔（毫秒）。 */
    public static final long HISTORY_SAVE_INTERVAL_MS = 10 * 1000L;
    /** 恢复播放时距离片尾过近的阈值（比例 0.0~1.0）。 */
    public static final double HISTORY_END_RESTART_RATIO = 0.97;

    // ===== 播放器 =====
    /** 快进/快退步长（毫秒）。 */
    public static final long SEEK_STEP_MS = 10 * 1000L;
    /** 手动 seek 后的卡顿判断冷却（毫秒）。 */
    public static final long SEEK_COOLDOWN_MS = 3 * 1000L;
    /** 倍速固定循环序列。 */
    public static final float[] SPEED_SEQUENCE = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
    /** 控制层自动隐藏（毫秒）。 */
    public static final long CONTROLLER_HIDE_DELAY_MS = 5 * 1000L;

    // ===== 卡顿判断（点播） =====
    public static final long VOD_CONTINUOUS_BUFFER_MS = 5 * 1000L;
    public static final int VOD_FREQUENT_BUFFER_COUNT = 3;
    public static final long VOD_FREQUENT_WINDOW_MS = 60 * 1000L;
    public static final long VOD_CUMULATIVE_BUFFER_MS = 8 * 1000L;

    // ===== 卡顿判断（电视直播） =====
    public static final long LIVE_CONTINUOUS_BUFFER_MS = 6 * 1000L;
    public static final int LIVE_FREQUENT_BUFFER_COUNT = 3;
    public static final long LIVE_FREQUENT_WINDOW_MS = 60 * 1000L;
    public static final long LIVE_CUMULATIVE_BUFFER_MS = 12 * 1000L;
    /** 直播播放位置停滞判定（毫秒）。 */
    public static final long LIVE_NO_PROGRESS_MS = 4 * 1000L;

    // ===== 自动换线 =====
    /** 失败线路自动换线冷却（毫秒）。 */
    public static final long LINE_RETRY_COOLDOWN_MS = 30 * 60 * 1000L;

    // ===== 直播页 =====
    /** 数字选台提交延迟（毫秒）。 */
    public static final long LIVE_DIGIT_COMMIT_DELAY_MS = 1500;

    // ===== 局域网配置服务 =====
    public static final int CONFIG_PORT_AI = 9978;
    public static final int CONFIG_PORT_API = 9979;
    public static final int CONFIG_PORT_FALLBACK_TRIES = 4;
    /** 配置会话 token 有效期（毫秒）。 */
    public static final long CONFIG_TOKEN_TTL_MS = 5 * 60 * 1000L;
    public static final int CONFIG_MAX_TOKEN_FAILURES = 5;

    // ===== OTA =====
    public static final String APK_MIME = "application/vnd.android-package-archive";

    // ===== 请求超时基线（毫秒） =====
    public static final long HTTP_CONNECT_TIMEOUT_MS = 10 * 1000L;
    public static final long HTTP_READ_TIMEOUT_MS = 15 * 1000L;
    /** 列表/分类请求整体上限（callTimeout）：须大于连接+读取之和，否则慢网误杀。 */
    public static final long LIST_CALL_TIMEOUT_MS =
            HTTP_CONNECT_TIMEOUT_MS + HTTP_READ_TIMEOUT_MS + 5 * 1000L;
    /** AI 请求基线：连接 30s / 读取 45s / 写入 20s。 */
    public static final long AI_CONNECT_TIMEOUT_MS = 30 * 1000L;
    public static final long AI_READ_TIMEOUT_MS = 45 * 1000L;
    public static final long AI_WRITE_TIMEOUT_MS = 20 * 1000L;

    // ===== 首页网格 =====
    public static final int GRID_COLUMNS_NORMAL = 6;
    public static final int GRID_COLUMNS_LARGE = 5;
    public static final int GRID_COLUMNS_XLARGE = 4;
}
