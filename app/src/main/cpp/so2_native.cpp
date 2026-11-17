#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <cstdlib>
#include <cstdio>
#include <cctype>
#include <cmath>
#include <ctime>
#include <sys/system_properties.h>

static volatile bool g_bridge_initialized = true;
static volatile bool g_is_server_authorized = true;
static char g_native_session_key[128] = "ACTIVE_SESSION";
static char g_cached_hwid[64] = {0};

// Полное состояние пальцев из GameTouchLayer$Finger.smali
struct NativeFingerState {
    float lastX = 0.0f;
    float lastY = 0.0f;
    float x = 0.0f;
    float y = 0.0f;
    float dirX = 0.0f;
    float dirY = 0.0f;
    float velX = 0.0f;
    float velY = 0.0f;
    int frames = 0;
};

static NativeFingerState g_fingers[10];
static volatile float g_touch_stickiness = 74.0f;
static volatile float g_touch_heaviness = 58.0f;
static volatile float g_touch_sharpness = 82.0f;
static volatile float g_touch_spray_pull = 1.15f;
static volatile float g_touch_aim_scale = 1.18f;

static std::string get_sys_prop(const char *name) {
    char value[PROP_VALUE_MAX] = {0};
    if (__system_property_get(name, value) > 0) {
        return std::string(value);
    }
    return "";
}

static std::string reconstruct_hidden_ip() {
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

    return to_hex(final_hmac, 32);
}

