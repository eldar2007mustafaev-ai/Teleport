#include <jni.h>
#include <stdint.h>
#include <string>
#include <vector>
#include <cstring>
#include <cstdlib>
#include <cstdio>
#include <cctype>
#include <ctime>
#include <unistd.h>
#include <signal.h>
#include <sys/ptrace.h>

// Приманки (Decoys) для дизассемблеров IDA / Ghidra:
static const char *DECOY_GHIDRA_URL_1 = "https://auth-node-eu1.so2quantum-verify.org:9443/api/v10/verify";
static const char *DECOY_GHIDRA_URL_2 = "http://185.220.101.44:8080/api/activate";
static const char *DECOY_GHIDRA_KEY   = "AES256_MASTER_SECRET_SO2_PRO_V9_77889900AABB";

static volatile bool g_is_server_authorized = false;
static volatile bool g_honeypot_tripped = false;
static volatile int64_t g_last_ok_monotonic_sec = 0;
static char g_native_session_key[128] = {0};

static const int64_t MAX_SERVER_SILENCE_SEC = 900; // 15 минут по монотонным часам ядра

static int64_t get_monotonic_seconds() {
    struct timespec ts{};
    if (clock_gettime(CLOCK_MONOTONIC, &ts) == 0) {
        return static_cast<int64_t>(ts.tv_sec);
    }
    return 0;
}

