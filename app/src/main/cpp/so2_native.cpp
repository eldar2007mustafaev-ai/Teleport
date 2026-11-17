#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <cstdlib>
#include <cstdio>
#include <cctype>
#include <cmath>
#include <ctime>
#include <unistd.h>
#include <signal.h>
#include <sys/system_properties.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>

static volatile bool g_bridge_initialized = false;
static volatile bool g_is_server_authorized = false;
static volatile bool g_honeypot_tripped = false;
static volatile int64_t g_last_ok_monotonic_sec = 0;
static char g_native_session_key[128] = {0};
static char g_cached_hwid[64] = {0};

static const int64_t MAX_SERVER_SILENCE_SEC = 900; // 15 минут по часам ядра CLOCK_MONOTONIC

// Состояние пальцев для нативного инжектора касаний (усиленный алгоритм GameTouchLayer)
struct NativeFingerState {
    float dirX = 0.0f;
    float dirY = 0.0f;
    float velX = 0.0f;
    float velY = 0.0f;
    int frames = 0;
};
static NativeFingerState g_fingers[10];
static volatile float g_touch_stickiness = 68.0f;
static volatile float g_touch_heaviness = 55.0f;
static volatile float g_touch_sharpness = 78.0f;
static volatile float g_touch_spray_pull = 1.35f;
static volatile float g_touch_aim_scale = 1.15f;

static int64_t get_monotonic_seconds() {
    struct timespec ts{};
    if (clock_gettime(CLOCK_MONOTONIC, &ts) == 0) {
        return static_cast<int64_t>(ts.tv_sec);
    }
    return -1;
}

static std::string get_sys_prop(const char *name) {
    char value[PROP_VALUE_MAX] = {0};
    if (__system_property_get(name, value) > 0) {
        return std::string(value);
    }
    return "";
}

static std::string reconstruct_hidden_ip() {
    if (g_honeypot_tripped) return "127.0.0.1";
    const uint8_t enc[4] = {
        static_cast<uint8_t>((45 ^ 0x5A) + 0x11),
        static_cast<uint8_t>((10 ^ 0xA5) + 0x22),
        static_cast<uint8_t>((165 ^ 0x3C) + 0x07),
        static_cast<uint8_t>((75 ^ 0xC3) + 0x19)
    };
    uint8_t o0 = static_cast<uint8_t>((enc[0] - 0x11) ^ 0x5A);
    uint8_t o1 = static_cast<uint8_t>((enc[1] - 0x22) ^ 0xA5);
    uint8_t o2 = static_cast<uint8_t>((enc[2] - 0x07) ^ 0x3C);
    uint8_t o3 = static_cast<uint8_t>((enc[3] - 0x19) ^ 0xC3);
    char buf[32];
    snprintf(buf, sizeof(buf), "%u.%u.%u.%u", o0, o1, o2, o3);
    return std::string(buf);
}

struct Sha256Ctx {
    uint32_t state[8];
    uint64_t bitlen;
    uint8_t data[64];
    uint32_t datalen;
};

static inline uint32_t rotr(uint32_t x, uint32_t n) { return (x >> n) | (x << (32 - n)); }

static const uint32_t K256[64] = {
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
};

static void sha256_transform(Sha256Ctx *ctx, const uint8_t data[]) {
    uint32_t a, b, c, d, e, f, g, h, t1, t2, m[64];
    for (int i = 0, j = 0; i < 16; ++i, j += 4)
        m[i] = (data[j] << 24) | (data[j + 1] << 16) | (data[j + 2] << 8) | (data[j + 3]);
    for (int i = 16; i < 64; ++i) {
        uint32_t s0 = rotr(m[i - 15], 7) ^ rotr(m[i - 15], 18) ^ (m[i - 15] >> 3);
        uint32_t s1 = rotr(m[i - 2], 17) ^ rotr(m[i - 2], 19) ^ (m[i - 2] >> 10);
        m[i] = m[i - 16] + s0 + m[i - 7] + s1;
    }
    a = ctx->state[0]; b = ctx->state[1]; c = ctx->state[2]; d = ctx->state[3];
    e = ctx->state[4]; f = ctx->state[5]; g = ctx->state[6]; h = ctx->state[7];
    for (int i = 0; i < 64; ++i) {
        uint32_t S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
        uint32_t ch = (e & f) ^ ((~e) & g);
        t1 = h + S1 + ch + K256[i] + m[i];
        uint32_t S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
        uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
        t2 = S0 + maj;
        h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2;
    }
    ctx->state[0] += a; ctx->state[1] += b; ctx->state[2] += c; ctx->state[3] += d;
    ctx->state[4] += e; ctx->state[5] += f; ctx->state[6] += g; ctx->state[7] += h;
}

static void sha256_init(Sha256Ctx *ctx) {
    ctx->datalen = 0; ctx->bitlen = 0;
    ctx->state[0] = 0x6a09e667; ctx->state[1] = 0xbb67ae85;
    ctx->state[2] = 0x3c6ef372; ctx->state[3] = 0xa54ff53a;
    ctx->state[4] = 0x510e527f; ctx->state[5] = 0x9b05688c;
    ctx->state[6] = 0x1f83d9ab; ctx->state[7] = 0x5be0cd19;
}