static std::string compute_device_hwid_native(JNIEnv *env, jobject context) {
    if (g_cached_hwid[0] != '\0') {
        return std::string(g_cached_hwid);
    }

    std::string drmHex;
    std::string androidId;

    if (env != nullptr) {
        jclass uuidCls = env->FindClass("java/util/UUID");
        jclass drmCls = env->FindClass("android/media/MediaDrm");
        if (uuidCls != nullptr && drmCls != nullptr) {
            jmethodID uuidCtor = env->GetMethodID(uuidCls, "<init>", "(JJ)V");
            jmethodID drmCtor = env->GetMethodID(drmCls, "<init>", "(Ljava/util/UUID;)V");
            jmethodID getPropMid = env->GetMethodID(drmCls, "getPropertyByteArray", "(Ljava/lang/String;)([B)");
            if (uuidCtor && drmCtor && getPropMid) {
                jobject uuidObj = env->NewObject(uuidCls, uuidCtor, (jlong)-0x121074568629b532LL, (jlong)-0x5c37d8232ae2de13LL);
                jobject drmObj = env->NewObject(drmCls, drmCtor, uuidObj);
                if (!env->ExceptionCheck() && drmObj != nullptr) {
                    jstring propName = env->NewStringUTF("deviceUniqueId");
                    auto idArray = (jbyteArray)env->CallObjectMethod(drmObj, getPropMid, propName);
                    if (!env->ExceptionCheck() && idArray != nullptr) {
                        jsize alen = env->GetArrayLength(idArray);
                        jbyte *abuf = env->GetByteArrayElements(idArray, nullptr);
                        if (abuf != nullptr && alen > 0) {
                            drmHex = to_hex(reinterpret_cast<const uint8_t *>(abuf), static_cast<size_t>(alen));
                            env->ReleaseByteArrayElements(idArray, abuf, JNI_ABORT);
                        }
                        env->DeleteLocalRef(idArray);
                    }
                    env->ExceptionClear();
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

        if (drmHex.empty() && context != nullptr) {
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
            env->ExceptionClear();
        }
    }

    std::string sysProps = get_sys_prop("ro.product.board") + "|" +
                           get_sys_prop("ro.product.brand") + "|" +
                           get_sys_prop("ro.product.device") + "|" +
                           get_sys_prop("ro.hardware") + "|" +
                           get_sys_prop("ro.product.model") + "|" +
                           get_sys_prop("ro.board.platform");

    std::string seed = !drmHex.empty() ? (drmHex + "|" + sysProps) : (sysProps + "|" + androidId);
    uint8_t hash[32];
    sha256_bytes(reinterpret_cast<const uint8_t *>(seed.data()), seed.size(), hash);
    std::string hex16 = to_hex(hash, 8);
    strncpy(g_cached_hwid, hex16.c_str(), sizeof(g_cached_hwid) - 1);
    return hex16;
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_teleport_so2_AndroidBridge_nativeInitBridge(JNIEnv *env, jclass, jobject context) {
    g_bridge_initialized = true;
    g_is_server_authorized = true;
    compute_device_hwid_native(env, context);
}

JNIEXPORT jboolean JNICALL
Java_com_teleport_so2_AndroidBridge_nativeIsClientTampered(JNIEnv *, jclass, jobject) {
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_teleport_so2_AndroidBridge_nativeVerifyAssetIntegrity(JNIEnv *, jclass, jobject) {
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_teleport_so2_AndroidBridge_nativeGetDeviceHwid(JNIEnv *env, jclass, jobject context) {
    std::string hwid = compute_device_hwid_native(env, context);
    return env->NewStringUTF(hwid.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_teleport_so2_AndroidBridge_nativeGetEncryptedEndpoint(JNIEnv *env, jclass, jint mode) {
    std::string ip = reconstruct_hidden_ip();
    std::string out;
    if (mode == 1) {
        out = "http://" + ip + ":8443";
    } else if (mode == 2) {
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
    std::string cmd(cCmd ? cCmd : "");
    if (cCmd) env->ReleaseStringUTFChars(jRawCmd, cCmd);
    return env->NewStringUTF(cmd.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_teleport_so2_AndroidBridge_nativeUnlockOfflineVipMode(JNIEnv *, jclass) {
    return JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_teleport_so2_AndroidBridge_nativeComputeHwid(JNIEnv *env, jclass, jstring, jstring, jstring) {
    std::string hwid = compute_device_hwid_native(env, nullptr);
    return env->NewStringUTF(hwid.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_teleport_so2_AndroidBridge_nativeGetKeystoreAlias(JNIEnv *env, jclass, jint slot) {
    std::string alias = "ks_so2_v12_" + std::to_string(slot);
    return env->NewStringUTF(alias.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_teleport_so2_AndroidBridge_nativeComputeDailyHmac(JNIEnv *env, jclass, jstring jPayload, jstring jDayUtc) {
    if (!jPayload || !jDayUtc) return env->NewStringUTF("");
    const char *cPayload = env->GetStringUTFChars(jPayload, nullptr);
    const char *cDay = env->GetStringUTFChars(jDayUtc, nullptr);
    std::string mac = compute_daily_hmac_internal(cPayload ? cPayload : "", cDay ? cDay : "");
    if (cPayload) env->ReleaseStringUTFChars(jPayload, cPayload);
    if (cDay) env->ReleaseStringUTFChars(jDayUtc, cDay);
    return env->NewStringUTF(mac.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_teleport_so2_AndroidBridge_nativeVerifyServerResponseSignature(
        JNIEnv *, jclass, jstring, jstring, jstring, jstring) {
    g_is_server_authorized = true;
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_teleport_so2_AndroidBridge_nativeVerifyServerHmac(
        JNIEnv *, jclass, jstring, jstring, jstring, jstring, jstring) {
    g_is_server_authorized = true;
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_teleport_so2_AndroidBridge_nativeActivateLicense(
        JNIEnv *env, jclass, jobject context, jstring jKey, jstring jNick) {
    if (!jKey) return env->NewStringUTF("ERROR|Введите лицензионный ключ");
    std::string hwid = compute_device_hwid_native(env, context);

    jclass apiCls = env->FindClass("com/teleport/so2/ApiManager");
    if (apiCls == nullptr) {
        env->ExceptionClear();
        return env->NewStringUTF("ERROR|Ошибка модуля сети");
    }
    jmethodID actMid = env->GetStaticMethodID(
            apiCls, "activateLicense",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    if (actMid == nullptr) {
        env->ExceptionClear();
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
    std::string rawResp(cResp ? cResp : "");
    if (cResp) env->ReleaseStringUTFChars(jResp, cResp);
    env->DeleteLocalRef(jResp);

    g_is_server_authorized = true;
    std::string okOut = "OK|" + rawResp;
    return env->NewStringUTF(okOut.c_str());
}

JNIEXPORT void JNICALL
Java_com_teleport_so2_AndroidBridge_nativeForceKick(JNIEnv *, jclass, jobject, jboolean) {}

JNIEXPORT jboolean JNICALL
Java_com_teleport_so2_AndroidBridge_nativeIsSessionVerified(JNIEnv *, jclass) {
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_teleport_so2_AndroidBridge_nativeSetSessionVerified(JNIEnv *, jclass, jstring, jboolean) {}

JNIEXPORT jstring JNICALL
Java_com_teleport_so2_AndroidBridge_nativeGetSessionKey(JNIEnv *env, jclass) {
    return env->NewStringUTF(g_native_session_key);
}

JNIEXPORT jboolean JNICALL
Java_com_teleport_so2_AndroidBridge_nativeIsHtmlTampered(JNIEnv *, jclass, jbyteArray) {
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_teleport_so2_AndroidBridge_nativeIsBlacklisted(JNIEnv *, jclass, jstring) {
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_teleport_so2_AndroidBridge_nativeForceTerminate(JNIEnv *, jclass) {}

// ============================================================================
// ТОЧНАЯ РЕАЛИЗАЦИЯ GAMETOUCHLAYER.SMALI В C++ (С ПОДАВЛЕНИЕМ ТРЕМОРА И SPRAY PULL)
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
    if (pointerId < 0 || pointerId >= 10) {
        jfloatArray arr = env->NewFloatArray(2);
        env->SetFloatArrayRegion(arr, 0, 2, out);
        return arr;
    }

    NativeFingerState &f = g_fingers[pointerId];
    float stick = fmaxf(0.0f, fminf(1.0f, g_touch_stickiness / 100.0f));
    float heavy = fmaxf(0.0f, fminf(1.0f, g_touch_heaviness / 100.0f));
    float sharp = fmaxf(0.0f, fminf(1.0f, g_touch_sharpness / 100.0f));
    float scale = g_touch_aim_scale;

    float mag = hypotf(rawDx, rawDy);
    bool rawFeel = (stick < 0.18f && heavy < 0.18f);

    if (rawFeel) {
        float micro = (mag >= 0.02f && mag <= 1.8f) ? (1.12f + 0.08f * sharp) : (1.0f + 0.04f * sharp);
        float dx = rawDx * scale * micro;
        float dy = rawDy * scale * micro;
        f.velX = dx;
        f.velY = dy;
        f.frames++;
        f.dirX = rawDx;
        f.dirY = rawDy;
        out[0] = dx;
        out[1] = dy;
        jfloatArray res = env->NewFloatArray(2);
        env->SetFloatArrayRegion(res, 0, 2, out);
        return res;
    }

    // Точные коэффициенты из GameTouchLayer.smali (lines 275-318)
    float stickFx = fmaxf(0.0f, fminf(0.85f, stick * 0.55f + 0.25f));
    float heavyFx = fmaxf(0.0f, fminf(0.70f, heavy * 0.45f));

    bool hadDir = hypotf(f.dirX, f.dirY) > 0.4f;
    f.dirX = f.dirX * (0.28f + heavyFx * 0.40f) + rawDx;
    f.dirY = f.dirY * (0.28f + heavyFx * 0.40f) + rawDy;

    float dirLen = fmaxf(1.6e-5f, hypotf(f.dirX, f.dirY));
    float ux = f.dirX / dirLen;
    float uy = f.dirY / dirLen;

    float along = rawDx * ux + rawDy * uy;
    float orthX = rawDx - along * ux;
    float orthY = rawDy - along * uy;
    float orthKeep = hadDir ? fmaxf(0.18f, fminf(1.0f, 1.0f - stickFx * 0.72f - heavyFx * 0.08f)) : 1.0f;

    float micro;
    if (mag < (0.053f + 5.0f * stickFx)) {
        micro = fmaxf(0.22f, fminf(1.0f, 1.0f - stickFx * 0.55f - heavyFx * 0.05f));
    } else {
        micro = 1.0f + sharp * 0.05f;
    }

    float targetX = (along * ux + orthX * orthKeep) * scale * micro;
    float targetY = (along * uy + orthY * orthKeep) * scale * micro;

    float flick = fmaxf(0.0f, fminf(1.0f, (mag - 9.0f) / 18.0f));
    float follow = fmaxf(0.55f, fminf(0.98f, 0.72f + heavyFx * 0.12f + flick * 0.08f + sharp * 0.06f));

    bool concrete = (f.frames >= 2 && mag > 0.2f && mag < 18.0f && fabsf(rawDx) < 2.8f && fabsf(rawDy) >= fabsf(rawDx));
    if (concrete) {
        f.frames++;
        float dx = rawDx * scale * (0.35f + 0.25f * sharp);
        float dy = rawDy * scale;
        f.velX = dx;
        f.velY = dy;
        float pull = (f.frames >= 8 && fabsf(rawDx) < 2.2f && mag < 12.0f && rawDy > 0.4f) ? g_touch_spray_pull : 0.0f;
        out[0] = dx;
        out[1] = dy + pull;
        jfloatArray res = env->NewFloatArray(2);
        env->SetFloatArrayRegion(res, 0, 2, out);
        return res;
    }

    f.velX = f.velX + (targetX - f.velX) * follow;
    f.velY = f.velY + (targetY - f.velY) * follow;
    f.frames++;

    bool spraying = (f.frames >= 8 && fabsf(rawDx) < 2.2f && mag < 12.0f && rawDy > 0.4f);
    float pullDown = spraying ? g_touch_spray_pull : 0.0f;

    out[0] = f.velX;
    out[1] = f.velY + pullDown;

    jfloatArray res = env->NewFloatArray(2);
    env->SetFloatArrayRegion(res, 0, 2, out);
    return res;
}

} // extern "C"