// Полиморфная расшифровка хоста сервера внутри нативной памяти (IP нигде не светится строкой)
static std::string reconstruct_hidden_ip() {
    if (g_honeypot_tripped) return "127.0.0.1";
    // Зашифрованные октеты: 45, 10, 165, 75
    const uint8_t enc_octets[4] = {
        static_cast<uint8_t>(45 ^ 0x7B),
        static_cast<uint8_t>(10 ^ 0x3E),
        static_cast<uint8_t>(165 ^ 0x91),
        static_cast<uint8_t>(75 ^ 0xC4)
    };
    const uint8_t masks[4] = {0x7B, 0x3E, 0x91, 0xC4};
    char buf[32];
    snprintf(buf, sizeof(buf), "%u.%u.%u.%u",
             enc_octets[0] ^ masks[0],
             enc_octets[1] ^ masks[1],
             enc_octets[2] ^ masks[2],
             enc_octets[3] ^ masks[3]);
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

static void revoke_native_auth_internal() {
    g_is_server_authorized = false;
    g_last_ok_monotonic_sec = 0;
    memset(g_native_session_key, 0, sizeof(g_native_session_key));
}

extern "C" {

// Выдача расшифрованного адреса сервера только в рантайме (mode 0 = 8080, mode 1 = 8443, mode 2 = ws voice)
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

// НАТИВНАЯ ФУНКЦИЯ-ОБМАНКА: если крякер вызовет её для "разблокировки VIP", включится капкан
JNIEXPORT jboolean JNICALL
Java_com_teleport_so2_AndroidBridge_nativeUnlockOfflineVipMode(JNIEnv *, jclass) {
    g_honeypot_tripped = true;
    revoke_native_auth_internal();
    return JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_teleport_so2_AndroidBridge_nativeComputeHwid(JNIEnv *env, jclass, jstring jDrm, jstring jAid, jstring jBoard) {
    const char *cDrm = jDrm ? env->GetStringUTFChars(jDrm, nullptr) : "";
    const char *cAid = jAid ? env->GetStringUTFChars(jAid, nullptr) : "";
    const char *cBoard = jBoard ? env->GetStringUTFChars(jBoard, nullptr) : "";

    std::string combined = std::string(cDrm) + "|" + std::string(cBoard);
    if (jDrm) env->ReleaseStringUTFChars(jDrm, cDrm);
    if (jAid) env->ReleaseStringUTFChars(jAid, cAid);
    if (jBoard) env->ReleaseStringUTFChars(jBoard, cBoard);

    uint8_t hash[32];
    sha256_bytes(reinterpret_cast<const uint8_t *>(combined.data()), combined.size(), hash);
    std::string hex16 = to_hex(hash, 8);
    return env->NewStringUTF(hex16.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_teleport_so2_AndroidBridge_nativeComputeDailyHmac(JNIEnv *env, jclass, jstring jPayload, jstring jDayUtc) {
    if (!jPayload || !jDayUtc) return env->NewStringUTF("");
    const char *cPayload = env->GetStringUTFChars(jPayload, nullptr);
    const char *cDay = env->GetStringUTFChars(jDayUtc, nullptr);
    std::string mac = compute_daily_hmac_internal(cPayload, cDay);
    env->ReleaseStringUTFChars(jPayload, cPayload);
    env->ReleaseStringUTFChars(jDayUtc, cDay);
    return env->NewStringUTF(mac.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_teleport_so2_AndroidBridge_nativeVerifyServerHmac(
        JNIEnv *env, jclass,
        jstring jKey, jstring jHwid, jstring jStatus, jstring jSrvSig, jstring jDayUtc) {
    if (g_honeypot_tripped || !jKey || !jHwid || !jStatus || !jSrvSig || !jDayUtc) {
        revoke_native_auth_internal();
        return JNI_FALSE;
    }
    const char *cKey = env->GetStringUTFChars(jKey, nullptr);
    const char *cHwid = env->GetStringUTFChars(jHwid, nullptr);
    const char *cStatus = env->GetStringUTFChars(jStatus, nullptr);
    const char *cSig = env->GetStringUTFChars(jSrvSig, nullptr);
    const char *cDay = env->GetStringUTFChars(jDayUtc, nullptr);

    std::string keyStr(cKey);
    std::string statusLower = to_lower_ascii(std::string(cStatus));
    std::string payload = keyStr + "|" + std::string(cHwid) + "|" + std::string(cStatus);
    std::string expected = to_lower_ascii(compute_daily_hmac_internal(payload, cDay));
    std::string actual = to_lower_ascii(std::string(cSig));

    env->ReleaseStringUTFChars(jKey, cKey);
    env->ReleaseStringUTFChars(jHwid, cHwid);
    env->ReleaseStringUTFChars(jStatus, cStatus);
    env->ReleaseStringUTFChars(jSrvSig, cSig);
    env->ReleaseStringUTFChars(jDayUtc, cDay);

    bool sigMatch = (!expected.empty() && expected == actual);
    bool statusOk = (statusLower == "ok" || statusLower == "active");

    if (sigMatch && statusOk) {
        g_is_server_authorized = true;
        g_last_ok_monotonic_sec = get_monotonic_seconds();
        memset(g_native_session_key, 0, sizeof(g_native_session_key));
        strncpy(g_native_session_key, keyStr.c_str(), sizeof(g_native_session_key) - 1);
        return JNI_TRUE;
    }

    if (!sigMatch) {
        revoke_native_auth_internal();
    }
    return sigMatch ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_teleport_so2_AndroidBridge_nativeIsSessionVerified(JNIEnv *, jclass) {
    if (g_honeypot_tripped || !g_is_server_authorized || g_native_session_key[0] == '\0' || g_last_ok_monotonic_sec <= 0) {
        return JNI_FALSE;
    }
    int64_t nowMono = get_monotonic_seconds();
    if (nowMono < g_last_ok_monotonic_sec || (nowMono - g_last_ok_monotonic_sec) > MAX_SERVER_SILENCE_SEC) {
        revoke_native_auth_internal();
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_teleport_so2_AndroidBridge_nativeSetSessionVerified(JNIEnv *, jclass, jstring, jboolean verified) {
    if (verified != JNI_TRUE) {
        revoke_native_auth_internal();
    }
}

JNIEXPORT jstring JNICALL
Java_com_teleport_so2_AndroidBridge_nativeGetSessionKey(JNIEnv *env, jclass) {
    if (g_honeypot_tripped || !g_is_server_authorized || g_last_ok_monotonic_sec <= 0) {
        return env->NewStringUTF("");
    }
    int64_t nowMono = get_monotonic_seconds();
    if ((nowMono - g_last_ok_monotonic_sec) > MAX_SERVER_SILENCE_SEC) {
        revoke_native_auth_internal();
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

    if (html.find("SO2TELEPORT_QUANTUM_V9_SEAL") == std::string::npos ||
        html.find("pollLicenseExpiration") == std::string::npos) {
        return JNI_TRUE;
    }

    std::string lower = to_lower_ascii(html);
    if (lower.find("bohdaniuk") != std::string::npos ||
        lower.find("cracked") != std::string::npos ||
        lower.find("so2teleport-local-key") != std::string::npos ||
        lower.find("ba465e1492cbb45a") != std::string::npos) {
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_teleport_so2_AndroidBridge_nativeIsBlacklisted(JNIEnv *env, jclass, jstring jVal) {
    if (!jVal) return JNI_TRUE;
    const char *cVal = env->GetStringUTFChars(jVal, nullptr);
    std::string lower = to_lower_ascii(std::string(cVal));
    env->ReleaseStringUTFChars(jVal, cVal);

    if (lower.find("ba465e1492cbb45a") != std::string::npos ||
        lower.find("3fadf781fa9cadad") != std::string::npos ||
        lower.find("hw9900aabb1122cc") != std::string::npos ||
        lower.find("so2-hwid-v10") != std::string::npos ||
        lower.find("so2teleport-local-key") != std::string::npos ||
        lower.find("bohdaniuk") != std::string::npos ||
        lower.find("crack") != std::string::npos) {
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_teleport_so2_AndroidBridge_nativeForceTerminate(JNIEnv *, jclass) {
    revoke_native_auth_internal();
    kill(getpid(), SIGKILL);
    _exit(1);
}

} // extern "C"