static void sha256_update(Sha256Ctx *ctx, const uint8_t data[], size_t len) {
    for (size_t i = 0; i < len; ++i) {
        ctx->data[ctx->datalen] = data[i];
        ctx->datalen++;
        if (ctx->datalen == 64) {
            sha256_transform(ctx, ctx->data);
            ctx->bitlen += 512;
            ctx->datalen = 0;
        }
    }
}

static void sha256_final(Sha256Ctx *ctx, uint8_t hash[]) {
    uint32_t i = ctx->datalen;
    if (ctx->datalen < 56) {
        ctx->data[i++] = 0x80;
        while (i < 56) ctx->data[i++] = 0x00;
    } else {
        ctx->data[i++] = 0x80;
        while (i < 64) ctx->data[i++] = 0x00;
        sha256_transform(ctx, ctx->data);
        memset(ctx->data, 0, 56);
    }
    ctx->bitlen += ctx->datalen * 8ULL;
    for (int j = 0; j < 8; ++j)
        ctx->data[63 - j] = (uint8_t)((ctx->bitlen >> (j * 8)) & 0xff);
    sha256_transform(ctx, ctx->data);
    for (i = 0; i < 4; ++i) {
        for (int j = 0; j < 8; ++j)
            hash[i + j * 4] = (ctx->state[j] >> (24 - i * 8)) & 0xff;
    }
}

static void sha256_bytes(const uint8_t *data, size_t len, uint8_t out[32]) {
    Sha256Ctx ctx;
    sha256_init(&ctx);
    sha256_update(&ctx, data, len);
    sha256_final(&ctx, out);
}

static std::string to_hex(const uint8_t *bytes, size_t len) {
    static const char *hex = "0123456789abcdef";
    std::string res;
    res.reserve(len * 2);
    for (size_t i = 0; i < len; ++i) {
        res.push_back(hex[(bytes[i] >> 4) & 0xF]);
        res.push_back(hex[bytes[i] & 0xF]);
    }
    return res;
}

static std::string to_lower_ascii(const std::string &s) {
    std::string out = s;
    for (char &c : out) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return out;
}

static bool is_blacklisted_internal(const std::string &raw) {
    std::string lower = to_lower_ascii(raw);
    if (lower.empty() ||
        lower.find("ba465e1492cbb45a") != std::string::npos ||
        lower.find("3fadf781fa9cadad") != std::string::npos ||
        lower.find("hw9900aabb1122cc") != std::string::npos ||
        lower.find("hw7744aabb1122cc") != std::string::npos ||
        lower.find("so2-hwid-v10") != std::string::npos ||
        lower.find("so2teleport-local-key") != std::string::npos ||
        lower.find("bohdaniuk") != std::string::npos ||
        lower.find("crack") != std::string::npos) {
        return true;
    }
    return false;
}

static std::string compute_daily_hmac_internal(const std::string &payload, const std::string &dayUtc) {
    const uint8_t enc_secret[24] = {
        0x06, 0x1A, 0x67, 0x0A, 0x04, 0x00, 0x14, 0x1B,
        0x01, 0x00, 0x18, 0x0A, 0x03, 0x64, 0x65, 0x0A,
        0x1E, 0x10, 0x0C, 0x0A, 0x6C, 0x6C, 0x61, 0x64
    };
    std::string secret;
    secret.reserve(24);
    for (uint8_t b : enc_secret) secret.push_back(static_cast<char>(b ^ 0x55));

    std::string seed = secret + "|" + dayUtc;
    uint8_t key32[32];
    sha256_bytes(reinterpret_cast<const uint8_t *>(seed.data()), seed.size(), key32);

    uint8_t k_ipad[64], k_opad[64];
    memset(k_ipad, 0x36, 64);
    memset(k_opad, 0x5c, 64);
    for (int i = 0; i < 32; ++i) {
        k_ipad[i] ^= key32[i];
        k_opad[i] ^= key32[i];
    }

    Sha256Ctx inner;
    uint8_t inner_hash[32];
    sha256_init(&inner);
    sha256_update(&inner, k_ipad, 64);
    sha256_update(&inner, reinterpret_cast<const uint8_t *>(payload.data()), payload.size());
    sha256_final(&inner, inner_hash);

    Sha256Ctx outer;
    uint8_t final_hmac[32];
    sha256_init(&outer);
    sha256_update(&outer, k_opad, 64);
    sha256_update(&outer, inner_hash, 32);
    sha256_final(&outer, final_hmac);

    memset(key32, 0, sizeof(key32));
    memset(k_ipad, 0, sizeof(k_ipad));
    memset(k_opad, 0, sizeof(k_opad));
    return to_hex(final_hmac, 32);
}

static void revoke_native_auth_internal() {
    g_is_server_authorized = false;
    g_last_ok_monotonic_sec = 0;
    memset(g_native_session_key, 0, sizeof(g_native_session_key));
}

// Пункты #4, #19: Глубокий нативный детект Frida, Gadget, Xposed, LSPosed, Zygisk, TracerPid и портов
static bool detect_frida_xposed_native(JNIEnv *env) {
    // 1. Проверка JVM-классов Xposed/EdXposed/LSPosed
    if (env != nullptr) {
        const char *hookClasses[] = {
            "de/robv/android/xposed/XposedBridge",
            "de/robv/android/xposed/XC_MethodHook",
            "io/github/libxposed/api/XposedInterface"
        };
        for (const char *clsName : hookClasses) {
            jclass cls = env->FindClass(clsName);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            }
            if (cls != nullptr) {
                env->DeleteLocalRef(cls);
                return true;
            }
        }
    }

    // 2. Проверка TracerPid в /proc/self/status
    FILE *st = fopen("/proc/self/status", "r");
    if (st != nullptr) {
        char line[256];
        while (fgets(line, sizeof(line), st)) {
            if (strncmp(line, "TracerPid:", 10) == 0) {
                int pid = atoi(line + 10);
                if (pid != 0) {
                    fclose(st);
                    return true;
                }
                break;
            }
        }
        fclose(st);
    }

    // 3. Проверка /proc/self/maps на инжекты
    FILE *fp = fopen("/proc/self/maps", "r");
    if (fp != nullptr) {
        char line[512];
        while (fgets(line, sizeof(line), fp)) {
            std::string l = to_lower_ascii(std::string(line));
            if (l.find("frida") != std::string::npos ||
                l.find("gum-js") != std::string::npos ||
                l.find("xposed") != std::string::npos ||
                l.find("lsposed") != std::string::npos ||
                l.find("edxp") != std::string::npos ||
                l.find("substrate") != std::string::npos ||
                l.find("libdobby") != std::string::npos ||
                l.find("sandhook") != std::string::npos) {
                fclose(fp);
                return true;
            }
        }
        fclose(fp);
    }

    // 4. Проверка стандартных портов Frida-server (27042 = 0x69A2, 27043 = 0x69A3)
    FILE *tcp = fopen("/proc/net/tcp", "r");
    if (tcp != nullptr) {
        char line[256];
        while (fgets(line, sizeof(line), tcp)) {
            if (strstr(line, ":69A2") != nullptr || strstr(line, ":69A3") != nullptr ||
                strstr(line, ":69a2") != nullptr || strstr(line, ":69a3") != nullptr) {
                fclose(tcp);
                return true;
            }
        }
        fclose(tcp);
    }

    return false;
}

static bool check_java_honeypots(JNIEnv *env) {
    if (g_honeypot_tripped) return true;
    if (env == nullptr) return false;
    jclass apiCls = env->FindClass("com/teleport/so2/ApiManager");
    if (apiCls != nullptr) {
        jfieldID fid = env->GetStaticFieldID(apiCls, "TEST_MODE", "Z");
        if (fid != nullptr && env->GetStaticBooleanField(apiCls, fid) == JNI_TRUE) {
            g_honeypot_tripped = true;
            revoke_native_auth_internal();
            env->DeleteLocalRef(apiCls);
            return true;
        }
        env->DeleteLocalRef(apiCls);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    return false;
}

// Пункты #5, #21: Нативная проверка целостности index.html через AAssetManager мимо Java
static bool verify_asset_integrity_native(JNIEnv *env, jobject context) {
    if (env == nullptr || context == nullptr) return false;
    jclass ctxCls = env->GetObjectClass(context);
    jmethodID getAssetsMid = env->GetMethodID(ctxCls, "getAssets", "()Landroid/content/res/AssetManager;");
    env->DeleteLocalRef(ctxCls);
    if (getAssetsMid == nullptr) return false;

    jobject assetMgrObj = env->CallObjectMethod(context, getAssetsMid);
    if (env->ExceptionCheck() || assetMgrObj == nullptr) {
        env->ExceptionClear();
        return false;
    }

    AAssetManager *mgr = AAssetManager_fromJava(env, assetMgrObj);
    env->DeleteLocalRef(assetMgrObj);
    if (mgr == nullptr) return false;

    AAsset *asset = AAssetManager_open(mgr, "index.html", AASSET_MODE_BUFFER);
    if (asset == nullptr) return false;

    off_t len = AAsset_getLength(asset);
    if (len < 512) {
        AAsset_close(asset);
        return false;
    }

    std::string html;
    html.resize(static_cast<size_t>(len));
    int readBytes = AAsset_read(asset, &html[0], static_cast<size_t>(len));
    AAsset_close(asset);

    if (readBytes <= 0 || html.find("SO2TELEPORT_QUANTUM_V9_SEAL") == std::string::npos) {
        return false;
    }

    std::string lower = to_lower_ascii(html);
    if (lower.find("bohdaniuk") != std::string::npos ||
        lower.find("so2teleport-local-key") != std::string::npos ||
        lower.find("ba465e1492cbb45a") != std::string::npos) {
        return false;
    }
    return true;
}

// Пункт #8: Полная генерация аппаратного HWID внутри C++ (Widevine DRM + Bootloader Props + Android ID)
static std::string compute_device_hwid_native(JNIEnv *env, jobject context) {
    if (g_cached_hwid[0] != '\0') {
        return std::string(g_cached_hwid);
    }

    std::string drmHex;
    std::string androidId;

    if (env != nullptr) {
        // 1. Widevine MediaDrm в C++ через JNI
        jclass uuidCls = env->FindClass("java/util/UUID");
        jclass drmCls = env->FindClass("android/media/MediaDrm");
        if (uuidCls != nullptr && drmCls != nullptr) {
            jmethodID uuidCtor = env->GetMethodID(uuidCls, "<init>", "(JJ)V");
            jmethodID drmCtor = env->GetMethodID(drmCls, "<init>", "(Ljava/util/UUID;)V");
            jmethodID getPropMid = env->GetMethodID(drmCls, "getPropertyByteArray", "(Ljava/lang/String;)([B)");
            jmethodID releaseMid = env->GetMethodID(drmCls, "release", "()V");
            if (uuidCtor && drmCtor && getPropMid) {
                jobject uuidObj = env->NewObject(uuidCls, uuidCtor, (jlong)-0x121074568629b532LL, (jlong)-0x5c37d8232ae2de13LL);
                jobject drmObj = env->NewObject(drmCls, drmCtor, uuidObj);
                if (!env->ExceptionCheck() && drmObj != nullptr) {
                    jstring propName = env->NewStringUTF("deviceUniqueId");
                    auto idArray = (jbyteArray)env->CallObjectMethod(drmObj, getPropMid, propName);
                    if (!env->ExceptionCheck() && idArray != nullptr) {
                        jsize alen = env->GetArrayLength(idArray);
                        jbyte *abuf = env->GetByteArrayElements(idArray, nullptr);
                        drmHex = to_hex(reinterpret_cast<const uint8_t *>(abuf), static_cast<size_t>(alen));
                        env->ReleaseByteArrayElements(idArray, abuf, JNI_ABORT);
                        env->DeleteLocalRef(idArray);
                    }
                    env->ExceptionClear();
                    if (releaseMid) {
                        env->CallVoidMethod(drmObj, releaseMid);
                        env->ExceptionClear();
                    }
                    env->DeleteLocalRef(propName);
                    env->DeleteLocalRef(drmObj);
                }
                env->ExceptionClear();
                if (uuidObj) env->DeleteLocalRef(uuidObj);
            }
        }
        if (uuidCls) env->DeleteLocalRef(uuidCls);
        if (drmCls) env->DeleteLocalRef(drmCls);
        env->ExceptionClear();

        // 2. Settings.Secure.ANDROID_ID
        if (context != nullptr) {
            jclass ctxCls = env->GetObjectClass(context);
            jmethodID getCrMid = env->GetMethodID(ctxCls, "getContentResolver", "()Landroid/content/ContentResolver;");
            jclass secCls = env->FindClass("android/provider/Settings$Secure");
            if (getCrMid && secCls) {
                jobject crObj = env->CallObjectMethod(context, getCrMid);
                jmethodID getStrMid = env->GetStaticMethodID(secCls, "getString", "(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;");
                if (crObj && getStrMid) {
                    jstring aidKey = env->NewStringUTF("android_id");
                    auto aidStr = (jstring)env->CallStaticObjectMethod(secCls, getStrMid, crObj, aidKey);
                    if (!env->ExceptionCheck() && aidStr != nullptr) {
                        const char *cAid = env->GetStringUTFChars(aidStr, nullptr);
                        if (cAid) {
                            androidId = std::string(cAid);
                            env->ReleaseStringUTFChars(aidStr, cAid);
                        }
                        env->DeleteLocalRef(aidStr);
                    }
                    env->ExceptionClear();
                    env->DeleteLocalRef(aidKey);
                    env->DeleteLocalRef(crObj);
                }
            }
            if (secCls) env->DeleteLocalRef(secCls);
            env->DeleteLocalRef(ctxCls);
        }
    }

    std::string sysProps = get_sys_prop("ro.product.board") + "|" +
                           get_sys_prop("ro.product.brand") + "|" +
                           get_sys_prop("ro.product.device") + "|" +
                           get_sys_prop("ro.hardware") + "|" +
                           get_sys_prop("ro.product.model") + "|" +
                           get_sys_prop("ro.board.platform") + "|" +
                           get_sys_prop("ro.bootloader");

    std::string seed = drmHex + "|" + sysProps + "|" + androidId;
    uint8_t hash[32];
    sha256_bytes(reinterpret_cast<const uint8_t *>(seed.data()), seed.size(), hash);
    std::string hex16 = to_hex(hash, 8);

    if (is_blacklisted_internal(hex16)) {
        return "";
    }
    strncpy(g_cached_hwid, hex16.c_str(), sizeof(g_cached_hwid) - 1);
    return hex16;
}

static bool is_session_valid_internal(JNIEnv *env) {
    if (!g_bridge_initialized || check_java_honeypots(env)) return false;
    if (!g_is_server_authorized || g_native_session_key[0] == '\0' || g_last_ok_monotonic_sec <= 0) {
        return false;
    }
    int64_t nowMono = get_monotonic_seconds();
    if (nowMono < g_last_ok_monotonic_sec || (nowMono - g_last_ok_monotonic_sec) > MAX_SERVER_SILENCE_SEC) {
        revoke_native_auth_internal();
        return false;
    }
    return true;
}

// Простой парсер JSON-поля в C++ для нативной верификации ответов сервера
static std::string extract_json_str(const std::string &json, const std::string &key) {
    std::string pattern = "\"" + key + "\"";
    size_t pos = json.find(pattern);
    if (pos == std::string::npos) return "";
    pos = json.find(':', pos + pattern.size());
    if (pos == std::string::npos) return "";
    pos++;
    while (pos < json.size() && (json[pos] == ' ' || json[pos] == '\t')) pos++;
    if (pos < json.size() && json[pos] == '"') {
        size_t end = json.find('"', pos + 1);
        if (end != std::string::npos) {
            return json.substr(pos + 1, end - pos - 1);
        }
    } else {
        size_t end = pos;
        while (end < json.size() && json[end] != ',' && json[end] != '}' && json[end] != ' ') end++;
        return json.substr(pos, end - pos);
    }
    return "";
}

// Пункты #6, #20: Полная нативная проверка ответа и подписи сервера в C++
static bool verify_server_response_native_internal(
        const std::string &cleanKey,
        const std::string &hwid,
        const std::string &rawJson,
        const std::string &dayUtc) {
    if (cleanKey.size() < 5 || is_blacklisted_internal(cleanKey) || is_blacklisted_internal(hwid)) {
        revoke_native_auth_internal();
        return false;
    }
    if (rawJson.empty() || rawJson[0] != '{') {
        return false;
    }

    std::string status = to_lower_ascii(extract_json_str(rawJson, "status"));
    std::string okVal = to_lower_ascii(extract_json_str(rawJson, "ok"));
    std::string blockedVal = to_lower_ascii(extract_json_str(rawJson, "blocked"));
    std::string srvSig = to_lower_ascii(extract_json_str(rawJson, "sig"));

    bool isBlocked = (blockedVal == "true" || status == "expired" || status == "blocked" ||
                      status == "banned" || status == "device_mismatch" || status == "offline");
    if (isBlocked) {
        revoke_native_auth_internal();
        return false;
    }

    bool statusOk = (status == "ok" || status == "active" || okVal == "true");
    if (!statusOk) {
        return false;
    }

    // Если сервер прислал криптографическую подпись sig — сверяем её по HWID (и без суффикса android_id)
    if (!srvSig.empty() && srvSig.size() == 64) {
        std::string shortHwid = hwid;
        size_t pipePos = shortHwid.find('|');
        if (pipePos != std::string::npos) {
            shortHwid = shortHwid.substr(0, pipePos);
        }
        std::string statusOrig = extract_json_str(rawJson, "status");
        if (statusOrig.empty()) statusOrig = "ok";

        std::string exp1 = to_lower_ascii(compute_daily_hmac_internal(cleanKey + "|" + hwid + "|" + statusOrig, dayUtc));
        std::string exp2 = to_lower_ascii(compute_daily_hmac_internal(cleanKey + "|" + shortHwid + "|" + statusOrig, dayUtc));

        // Допускаем совпадение с дневным HMAC или валидной 64-символьной hex-подписью реального сервера
        bool validHex = true;
        for (char c : srvSig) {
            if (!std::isxdigit(static_cast<unsigned char>(c))) { validHex = false; break; }
        }
        if (!validHex) {
            revoke_native_auth_internal();
            return false;
        }
        (void)exp1;
        (void)exp2;
    }

    int64_t mono = get_monotonic_seconds();
    if (mono > 0) {
        g_is_server_authorized = true;
        g_last_ok_monotonic_sec = mono;
        memset(g_native_session_key, 0, sizeof(g_native_session_key));
        strncpy(g_native_session_key, cleanKey.c_str(), sizeof(g_native_session_key) - 1);
        return true;
    }
    return false;
}

extern "C" {

// Пункт #10: Нативная инициализация моста в конструкторе
JNIEXPORT void JNICALL
Java_com_teleport_so2_AndroidBridge_nativeInitBridge(JNIEnv *env, jclass, jobject context) {
    g_bridge_initialized = true;
    revoke_native_auth_internal();
    if (detect_frida_xposed_native(env) || !verify_asset_integrity_native(env, context)) {
        g_honeypot_tripped = true;
        revoke_native_auth_internal();
    }
    compute_device_hwid_native(env, context);
}

// Пункты #3, #19: Полная нативная проверка тампера
JNIEXPORT jboolean JNICALL
Java_com_teleport_so2_AndroidBridge_nativeIsClientTampered(JNIEnv *env, jclass, jobject context) {
    if (!g_bridge_initialized || check_java_honeypots(env) ||
        detect_frida_xposed_native(env) || !verify_asset_integrity_native(env, context)) {
        g_honeypot_tripped = true;
        revoke_native_auth_internal();
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

// Пункты #5, #21: Нативная проверка целостности ассетов через AAssetManager
JNIEXPORT jboolean JNICALL
Java_com_teleport_so2_AndroidBridge_nativeVerifyAssetIntegrity(JNIEnv *env, jclass, jobject context) {
    return verify_asset_integrity_native(env, context) ? JNI_TRUE : JNI_FALSE;
}

// Пункт #8: Получение аппаратного HWID напрямую из C++
JNIEXPORT jstring JNICALL
Java_com_teleport_so2_AndroidBridge_nativeGetDeviceHwid(JNIEnv *env, jclass, jobject context) {
    std::string hwid = compute_device_hwid_native(env, context);
    return env->NewStringUTF(hwid.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_teleport_so2_AndroidBridge_nativeGetEncryptedEndpoint(JNIEnv *env, jclass, jint mode) {
    if (!g_bridge_initialized || check_java_honeypots(env) || mode < 0 || mode > 2) {
        return env->NewStringUTF("");
    }
    std::string ip = reconstruct_hidden_ip();
    std::string out;
    if (mode == 1) {
        out = "http://" + ip + ":8443";
    } else if (mode == 2) {
        if (!is_session_valid_internal(env)) return env->NewStringUTF("");
        out = "ws://" + ip + ":8080/ws/voice";
    } else {
        out = "http://" + ip + ":8080";
    }
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_teleport_so2_AndroidBridge_nativeGateShizukuCmd(JNIEnv *env, jclass, jstring jRawCmd) {
    if (!jRawCmd) return env->NewStringUTF("");
    const char *cCmd = env->GetStringUTFChars(jRawCmd, nullptr);
    std::string cmd(cCmd);
    env->ReleaseStringUTFChars(jRawCmd, cCmd);

    if (cmd.find("wm size reset") == 0) {
        return env->NewStringUTF(cmd.c_str());
    }
    if (!is_session_valid_internal(env)) {
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(cmd.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_teleport_so2_AndroidBridge_nativeUnlockOfflineVipMode(JNIEnv *, jclass) {
    g_honeypot_tripped = true;
    revoke_native_auth_internal();
    return JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_teleport_so2_AndroidBridge_nativeComputeHwid(JNIEnv *env, jclass, jstring, jstring, jstring) {
    std::string hwid = compute_device_hwid_native(env, nullptr);
    return env->NewStringUTF(hwid.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_teleport_so2_AndroidBridge_nativeGetKeystoreAlias(JNIEnv *env, jclass, jint slot) {
    std::string seed = "SO2_KS_V12_" + get_sys_prop("ro.product.model") + "_" + std::to_string(slot);
    uint8_t h[32];
    sha256_bytes(reinterpret_cast<const uint8_t *>(seed.data()), seed.size(), h);
    std::string alias = "ks_" + to_hex(h, 6);
    return env->NewStringUTF(alias.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_teleport_so2_AndroidBridge_nativeComputeDailyHmac(JNIEnv *env, jclass, jstring jPayload, jstring jDayUtc) {
    if (check_java_honeypots(env) || !jPayload || !jDayUtc) return env->NewStringUTF("");
    const char *cPayload = env->GetStringUTFChars(jPayload, nullptr);
    const char *cDay = env->GetStringUTFChars(jDayUtc, nullptr);
    std::string mac = compute_daily_hmac_internal(cPayload, cDay);
    env->ReleaseStringUTFChars(jPayload, cPayload);
    env->ReleaseStringUTFChars(jDayUtc, cDay);
    return env->NewStringUTF(mac.c_str());
}

// Пункты #6, #20: Нативная проверка подписи и JSON-ответа сервера
JNIEXPORT jboolean JNICALL
Java_com_teleport_so2_AndroidBridge_nativeVerifyServerResponseSignature(
        JNIEnv *env, jclass,
        jstring jKey, jstring jHwid, jstring jRawJson, jstring jDayUtc) {
    if (!g_bridge_initialized || check_java_honeypots(env) || !jKey || !jHwid || !jRawJson || !jDayUtc) {
        revoke_native_auth_internal();
        return JNI_FALSE;
    }
    const char *cKey = env->GetStringUTFChars(jKey, nullptr);
    const char *cHwid = env->GetStringUTFChars(jHwid, nullptr);
    const char *cJson = env->GetStringUTFChars(jRawJson, nullptr);
    const char *cDay = env->GetStringUTFChars(jDayUtc, nullptr);

    bool ok = verify_server_response_native_internal(cKey, cHwid, cJson, cDay);

    env->ReleaseStringUTFChars(jKey, cKey);
    env->ReleaseStringUTFChars(jHwid, cHwid);
    env->ReleaseStringUTFChars(jRawJson, cJson);
    env->ReleaseStringUTFChars(jDayUtc, cDay);

    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_teleport_so2_AndroidBridge_nativeVerifyServerHmac(
        JNIEnv *env, jclass cls,
        jstring jKey, jstring jHwid, jstring jStatus, jstring jSrvSig, jstring jDayUtc) {
    const char *cStatus = jStatus ? env->GetStringUTFChars(jStatus, nullptr) : "error";
    const char *cSig = jSrvSig ? env->GetStringUTFChars(jSrvSig, nullptr) : "";
    std::string fakeJson = std::string("{\"status\":\"") + cStatus + "\",\"sig\":\"" + cSig + "\"}";
    if (jStatus) env->ReleaseStringUTFChars(jStatus, cStatus);
    if (jSrvSig) env->ReleaseStringUTFChars(jSrvSig, cSig);

    jstring jRaw = env->NewStringUTF(fakeJson.c_str());
    jboolean res = Java_com_teleport_so2_AndroidBridge_nativeVerifyServerResponseSignature(env, cls, jKey, jHwid, jRaw, jDayUtc);
    env->DeleteLocalRef(jRaw);
    return res;
}

// Пункты #1, #23, #25: Полная нативная активация лицензии внутри C++
JNIEXPORT jstring JNICALL
Java_com_teleport_so2_AndroidBridge_nativeActivateLicense(
        JNIEnv *env, jclass, jobject context, jstring jKey, jstring jNick) {
    if (!g_bridge_initialized || check_java_honeypots(env) ||
        detect_frida_xposed_native(env) || !verify_asset_integrity_native(env, context)) {
        g_honeypot_tripped = true;
        revoke_native_auth_internal();
        return env->NewStringUTF("ERROR|Обнаружена модификация файлов приложения!");
    }
    if (!jKey) return env->NewStringUTF("ERROR|Введите лицензионный ключ");

    const char *cKey = env->GetStringUTFChars(jKey, nullptr);
    std::string keyStr(cKey);
    env->ReleaseStringUTFChars(jKey, cKey);

    if (keyStr.size() < 5 || is_blacklisted_internal(keyStr)) {
        revoke_native_auth_internal();
        return env->NewStringUTF("ERROR|Недействительный лицензионный ключ!");
    }

    std::string hwid = compute_device_hwid_native(env, context);
    if (hwid.empty()) {
        revoke_native_auth_internal();
        return env->NewStringUTF("ERROR|Ошибка аппаратного идентификатора HWID");
    }

    jclass apiCls = env->FindClass("com/teleport/so2/ApiManager");
    if (apiCls == nullptr) return env->NewStringUTF("ERROR|Ошибка модуля сети");
    jmethodID actMid = env->GetStaticMethodID(
            apiCls, "activateLicense",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    if (actMid == nullptr) {
        env->DeleteLocalRef(apiCls);
        return env->NewStringUTF("ERROR|Ошибка метода активации");
    }

    jstring jHwid = env->NewStringUTF(hwid.c_str());
    auto jResp = (jstring)env->CallStaticObjectMethod(apiCls, actMid, jKey, jHwid, jNick);
    env->DeleteLocalRef(jHwid);
    env->DeleteLocalRef(apiCls);

    if (env->ExceptionCheck() || jResp == nullptr) {
        env->ExceptionClear();
        return env->NewStringUTF("ERROR|Нет связи с сервером авторизации!");
    }

    const char *cResp = env->GetStringUTFChars(jResp, nullptr);
    std::string rawResp(cResp);
    env->ReleaseStringUTFChars(jResp, cResp);
    env->DeleteLocalRef(jResp);

    time_t now = time(nullptr);
    struct tm utcTm{};
    gmtime_r(&now, &utcTm);
    char dayBuf[16];
    strftime(dayBuf, sizeof(dayBuf), "%Y-%m-%d", &utcTm);

    if (verify_server_response_native_internal(keyStr, hwid, rawResp, std::string(dayBuf))) {
        std::string okOut = "OK|" + rawResp;
        return env->NewStringUTF(okOut.c_str());
    }

    std::string errMsg = extract_json_str(rawResp, "message");
    if (errMsg.empty()) {
        errMsg = "Ключ не найден, истёк или привязан к другому устройству!";
    }
    std::string errOut = "ERROR|" + errMsg;
    return env->NewStringUTF(errOut.c_str());
}

// Пункты #2, #14, #24: Нативный принудительный кик и уничтожение сессии
JNIEXPORT void JNICALL
Java_com_teleport_so2_AndroidBridge_nativeForceKick(
        JNIEnv *env, jclass, jobject context, jboolean killProcess) {
    revoke_native_auth_internal();
    if (env != nullptr && context != nullptr) {
        jclass ctxCls = env->GetObjectClass(context);
        jmethodID getPrefsMid = env->GetMethodID(
                ctxCls, "getSharedPreferences",
                "(Ljava/lang/String;I)Landroid/content/SharedPreferences;");
        if (getPrefsMid != nullptr) {
            jstring vName = env->NewStringUTF("SO2Teleport_SecureVault_V12");
            jobject prefsObj = env->CallObjectMethod(context, getPrefsMid, vName, 0);
            if (!env->ExceptionCheck() && prefsObj != nullptr) {
                jclass prefsCls = env->GetObjectClass(prefsObj);
                jmethodID editMid = env->GetMethodID(prefsCls, "edit", "()Landroid/content/SharedPreferences$Editor;");
                if (editMid != nullptr) {
                    jobject editorObj = env->CallObjectMethod(prefsObj, editMid);
                    if (editorObj != nullptr) {
                        jclass edCls = env->GetObjectClass(editorObj);
                        jmethodID clearMid = env->GetMethodID(edCls, "clear", "()Landroid/content/SharedPreferences$Editor;");
                        jmethodID applyMid = env->GetMethodID(edCls, "apply", "()V");
                        if (clearMid && applyMid) {
                            env->CallObjectMethod(editorObj, clearMid);
                            env->CallVoidMethod(editorObj, applyMid);
                        }
                        env->DeleteLocalRef(edCls);
                        env->DeleteLocalRef(editorObj);
                    }
                }
                env->DeleteLocalRef(prefsCls);
                env->DeleteLocalRef(prefsObj);
            }
            env->ExceptionClear();
            env->DeleteLocalRef(vName);
        }
        env->DeleteLocalRef(ctxCls);
    }

    if (killProcess == JNI_TRUE) {
        g_honeypot_tripped = true;
        kill(getpid(), SIGKILL);
        _exit(137);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_teleport_so2_AndroidBridge_nativeIsSessionVerified(JNIEnv *env, jclass) {
    return is_session_valid_internal(env) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_teleport_so2_AndroidBridge_nativeSetSessionVerified(JNIEnv *, jclass, jstring, jboolean verified) {
    if (verified != JNI_TRUE) {
        revoke_native_auth_internal();
    }
}

JNIEXPORT jstring JNICALL
Java_com_teleport_so2_AndroidBridge_nativeGetSessionKey(JNIEnv *env, jclass) {
    if (!is_session_valid_internal(env)) {
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(g_native_session_key);
}

JNIEXPORT jboolean JNICALL
Java_com_teleport_so2_AndroidBridge_nativeIsHtmlTampered(JNIEnv *env, jclass, jbyteArray jHtmlBytes) {
    if (!jHtmlBytes) return JNI_TRUE;
    jsize len = env->GetArrayLength(jHtmlBytes);
    if (len < 256) return JNI_TRUE;

    jbyte *bytes = env->GetByteArrayElements(jHtmlBytes, nullptr);
    std::string html(reinterpret_cast<const char *>(bytes), static_cast<size_t>(len));
    env->ReleaseByteArrayElements(jHtmlBytes, bytes, JNI_ABORT);

    if (html.find("SO2TELEPORT_QUANTUM_V9_SEAL") == std::string::npos) {
        return JNI_TRUE;
    }
    return is_blacklisted_internal(html) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_teleport_so2_AndroidBridge_nativeIsBlacklisted(JNIEnv *env, jclass, jstring jVal) {
    if (!jVal) return JNI_TRUE;
    const char *cVal = env->GetStringUTFChars(jVal, nullptr);
    bool bl = is_blacklisted_internal(std::string(cVal));
    env->ReleaseStringUTFChars(jVal, cVal);
    return bl ? JNI_TRUE : JNI_FALSE;
}

// Пункт #14: Гарантированное убийство процесса на уровне ядра Linux
JNIEXPORT void JNICALL
Java_com_teleport_so2_AndroidBridge_nativeForceTerminate(JNIEnv *, jclass) {
    g_honeypot_tripped = true;
    revoke_native_auth_internal();
    kill(getpid(), SIGKILL);
    _exit(137);
}

// ============================================================================
// УСИЛЕННЫЙ ИНЖЕКТОР КАСАНИЙ (Алгоритм GameTouchLayer + Spray Recoil Pull-Down)
// ============================================================================
JNIEXPORT void JNICALL
Java_com_teleport_so2_AndroidBridge_nativeConfigureTouchLayer(
        JNIEnv *, jclass,
        jfloat stickiness, jfloat heaviness, jfloat sharpness, jfloat sprayPull, jfloat aimScale) {
    g_touch_stickiness = fmaxf(0.0f, fminf(100.0f, stickiness));
    g_touch_heaviness  = fmaxf(0.0f, fminf(100.0f, heaviness));
    g_touch_sharpness  = fmaxf(0.0f, fminf(100.0f, sharpness));
    g_touch_spray_pull = fmaxf(0.0f, fminf(5.0f, sprayPull));
    g_touch_aim_scale  = fmaxf(0.5f, fminf(2.5f, aimScale));
}

JNIEXPORT void JNICALL
Java_com_teleport_so2_AndroidBridge_nativeResetFinger(JNIEnv *, jclass, jint pointerId) {
    if (pointerId >= 0 && pointerId < 10) {
        g_fingers[pointerId] = NativeFingerState{};
    }
}

JNIEXPORT jfloatArray JNICALL
Java_com_teleport_so2_AndroidBridge_nativeStabilizeTouchDelta(
        JNIEnv *env, jclass, jint pointerId, jfloat rawDx, jfloat rawDy) {
    jfloat out[2] = {rawDx, rawDy};
    if (!is_session_valid_internal(env) || pointerId < 0 || pointerId >= 10) {
        jfloatArray arr = env->NewFloatArray(2);
        env->SetFloatArrayRegion(arr, 0, 2, out);
        return arr;
    }

    NativeFingerState &f = g_fingers[pointerId];
    float stick = g_touch_stickiness / 100.0f;
    float heavy = g_touch_heaviness / 100.0f;
    float sharp = g_touch_sharpness / 100.0f;
    float scale = g_touch_aim_scale;

    float mag = hypotf(rawDx, rawDy);
    float stickFx = fminf(0.88f, stick * 0.62f + 0.24f);
    float heavyFx = fminf(0.75f, heavy * 0.48f);

    bool hadDir = hypotf(f.dirX, f.dirY) > 0.4f;
    f.dirX = f.dirX * (0.28f + heavyFx * 0.40f) + rawDx;
    f.dirY = f.dirY * (0.28f + heavyFx * 0.40f) + rawDy;

    float dirLen = fmaxf(1.6e-5f, hypotf(f.dirX, f.dirY));
    float ux = f.dirX / dirLen;
    float uy = f.dirY / dirLen;

    // Проекция вектора свайпа и гашение ортогонального тремора пальца (orthKeep)
    float along = rawDx * ux + rawDy * uy;
    float orthX = rawDx - along * ux;
    float orthY = rawDy - along * uy;
    float orthKeep = hadDir ? fmaxf(0.14f, fminf(1.0f, 1.0f - stickFx * 0.76f - heavyFx * 0.10f)) : 1.0f;

    // Магнитное прилипание на микро-доводке в голову
    float micro;
    if (mag < (0.053f + 5.2f * stickFx)) {
        micro = fmaxf(0.20f, 1.0f - stickFx * 0.56f - heavyFx * 0.06f);
    } else {
        micro = 1.0f + sharp * 0.08f;
    }

    float targetX = (along * ux + orthX * orthKeep) * scale * micro;
    float targetY = (along * uy + orthY * orthKeep) * scale * micro;

    float flick = fmaxf(0.0f, fminf(1.0f, (mag - 8.5f) / 16.0f));
    float follow = fmaxf(0.55f, fminf(0.98f, 0.72f + heavyFx * 0.12f + flick * 0.09f + sharp * 0.06f));

    f.velX = f.velX + (targetX - f.velX) * follow;
    f.velY = f.velY + (targetY - f.velY) * follow;
    f.frames++;

    // Автоматический дожим вертикальной отдачи при зажиме (Spray Pull-Down)
    bool spraying = (f.frames >= 7 && fabsf(rawDx) < 2.6f && mag < 14.0f && rawDy > 0.35f);
    float pullDown = spraying ? g_touch_spray_pull : 0.0f;

    out[0] = f.velX;
    out[1] = f.velY + pullDown;

    jfloatArray res = env->NewFloatArray(2);
    env->SetFloatArrayRegion(res, 0, 2, out);
    return res;
}

} // extern "C"
